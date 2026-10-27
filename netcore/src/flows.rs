//! On-device flow table: which destinations this session talked to, how much,
//! and how often they were refused (REDSHIELD-TRAFFIC-2026-10-25, section 1 and
//! Amendment A).
//!
//! COST. One mutex lookup per SOCKS5 CONNECT (`open` / `refused`), never per
//! packet: the relay gets an `Arc<Counters>` back and adds byte counts with
//! relaxed atomics. Everything else (sorting, the "unusual" checks, JSON) runs
//! only when the app reads the table.
//!
//! PRIVACY. Memory only, in the tunnel process. Cleared on stop and on
//! `rs_clear_flows`. Nothing here sends anything anywhere; the app decides
//! what leaves the device.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Keys kept; the least recently seen goes first.
pub const CAPACITY: usize = 400;
/// Ports remembered per destination.
const MAX_PORTS: usize = 8;
/// Connect timestamps kept per destination for the beaconing check.
const RING: usize = 32;
const MAX_HOST_LEN: usize = 253;

/// Ports a raw-IP destination is ordinarily reached on (web, DoT, Apple and
/// Google push). Anything else, many times, is worth a second look.
const COMMON_PORTS: [u16; 5] = [80, 443, 853, 5223, 5228];
const RAW_IP_MIN_CONNECTIONS: u64 = 20;
const BEACON_MIN_CONNECTIONS: u64 = 50;
const BEACON_WINDOW: Duration = Duration::from_secs(600);
/// Coefficient of variation of the inter-arrival times below which the
/// connections look machine-timed.
const BEACON_MAX_CV: f64 = 0.10;

/// Byte counters a relay holds for the life of one connection.
#[derive(Default, Debug)]
pub struct Counters {
    pub up: AtomicU64,
    pub down: AtomicU64,
}

impl Counters {
    #[inline]
    pub fn add_up(&self, n: usize) {
        self.up.fetch_add(n as u64, Ordering::Relaxed);
    }
    #[inline]
    pub fn add_down(&self, n: usize) {
        self.down.fetch_add(n as u64, Ordering::Relaxed);
    }
}

/// Byte counters per destination port, at most MAX_PORTS of them; bytes on
/// any further port go to `rest`. Summed only at read time.
#[derive(Debug, Default)]
struct PortBytes {
    ports: Vec<(u16, Arc<Counters>)>,
    rest: Arc<Counters>,
}

impl PortBytes {
    fn get(&mut self, port: u16) -> Arc<Counters> {
        if let Some((_, c)) = self.ports.iter().find(|(p, _)| *p == port) {
            return c.clone();
        }
        if self.ports.len() < MAX_PORTS {
            let c = Arc::new(Counters::default());
            self.ports.push((port, c.clone()));
            return c;
        }
        self.rest.clone()
    }
    fn up(&self) -> u64 {
        self.ports.iter().map(|(_, c)| c.up.load(Ordering::Relaxed)).sum::<u64>() + self.rest.up.load(Ordering::Relaxed)
    }
    fn down(&self) -> u64 {
        self.ports.iter().map(|(_, c)| c.down.load(Ordering::Relaxed)).sum::<u64>() + self.rest.down.load(Ordering::Relaxed)
    }
    /// `{"443": up + down}` for the named ports (the rest is not attributed).
    fn json(&self) -> serde_json::Map<String, serde_json::Value> {
        self.ports
            .iter()
            .map(|(p, c)| {
                let n = c.up.load(Ordering::Relaxed).saturating_add(c.down.load(Ordering::Relaxed));
                (p.to_string(), serde_json::Value::from(n))
            })
            .collect()
    }
}

#[derive(Debug)]
struct Entry {
    /// TCP ports in arrival order (CONNECTs relayed or refused).
    ports: Vec<u16>,
    connections: u64,
    blocked: u64,
    first: Instant,
    last: Instant,
    /// TCP bytes, per port.
    tcp: PortBytes,
    /// UDP associations to this destination and their bytes, per port
    /// (443 = QUIC). Kept apart from the TCP counts, as iOS reads them.
    udp_connections: u64,
    udp: PortBytes,
    /// Last RING connect times, oldest overwritten first.
    ring: [Option<Instant>; RING],
    ring_next: usize,
    /// The app-side sockets of the latest connections (newest last), as the
    /// tun bridge reported them: "src:port>dst:port". The app resolves them to
    /// the owning app while the connection is open. Memory only.
    origins: Vec<String>,
}

