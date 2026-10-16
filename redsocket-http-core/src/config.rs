// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Endpoint list for the http-only client.

/// Fallback Host header for direct-IP dials when the config names none. A bare
/// IP in `Host:` looks anomalous to a middlebox; a plausible hostname does not.
const DEFAULT_UPGRADE_HOST: &str = "login.microsoftonline.com";
/// The historical path. Used when the config carries no `upgrade_paths`, so an
/// old config keeps producing byte-identical requests.
const DEFAULT_UPGRADE_PATH: &str = "/noise";

/// Pick this install's decoy Host: stable per (cid, endpoint) so one device is
/// consistent, but spread across the fleet. Pick the upgrade URL path.
fn pick_upgrade_path(paths: &[String], cid: &str, endpoint: &str) -> String {
    let usable: Vec<&String> = paths
        .iter()
        .filter(|p| p.starts_with('/') && !p.contains('?') && !p.contains('#')
                    && !p.contains(char::is_whitespace))
        .collect();
    if usable.is_empty() {
        return DEFAULT_UPGRADE_PATH.to_string();
    }
    let idx = (mix64(fnv1a(&format!("path|{}|{}", cid, endpoint))) % usable.len() as u64) as usize;
    usable[idx].clone()
}

/// Mixed before the modulo for the same reason as `pick_upgrade_path` — see
/// `mix64`.
fn pick_upgrade_host(hosts: &[String], cid: &str, endpoint: &str) -> String {
    if hosts.is_empty() {
        return DEFAULT_UPGRADE_HOST.to_string();
    }
    let idx = (mix64(fnv1a(&format!("{}|{}", cid, endpoint))) % hosts.len() as u64) as usize;
    hosts[idx].clone()
}

const HTTP_PORT: u16 = 80;

#[derive(Clone, Debug)]
pub struct Endpoint {
    /// Where we actually connect (IP literal or hostname).
    pub host: String,
    pub port: u16,
    /// What goes in the `Host:` header of the upgrade request.
    pub upgrade_host: String,
    /// Request-line path for the upgrade. Decorative — the server routes on the
    /// Upgrade header, never the URL — so it exists purely to avoid a fleet-wide
    /// constant. See `pick_upgrade_path`.
    pub upgrade_path: String,
    /// Lower is tried first (SRV semantics). Defaults come from which list the
    /// endpoint came from — see `prio` — so a config with no explicit values
    /// reproduces the historical tier order exactly.
    pub priority: u32,
    /// Tie-break within one priority. Higher = tried earlier, but the order is
    /// randomised per install (stable, keyed on the cid) so ~500K devices don't all
    /// hammer the same POP.
    pub weight: u32,
    /// Part of the "someone is blocking us" tier (CDN fronts, distinctive names).
    /// Selection reserves budget so these are always reachable. A flag rather than
    /// an index boundary, so it survives arbitrary reordering.
    pub fallback: bool,
    /// Paid-tier pool. Kept as a flag (not an index range) for the same reason.
    pub premium: bool,
}

/// Default priorities per source list. Explicit `priority` in the config wins.
pub mod prio {
    /// Paid pool — dedicated capacity is the point of paying.
    pub const PREMIUM: u32 = 10;
    /// Explicit `entries[]`: no DNS at all.
    pub const ENTRY: u32 = 20;
    /// `regions.*.ips` — direct POP IPs.
    pub const REGION: u32 = 22;
    /// Added to an IP literal whose family is not the preferred one.
    pub const OTHER_FAMILY: u32 = 10;
    /// CDN fronts: DNS, but unremarkable cover.
    pub const CDN: u32 = 40;
    /// CDN fronts when `cdn_first_regions` matches: ahead of ENTRY (20) and of the
    /// region tier (22), behind PREMIUM (10) so a paying user still gets reserved
    /// capacity first.
    pub const CDN_FIRST: u32 = 15;
    /// Distinctive names: last resort, one lookup enumerates the fleet.
    pub const NAMED: u32 = 50;
}

/// Which address family to try first, for this client.
fn cdn_first(v: &serde_json::Value, tz: &str) -> bool {
    if tz.is_empty() {
        return false;
    }
    let Some(regions) = v.get("freedom_regions").and_then(|f| f.as_object()) else {
        return false;
    };
    let wanted: Vec<String> = v
        .get("cdn_first_regions")
        .and_then(|a| a.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).map(|s| s.to_uppercase()).collect())
        .unwrap_or_default();
    if wanted.is_empty() {
        return false;
    }
    for (region, zones) in regions {
        let hit = zones.as_array().map(|z| {
            z.iter().filter_map(|x| x.as_str()).any(|zone| {
                tz.eq_ignore_ascii_case(zone)
                    || tz.to_lowercase().starts_with(&zone.to_lowercase())
            })
        });
        if hit == Some(true) && wanted.iter().any(|w| w.eq_ignore_ascii_case(region)) {
            return true;
        }
    }
    false
}

fn prefer_v6(v: &serde_json::Value, tz: &str) -> bool {
    let default_v6_first = v.get("ipv6_first").and_then(|b| b.as_bool()).unwrap_or(true);
    if !default_v6_first || tz.is_empty() {
        return default_v6_first;
    }
    let Some(regions) = v.get("freedom_regions").and_then(|f| f.as_object()) else {
        return default_v6_first;
    };
    let exceptions: Vec<String> = v
        .get("ipv4_first_regions")
        .and_then(|a| a.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).map(|s| s.to_uppercase()).collect())
        .unwrap_or_default();
    for (region, zones) in regions {
        let hit = zones.as_array().map(|z| {
            z.iter().filter_map(|x| x.as_str()).any(|zone| {
                tz.eq_ignore_ascii_case(zone)
                    || tz.to_lowercase().starts_with(&zone.to_lowercase())
            })
        });
        if hit == Some(true) && exceptions.iter().any(|e| e.eq_ignore_ascii_case(region)) {
            return false;
        }
    }
    default_v6_first
}

impl Endpoint {
    pub fn label(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

/// The probe gateway address, from the config's `probe` block.
#[derive(Clone, Debug)]
pub struct ProbeSettings {
    pub enabled: bool,
    pub gateway_host: String,
    pub gateway_port: u16,
    /// Inclusive `(min, max)` seconds to wait before the *first* gateway dial.
    pub start_delay_secs: (u64, u64),
    /// Destination ports the client will relay to. Empty = no restriction.
    pub allowed_ports: Vec<u16>,
    /// Per-stream byte cap. 0 = uncapped.
    pub max_stream_bytes: u64,
    /// Random pause before each dial, inclusive ms range. (0,0) = none.
    pub stream_jitter_ms: (u64, u64),
    /// Dials allowed per rolling hour. 0 = unlimited.
    pub max_streams_per_hour: u32,
}

/// Startup delay used when the config omits `start_delay_secs`. Deliberately
/// non-zero: an older config should still spread its first connections rather
/// than have every client dial the gateway the instant its tunnel comes up.
const DEFAULT_START_DELAY: (u64, u64) = (5, 15);

impl Default for ProbeSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            gateway_host: String::new(),
            gateway_port: 0,
            start_delay_secs: DEFAULT_START_DELAY,
            allowed_ports: Vec::new(),
            max_stream_bytes: 0,
            stream_jitter_ms: (0, 0),
            max_streams_per_hour: 0,
        }
    }
}

/// On-device destination filtering (malware / bandwidth-consuming SDKs).
#[derive(Clone, Debug)]
pub struct BlocklistSettings {
    pub enabled: bool,
    /// Where the list is fetched from, through the tunnel's own SOCKS5 port. A
    /// *config value*, never a constant in the binary — this client points at no
    /// third-party list provider.
    pub url: String,
    pub refresh_secs: u64,
    pub max_bytes: usize,
    /// Hosts no list may ever block, derived from the config's own bootstrap: the
    /// update URL itself, the cashier bases, the health probe. A shipped list that
    /// blocked its own update path could not be corrected.
    pub never_block: Vec<String>,
}

/// Six hours. Long enough that the fleet costs the origin almost nothing, short
/// enough that a correction reaches a user the same day.
const DEFAULT_REFRESH_SECS: u64 = 21_600;
/// Floor on the refresh interval. A config typo must not turn ~500K clients
/// into a hot loop against the update host.
const MIN_REFRESH_SECS: u64 = 900;
const DEFAULT_MAX_BYTES: usize = 2 * 1024 * 1024;
const MAX_MAX_BYTES: usize = 8 * 1024 * 1024;

impl Default for BlocklistSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            url: String::new(),
            refresh_secs: DEFAULT_REFRESH_SECS,
            max_bytes: DEFAULT_MAX_BYTES,
            never_block: Vec::new(),
        }
    }
}

/// Endpoint reachability measurement (`probe.measure`).
#[derive(Clone, Debug)]
pub struct MeasureSettings {
    pub enabled: bool,
    pub targets: Vec<usize>,
    /// Configured targets that did not resolve to a known endpoint. Counted so a
    /// config mistake is visible on the diagnostics surface.
    pub rejected: usize,
    pub interval_secs: u64,
    pub jitter_secs: (u64, u64),
    pub max_per_run: usize,
    pub connect_timeout_ms: u64,
    pub start_delay_secs: (u64, u64),
    pub gap_ms: (u64, u64),
}

/// One hour between runs by default. The floor below it is the load-bearing
/// number: several million clients at a shorter interval is a self-inflicted
/// DDoS on our own POPs, and a config typo must not be able to cause one.
const MEASURE_DEFAULT_INTERVAL: u64 = 3600;
const MEASURE_MIN_INTERVAL: u64 = 900;
/// Ceiling on targets touched per run. Above this a handset stops looking like
/// a client and starts looking like a tool.
const MEASURE_MAX_PER_RUN: usize = 16;
const MEASURE_DEFAULT_PER_RUN: usize = 4;
/// Connect timeouts are clamped into this window: too short measures the RTT
/// rather than reachability, too long holds a thread on a blackholed address.
const MEASURE_MIN_TIMEOUT_MS: u64 = 500;
const MEASURE_MAX_TIMEOUT_MS: u64 = 5000;
const MEASURE_DEFAULT_TIMEOUT_MS: u64 = 4000;
const MEASURE_DEFAULT_START_DELAY: (u64, u64) = (60, 600);
const MEASURE_DEFAULT_JITTER: (u64, u64) = (0, 900);
const MEASURE_DEFAULT_GAP_MS: (u64, u64) = (500, 5000);

