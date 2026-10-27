//! The regional address map ("geo cache"): every IPv4 and IPv6 prefix with
//! its country, shipped to the phone once and refreshed weekly, so a country
//! or region profile is decided here by country code instead of by a
//! per-country list body.
//!
//! The reader half (constants, header, `GeoMap`, `lookup`) is
//! **mirrored from redshield/crates/core/src/geo.rs** (the server's
//! reference reader) and must stay textually identical to it: the body is
//! written there and read here. The writer (`prefixes_from_ranges`,
//! `coarsen`, `encode`, `build`) stays on the server; the tests below carry
//! a minimal encoder for the round trip only. `anyhow` is not a dependency
//! of this crate, so `Result`/`bail!` are the local one-line equivalents.
//!
//! Body (little-endian, before gzip), 32-byte header:
//! ```text
//!   0  magic   [u8;6]  "RSGEO1"
//!   6  version u16     1
//!   8  built   u32     build date as unix days
//!  12  v4_off  u32     byte offset of the v4 section (= 32)
//!  16  v4_n    u32     v4 record count
//!  20  v6_off  u32     byte offset of the v6 section
//!  24  v6_n    u32     v6 record count
//!  28  flags   u32     bit 0: coarsened (lossy coalescing applied)
//! ```
//! v4 section: `v4_n × {addr u32, len u8, cc [u8;2]}` (7 B); v6 section:
//! `v6_n × {addr [u8;16], len u8, cc [u8;2]}` (19 B); both sorted by
//! (addr, len), prefixes **non-overlapping** (the writer refuses overlap), so
//! a lookup is one binary search for the last record whose address is <= the
//! query plus a containment check: O(log n), no allocation. `cc` is two
//! uppercase ASCII letters.
//!
//! # What it can and cannot classify
//!
//! The core sees a hostname for almost every CONNECT (the app routes DNS
//! through mapdns and the exit resolves the name), so the map answers for
//! **literal-IP destinations only** — exactly the scope the `ranges` profile
//! kind already had. Hostnames keep their country from the reputation score,
//! which the app applies through the automatic rules as before.
//!
//! # Memory
//!
//! The table is kept as the raw section bytes (7 B / 19 B per record) and
//! searched in place: a full map is a few megabytes, nothing is unpacked into
//! hash maps, and the Network Extension's memory ceiling is not approached.
//! Installed once per process (not dropped on `rs_stop`: a reconnect must not
//! re-parse), replaced atomically, and read through an `Arc` snapshot.

use std::net::IpAddr;
use std::sync::{Arc, RwLock};

type Result<T> = std::result::Result<T, String>;
macro_rules! bail {
    ($($arg:tt)*) => { return Err(format!($($arg)*)) };
}

pub const MAGIC: &[u8; 6] = b"RSGEO1";
pub const VERSION: u16 = 1;
pub const HEADER: usize = 32;
pub const V4_REC: usize = 7;
pub const V6_REC: usize = 19;
pub const FLAG_COARSENED: u32 = 1;

// ---------------------------------------------------------------- reader (mirrored)

pub struct GeoMap<'a> {
    v4: &'a [u8],
    v6: &'a [u8],
    pub built_day: u32,
    pub flags: u32,
}

fn rd_u32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}