impl Entry {
    fn new(now: Instant) -> Self {
        Entry {
            ports: Vec::new(),
            connections: 0,
            blocked: 0,
            first: now,
            last: now,
            tcp: PortBytes::default(),
            udp_connections: 0,
            udp: PortBytes::default(),
            ring: [None; RING],
            ring_next: 0,
            origins: Vec::new(),
        }
    }

    fn touch(&mut self, port: u16, now: Instant) {
        self.last = now;
        if !self.ports.contains(&port) && self.ports.len() < MAX_PORTS {
            self.ports.push(port);
        }
    }

    fn up(&self) -> u64 {
        self.tcp.up()
    }
    fn down(&self) -> u64 {
        self.tcp.down()
    }
    fn udp_bytes(&self) -> u64 {
        self.udp.up().saturating_add(self.udp.down())
    }
    /// TCP and UDP, for ordering.
    fn total(&self) -> u64 {
        self.up().saturating_add(self.down()).saturating_add(self.udp_bytes())
    }

    /// Amendment A local checks. Read time only.
    fn unusual(&self, host: &str) -> bool {
        // 1. A raw IP reached on an uncommon port, many times.
        if self.connections >= RAW_IP_MIN_CONNECTIONS
            && host.parse::<IpAddr>().is_ok()
            && self.ports.iter().any(|p| !COMMON_PORTS.contains(p))
        {
            return true;
        }
        // 2. Beaconing: at least 50 connections, and the last 32 of them fall
        //    inside ten minutes at a near-constant interval.
        if self.connections >= BEACON_MIN_CONNECTIONS {
            let mut ts: Vec<Instant> = self.ring.iter().flatten().copied().collect();
            if ts.len() == RING {
                ts.sort();
                if ts[RING - 1].duration_since(ts[0]) <= BEACON_WINDOW {
                    return near_constant(&ts);
                }
            }
        }
        false
    }
}

/// Inter-arrival stdev under BEACON_MAX_CV of the mean.
fn near_constant(sorted: &[Instant]) -> bool {
    let gaps: Vec<f64> = sorted.windows(2).map(|w| w[1].duration_since(w[0]).as_secs_f64()).collect();
    if gaps.is_empty() {
        return false;
    }
    let mean = gaps.iter().sum::<f64>() / gaps.len() as f64;
    if mean <= 0.0 {
        return false;
    }
    let var = gaps.iter().map(|g| (g - mean) * (g - mean)).sum::<f64>() / gaps.len() as f64;
    var.sqrt() < BEACON_MAX_CV * mean
}

struct Table {
    map: HashMap<String, Entry>,
    since: Instant,
}

impl Table {
    fn new(now: Instant) -> Self {
        Table { map: HashMap::new(), since: now }
    }

    /// The entry for `host`, evicting the least recently seen key when full.
    fn entry(&mut self, host: String, now: Instant) -> &mut Entry {
        if !self.map.contains_key(&host) && self.map.len() >= CAPACITY {
            if let Some(oldest) = self.map.iter().min_by_key(|(_, e)| e.last).map(|(k, _)| k.clone()) {
                self.map.remove(&oldest);
            }
        }
        self.map.entry(host).or_insert_with(|| Entry::new(now))
    }

    fn open(&mut self, host: String, port: u16, now: Instant) -> Arc<Counters> {
        let e = self.entry(host, now);
        e.touch(port, now);
        e.connections += 1;
        e.ring[e.ring_next] = Some(now);
        e.ring_next = (e.ring_next + 1) % RING;
        e.tcp.get(port)
    }

    fn open_udp(&mut self, host: String, port: u16, now: Instant) -> Arc<Counters> {
        let e = self.entry(host, now);
        e.last = now;
        e.udp_connections += 1;
        e.udp.get(port)
    }

    fn refused(&mut self, host: String, port: u16, now: Instant) {
        let e = self.entry(host, now);
        e.touch(port, now);
        e.blocked += 1;
    }