impl Default for MeasureSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            targets: Vec::new(),
            rejected: 0,
            interval_secs: MEASURE_DEFAULT_INTERVAL,
            jitter_secs: MEASURE_DEFAULT_JITTER,
            max_per_run: MEASURE_DEFAULT_PER_RUN,
            connect_timeout_ms: MEASURE_DEFAULT_TIMEOUT_MS,
            start_delay_secs: MEASURE_DEFAULT_START_DELAY,
            gap_ms: MEASURE_DEFAULT_GAP_MS,
        }
    }
}

/// Inclusive `[min, max]` pair, with reversed bounds swapped rather than
/// rejected — the same tolerance `start_delay_secs` already has, for the same
/// reason: a typo must degrade, not disable the spreading.
fn pair(v: Option<&serde_json::Value>, key: &str, dflt: (u64, u64)) -> (u64, u64) {
    v.and_then(|m| m.get(key))
        .and_then(|d| d.as_array())
        .and_then(|a| {
            let lo = a.first()?.as_u64()?;
            let hi = a.get(1)?.as_u64()?;
            Some(if lo <= hi { (lo, hi) } else { (hi, lo) })
        })
        .unwrap_or(dflt)
}

/// Parse `probe.measure` and **intersect its target list with `endpoints`**.
fn parse_measure(probe: Option<&serde_json::Value>, endpoints: &[Endpoint]) -> MeasureSettings {
    let mut s = MeasureSettings::default();
    let Some(m) = probe.and_then(|p| p.get("measure")) else { return s };

    s.enabled = m.get("enabled").and_then(|x| x.as_bool()).unwrap_or(false);

    if let Some(arr) = m.get("targets").and_then(|x| x.as_array()) {
        for t in arr {
            let Some(host) = t.get("host").and_then(|x| x.as_str()) else {
                s.rejected += 1;
                continue;
            };

            let want_port = t.get("port").and_then(|x| x.as_u64()).map(|p| p as u16);
            let found = endpoints.iter().position(|e| {
                e.host == host && want_port.is_none_or(|p| e.port == p)
            });
            match found {
                Some(i) if !s.targets.contains(&i) => s.targets.push(i),
                Some(_) => {}
                None => s.rejected += 1,
            }
        }
    }

    s.interval_secs = m
        .get("interval_secs")
        .and_then(|x| x.as_u64())
        .unwrap_or(MEASURE_DEFAULT_INTERVAL)
        .max(MEASURE_MIN_INTERVAL);
    s.max_per_run = m
        .get("max_targets_per_run")
        .and_then(|x| x.as_u64())
        .unwrap_or(MEASURE_DEFAULT_PER_RUN as u64)
        .clamp(1, MEASURE_MAX_PER_RUN as u64) as usize;
    s.connect_timeout_ms = m
        .get("connect_timeout_ms")
        .and_then(|x| x.as_u64())
        .unwrap_or(MEASURE_DEFAULT_TIMEOUT_MS)
        .clamp(MEASURE_MIN_TIMEOUT_MS, MEASURE_MAX_TIMEOUT_MS);
    s.jitter_secs = pair(Some(m), "jitter_secs", MEASURE_DEFAULT_JITTER);
    s.start_delay_secs = pair(Some(m), "start_delay_secs", MEASURE_DEFAULT_START_DELAY);
    s.gap_ms = pair(Some(m), "gap_ms", MEASURE_DEFAULT_GAP_MS);
    s
}

/// Pull the bare host out of a URL, for the never-block set.
fn host_of(url: &str) -> Option<String> {
    let rest = url.split("://").last()?;
    let hostport = rest.split('/').next()?;
    let host = match hostport.strip_prefix('[') {
        Some(h) => h.split(']').next()?,
        None => hostport.rsplit_once(':').map(|(h, _)| h).unwrap_or(hostport),
    };
    let host = host.trim();
    if host.is_empty() { None } else { Some(host.to_lowercase()) }
}

fn parse_blocklist(v: &serde_json::Value) -> BlocklistSettings {
    let mut s = v
        .get("blocklist")
        .map(|b| BlocklistSettings {
            enabled: b.get("enabled").and_then(|x| x.as_bool()).unwrap_or(false),
            url: b.get("url").and_then(|x| x.as_str()).unwrap_or("").trim().to_string(),

            refresh_secs: b
                .get("refresh_secs")
                .and_then(|x| x.as_u64())
                .unwrap_or(DEFAULT_REFRESH_SECS)
                .max(MIN_REFRESH_SECS),
            max_bytes: (b
                .get("max_bytes")
                .and_then(|x| x.as_u64())
                .unwrap_or(DEFAULT_MAX_BYTES as u64) as usize)
                .clamp(4096, MAX_MAX_BYTES),
            never_block: Vec::new(),
        })
        .unwrap_or_default();

    let mut never: Vec<String> = Vec::new();
    let mut push = |u: Option<&str>| {
        if let Some(h) = u.and_then(host_of) {
            if !never.contains(&h) {
                never.push(h);
            }
        }
    };
    push(Some(s.url.as_str()));
    if let Some(api) = v.get("api") {
        push(api.get("base").and_then(|x| x.as_str()));
        if let Some(list) = api.get("fallback_bases").and_then(|x| x.as_array()) {
            for b in list.iter().filter_map(|x| x.as_str()) {
                push(Some(b));
            }
        }
    }
    push(v.get("health").and_then(|h| h.get("probe_url")).and_then(|x| x.as_str()));
    s.never_block = never;
    s
}

pub struct Config {
    pub server_pubkey: [u8; 32],
    pub endpoints: Vec<Endpoint>,
    pub probe: ProbeSettings,
    pub blocklist: BlocklistSettings,
    /// Reachability measurement. Its `targets` index into [`Self::endpoints`], so
    /// the two cannot drift apart and a target outside our own POPs is not
    /// expressible. See [`MeasureSettings`].
    pub measure: MeasureSettings,
    /// MUX2: carry many SOCKS5 streams over one Noise carrier (`crate::mux`).
    pub mux: bool,
}

/// Tier default adjusted for address family. Names are untouched — they resolve
/// to whatever the resolver returns, so family preference is meaningless there.
fn host_family_is_v6(host: &str) -> Option<bool> {
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return Some(ip.is_ipv6());
    }
    let label = host.split('.').next().unwrap_or(host);
    if label.ends_with("-v6") {
        Some(true)
    } else if label.ends_with("-v4") {
        Some(false)
    } else {
        None
    }
}

fn default_prio(base: u32, host: &str, v6_first: bool) -> u32 {
    match host_family_is_v6(host) {
        Some(is_v6) if is_v6 != v6_first => base + prio::OTHER_FAMILY,
        _ => base,
    }
}

/// `priority`/`weight` from a config entry, falling back to the list default.
/// Weight defaults to 1 (equal footing); 0 means "disabled".
fn prio_weight(e: &serde_json::Value, default_prio: u32) -> (u32, u32) {
    let p = e.get("priority").and_then(|x| x.as_u64()).unwrap_or(default_prio as u64) as u32;
    let w = e.get("weight").and_then(|x| x.as_u64()).unwrap_or(1) as u32;
    (p, w)
}

fn parse_pubkey(hex_str: &str) -> Result<[u8; 32], String> {
    let bytes = hex::decode(hex_str.trim()).map_err(|e| format!("pubkey not hex: {}", e))?;
    bytes
        .as_slice()
        .try_into()
        .map_err(|_| format!("pubkey must be 32 bytes, got {}", bytes.len()))
}

fn mix64(mut x: u64) -> u64 {
    x ^= x >> 30;
    x = x.wrapping_mul(0xbf58_476d_1ce4_e5b9);
    x ^= x >> 27;
    x = x.wrapping_mul(0x94d0_49bb_1331_11eb);
    x ^ (x >> 31)
}