impl<'a> GeoMap<'a> {
    /// Parse a decompressed body; borrows it (no copy).
    pub fn load(body: &'a [u8]) -> Result<GeoMap<'a>> {
        if body.len() < HEADER || &body[..6] != MAGIC {
            bail!("geo: bad magic");
        }
        if u16::from_le_bytes([body[6], body[7]]) != VERSION {
            bail!("geo: unsupported version");
        }
        let (v4_off, v4_n, v6_off, v6_n) = (rd_u32(body, 12) as usize, rd_u32(body, 16) as usize, rd_u32(body, 20) as usize, rd_u32(body, 24) as usize);
        if v4_off + v4_n * V4_REC > body.len() || v6_off + v6_n * V6_REC > body.len() {
            bail!("geo: truncated");
        }
        Ok(GeoMap { v4: &body[v4_off..v4_off + v4_n * V4_REC], v6: &body[v6_off..v6_off + v6_n * V6_REC], built_day: rd_u32(body, 8), flags: rd_u32(body, 28) })
    }

    pub fn counts(&self) -> (usize, usize) {
        (self.v4.len() / V4_REC, self.v6.len() / V6_REC)
    }

    fn v4_at(&self, i: usize) -> (u32, u8, [u8; 2]) {
        let o = i * V4_REC;
        (rd_u32(self.v4, o), self.v4[o + 4], [self.v4[o + 5], self.v4[o + 6]])
    }

    fn v6_at(&self, i: usize) -> (u128, u8, [u8; 2]) {
        let o = i * V6_REC;
        (u128::from_le_bytes(self.v6[o..o + 16].try_into().unwrap()), self.v6[o + 16], [self.v6[o + 17], self.v6[o + 18]])
    }

    /// Country of the prefix containing `ip`, if any. One binary search + a containment check
    /// (the prefixes are non-overlapping by construction).
    pub fn lookup(&self, ip: IpAddr) -> Option<[u8; 2]> {
        match ip {
            IpAddr::V4(a) => {
                let q = u32::from(a);
                let n = self.v4.len() / V4_REC;
                // last record with addr <= q
                let (mut lo, mut hi) = (0usize, n);
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    if self.v4_at(mid).0 <= q { lo = mid + 1 } else { hi = mid }
                }
                if lo == 0 {
                    return None;
                }
                let (addr, len, cc) = self.v4_at(lo - 1);
                let mask = if len == 0 { 0 } else { u32::MAX << (32 - len as u32) };
                (q & mask == addr).then_some(cc)
            }
            IpAddr::V6(a) => {
                let q = u128::from(a);
                let n = self.v6.len() / V6_REC;
                let (mut lo, mut hi) = (0usize, n);
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    if self.v6_at(mid).0 <= q { lo = mid + 1 } else { hi = mid }
                }
                if lo == 0 {
                    return None;
                }
                let (addr, len, cc) = self.v6_at(lo - 1);
                let mask = if len == 0 { 0 } else { u128::MAX << (128 - len as u32) };
                (q & mask == addr).then_some(cc)
            }
        }
    }

    pub fn lookup_str(&self, ip: IpAddr) -> Option<String> {
        self.lookup(ip).map(|cc| String::from_utf8_lossy(&cc).into_owned())
    }
}

// ---------------------------------------------------------------- phone side: the installed table

/// Ceiling on an installed body. A full map is a few MB; this bounds what a
/// wrong or hostile download can make the tunnel process hold.
const MAX_BODY: usize = 64 << 20;

/// The installed body. `GeoMap` borrows it per lookup; the header fields are
/// copied out once so `info` needs no re-parse.
struct Table {
    body: Vec<u8>,
    built_day: u32,
    flags: u32,
    n4: usize,
    n6: usize,
}

static GEO: RwLock<Option<Arc<Table>>> = RwLock::new(None);

fn snapshot() -> Option<Arc<Table>> {
    GEO.read().ok().and_then(|g| g.clone())
}