    fn json(&self, now: Instant) -> String {
        let mut rows: Vec<(&String, &Entry)> = self.map.iter().collect();
        rows.sort_by(|a, b| {
            let ta = a.1.total();
            let tb = b.1.total();
            tb.cmp(&ta).then_with(|| b.1.last.cmp(&a.1.last)).then_with(|| a.0.cmp(b.0))
        });
        let flows: Vec<serde_json::Value> = rows
            .into_iter()
            .map(|(h, e)| {
                // The regional map's country for a literal-IP destination
                // (hostnames resolve at the exit; their country comes from
                // the score, which the app prefers when it has one). Read
                // time only: 400 keys at most, one binary search each.
                let cc = crate::geo::lookup_host(h).map(|c| String::from_utf8_lossy(&c).into_owned());
                serde_json::json!({
                    "host": h,
                    "cc": cc,
                    "ports": e.ports,
                    "connections": e.connections,
                    "bytes_up": e.up(),
                    "bytes_down": e.down(),
                    "first_secs_ago": now.saturating_duration_since(e.first).as_secs(),
                    "last_secs_ago": now.saturating_duration_since(e.last).as_secs(),
                    "blocked": e.blocked,
                    "unusual": e.unusual(h),
                    "origins": e.origins,
                    "port_bytes": e.tcp.json(),
                    "udp_connections": e.udp_connections,
                    "udp_bytes": e.udp_bytes(),
                    "udp_port_bytes": e.udp.json(),
                })
            })
            .collect();
        serde_json::json!({
            "since_secs": now.saturating_duration_since(self.since).as_secs(),
            "flows": flows,
        })
        .to_string()
    }
}

static TABLE: Mutex<Option<Table>> = Mutex::new(None);

/// Lowercased, trailing dot and brackets stripped, bounded. None = do not track.
fn key(host: &str) -> Option<String> {
    let h = host.trim().trim_end_matches('.');
    let h = h.strip_prefix('[').map(|s| s.trim_end_matches(']')).unwrap_or(h);
    if h.is_empty() {
        return None;
    }
    Some(h.chars().take(MAX_HOST_LEN).collect::<String>().to_lowercase())
}

fn with_table<R>(f: impl FnOnce(&mut Table, Instant) -> R) -> Option<R> {
    let now = Instant::now();
    let mut g = TABLE.lock().ok()?;
    let t = g.get_or_insert_with(|| Table::new(now));
    Some(f(t, now))
}

/// A CONNECT that will be relayed. Returns the counters the relay adds to.
/// Never fails: a poisoned lock yields counters nobody reads.
pub fn open(host: &str, port: u16) -> Arc<Counters> {
    let Some(k) = key(host) else { return Arc::default() };
    with_table(|t, now| t.open(k, port, now)).unwrap_or_default()
}

/// A UDP association to `host:port` (its first datagram's destination).
/// Returns the counters the datagram relay adds to.
pub fn open_udp(host: &str, port: u16) -> Arc<Counters> {
    let Some(k) = key(host) else { return Arc::default() };
    with_table(|t, now| t.open_udp(k, port, now)).unwrap_or_default()
}

/// A CONNECT refused on the device or by the exit.
pub fn refused(host: &str, port: u16) {
    if let Some(k) = key(host) {
        with_table(|t, now| t.refused(k, port, now));
    }
}