fn fnv1a(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.as_bytes() {
        h ^= *b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// Build the endpoint list. `selected_region` scopes to one region
/// ("fra"/"lax"); None means all regions. `cid` only affects ordering. Build
/// the endpoint list.
pub fn load(
    config_json: &str,
    selected_region: Option<&str>,
    cid: &str,
    premium: bool,

    tz: &str,
) -> Result<Config, String> {
    let v: serde_json::Value =
        serde_json::from_str(config_json).map_err(|e| format!("config parse: {}", e))?;

    let rs = v
        .get("transport")
        .and_then(|t| t.get("redsocket"))
        .ok_or_else(|| "config has no transport.redsocket".to_string())?;

    if rs.get("enabled").and_then(|e| e.as_bool()) == Some(false) {
        return Err("transport.redsocket disabled in config".into());
    }

    let v6_first = prefer_v6(&v, tz);
    let fronts_lead = cdn_first(&v, tz);

    let upgrade_paths: Vec<String> = rs
        .get("upgrade_paths")
        .and_then(|h| h.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).map(str::to_string).collect())
        .unwrap_or_default();

    let upgrade_hosts: Vec<String> = rs
        .get("upgrade_hosts")
        .and_then(|h| h.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).map(str::to_string).collect())
        .unwrap_or_default();

    let mux = rs.get("mux").and_then(|m| m.as_bool()).unwrap_or(false);

    let server_pubkey = parse_pubkey(
        rs.get("pubkey")
            .and_then(|p| p.as_str())
            .ok_or_else(|| "config has no transport.redsocket.pubkey".to_string())?,
    )?;

    let mut premium_eps = Vec::new();
    if premium {
        if let Some(entries) = rs.get("premium_entries").and_then(|e| e.as_array()) {
            for e in entries {
                match e.get("protocol").and_then(|p| p.as_str()) {
                    None | Some("") | Some("http") => {}
                    Some(_) => continue,
                }
                let Some(host) = e.get("host").and_then(|h| h.as_str()) else { continue };
                let port = e.get("port").and_then(|p| p.as_u64()).unwrap_or(HTTP_PORT as u64) as u16;
                let sni = e.get("sni").and_then(|x| x.as_str()).unwrap_or("");

                let ep_region = e.get("region").and_then(|r| r.as_str()).unwrap_or("");
                let region_ok = match selected_region {
                    None => true,
                    Some(sel) => sel.eq_ignore_ascii_case(ep_region),
                };
                let (priority, weight) = prio_weight(e, default_prio(prio::PREMIUM, host, v6_first));

                let (priority, fallback, is_premium) = if region_ok {
                    (priority, false, true)
                } else {
                    (prio::CDN - 1, true, false)
                };
                premium_eps.push(Endpoint {
                    host: host.to_string(),
                    port,
                    upgrade_host: if sni.is_empty() { pick_upgrade_host(&upgrade_hosts, cid, host) } else { sni.to_string() },
                    upgrade_path: pick_upgrade_path(&upgrade_paths, cid, host),
                    priority, weight, fallback, premium: is_premium,
                });
            }
        }
    }

    if premium {
        if let Some(regions) = v.get("premium_regions").and_then(|r| r.as_object()) {
            for (name, region) in regions {
                let region_ok = match selected_region {
                    None => true,
                    Some(sel) => sel.eq_ignore_ascii_case(name),
                };
                let Some(ips) = region.get("ips").and_then(|i| i.as_array()) else { continue };
                for (rank, ip) in ips.iter().filter_map(|i| i.as_str()).enumerate() {
                    let base = default_prio(prio::PREMIUM + rank as u32 * 2, ip, v6_first);
                    let (priority, fallback, is_premium) = if region_ok {
                        (base, false, true)
                    } else {
                        (prio::CDN - 1, true, false)
                    };
                    premium_eps.push(Endpoint {
                        host: ip.to_string(),
                        port: HTTP_PORT,
                        upgrade_host: pick_upgrade_host(&upgrade_hosts, cid, ip),
                        upgrade_path: pick_upgrade_path(&upgrade_paths, cid, ip),
                        priority, weight: 1, fallback, premium: is_premium,
                    });
                }
            }
        }
    }

    let mut explicit = Vec::new();
    if let Some(entries) = rs.get("entries").and_then(|e| e.as_array()) {
        for e in entries {
            match e.get("protocol").and_then(|p| p.as_str()) {
                None | Some("") | Some("http") => {}
                Some(_) => continue,
            }
            let Some(host) = e.get("host").and_then(|h| h.as_str()) else { continue };
            let port = e.get("port").and_then(|p| p.as_u64()).unwrap_or(HTTP_PORT as u64) as u16;
            let sni = e.get("sni").and_then(|s| s.as_str()).unwrap_or("");

            let is_name = host.parse::<std::net::IpAddr>().is_err();
            let base = if is_name { prio::NAMED } else { prio::ENTRY };
            let (priority, weight) = prio_weight(e, default_prio(base, host, v6_first));
            explicit.push(Endpoint {
                host: host.to_string(),
                port,
                upgrade_host: if sni.is_empty() { pick_upgrade_host(&upgrade_hosts, cid, host) } else { sni.to_string() },
                upgrade_path: pick_upgrade_path(&upgrade_paths, cid, host),
                priority, weight, fallback: is_name, premium: false,
            });
        }
    }

    let mut v4 = Vec::new();
    let mut v6 = Vec::new();
    if let Some(regions) = v.get("regions").and_then(|r| r.as_object()) {
        for (name, region) in regions {
            if let Some(sel) = selected_region {
                if !sel.eq_ignore_ascii_case(name) {
                    continue;
                }
            }
            let Some(ips) = region.get("ips").and_then(|i| i.as_array()) else { continue };
            let mut name_rank = 0u32;
            for ip in ips.iter().filter_map(|i| i.as_str()) {
                let is_name = ip.parse::<std::net::IpAddr>().is_err();
                let ep = Endpoint {
                    host: ip.to_string(),
                    port: HTTP_PORT,
                    upgrade_host: pick_upgrade_host(&upgrade_hosts, cid, ip),
                    upgrade_path: pick_upgrade_path(&upgrade_paths, cid, ip),
                    priority: if is_name {
                        let p = default_prio(prio::NAMED + name_rank * 2, ip, v6_first);
                        name_rank += 1;
                        p
                    } else {
                        default_prio(prio::REGION, ip, v6_first)
                    },
                    weight: 1,
                    fallback: is_name,
                    premium: false,
                };

                if ip.contains(':') { v6.push(ep) } else { v4.push(ep) }
            }
        }
    }

    let mut direct = v4;
    let n_direct = direct.len();
    if n_direct > 0 {
        direct.rotate_left((fnv1a(cid) % n_direct as u64) as usize);
    }
    direct.extend(v6);

    let cdn: Vec<Endpoint> = rs
        .get("cloudfront_domains")
        .and_then(|c| c.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|d| d.as_str())
                .map(|d| Endpoint {
                    host: d.to_string(),
                    port: HTTP_PORT,

                    upgrade_host: d.to_string(),
                    upgrade_path: pick_upgrade_path(&upgrade_paths, cid, d),
                    priority: if fronts_lead { prio::CDN_FIRST } else { prio::CDN },

                    weight: 1,
                    fallback: !fronts_lead,
                    premium: false,
                })
                .collect()
        })
        .unwrap_or_default();

    let (mut named, literal): (Vec<Endpoint>, Vec<Endpoint>) = explicit
        .into_iter()
        .partition(|e| e.host.parse::<std::net::IpAddr>().is_err());

    let mut endpoints = Vec::new();
    endpoints.extend(premium_eps);
    let mut direct_named_selected: Vec<Endpoint> = Vec::new();

    let (region_named, direct): (Vec<Endpoint>, Vec<Endpoint>) = direct
        .into_iter()
        .partition(|e| e.host.parse::<std::net::IpAddr>().is_err());
    if selected_region.is_some() {
        let mut region_named = region_named;
        region_named.sort_by_key(|e| e.priority);
        let n = region_named.len() as u32;
        for (i, mut e) in region_named.into_iter().enumerate() {
            let back = n - 1 - i as u32;
            e.priority = (prio::REGION - 1).saturating_sub(back).max(prio::PREMIUM + 1);
            e.fallback = false;
            direct_named_selected.push(e);
        }
    } else {
        named.extend(region_named);
    }

    let mut direct = direct;
    direct.extend(direct_named_selected);
    if selected_region.is_some() {
        for e in &mut direct {
            e.priority = e.priority.saturating_sub(3);
        }
    }
    endpoints.extend(literal);
    endpoints.extend(direct);
    endpoints.extend(cdn);

    named.sort_by_key(|e| e.priority);
    endpoints.extend(named);

    {
        let mut order: Vec<usize> = (0..endpoints.len()).collect();
        order.sort_by_key(|&i| (endpoints[i].priority, i));
        let mut seen = std::collections::HashSet::new();
        let keep: std::collections::HashSet<usize> = order
            .into_iter()
            .filter(|&i| seen.insert((endpoints[i].host.clone(), endpoints[i].port)))
            .collect();
        let mut i = 0;
        endpoints.retain(|_| { let k = keep.contains(&i); i += 1; k });
    }

    if endpoints.is_empty() {
        return Err("config yielded no http endpoints".into());
    }

    let probe = v
        .get("probe")
        .map(|p| ProbeSettings {
            enabled: p.get("enabled").and_then(|e| e.as_bool()).unwrap_or(false),
            gateway_host: p
                .get("gateway_host")
                .and_then(|h| h.as_str())
                .unwrap_or("")
                .to_string(),
            gateway_port: p.get("gateway_port").and_then(|x| x.as_u64()).unwrap_or(0) as u16,

            start_delay_secs: p
                .get("start_delay_secs")
                .and_then(|d| d.as_array())
                .and_then(|a| {
                    let lo = a.first()?.as_u64()?;
                    let hi = a.get(1)?.as_u64()?;
                    Some(if lo <= hi { (lo, hi) } else { (hi, lo) })
                })
                .unwrap_or(DEFAULT_START_DELAY),
            allowed_ports: p
                .get("allowed_ports")
                .and_then(|a| a.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_u64()).map(|x| x as u16).collect())
                .unwrap_or_default(),
            max_stream_bytes: p
                .get("max_stream_bytes")
                .and_then(|x| x.as_u64())
                .unwrap_or(0),
            stream_jitter_ms: p
                .get("stream_jitter_ms")
                .and_then(|a| a.as_array())
                .and_then(|a| Some((a.first()?.as_u64()?, a.get(1)?.as_u64()?)))
                .unwrap_or((0, 0)),
            max_streams_per_hour: p
                .get("max_streams_per_hour")
                .and_then(|x| x.as_u64())
                .unwrap_or(0) as u32,
        })
        .unwrap_or_default();

    let blocklist = parse_blocklist(&v);

    let measure = parse_measure(v.get("probe"), &endpoints);

    Ok(Config { server_pubkey, endpoints, probe, blocklist, measure, mux })
}

#[cfg(test)]
mod dual_stack_tests {
    use super::*;