/// Validate beyond what the reader checks: lengths in range, canonical
/// addresses, uppercase ASCII cc, strictly ascending and non-overlapping.
/// O(n) once per install; a body failing it is refused whole.
fn validate(m: &GeoMap<'_>) -> Result<()> {
    let (n4, n6) = m.counts();
    let mut prev_end: Option<u32> = None;
    for i in 0..n4 {
        let (addr, len, cc) = m.v4_at(i);
        if len > 32 {
            bail!("geo: v4 record {i}: bad length {len}");
        }
        let span = if len == 0 { u32::MAX } else { (1u32 << (32 - len as u32)) - 1 };
        if addr & span != 0 {
            bail!("geo: v4 record {i}: not canonical");
        }
        if !cc.iter().all(|c| c.is_ascii_uppercase()) {
            bail!("geo: v4 record {i}: bad cc");
        }
        if let Some(e) = prev_end {
            if addr <= e {
                bail!("geo: v4 record {i}: unsorted or overlapping");
            }
        }
        prev_end = Some(addr | span);
    }
    let mut prev_end: Option<u128> = None;
    for i in 0..n6 {
        let (addr, len, cc) = m.v6_at(i);
        if len > 128 {
            bail!("geo: v6 record {i}: bad length {len}");
        }
        let span = if len == 0 { u128::MAX } else { (1u128 << (128 - len as u32)) - 1 };
        if addr & span != 0 {
            bail!("geo: v6 record {i}: not canonical");
        }
        if !cc.iter().all(|c| c.is_ascii_uppercase()) {
            bail!("geo: v6 record {i}: bad cc");
        }
        if let Some(e) = prev_end {
            if addr <= e {
                bail!("geo: v6 record {i}: unsorted or overlapping");
            }
        }
        prev_end = Some(addr | span);
    }
    Ok(())
}

/// Install a decompressed body, replacing the previous map atomically. The
/// bytes are copied (the caller frees its buffer); a body that fails any
/// check leaves the previous map in force. Returns (v4, v6) record counts.
pub fn install(bytes: &[u8]) -> Result<(usize, usize)> {
    if bytes.len() > MAX_BODY {
        bail!("geo: body too large ({} bytes)", bytes.len());
    }
    let m = GeoMap::load(bytes)?;
    validate(&m)?;
    let (n4, n6) = m.counts();
    if n4 + n6 == 0 {
        bail!("geo: empty");
    }
    let t = Table { body: bytes.to_vec(), built_day: m.built_day, flags: m.flags, n4, n6 };
    if let Ok(mut g) = GEO.write() {
        *g = Some(Arc::new(t));
    }
    log::info!("[geo] installed: day {} v4 {n4} v6 {n6}", m.built_day);
    Ok((n4, n6))
}

/// Drop the map. Country and region profiles fall back to their list bodies.
pub fn clear() {
    if let Ok(mut g) = GEO.write() {
        *g = None;
    }
}

pub fn is_loaded() -> bool {
    snapshot().is_some()
}

/// Country of a literal IP destination; `None` for a hostname, an address
/// outside the map, or no map. A v4-mapped v6 literal is looked up as v4.
pub fn lookup_host(host: &str) -> Option<[u8; 2]> {
    let t = snapshot()?;
    let h = host.trim().trim_end_matches('.');
    let h = h.strip_prefix('[').map(|s| s.trim_end_matches(']')).unwrap_or(h);
    let ip: IpAddr = h.parse().ok()?;
    let ip = match ip {
        IpAddr::V6(a) => a.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(IpAddr::V6(a)),
        v4 => v4,
    };
    // Loaded and validated at install: cannot fail here.
    GeoMap::load(&t.body).ok()?.lookup(ip)
}