/// `{"since_secs": N, "flows": [...]}`, sorted by bytes up + down, descending.
pub fn json() -> String {
    with_table(|t, now| t.json(now)).unwrap_or_else(|| r#"{"since_secs":0,"flows":[]}"#.into())
}

/// Origins kept per destination.
const MAX_ORIGINS: usize = 4;

/// The app-side socket of a connection just opened to `host` (see Entry::origins).
pub fn origin(host: &str, origin: &str) {
    let Some(k) = key(host) else { return };
    let _ = with_table(|t, _| {
        if let Some(e) = t.map.get_mut(&k) {
            if e.origins.len() >= MAX_ORIGINS {
                e.origins.remove(0);
            }
            e.origins.push(origin.to_string());
        }
    });
}

/// Forget every destination and restart the "since" clock.
pub fn clear() {
    if let Ok(mut g) = TABLE.lock() {
        *g = Some(Table::new(Instant::now()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(t: &Table, now: Instant) -> serde_json::Value {
        serde_json::from_str(&t.json(now)).unwrap()
    }

    #[test]
    fn lru_evicts_the_least_recently_seen() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        for i in 0..CAPACITY {
            t.open(format!("h{i}.example"), 443, t0 + Duration::from_secs(i as u64));
        }
        // Refresh the oldest so h1 becomes the least recent.
        t.open("h0.example".into(), 443, t0 + Duration::from_secs(10_000));
        t.open("new.example".into(), 443, t0 + Duration::from_secs(10_001));
        assert_eq!(t.map.len(), CAPACITY);
        assert!(t.map.contains_key("h0.example"));
        assert!(!t.map.contains_key("h1.example"));
        assert!(t.map.contains_key("new.example"));
        // An existing key never evicts anything.
        t.refused("h2.example".into(), 80, t0 + Duration::from_secs(10_002));
        assert_eq!(t.map.len(), CAPACITY);
        assert!(t.map.contains_key("h3.example"));
    }

    #[test]
    fn sorted_by_total_bytes_desc() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        t.open("small.example".into(), 443, t0).add_down(10);
        let big = t.open("big.example".into(), 443, t0);
        big.add_up(500);
        big.add_down(600);
        t.open("mid.example".into(), 443, t0).add_up(300);
        let v = parse(&t, t0);
        let hosts: Vec<&str> = v["flows"].as_array().unwrap().iter().map(|f| f["host"].as_str().unwrap()).collect();
        assert_eq!(hosts, ["big.example", "mid.example", "small.example"]);
    }

    #[test]
    fn json_shape_matches_the_contract() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        let c = t.open("cdn.example".into(), 443, t0 + Duration::from_secs(5));
        c.add_up(1);
        c.add_down(2);
        t.open("cdn.example".into(), 8443, t0 + Duration::from_secs(8));
        t.refused("cdn.example".into(), 443, t0 + Duration::from_secs(9));
        let v = parse(&t, t0 + Duration::from_secs(20));
        assert_eq!(v["since_secs"], 20);
        let f = &v["flows"][0];
        assert_eq!(f["host"], "cdn.example");
        assert_eq!(f["ports"], serde_json::json!([443, 8443]));
        assert_eq!(f["connections"], 2);
        assert_eq!(f["bytes_up"], 1);
        assert_eq!(f["bytes_down"], 2);
        assert_eq!(f["first_secs_ago"], 15);
        assert_eq!(f["last_secs_ago"], 11);
        assert_eq!(f["blocked"], 1);
        assert_eq!(f["unusual"], false);
        assert_eq!(f["origins"], serde_json::json!([]));
        assert_eq!(f["port_bytes"], serde_json::json!({"443": 3, "8443": 0}));
        assert_eq!(f["udp_connections"], 0);
        assert_eq!(f["udp_bytes"], 0);
        assert_eq!(f["udp_port_bytes"], serde_json::json!({}));
        let mut keys: Vec<&String> = f.as_object().unwrap().keys().collect();
        keys.sort();
        assert_eq!(
            keys,
            [
                "blocked",
                "bytes_down",
                "bytes_up",
                "cc",
                "connections",
                "first_secs_ago",
                "host",
                "last_secs_ago",
                "origins",
                "port_bytes",
                "ports",
                "udp_bytes",
                "udp_connections",
                "udp_port_bytes",
                "unusual"
            ]
        );
    }

    #[test]
    fn ports_are_capped() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        for p in 1..=20u16 {
            t.open("many.example".into(), p, t0);
        }
        assert_eq!(t.map["many.example"].ports.len(), MAX_PORTS);
    }

    #[test]
    fn raw_ip_on_an_uncommon_port_is_unusual() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        for i in 0..19 {
            t.open("185.220.101.47".into(), 8080, t0 + Duration::from_secs(i * 37 % 11));
            t.open("17.57.144.10".into(), 5223, t0);
            t.open("odd.example".into(), 8080, t0);
        }
        assert!(!t.map["185.220.101.47"].unusual("185.220.101.47"));
        t.open("185.220.101.47".into(), 8080, t0);
        t.open("17.57.144.10".into(), 5223, t0);
        t.open("odd.example".into(), 8080, t0);
        assert!(t.map["185.220.101.47"].unusual("185.220.101.47"));
        assert!(!t.map["17.57.144.10"].unusual("17.57.144.10")); // push port
        assert!(!t.map["odd.example"].unusual("odd.example")); // a name, not an IP
    }

    #[test]
    fn beaconing_is_unusual_and_jitter_is_not() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        for i in 0..60u64 {
            // Every 10 s exactly.
            t.open("beacon.example".into(), 443, t0 + Duration::from_secs(i * 10));
            // Irregular: 1 s, 19 s, 1 s, 19 s ...
            t.open("human.example".into(), 443, t0 + Duration::from_secs(i * 10 + if i % 2 == 0 { 0 } else { 9 }));
        }
        assert!(t.map["beacon.example"].unusual("beacon.example"));
        assert!(!t.map["human.example"].unusual("human.example"));

        // Regular but slow (every 60 s): 32 connects span more than 10 minutes.
        let mut t = Table::new(t0);
        for i in 0..60u64 {
            t.open("slow.example".into(), 443, t0 + Duration::from_secs(i * 60));
        }
        assert!(!t.map["slow.example"].unusual("slow.example"));
    }

    #[test]
    fn literal_ip_rows_carry_the_map_country() {
        let _g = crate::blocklist::TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        clear();
        crate::geo::install(&crate::geo::tests::sample()).unwrap();
        let _ = open("10.0.1.9", 443);
        let _ = open("[2001:db8::7]", 443);
        let _ = open("8.8.8.8", 53);
        let _ = open("de.example", 443);
        let v: serde_json::Value = serde_json::from_str(&json()).unwrap();
        let cc = |h: &str| v["flows"].as_array().unwrap().iter().find(|f| f["host"] == h).unwrap()["cc"].clone();
        assert_eq!(cc("10.0.1.9"), "DE");
        assert_eq!(cc("2001:db8::7"), "NL");
        assert_eq!(cc("8.8.8.8"), serde_json::Value::Null, "outside the map");
        assert_eq!(cc("de.example"), serde_json::Value::Null, "a hostname is never classified here");
        crate::geo::clear();
        let v: serde_json::Value = serde_json::from_str(&json()).unwrap();
        assert_eq!(v["flows"][0]["cc"], serde_json::Value::Null, "no map, no country");
        clear();
    }

    #[test]
    fn keys_are_normalised() {
        assert_eq!(key("WWW.Apple.COM.").as_deref(), Some("www.apple.com"));
        assert_eq!(key("[2001:db8::1]").as_deref(), Some("2001:db8::1"));
        assert_eq!(key("  "), None);
    }

    #[test]
    fn clear_empties_and_restarts_the_clock() {
        let _g = crate::blocklist::TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        clear();
        open("a.example", 443).add_down(5);
        refused("b.example", 443);
        let v: serde_json::Value = serde_json::from_str(&json()).unwrap();
        assert_eq!(v["flows"].as_array().unwrap().len(), 2);
        clear();
        let v: serde_json::Value = serde_json::from_str(&json()).unwrap();
        assert_eq!(v["flows"].as_array().unwrap().len(), 0);
        assert_eq!(v["since_secs"], 0);
    }

    #[test]
    fn origins_keep_the_newest_four() {
        clear();
        let _ = open("o.example", 443);
        for i in 0..6 {
            origin("o.example", &format!("10.0.0.2:{}>198.18.0.9:443", 40000 + i));
        }
        origin("absent.example", "10.0.0.2:1>1.1.1.1:443"); // no entry: ignored
        let v: serde_json::Value = serde_json::from_str(&json()).unwrap();
        let f = v["flows"].as_array().unwrap().iter().find(|f| f["host"] == "o.example").unwrap();
        assert_eq!(f["origins"].as_array().unwrap().len(), 4);
        assert_eq!(f["origins"][3], "10.0.0.2:40005>198.18.0.9:443");
        assert!(v["flows"].as_array().unwrap().iter().all(|f| f["host"] != "absent.example"));
    }

    #[test]
    fn bytes_per_port_and_udp_apart_from_tcp() {
        let t0 = Instant::now();
        let mut t = Table::new(t0);
        t.open("q.example".into(), 443, t0).add_down(100);
        t.open("q.example".into(), 80, t0).add_up(7);
        let u = t.open_udp("q.example".into(), 443, t0);
        u.add_up(30);
        u.add_down(1000);
        t.open_udp("q.example".into(), 443, t0).add_down(5);
        t.open_udp("udp-only.example".into(), 3478, t0).add_up(1);
        // Past MAX_PORTS: bytes still counted in the totals, not per port.
        for p in 1000..1010u16 {
            t.open("q.example".into(), p, t0).add_down(1);
        }
        let v = parse(&t, t0);
        let f = v["flows"].as_array().unwrap().iter().find(|f| f["host"] == "q.example").unwrap();
        assert_eq!(f["bytes_down"], 110);
        assert_eq!(f["bytes_up"], 7);
        assert_eq!(f["port_bytes"]["443"], 100);
        assert_eq!(f["port_bytes"]["80"], 7);
        assert_eq!(f["port_bytes"].as_object().unwrap().len(), MAX_PORTS);
        assert_eq!(f["udp_connections"], 2);
        assert_eq!(f["udp_bytes"], 1035);
        assert_eq!(f["udp_port_bytes"], serde_json::json!({"443": 1035}));
        assert_eq!(f["connections"], 12);
        let o = v["flows"].as_array().unwrap().iter().find(|f| f["host"] == "udp-only.example").unwrap();
        assert_eq!(o["connections"], 0);
        assert_eq!(o["ports"], serde_json::json!([]));
        assert_eq!(o["udp_port_bytes"], serde_json::json!({"3478": 1}));
    }
}