    /// `entries[]` must accept an IPv6 literal, not just `regions.*.ips`.
    #[test]
    fn entries_accept_ipv6_literals() {
        let json = r#"{
            "version": 1,
            "transport": { "redsocket": { "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405", "entries": [
                { "host": "2001:db8::110", "port": 80 },
                { "host": "192.0.2.180", "port": 80 },
                { "host": "pop-b.example.net", "port": 80 }
            ] } }
        }"#;
        let cfg = load(json, None, "cid0", false, "").expect("v6 entry must parse");
        let hosts: Vec<&str> = cfg.endpoints.iter().map(|e| e.host.as_str()).collect();
        assert!(hosts.contains(&"2001:db8::110"), "v6 entry dropped: {hosts:?}");
        assert!(hosts.contains(&"192.0.2.180"), "v4 entry dropped: {hosts:?}");
        assert!(hosts.contains(&"pop-b.example.net"), "name entry dropped: {hosts:?}");
    }

    /// A bare v6 literal must survive as-is, unbracketed. protected_connect_by_name
    /// parses it with `host.parse::<IpAddr>()`, so bracketing it here would break
    /// the fast path and send it down DNS resolution instead.
    #[test]
    fn ipv6_host_is_stored_unbracketed() {
        let json = r#"{
            "version": 1,
            "transport": { "redsocket": { "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405", "entries": [
                { "host": "2001:db8::110", "port": 80 }
            ] } }
        }"#;
        let cfg = load(json, None, "cid0", false, "").unwrap();
        let ep = cfg.endpoints.iter().find(|e| e.host.contains(':')).unwrap();
        assert!(!ep.host.starts_with('['), "v6 host must not be bracketed: {}", ep.host);
        assert!(ep.host.parse::<std::net::IpAddr>().is_ok(),
                "v6 host must parse as an IpAddr: {}", ep.host);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CFG: &str = r#"{
      "transport": { "redsocket": {
        "enabled": true,
        "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
        "entries": [],
        "cloudfront_domains": ["d1.cloudfront.net"]
      }},
      "regions": {
        "fra": { "ips": ["198.51.100.99", "2001:db8:5::20"] },
        "lax": { "ips": ["203.0.113.124"] }
      }
    }"#;

    #[test]
    fn builds_v4_first_then_v6_then_cdn() {
        let c = load(CFG, None, "abcd1234abcd1234", false, "").unwrap();
        let labels: Vec<String> = c.endpoints.iter().map(|e| e.label()).collect();

        assert_eq!(labels.len(), 4);
        assert!(!labels[0].contains("::"), "v4 must come first, got {:?}", labels);
        assert!(!labels[1].contains("::"), "v4 must come first, got {:?}", labels);
        assert_eq!(labels[2], "2001:db8:5::20:80");
        assert_eq!(labels[3], "d1.cloudfront.net:80");

        assert_eq!(c.endpoints[3].upgrade_host, "d1.cloudfront.net");
        assert_eq!(c.endpoints[0].upgrade_host, DEFAULT_UPGRADE_HOST);

        assert_eq!(c.endpoints[0].upgrade_path, DEFAULT_UPGRADE_PATH);
    }

    #[test]
    fn region_selection_scopes_direct_ips() {
        let c = load(CFG, Some("lax"), "cid0", false, "").unwrap();
        assert_eq!(c.endpoints[0].host, "203.0.113.124");

        assert_eq!(c.endpoints.len(), 2);
    }

    #[test]
    fn different_cids_can_lead_with_different_pops() {
        let leads: std::collections::HashSet<String> = ["a", "b", "c", "d", "e", "f", "g", "h"]
            .iter()
            .map(|cid| load(CFG, None, cid, false, "").unwrap().endpoints[0].host.clone())
            .collect();
        assert!(leads.len() > 1, "cid rotation never varies the lead POP");
    }

    /// `cdn_first_regions` puts the fronts at the head of the plan for the listed
    /// region ONLY, and changes nothing anywhere else.
    #[test]
    fn cdn_first_regions_leads_only_where_listed() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let mut v: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        v["cdn_first_regions"] = serde_json::json!(["IR"]);
        let json = v.to_string();

        let lead = |tz: &str| -> String {
            let cfg = load(&json, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, false, None, "deadbeefdeadbeef");
            cfg.endpoints[p.order[0]].host.clone()
        };

        assert!(lead("Asia/Tehran").contains("cloudfront.net"),
                "Tehran must lead with a front, got {}", lead("Asia/Tehran"));

        let mut v2: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        v2["cdn_first_regions"] = serde_json::json!(["IR", "RU"]);
        let two = v2.to_string();

        let ru: Vec<String> = serde_json::from_str::<serde_json::Value>(SHIPPED)
            .unwrap()["freedom_regions"]["RU"]
            .as_array().unwrap().iter()
            .map(|z| z.as_str().unwrap().to_string()).collect();
        assert_eq!(ru.len(), 27, "RU zone count changed; re-check this test");
        for tz in ru.iter().map(String::as_str).chain(["Asia/Tehran"]) {
            let cfg = load(&two, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, false, None, "deadbeefdeadbeef");
            assert!(cfg.endpoints[p.order[0]].host.contains("cloudfront.net"),
                    "{tz} must lead with a front");
        }

        for tz in ["America/Los_Angeles", "Europe/Berlin"] {
            let cfg = load(&two, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, false, None, "deadbeefdeadbeef");
            assert!(!cfg.endpoints[p.order[0]].host.contains("cloudfront.net"),
                    "{tz} must not lead with a front");
        }

        for tz in ["Europe/Moscow", "America/Los_Angeles", "Europe/Berlin", ""] {
            assert!(!lead(tz).contains("cloudfront.net"),
                    "{tz} must not lead with a front, got {}", lead(tz));
        }

        let mut off: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        off.as_object_mut().unwrap().remove("cdn_first_regions");
        let base = load(&off.to_string(), None, "deadbeefdeadbeef", false, "Asia/Tehran").unwrap();
        let bp = crate::select::plan(&base, false, None, "deadbeefdeadbeef");
        assert!(!base.endpoints[bp.order[0]].host.contains("cloudfront.net"));
    }

    /// Guards the actual shipped asset: a config edit that leaves the client with
    /// no usable endpoint would otherwise only surface on a device.
    #[test]
    fn shipped_config_yields_a_usable_endpoint_list() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");

        let auto = load(SHIPPED, None, "abcd1234abcd1234", false, "").expect("shipped config must parse");
        assert!(
            auto.endpoints.len() >= 10,
            "suspiciously few endpoints: {}",
            auto.endpoints.len()
        );

        assert!(
            auto.endpoints[0].host.parse::<std::net::IpAddr>().is_ok(),
            "first endpoint must be an IP literal, got {}",
            auto.endpoints[0].host
        );

        let named: Vec<usize> = auto
            .endpoints
            .iter()
            .enumerate()
            .filter(|(_, e)| e.host.parse::<std::net::IpAddr>().is_err()
                && !e.host.ends_with(".cloudfront.net"))
            .map(|(i, _)| i)
            .collect();
        if let Some(&first) = named.first() {
            let tail = auto.endpoints.len() - named.len();
            assert_eq!(
                first, tail,
                "named endpoints must occupy the tail; {} is at {} of {}",
                auto.endpoints[first].host, first, auto.endpoints.len()
            );
        }

        for e in auto.endpoints.iter().filter(|e| e.host.ends_with(".cloudfront.net")) {
            assert_eq!(e.upgrade_host, e.host, "CDN front needs its own Host");
        }

        if auto.probe.enabled {
            assert!(
                !auto.probe.gateway_host.is_empty(),
                "probe is enabled but has no gateway_host"
            );
        }

        for r in ["fra", "lax"] {
            let scoped = load(SHIPPED, Some(r), "abcd1234abcd1234", false, "")
                .unwrap_or_else(|e| panic!("region {} must load: {}", r, e));
            assert!(!scoped.endpoints.is_empty(), "region {} has no endpoints", r);

            assert!(
                !scoped.endpoints[0].host.contains("example.net"),
                "region {} led with the roaming hostname",
                r
            );
        }
    }

    #[test]
    fn explicit_region_beats_region_agnostic_entries() {
        const WITH_ENTRY: &str = r#"{
          "transport": { "redsocket": {
            "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "entries": [{ "host": "any-region.example.net", "port": 80, "protocol": "http" }],
            "cloudfront_domains": []
          }},
          "regions": { "fra": { "ips": ["198.51.100.99"] }, "lax": { "ips": ["203.0.113.124"] } }
        }"#;

        let picked = load(WITH_ENTRY, Some("fra"), "cid0", false, "").unwrap();
        assert_eq!(picked.endpoints[0].host, "198.51.100.99");
        assert_eq!(picked.endpoints.last().unwrap().host, "any-region.example.net");

        let auto = load(WITH_ENTRY, None, "cid0", false, "").unwrap();
        assert!(auto.endpoints[0].host.parse::<std::net::IpAddr>().is_ok());
        assert_eq!(auto.endpoints.last().unwrap().host, "any-region.example.net");
    }

    #[test]
    fn probe_settings_fail_closed() {
        let none = load(CFG, None, "cid0", false, "").unwrap().probe;
        assert!(!none.enabled && none.gateway_host.is_empty());

        const NO_FLAG: &str = r#"{
          "transport": { "redsocket": {
            "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "cloudfront_domains": ["d1.cloudfront.net"] }},
          "probe": { "gateway_host": "gw.example.net", "gateway_port": 9080 }
        }"#;
        let implicit = load(NO_FLAG, None, "cid0", false, "").unwrap().probe;
        assert!(!implicit.enabled, "absent `enabled` must default to false");
        assert_eq!(implicit.gateway_host, "gw.example.net");
        assert_eq!(implicit.gateway_port, 9080);

        assert_eq!(implicit.start_delay_secs, DEFAULT_START_DELAY);
    }

    /// Config with three known endpoints to resolve targets against.
    const MEASURE_BASE: &str = r#"{
      "transport": { "redsocket": {
        "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
        "entries": [
          { "host": "192.0.2.160", "port": 80 },
          { "host": "198.51.100.130",  "port": 80 }
        ],
        "cloudfront_domains": ["d1.cloudfront.net"] }},
      "probe": { "measure": MEASURE }
    }"#;

    fn measured(measure: &str) -> MeasureSettings {
        let cfg = MEASURE_BASE.replace("MEASURE", measure);
        load(&cfg, None, "cid0", false, "").unwrap().measure
    }

    fn labels(m: &MeasureSettings, measure: &str) -> Vec<String> {
        let cfg = MEASURE_BASE.replace("MEASURE", measure);
        let full = load(&cfg, None, "cid0", false, "").unwrap();
        m.targets.iter().map(|&i| full.endpoints[i].label()).collect()
    }

    #[test]
    fn measure_is_absent_and_off_by_default() {
        let none = load(CFG, None, "cid0", false, "").unwrap().measure;
        assert!(!none.enabled);
        assert!(none.targets.is_empty());

        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let shipped = load(SHIPPED, None, "abcd1234abcd1234", false, "").unwrap().measure;
        assert!(
            !shipped.enabled || !shipped.targets.is_empty(),
            "enabled with no resolvable target is a config mistake, not a feature"
        );

        assert_eq!(
            shipped.rejected, 0,
            "the shipped config names {} measurement target(s) that are not in its own endpoint list",
            shipped.rejected
        );
    }

    #[test]
    fn a_target_outside_the_config_endpoint_set_cannot_be_expressed() {
        let m = measured(
            r#"{ "enabled": true, "targets": [
                 { "host": "192.0.2.160", "port": 80 },
                 { "host": "8.8.8.8", "port": 53 },
                 { "host": "192.168.1.1", "port": 22 },
                 { "host": "scanme.example.org", "port": 80 },
                 { "host": "198.51.100.130", "port": 80 } ] }"#,
        );
        assert_eq!(m.targets.len(), 2, "only the two real endpoints may survive");
        assert_eq!(m.rejected, 3, "the other three must be counted, not silently dropped");

        let got = labels(&m, r#"{ "enabled": true, "targets": [
                 { "host": "192.0.2.160", "port": 80 },
                 { "host": "8.8.8.8", "port": 53 },
                 { "host": "192.168.1.1", "port": 22 },
                 { "host": "scanme.example.org", "port": 80 },
                 { "host": "198.51.100.130", "port": 80 } ] }"#);
        assert_eq!(got, vec!["192.0.2.160:80", "198.51.100.130:80"]);
    }

    #[test]
    fn a_known_host_on_an_unshipped_port_is_still_rejected() {
        let m = measured(
            r#"{ "enabled": true, "targets": [
                 { "host": "192.0.2.160", "port": 22 },
                 { "host": "192.0.2.160", "port": 8080 } ] }"#,
        );
        assert!(m.targets.is_empty(), "port must match an endpoint we actually ship");
        assert_eq!(m.rejected, 2);
    }

    #[test]
    fn an_omitted_port_adopts_the_endpoints_own() {
        let m = measured(r#"{ "enabled": true, "targets": [ { "host": "198.51.100.130" } ] }"#);
        assert_eq!(m.targets.len(), 1);
        assert_eq!(m.rejected, 0);
        let got = labels(&m, r#"{ "enabled": true, "targets": [ { "host": "198.51.100.130" } ] }"#);
        assert_eq!(got, vec!["198.51.100.130:80"]);
    }

    #[test]
    fn duplicate_targets_collapse() {
        let m = measured(
            r#"{ "enabled": true, "targets": [
                 { "host": "192.0.2.160", "port": 80 },
                 { "host": "192.0.2.160", "port": 80 } ] }"#,
        );
        assert_eq!(m.targets.len(), 1);
        assert_eq!(m.rejected, 0, "a repeat is not a rejection");
    }

    #[test]
    fn a_malformed_target_is_rejected_rather_than_guessed_at() {
        let m = measured(
            r#"{ "enabled": true, "targets": [ {}, { "port": 80 }, "192.0.2.160", 42 ] }"#,
        );
        assert!(m.targets.is_empty());
        assert_eq!(m.rejected, 4);
    }

    #[test]
    fn cdn_fronts_are_legitimate_targets_because_the_config_ships_them() {
        let m = measured(r#"{ "enabled": true, "targets": [ { "host": "d1.cloudfront.net" } ] }"#);
        assert_eq!(m.targets.len(), 1);
    }

    #[test]
    fn measure_numerics_are_floored_so_a_typo_cannot_make_a_hot_loop() {
        let m = measured(
            r#"{ "enabled": true, "interval_secs": 1, "max_targets_per_run": 9999,
                 "connect_timeout_ms": 1 }"#,
        );
        assert_eq!(m.interval_secs, MEASURE_MIN_INTERVAL, "a fleet this size cannot poll every second");
        assert_eq!(m.max_per_run, MEASURE_MAX_PER_RUN);
        assert_eq!(m.connect_timeout_ms, MEASURE_MIN_TIMEOUT_MS);

        let huge = measured(r#"{ "enabled": true, "connect_timeout_ms": 600000 }"#);
        assert_eq!(huge.connect_timeout_ms, MEASURE_MAX_TIMEOUT_MS);

        let zero = measured(r#"{ "enabled": true, "max_targets_per_run": 0 }"#);
        assert_eq!(zero.max_per_run, 1, "zero would be a no-op loop, not a disable");
    }

    #[test]
    fn measure_ranges_tolerate_bad_input_the_way_start_delay_does() {
        let m = measured(
            r#"{ "enabled": true, "jitter_secs": [900, 0], "start_delay_secs": "soon",
                 "gap_ms": [5] }"#,
        );
        assert_eq!(m.jitter_secs, (0, 900), "reversed bounds are swapped, not dropped");
        assert_eq!(m.start_delay_secs, MEASURE_DEFAULT_START_DELAY);
        assert_eq!(m.gap_ms, MEASURE_DEFAULT_GAP_MS);
    }

    #[test]
    fn measure_fails_closed_when_the_flag_is_absent() {
        let m = measured(r#"{ "targets": [ { "host": "192.0.2.160", "port": 80 } ] }"#);
        assert!(!m.enabled, "absent `enabled` must default to false");

        assert_eq!(m.targets.len(), 1);
    }

    #[test]
    fn start_delay_range_parses_and_tolerates_bad_input() {
        let with = |probe: &str| -> ProbeSettings {
            let cfg = format!(
                r#"{{ "transport": {{ "redsocket": {{
                     "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
                     "cloudfront_domains": ["d1.cloudfront.net"] }} }}, "probe": {} }}"#,
                probe
            );
            load(&cfg, None, "cid0", false, "").unwrap().probe
        };

        assert_eq!(with(r#"{ "start_delay_secs": [5, 15] }"#).start_delay_secs, (5, 15));

        assert_eq!(with(r#"{ "start_delay_secs": [15, 5] }"#).start_delay_secs, (5, 15));

        assert_eq!(with(r#"{ "start_delay_secs": [0, 0] }"#).start_delay_secs, (0, 0));

        for bad in [
            r#"{ "start_delay_secs": [7] }"#,
            r#"{ "start_delay_secs": [] }"#,
            r#"{ "start_delay_secs": "5-15" }"#,
            r#"{ "start_delay_secs": [-1, 9] }"#,
        ] {
            assert_eq!(
                with(bad).start_delay_secs,
                DEFAULT_START_DELAY,
                "malformed {} should fall back to default",
                bad
            );
        }
    }

    /// Prints the real dial order so a human can read it. Ignored: it is a window,
    /// not an assertion.
    #[test]
    #[ignore]
    fn print_dial_order() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        for (label, region, v6, paid) in [("free, auto, v6-capable", None, true, false),
                                          ("free, Frankfurt picked", Some("fra"), true, false),
                                          ("free, Tehran-style, v4 only", None, false, false),
                                          ("PREMIUM, auto, v6-capable", None, true, true)] {
            let cfg = load(SHIPPED, region, "deadbeefdeadbeef", paid, "").unwrap();
            let p = crate::select::plan(&cfg, v6, None, "deadbeefdeadbeef");
            println!("\n--- {label}");
            for (n, &i) in p.order.iter().enumerate().take(30) {
                let e = &cfg.endpoints[i];
                println!("  {:>2}. {:<24} prio={:<3} fallback={}", n + 1, e.host, e.priority, e.fallback);
            }
        }
    }

    #[test]
    fn free_never_sees_premium_regions() {
        let json = r#"{
          "version": "t",
          "transport": { "redsocket": {
            "pubkey": "0000000000000000000000000000000000000000000000000000000000000000",
            "entries": [{ "host": "1.2.3.4", "port": 80 }]
          }},
          "regions": {
            "lax": { "ips": ["pop-a-f-v4.example.net", "pop-a-f-v6.example.net"] },
            "fra": { "ips": ["pop-b-f-v4.example.net", "pop-b-f-v6.example.net"] }
          },
          "premium_regions": {
            "lax": { "ips": ["pop-a-p-v4.example.net", "pop-a-p-v6.example.net"] },
            "fra": { "ips": ["pop-b-p-v4.example.net", "pop-b-p-v6.example.net"] }
          }
        }"#;
        let free = load(json, Some("lax"), "cid-free", false, "America/Los_Angeles").unwrap();
        let order = crate::select::plan(&free, true, None, "cid-free").order;
        assert!(
            order.iter().all(|&i| !free.endpoints[i].host.contains("-p-")),
            "a free client was handed reserved capacity: {:?}",
            order.iter().map(|&i| free.endpoints[i].host.clone()).collect::<Vec<_>>()
        );
        assert!(
            order.iter().any(|&i| free.endpoints[i].host.contains("-f-")),
            "the free pool is missing from a free plan"
        );

        let paid = load(json, Some("lax"), "cid-paid", true, "America/Los_Angeles").unwrap();
        let porder = crate::select::plan(&paid, true, None, "cid-paid").order;
        let first = &paid.endpoints[porder[0]].host;
        assert!(first.contains("pop-a-p-"), "paid must lead on reserved capacity, got {first}");

        assert!(
            porder.iter().any(|&i| paid.endpoints[i].host.contains("-f-")),
            "paid has no free-pool fallback"
        );

        assert!(!first.contains("pop-b-p-"), "paid picked lax and led with fra: {first}");
    }

    /// pop-a-03 and pop-b-06 are reserved for paying users. Two things have to hold
    /// and neither is visible by reading the config.

    #[test]
    fn premium_pool_is_reserved_but_not_a_trap() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");

        let reserved = |h: &str| {
            h.strip_prefix("203.0.113.").and_then(|n| n.parse::<u32>().ok())
                .is_some_and(|n| (171..=176).contains(&n))
            || h.strip_prefix("192.0.2.").and_then(|n| n.parse::<u32>().ok())
                .is_some_and(|n| (40..=90).contains(&n))
            || h.starts_with("2001:db8::14")
            || h.starts_with("2001:db8:5::3")
            || h.contains("-p-v")
        };

        let free = load(SHIPPED, None, "deadbeefdeadbeef", false, "").unwrap();
        for e in &free.endpoints {
            assert!(!reserved(&e.host), "free client was offered reserved capacity: {}", e.host);
        }

        let paid = load(SHIPPED, None, "deadbeefdeadbeef", true, "").unwrap();
        let p = crate::select::plan(&paid, true, None, "deadbeefdeadbeef");
        let first = &paid.endpoints[p.order[0]].host;
        assert!(reserved(first), "premium must start on reserved capacity, got {first}");

        assert!(p.order.iter().any(|&i| !reserved(&paid.endpoints[i].host)),
                "premium has no shared fallback");

        let pos = |n: &str| p.order.iter().position(|&i| paid.endpoints[i].host.contains(n));
        if let (Some(a), Some(b)) = (pos("2001:db8::141"), pos("pop-a-p-v6")) {
            assert!(a < b, "premium literal must be tried before the premium name");
        }
        if let (Some(a), Some(b)) = (pos("pop-a-p-v4"), pos("pop-a-p-v6")) {
            assert!(a < b, "premium v4 name must be tried before the v6 one");
        }
    }

    /// "Auto" must not mean "always Frankfurt".
    #[test]
    fn auto_spreads_across_both_regions() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let mut lax = 0;
        let mut fra = 0;
        for i in 0..400u32 {
            let cid = format!("{:016x}", i.wrapping_mul(0x9E37_79B9));
            let cfg = load(SHIPPED, None, &cid, false, "").unwrap();
            let p1 = crate::select::plan(&cfg, true, None, &cid);
            let first = &cfg.endpoints[p1.order[0]].host;
            let p2 = crate::select::plan(&cfg, true, None, &cid);
            assert_eq!(first, &cfg.endpoints[p2.order[0]].host,
                       "auto must be stable for one install");
            if first.starts_with("203.0.113.") || first.starts_with("2001:db8::") {
                lax += 1;
            } else {
                fra += 1;
            }
        }
        println!("auto first POP over 400 installs: lax={lax} fra={fra}");
        assert!(lax > 40 && fra > 40, "auto favoured one region: lax={lax} fra={fra}");
    }

    /// The CDN fronts are the whole answer to "the direct IPs are blocked", so they
    /// must be reachable. Now a per-endpoint flag rather than an index boundary, so
    /// it survives arbitrary priority/weight reordering.
    #[test]
    fn fallback_tier_is_flagged_and_non_empty() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let cfg = load(SHIPPED, None, "abcd1234abcd1234", false, "").unwrap();
        let fb: Vec<_> = cfg.endpoints.iter().filter(|e| e.fallback).collect();
        let direct: Vec<_> = cfg.endpoints.iter().filter(|e| !e.fallback).collect();
        assert!(!fb.is_empty(), "nothing left to try when the direct IPs are blocked");
        assert!(!direct.is_empty(), "no direct endpoints at all");

        for e in &fb {
            assert!(e.host.parse::<std::net::IpAddr>().is_err(),
                    "{} is an IP literal but flagged fallback", e.host);
            assert!(e.priority >= prio::CDN, "{} fallback must sort after direct", e.host);
        }

        for e in &direct {
            assert!(e.host.parse::<std::net::IpAddr>().is_ok(),
                    "{} needs DNS but is in the direct tier", e.host);
            assert!(e.priority < prio::CDN, "{} direct must sort before fallback", e.host);
        }
    }

    #[test]
    fn russia_dials_ipv4_before_ipv6() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let cfg = load(SHIPPED, None, "deadbeefdeadbeef", false, "Europe/Moscow").unwrap();
        let p = crate::select::plan(&cfg, true, None, "deadbeefdeadbeef");
        let first_v6 = p.order.iter().position(|&i| cfg.endpoints[i].host.contains(':'));
        let first_v4 = p.order.iter().position(|&i| {
            let h = &cfg.endpoints[i].host;
            !h.contains(':') && h.parse::<std::net::Ipv4Addr>().is_ok()
        });
        let (v4, v6) = (first_v4.expect("a v4 literal"), first_v6.expect("a v6 literal"));
        assert!(v4 < v6, "Moscow must reach a v4 literal (pos {v4}) before any v6 (pos {v6})");
        assert!(
            p.order[..v4].iter().all(|&i| cfg.endpoints[i].host.parse::<std::net::IpAddr>().is_err()),
            "only CDN fronts may precede the first v4 literal (pos {v4})"
        );

        let ir = load(SHIPPED, None, "deadbeefdeadbeef", false, "Asia/Tehran").unwrap();
        let pi = crate::select::plan(&ir, true, None, "deadbeefdeadbeef");
        assert!(!ir.endpoints[pi.order[0]].host.contains(':'), "Tehran must still lead with v4");

        let v6_first = serde_json::from_str::<serde_json::Value>(SHIPPED).unwrap()["ipv6_first"]
            .as_bool()
            .unwrap_or(false);
        let de = load(SHIPPED, None, "deadbeefdeadbeef", false, "Europe/Berlin").unwrap();
        let pd = crate::select::plan(&de, true, None, "deadbeefdeadbeef");
        assert_eq!(
            de.endpoints[pd.order[0]].host.contains(':'), v6_first,
            "Berlin must follow the shipped ipv6_first flag ({v6_first}), got {}",
            de.endpoints[pd.order[0]].host
        );
    }

    /// v6 transit is effectively free for us, so v6 leads everywhere EXCEPT the
    /// regions named in `ipv4_first_regions` — Iran, whose mobile networks are
    /// largely v4-only, where a v6 dial is a guaranteed timeout rather than a cheap
    /// win.
    #[test]
    fn ipv6_leads_except_in_ipv4_first_regions() {
        const CFG: &str = r#"{
            "freedom_regions": {"IR": ["Asia/Tehran", "Iran"], "RU": ["Europe/Moscow"]},
            "ipv6_first": true,
            "ipv4_first_regions": ["IR"],
            "transport":{"redsocket":{
              "pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
              "entries":[{"host":"1.1.1.1","port":80}]}},
            "regions":{"fra":{"ips":["9.9.9.9","2001:db8::1"]}}}"#;

        let first_host = |tz: &str| {
            let cfg = load(CFG, None, "abcd1234abcd1234", false, tz).unwrap();
            let p = crate::select::plan(&cfg, true, None, "abcd1234abcd1234");
            cfg.endpoints[p.order[0]].host.clone()
        };

        assert_eq!(first_host("Asia/Tehran"), "1.1.1.1");

        assert!(first_host("Europe/Moscow").contains(':'), "RU should lead v6");
        assert!(first_host("America/Los_Angeles").contains(':'), "US should lead v6");
        assert!(first_host("").contains(':'), "unknown tz should lead v6");

        let cfg = load(CFG, None, "abcd1234abcd1234", false, "America/Los_Angeles").unwrap();
        for e in &cfg.endpoints {
            if e.host.parse::<std::net::IpAddr>().is_err() {
                assert!(e.priority >= prio::CDN, "{} jumped ahead of the literals", e.host);
            } else {
                assert!(e.priority < prio::CDN, "{} fell behind a name", e.host);
            }
        }
    }

    /// The decoy Host must vary across installs — one constant is one DPI rule.
    #[test]
    fn decoy_host_rotates_across_installs_but_is_stable_per_install() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let hosts_for = |cid: &str| {
            let c = load(SHIPPED, None, cid, false, "").unwrap();
            c.endpoints.iter()
                .filter(|e| e.host.parse::<std::net::IpAddr>().is_ok())
                .map(|e| e.upgrade_host.clone()).collect::<Vec<_>>()
        };
        let a = hosts_for("aaaa1111aaaa1111");
        assert_eq!(a, hosts_for("aaaa1111aaaa1111"), "not stable for one install");

        let seen: std::collections::HashSet<String> = (0..40)
            .flat_map(|n| hosts_for(&format!("cid{:013x}", n)))
            .collect();
        assert!(seen.len() > 1, "every install sends the same decoy Host: {:?}", seen);

        let c = load(SHIPPED, None, "aaaa1111aaaa1111", false, "").unwrap();
        for e in c.endpoints.iter().filter(|e| e.host.ends_with(".cloudfront.net")) {
            assert_eq!(e.upgrade_host, e.host, "CDN front lost its own Host");
        }
    }

    /// Weight 0 is a kill switch, and explicit priority overrides the tier default.
    #[test]
    fn priority_and_weight_are_honoured_from_config() {
        const CFG: &str = r#"{"transport":{"redsocket":{
            "pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "entries":[
              {"host":"1.1.1.1","port":80,"priority":99},
              {"host":"2.2.2.2","port":80},
              {"host":"3.3.3.3","port":80,"weight":0}
            ]}}}"#;
        let cfg = load(CFG, None, "abcd1234abcd1234", false, "").unwrap();
        let by = |h: &str| cfg.endpoints.iter().find(|e| e.host == h).unwrap().clone();
        assert_eq!(by("1.1.1.1").priority, 99, "explicit priority ignored");

        assert_eq!(by("2.2.2.2").priority, prio::ENTRY + prio::OTHER_FAMILY,
                   "tier default not applied");
        assert_eq!(by("3.3.3.3").weight, 0);

        let p = crate::select::plan(&cfg, true, None, "abcd1234abcd1234");
        let hosts: Vec<_> = p.order.iter().map(|&i| cfg.endpoints[i].host.clone()).collect();
        assert!(!hosts.contains(&"3.3.3.3".to_string()), "weight 0 must disable");

        assert_eq!(hosts.last().unwrap(), "1.1.1.1");
    }

    /// The REAL shipped config, paid user, both regions.
    #[test]
    fn shipped_config_honours_the_picked_region_for_a_paid_user() {
        let cfg = include_str!("../../brands/configs/rostam.json");
        let v: serde_json::Value = serde_json::from_str(cfg).unwrap();
        let region_hosts = |want: &str| -> Vec<String> {
            let mut out: Vec<String> = Vec::new();
            for pool in ["regions", "premium_regions"] {
                if let Some(ips) = v[pool][want]["ips"].as_array() {
                    out.extend(ips.iter().filter_map(|i| i.as_str()).map(str::to_string));
                }
            }
            if let Some(prem) = v["transport"]["redsocket"]["premium_entries"].as_array() {
                out.extend(
                    prem.iter()
                        .filter(|e| e["region"].as_str() == Some(want))
                        .filter_map(|e| e["host"].as_str())
                        .map(str::to_string),
                );
            }
            out
        };
        for want in ["lax", "fra"] {
            let c = load(cfg, Some(want), "deadbeefdeadbeef", true, "America/Los_Angeles").unwrap();
            let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
            let hosts: Vec<&str> =
                p.order.iter().take(8).map(|&i| c.endpoints[i].host.as_str()).collect();
            let mine = region_hosts(want);
            assert!(!mine.is_empty(), "{want} has no hosts in the shipped config");
            assert!(
                mine.iter().any(|h| h == hosts[0]),
                "picked {want}, first dial {} -- {hosts:?}", hosts[0]
            );
        }
    }

    /// Picking a region must decide the FIRST dial, for a paid user too.
    #[test]
    fn a_picked_region_leads_even_for_a_paid_user() {
        let cfg = serde_json::json!({
            "version": "t",
            "transport": {"redsocket": {
                "pubkey": "0000000000000000000000000000000000000000000000000000000000000000",
                "entries": [{"host": "203.0.113.9", "port": 80}],
                "premium_entries": [
                    {"host": "fra-v6.example.net", "port": 80, "region": "fra"},
                    {"host": "lax-v6.example.net", "port": 80, "region": "lax"}
                ]}},
            "regions": {
                "fra": {"ips": ["fra-v6.example.net", "fra-v4.example.net"]},
                "lax": {"ips": ["lax-v6.example.net", "lax-v4.example.net"]}
            }
        })
        .to_string();

        for (want, other) in [("lax", "fra"), ("fra", "lax")] {
            let c = load(&cfg, Some(want), "cid", true, "America/Los_Angeles").unwrap();

            let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
            let hosts: Vec<&str> = p.order.iter().map(|&i| c.endpoints[i].host.as_str()).collect();
            assert!(
                hosts[0].starts_with(want),
                "picked {want} but the first dial was {} -- full order {hosts:?}",
                hosts[0]
            );

            let other_pos = hosts.iter().position(|h| h.starts_with(other));
            assert!(other_pos.is_some(), "{other} was dropped; it must remain a fallback");
            assert!(other_pos.unwrap() > 0, "{other} preempted the picked region");
        }

        let c = load(&cfg, None, "cid", true, "America/Los_Angeles").unwrap();
        let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
        assert!(
            c.endpoints[p.order[0]].premium,
            "on auto, reserved capacity still leads"
        );
    }

    /// Premium endpoints are paid-only and go first, with the shared pool kept as
    /// fallback so a premium user is never stranded when a premium POP is down.
    #[test]
    fn premium_entries_are_paid_only_and_lead() {
        const PREM: &str = r#"{"transport":{"redsocket":{
            "pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "premium_entries":[{"host":"prem.example.net","port":80}],
            "entries":[{"host":"shared.example.net","port":80}]}}}"#;

        let free = load(PREM, None, "abcd1234abcd1234", false, "").unwrap();
        assert!(
            !free.endpoints.iter().any(|e| e.host.starts_with("prem.")),
            "free tier must never be handed a premium endpoint"
        );

        let paid = load(PREM, None, "abcd1234abcd1234", true, "").unwrap();
        assert_eq!(paid.endpoints[0].host, "prem.example.net", "premium goes first");
        assert!(
            paid.endpoints.iter().any(|e| e.host == "shared.example.net"),
            "shared pool must remain as fallback for premium users"
        );
    }

    /// A config with no `premium_entries` must leave a premium user with exactly
    /// the free list — a no-op, not an empty list. The shipped config carries a
    /// pool now, so this builds its own config rather than relying on that.
    #[test]
    fn absent_premium_entries_falls_back_to_shared_pool() {
        const NO_PREMIUM: &str = r#"{
          "transport": { "redsocket": {
            "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "entries": [ {"host": "203.0.113.120", "port": 80} ] } },
          "regions": { "lax": { "ips": ["203.0.113.188"] } }
        }"#;
        let free = load(NO_PREMIUM, None, "abcd1234abcd1234", false, "").unwrap();
        let paid = load(NO_PREMIUM, None, "abcd1234abcd1234", true, "").unwrap();
        assert!(!paid.endpoints.is_empty(), "premium must never get an empty list");
        assert_eq!(free.endpoints.len(), paid.endpoints.len());
        assert!(paid.endpoints.iter().all(|e| !e.premium));
    }

    #[test]
    fn rejects_bad_pubkey_and_empty_endpoints() {
        assert!(load(r#"{"transport":{"redsocket":{"pubkey":"zz"}}}"#, None, "c", false, "").is_err());
        assert!(load(
            r#"{"transport":{"redsocket":{"pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405"}}}"#,
            None, "c", false, ""
        )
        .is_err());
    }

    /// The whole point: one DPI rule on `/noise` must not match the fleet.
    #[test]
    fn upgrade_paths_spread_across_the_fleet() {
        let paths: Vec<String> = ["/v1/assets/app.js", "/static/css/main.css",
                                  "/api/v2/sync", "/cdn-cgi/trace"]
            .iter().map(|s| s.to_string()).collect();
        let chosen: std::collections::HashSet<String> = (0..400)
            .map(|i| pick_upgrade_path(&paths, &format!("cid{:04x}", i), "1.2.3.4"))
            .collect();
        assert_eq!(chosen.len(), paths.len(), "paths not all reachable: {:?}", chosen);

        let structured = ["aaaa1111aaaa1111", "bbbb2222bbbb2222", "cccc3333cccc3333",
                          "dddd4444dddd4444", "eeee5555eeee5555", "ffff6666ffff6666"];
        let spread: std::collections::HashSet<String> = structured
            .iter().map(|c| pick_upgrade_path(&paths, c, "127.0.0.1")).collect();
        assert!(spread.len() > 1, "low-bit-uniform cids all collapsed to {:?}", spread);
        for c in &chosen {
            assert!(paths.contains(c), "picked a path not in the list: {}", c);
        }
    }

    /// The filter must fail CLOSED on config: no block, a malformed block, or a
    /// block without `enabled` all mean "do not filter anybody's traffic".
    #[test]
    fn blocklist_settings_fail_closed() {
        let none = load(CFG, None, "cid0", false, "").unwrap().blocklist;
        assert!(!none.enabled && none.url.is_empty());

        let with = |bl: &str| -> BlocklistSettings {
            let cfg = format!(
                r#"{{ "transport": {{ "redsocket": {{
                     "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
                     "cloudfront_domains": ["d1.cloudfront.net"] }} }}, "blocklist": {} }}"#,
                bl
            );
            load(&cfg, None, "cid0", false, "").unwrap().blocklist
        };
        assert!(!with(r#"{ "url": "http://x.example/l.json" }"#).enabled,
                "absent `enabled` must default to false");

        assert_eq!(with(r#"{ "enabled": true, "refresh_secs": 1 }"#).refresh_secs, MIN_REFRESH_SECS);
        assert_eq!(with(r#"{ "enabled": true, "refresh_secs": 43200 }"#).refresh_secs, 43_200);
        assert_eq!(with(r#"{ "enabled": true, "max_bytes": 1 }"#).max_bytes, 4096);
        assert_eq!(with(r#"{ "enabled": true }"#).max_bytes, DEFAULT_MAX_BYTES);
    }

    /// The list must never be able to block the channel that corrects it, nor the
    /// cashier the app needs for entitlement: every host the shipped config names
    /// for its control plane (the API base, each fallback front, the health probe)
    /// must be exempt.
    #[test]
    fn never_block_covers_the_apps_own_control_plane() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let bl = load(SHIPPED, None, "abcd1234abcd1234", false, "").unwrap().blocklist;
        assert!(!bl.enabled, "the shipped config must not ship filtering switched on");

        let v: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        let mut expected: Vec<String> = Vec::new();
        expected.extend(v["api"]["base"].as_str().and_then(host_of));
        if let Some(list) = v["api"]["fallback_bases"].as_array() {
            expected.extend(list.iter().filter_map(|b| b.as_str()).filter_map(host_of));
        }
        expected.extend(v["health"]["probe_url"].as_str().and_then(host_of));
        assert!(expected.len() >= 3, "the shipped config names too few control-plane hosts: {expected:?}");
        for h in &expected {
            assert!(
                bl.never_block.iter().any(|n| n == h),
                "{h} missing from never_block: {:?}",
                bl.never_block
            );
        }
    }

    #[test]
    fn host_of_handles_the_shapes_a_config_can_carry() {
        assert_eq!(host_of("https://rostam.app").as_deref(), Some("rostam.app"));
        assert_eq!(host_of("http://x.example:8080/a/b").as_deref(), Some("x.example"));
        assert_eq!(host_of("https://[2001:db8::1]:443/x").as_deref(), Some("2001:db8::1"));
        assert_eq!(host_of("EXAMPLE.com/x").as_deref(), Some("example.com"));
        assert_eq!(host_of(""), None);
    }

    /// Same power-of-two hazard as the path picker.
    #[test]
    fn upgrade_hosts_spread_across_the_fleet() {
        let hosts: Vec<String> = ["a.example", "b.example", "c.example", "d.example"]
            .iter().map(|s| s.to_string()).collect();
        let chosen: std::collections::HashSet<String> = (0..400)
            .map(|i| pick_upgrade_host(&hosts, &format!("cid{:04x}", i), "1.2.3.4"))
            .collect();
        assert_eq!(chosen.len(), hosts.len(), "hosts not all reachable: {:?}", chosen);

        let structured = ["aaaa1111aaaa1111", "bbbb2222bbbb2222", "cccc3333cccc3333",
                          "dddd4444dddd4444", "eeee5555eeee5555", "ffff6666ffff6666"];
        let spread: std::collections::HashSet<String> = structured
            .iter().map(|c| pick_upgrade_host(&hosts, c, "127.0.0.1")).collect();
        assert!(spread.len() > 1, "low-bit-uniform cids all collapsed to {:?}", spread);
    }

    /// A device must not change path between reconnects — support logs stay
    /// reproducible, and a flapping path is itself a signal.
    #[test]
    fn upgrade_path_is_stable_per_device() {
        let paths = vec!["/a".to_string(), "/b".to_string(), "/c".to_string()];
        let first = pick_upgrade_path(&paths, "deadbeefdeadbeef", "1.2.3.4");
        for _ in 0..5 {
            assert_eq!(pick_upgrade_path(&paths, "deadbeefdeadbeef", "1.2.3.4"), first);
        }
    }

    /// Host and path are keyed on the same cid, so they must be salted apart or the
    /// pair leaks that one index drives both.
    #[test]
    fn host_and_path_choices_are_independent() {
        let three: Vec<String> = ["/0", "/1", "/2"].iter().map(|s| s.to_string()).collect();
        let hosts: Vec<String> = ["h0", "h1", "h2"].iter().map(|s| s.to_string()).collect();
        let mut differed = 0;
        for i in 0..200 {
            let cid = format!("cid{:04x}", i);
            let hp = pick_upgrade_host(&hosts, &cid, "e");
            let pp = pick_upgrade_path(&three, &cid, "e");
            if hp[1..] != pp[1..] { differed += 1; }
        }
        assert!(differed > 40, "path index tracks host index ({differed}/200 differed)");
    }

    /// OTA config is operator-controlled, but the no-identifier-in-URL invariant is
    /// load-bearing: reject anything that could carry data.
    #[test]
    fn malformed_paths_fall_back_to_default() {
        for bad in ["noise", "/x?cid=abc", "/x#frag", "/has space", ""] {
            let got = pick_upgrade_path(&[bad.to_string()], "cid", "e");
            assert_eq!(got, DEFAULT_UPGRADE_PATH, "accepted bad path {bad:?}");
        }

        let mixed = vec!["/x?cid=abc".to_string(), "/good".to_string()];
        assert_eq!(pick_upgrade_path(&mixed, "cid", "e"), "/good");
    }
}

#[cfg(test)]
mod mux_config_tests {
    use super::*;

    /// The shipped config must actually turn MUX2 on.
    #[test]
    fn the_shipped_config_enables_mux() {
        let raw = include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let cfg = load(raw, None, "abcd1234abcd1234", false, "").expect("shipped config must parse");
        assert!(cfg.mux, "transport.redsocket.mux must be true in the shipped config");
    }

    /// Absent or non-boolean must be OFF, never on.
    #[test]
    fn mux_fails_closed() {
        let base = r#"{"transport":{"redsocket":{"pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405","entries":[{"host":"1.2.3.4","port":80}]"#;
        for tail in [r#"}}}"#, r#","mux":"yes"}}}"#, r#","mux":1}}}"#, r#","mux":false}}}"#] {
            let cfg = load(&format!("{base}{tail}"), None, "abcd1234abcd1234", false, "")
                .expect("parses");
            assert!(!cfg.mux, "mux must be off for tail {tail}");
        }
        let cfg = load(&format!("{base}{}", r#","mux":true}}}"#), None, "abcd1234abcd1234", false, "")
            .expect("parses");
        assert!(cfg.mux, "an explicit true must switch it on");
    }
}

/// The OTA config (`brands/configs/rostam.json`), held to the SAME bar as the
/// bundled asset.
#[cfg(test)]
mod brand_config_tests {
    use super::*;
    use std::path::PathBuf;

    fn configs_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../brands/configs")
    }

    /// `(filename, contents)` for every config under `brands/configs/`.
    fn brand_configs() -> Vec<(String, String)> {
        let dir = configs_dir();
        let mut out: Vec<(String, String)> = std::fs::read_dir(&dir)
            .unwrap_or_else(|e| panic!("brands/configs is unreadable at {}: {e}", dir.display()))
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("json"))
            .map(|p| {
                let name = p.file_name().unwrap().to_string_lossy().into_owned();
                let raw = std::fs::read_to_string(&p)
                    .unwrap_or_else(|e| panic!("{name} unreadable: {e}"));
                (name, raw)
            })
            .collect();
        out.sort();
        assert!(
            !out.is_empty(),
            "expected the OTA config in {} — did the directory move?",
            dir.display()
        );
        out
    }

    /// Recursive hunt for `_`-prefixed keys. `tools/config/strip-notes.py` moves
    /// them to a `.notes.md` sidecar because the config ships sealed to ~750K
    /// devices on metered, censored links.
    fn note_keys(v: &serde_json::Value, path: &str, out: &mut Vec<String>) {
        match v {
            serde_json::Value::Object(m) => {
                for (k, val) in m {
                    let here = if path.is_empty() { k.clone() } else { format!("{path}.{k}") };
                    if k.starts_with('_') {
                        out.push(here.clone());
                    }
                    note_keys(val, &here, out);
                }
            }
            serde_json::Value::Array(a) => {
                for (i, val) in a.iter().enumerate() {
                    note_keys(val, &format!("{path}[{i}]"), out);
                }
            }
            _ => {}
        }
    }

    /// The invariants a config must satisfy before it is safe to serve to a fleet.
    /// Applied to the brand configs AND to the bundled asset, so the two are held
    /// to one standard instead of drifting into two.
    fn assert_shippable(name: &str, raw: &str) {
        let cfg = load(raw, None, "abcd1234abcd1234", false, "")
            .unwrap_or_else(|e| panic!("{name}: must parse with the client loader: {e}"));

        assert!(
            cfg.endpoints.len() >= 10,
            "{name}: suspiciously few endpoints ({})",
            cfg.endpoints.len()
        );

        assert!(
            cfg.endpoints[0].host.parse::<std::net::IpAddr>().is_ok(),
            "{name}: first endpoint must be an IP literal, got {}",
            cfg.endpoints[0].host
        );

        let named: Vec<usize> = cfg.endpoints.iter().enumerate()
            .filter(|(_, e)| e.host.parse::<std::net::IpAddr>().is_err()
                && !e.host.ends_with(".cloudfront.net"))
            .map(|(i, _)| i)
            .collect();
        if let Some(&first) = named.first() {
            assert_eq!(
                first,
                cfg.endpoints.len() - named.len(),
                "{name}: named endpoints must occupy the tail; {} is at {first} of {}",
                cfg.endpoints[first].host, cfg.endpoints.len()
            );
        }

        let p = crate::select::plan(&cfg, true, None, "deadbeefdeadbeef");
        let pos = |needle: &str| p.order.iter().position(|&i| cfg.endpoints[i].host.contains(needle));

        if let (Some(a), Some(b)) = (pos("-v4."), pos("-v6.")) {
            assert!(a < b, "{name}: v4 region name must be dialled before the v6 one ({a} vs {b})");
        }

        let first_v6 = p.order.iter().position(|&i| cfg.endpoints[i].host.contains(':'));
        let first_v4 = p.order.iter().position(|&i| {
            cfg.endpoints[i].host.parse::<std::net::Ipv4Addr>().is_ok()
        });
        let first_cdn = p.order.iter().position(|&i| cfg.endpoints[i].host.ends_with(".cloudfront.net"));
        if let (Some(a), Some(b)) = (first_v4, first_v6) {
            assert!(a < b, "{name}: v4 literals must be dialled before v6 ({a} vs {b})");
        }
        if let (Some(b), Some(c)) = (first_v6, first_cdn) {
            assert!(b < c, "{name}: v6 literals must be dialled before the CDN fronts");
        }

        let mut seen = std::collections::HashSet::new();
        for &i in &p.order {
            let e = &cfg.endpoints[i];
            assert!(seen.insert((e.host.as_str(), e.port)),
                    "{name}: {} is in the dial order twice", e.host);
        }

        for e in cfg.endpoints.iter().filter(|e| e.host.ends_with(".cloudfront.net")) {
            assert_eq!(e.upgrade_host, e.host, "{name}: CDN front {} needs its own Host", e.host);
        }

        assert!(
            cfg.endpoints.iter().any(|e| e.fallback),
            "{name}: no fallback tier — nothing to try when the direct IPs are blocked"
        );

        assert!(cfg.mux, "{name}: transport.redsocket.mux must be true");

        if cfg.probe.enabled {
            assert!(!cfg.probe.gateway_host.is_empty(), "{name}: probe enabled with no gateway_host");
        }

        let v: serde_json::Value = serde_json::from_str(raw).expect("valid json");

        let mut notes = Vec::new();
        note_keys(&v, "", &mut notes);
        assert!(
            notes.is_empty(),
            "{name}: note keys must live in the .notes.md sidecar, not the shipped JSON: {notes:?}"
        );

        if let Some(regions) = v.get("regions").and_then(|r| r.as_object()) {
            for r in regions.keys() {
                let scoped = load(raw, Some(r), "abcd1234abcd1234", false, "")
                    .unwrap_or_else(|e| panic!("{name}: region {r} must load: {e}"));
                assert!(!scoped.endpoints.is_empty(), "{name}: region {r} has no endpoints");
                assert!(
                    scoped.endpoints[0].host.parse::<std::net::IpAddr>().is_ok(),
                    "{name}: region {r} led with a name ({}), not its own IP",
                    scoped.endpoints[0].host
                );
            }
        }

        let freedom = v
            .get("freedom_regions")
            .and_then(|f| f.as_object())
            .unwrap_or_else(|| panic!("{name}: no freedom_regions block"));
        for iso in ["IR", "RU", "CU", "SY"] {
            let tzs = freedom
                .get(iso)
                .and_then(|t| t.as_array())
                .unwrap_or_else(|| panic!("{name}: freedom_regions is missing {iso}"));
            assert!(!tzs.is_empty(), "{name}: freedom region {iso} has no timezones");
        }

    }

    /// The fleet config parses and satisfies the shipping invariants.
    #[test]
    fn the_brand_config_is_shippable() {
        for (name, raw) in brand_configs() {
            assert_shippable(&name, &raw);
        }
    }

    /// The bundled asset is a config too, and must clear the same bar. If this and
    /// the brand test ever disagree, one of them is asserting the wrong thing — not
    /// two different standards.
    #[test]
    fn the_bundled_asset_clears_the_same_bar() {
        assert_shippable(
            "default-config.json",
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json"),
        );
    }

    /// The OTA config must not fall behind the bundled asset's schema.
    #[test]
    fn the_brand_config_is_not_missing_a_block_the_shipped_asset_has() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let shipped: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();

        /// Paths present in `a` but absent in `b`, to a bounded depth — deep enough to
        /// catch a whole block, shallow enough that per-endpoint differences (which are
        /// legitimately per-brand) are not flagged.
        fn missing(a: &serde_json::Value, b: &serde_json::Value, path: &str, depth: u8, out: &mut Vec<String>) {
            let (Some(am), Some(bm)) = (a.as_object(), b.as_object()) else { return };
            for (k, av) in am {
                let here = if path.is_empty() { k.clone() } else { format!("{path}.{k}") };
                match bm.get(k) {
                    None => out.push(here),
                    Some(bv) if depth > 0 => missing(av, bv, &here, depth - 1, out),
                    _ => {}
                }
            }
        }

        for (name, raw) in brand_configs() {
            let brand: serde_json::Value = serde_json::from_str(&raw).unwrap();
            let mut gaps = Vec::new();
            missing(&shipped, &brand, "", 2, &mut gaps);
            assert!(
                gaps.is_empty(),
                "{name} is behind the bundled asset — publishing it would drop {gaps:?}. \
                 Regenerate the config, or delete the key from the asset if it is dead."
            );
        }
    }
}