/// Unix days -> (year, month, day). Howard Hinnant's civil_from_days.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// `YYYY-MM-DD` for a build day.
pub fn day_string(day: u32) -> String {
    let (y, m, d) = civil_from_days(day as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

/// `{"loaded":bool,"built_day":N,"date":"YYYY-MM-DD","v4":N,"v6":N,
/// "ranges":N,"coarsened":bool,"bytes":N}` for the UI's one line.
pub fn info_json() -> String {
    match snapshot() {
        Some(t) => serde_json::json!({
            "loaded": true,
            "built_day": t.built_day,
            "date": day_string(t.built_day),
            "v4": t.n4,
            "v6": t.n6,
            "ranges": t.n4 + t.n6,
            "coarsened": t.flags & FLAG_COARSENED != 0,
            "bytes": t.body.len(),
        })
        .to_string(),
        None => r#"{"loaded":false}"#.to_string(),
    }
}

// ---------------------------------------------------------------- regions

/// Region id -> member countries, mirroring the `[[region]]` entries of
/// redshield/crates/core/data/sources.toml. The catalogue entry carries the
/// same list (`countries`), which the app prefers; this table is the fallback
/// when a region is enabled with no catalogue copy at hand.
pub fn region_countries(id: &str) -> Option<&'static [&'static str]> {
    Some(match id.trim().trim_start_matches("region:") {
        "eu" => &["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"],
        "china" => &["CN", "HK", "MO"],
        "russia" => &["RU", "BY"],
        "five-eyes" => &["US", "GB", "CA", "AU", "NZ"],
        "middle-east" => &["AE", "BH", "EG", "IL", "IQ", "IR", "JO", "KW", "LB", "OM", "PS", "QA", "SA", "SY", "TR", "YE"],
        "africa" => &["DZ", "AO", "BJ", "BW", "BF", "BI", "CM", "CV", "CF", "TD", "KM", "CG", "CD", "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET", "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG", "MW", "ML", "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW", "ST", "SN", "SC", "SL", "SO", "ZA", "SS", "SD", "TZ", "TG", "TN", "UG", "ZM", "ZW", "EH", "RE", "YT", "SH"],
        "latam" => &["AR", "BO", "BR", "CL", "CO", "CR", "CU", "DO", "EC", "SV", "GT", "HT", "HN", "MX", "NI", "PA", "PY", "PE", "PR", "UY", "VE", "BZ", "GY", "SR", "JM", "TT", "BS", "BB", "AG", "AW", "BQ", "CW", "DM", "GD", "GP", "KN", "KY", "LC", "MF", "MQ", "MS", "SX", "TC", "VC", "VG", "VI", "AI", "BL", "GF", "FK"],
        "apac" => &["AU", "BD", "BN", "BT", "KH", "CN", "FJ", "HK", "ID", "IN", "JP", "KR", "LA", "LK", "MO", "MV", "MN", "MM", "MY", "NP", "NZ", "PG", "PH", "PK", "SG", "TW", "TH", "TL", "VN", "KP", "WS", "TO", "VU", "SB", "FM", "KI", "PW", "MH", "NR", "TV", "NC", "PF", "GU", "MP", "AS", "CK", "NU", "NF"],
        "sanctioned" => &["IR", "KP", "SY", "CU", "RU", "BY"],
        _ => return None,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Test-only encoder, the shape `encode` on the server writes.
    pub(crate) fn encode(built_day: u32, v4: &[(u32, u8, [u8; 2])], v6: &[(u128, u8, [u8; 2])], coarsened: bool) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER + v4.len() * V4_REC + v6.len() * V6_REC);
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        out.extend_from_slice(&built_day.to_le_bytes());
        out.extend_from_slice(&(HEADER as u32).to_le_bytes());
        out.extend_from_slice(&(v4.len() as u32).to_le_bytes());
        out.extend_from_slice(&((HEADER + v4.len() * V4_REC) as u32).to_le_bytes());
        out.extend_from_slice(&(v6.len() as u32).to_le_bytes());
        out.extend_from_slice(&(if coarsened { FLAG_COARSENED } else { 0 }).to_le_bytes());
        for (addr, len, cc) in v4 {
            out.extend_from_slice(&addr.to_le_bytes());
            out.push(*len);
            out.extend_from_slice(cc);
        }
        for (addr, len, cc) in v6 {
            out.extend_from_slice(&addr.to_le_bytes());
            out.push(*len);
            out.extend_from_slice(cc);
        }
        out
    }

    pub(crate) fn ip4(s: &str) -> u32 {
        u32::from(s.parse::<std::net::Ipv4Addr>().unwrap())
    }
    pub(crate) fn ip6(s: &str) -> u128 {
        u128::from(s.parse::<std::net::Ipv6Addr>().unwrap())
    }

    /// A small map: 1.0.0.0/22 AU, 10.0.0.0/23 DE, 10.0.2.0/25 FR,
    /// 2001:db8::/32 NL, 2a00::/48 SE. Non-overlapping, sorted.
    pub(crate) fn sample() -> Vec<u8> {
        encode(
            20_751,
            &[(ip4("1.0.0.0"), 22, *b"AU"), (ip4("10.0.0.0"), 23, *b"DE"), (ip4("10.0.2.0"), 25, *b"FR")],
            &[(ip6("2001:db8::"), 32, *b"NL"), (ip6("2a00::"), 48, *b"SE")],
            false,
        )
    }

    #[test]
    fn roundtrip_and_lookup() {
        let body = sample();
        assert_eq!(body.len(), HEADER + 3 * V4_REC + 2 * V6_REC);
        let g = GeoMap::load(&body).unwrap();
        assert_eq!(g.counts(), (3, 2));
        assert_eq!(g.built_day, 20_751);
        assert_eq!(g.lookup_str("10.0.1.77".parse().unwrap()).as_deref(), Some("DE"));
        assert_eq!(g.lookup_str("10.0.2.5".parse().unwrap()).as_deref(), Some("FR"));
        assert_eq!(g.lookup("10.0.2.200".parse().unwrap()), None, "beyond the /25");
        assert_eq!(g.lookup("10.0.3.1".parse().unwrap()), None);
        assert_eq!(g.lookup("0.0.0.1".parse().unwrap()), None, "before the first record");
        assert_eq!(g.lookup_str("1.0.2.9".parse().unwrap()).as_deref(), Some("AU"));
        assert_eq!(g.lookup_str("1.0.3.255".parse().unwrap()).as_deref(), Some("AU"), "last address of the /22");
        assert_eq!(g.lookup("1.0.4.0".parse().unwrap()), None, "first address after it");
        assert_eq!(g.lookup_str("2001:db8::1".parse().unwrap()).as_deref(), Some("NL"));
        assert_eq!(g.lookup_str("2a00::ffff".parse().unwrap()).as_deref(), Some("SE"));
        assert_eq!(g.lookup("2a00:1::".parse().unwrap()), None);
        assert_eq!(g.lookup("::1".parse().unwrap()), None);
        assert!(GeoMap::load(&body[..20]).is_err());
        assert!(GeoMap::load(b"nope").is_err());
    }

    #[test]
    fn longest_prefix_is_the_record_itself() {
        // The writer never nests, so "longest prefix" means: the one record
        // whose span holds the address, however small it is next to its
        // neighbours. A /32 between two wide blocks is found exactly.
        let body = encode(
            1,
            &[(ip4("20.0.0.0"), 8, *b"US"), (ip4("21.0.0.0"), 32, *b"CH"), (ip4("21.0.0.1"), 32, *b"IT"), (ip4("21.0.0.2"), 31, *b"US")],
            &[],
            false,
        );
        let g = GeoMap::load(&body).unwrap();
        assert_eq!(g.lookup_str("20.255.255.255".parse().unwrap()).as_deref(), Some("US"));
        assert_eq!(g.lookup_str("21.0.0.0".parse().unwrap()).as_deref(), Some("CH"));
        assert_eq!(g.lookup_str("21.0.0.1".parse().unwrap()).as_deref(), Some("IT"));
        assert_eq!(g.lookup_str("21.0.0.3".parse().unwrap()).as_deref(), Some("US"));
        assert_eq!(g.lookup("21.0.0.4".parse().unwrap()), None);
    }

    #[test]
    fn install_validates_and_keeps_the_previous_map() {
        let _g = crate::blocklist::TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        clear();
        assert!(!is_loaded());
        assert_eq!(lookup_host("10.0.1.1"), None, "no map, no answer");
        assert_eq!(install(&sample()).unwrap(), (3, 2));
        assert!(is_loaded());
        assert_eq!(lookup_host("10.0.1.1"), Some(*b"DE"));
        assert_eq!(lookup_host("[2001:db8::9]"), Some(*b"NL"));
        assert_eq!(lookup_host("::ffff:10.0.2.1"), Some(*b"FR"), "v4-mapped looks up as v4");
        assert_eq!(lookup_host("example.com"), None, "a hostname is never classified here");
        assert_eq!(lookup_host("8.8.8.8"), None);

        // Every refusal leaves the sample in force.
        let mut bad_magic = sample();
        bad_magic[0] = b'X';
        assert!(install(&bad_magic).is_err());
        let mut bad_version = sample();
        bad_version[6] = 2;
        assert!(install(&bad_version).is_err());
        let mut over_count = sample();
        over_count[16..20].copy_from_slice(&1_000u32.to_le_bytes());
        assert!(install(&over_count).unwrap_err().contains("truncated"));
        assert!(install(&sample()[..HEADER + 10]).is_err());
        let unsorted = encode(1, &[(ip4("10.0.0.0"), 24, *b"DE"), (ip4("9.0.0.0"), 24, *b"FR")], &[], false);
        assert!(install(&unsorted).unwrap_err().contains("unsorted"));
        let overlapping = encode(1, &[(ip4("10.0.0.0"), 8, *b"DE"), (ip4("10.1.0.0"), 16, *b"FR")], &[], false);
        assert!(install(&overlapping).unwrap_err().contains("overlapping"));
        let lowercase = encode(1, &[(ip4("10.0.0.0"), 24, *b"de")], &[], false);
        assert!(install(&lowercase).unwrap_err().contains("bad cc"));
        let bad_len = encode(1, &[(ip4("10.0.0.0"), 33, *b"DE")], &[], false);
        assert!(install(&bad_len).unwrap_err().contains("bad length"));
        let not_canonical = encode(1, &[(ip4("10.0.0.1"), 24, *b"DE")], &[], false);
        assert!(install(&not_canonical).unwrap_err().contains("canonical"));
        let bad_v6 = encode(1, &[], &[(ip6("2001:db8::"), 129, *b"NL")], false);
        assert!(install(&bad_v6).is_err());
        assert!(install(&encode(1, &[], &[], false)).unwrap_err().contains("empty"));
        assert_eq!(lookup_host("10.0.1.1"), Some(*b"DE"), "the sample survived every refusal");

        // A reload swaps the whole map.
        let next = encode(20_752, &[(ip4("10.0.0.0"), 24, *b"NO")], &[], true);
        assert_eq!(install(&next).unwrap(), (1, 0));
        assert_eq!(lookup_host("10.0.0.9"), Some(*b"NO"));
        assert_eq!(lookup_host("10.0.1.1"), None, "the old /23 is gone");
        let info: serde_json::Value = serde_json::from_str(&info_json()).unwrap();
        assert_eq!(info["loaded"], true);
        assert_eq!(info["built_day"], 20_752);
        assert_eq!(info["date"], "2026-10-26");
        assert_eq!(info["ranges"], 1);
        assert_eq!(info["coarsened"], true);
        clear();
        assert_eq!(info_json(), r#"{"loaded":false}"#);
    }

    #[test]
    fn days_to_dates() {
        assert_eq!(day_string(0), "1970-01-01");
        assert_eq!(day_string(19_723), "2024-01-01");
        assert_eq!(day_string(20_751), "2026-10-25");
        assert_eq!(day_string(11_016), "2000-02-29");
    }

    #[test]
    fn regions_mirror_the_catalogue_table() {
        assert_eq!(region_countries("eu").map(|c| c.len()), Some(27));
        assert_eq!(region_countries("region:china"), Some(&["CN", "HK", "MO"][..]));
        assert!(region_countries("sanctioned").unwrap().contains(&"IR"));
        assert_eq!(region_countries("mars"), None);
    }
}
