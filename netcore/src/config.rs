//! Endpoint list for the http-only client.
//!
//! Reads the same `default-config.json` the app already ships (no schema change),
//! but only extracts what an HTTP-Upgrade carrier needs:
//!   - `transport.redsocket.pubkey`            — the shared Noise NK server key
//!   - `regions.<r>.ips`                       — direct POP IPs (port 80)
//!   - `transport.redsocket.cloudfront_domains`— CDN fronts (port 80)
//!   - `transport.redsocket.entries[]`         — explicit host/port/sni overrides
//!
//! Everything else in the config (other transports, tier budgets, scan ranges,
//! dnstt, gdocs, reality/spectrum) is ignored by design — the fleet serves
//! HTTP-Upgrade only.

/// Fallback Host header for direct-IP dials when the config names none. A bare
/// IP in `Host:` looks anomalous to a middlebox; a plausible hostname does not.
///
/// A SINGLE constant is a liability: it is one grep in the shipped binary and
/// then one DPI rule ("Upgrade request claiming this Host to an IP that is not
/// theirs") catches the whole fleet. `transport.redsocket.upgrade_hosts` holds a
/// list instead, and each install picks deterministically from it — so blocking
/// requires a set, not a string, and installs do not all look identical.
///
/// The server reads Host for logging only (it routes on the Upgrade header), so
/// varying this is not a wire change.
const DEFAULT_UPGRADE_HOST: &str = "login.microsoftonline.com";
/// The historical path. Used when the config carries no `upgrade_paths`, so an
/// old config keeps producing byte-identical requests.
const DEFAULT_UPGRADE_PATH: &str = "/noise";

/// Pick this install's decoy Host: stable per (cid, endpoint) so one device is
/// consistent, but spread across the fleet.
/// Pick the upgrade URL path. `/noise` fleet-wide is a single string that
/// matches every client, so one DPI rule covers the whole network; drawing the
/// path from the OTA config removes that constant without a client rebuild,
/// exactly as `upgrade_hosts` does for the Host header.
///
/// Keyed on the cid so a device is consistent across reconnects (reproducible
/// in support logs) while the fleet spreads across the list. Salted differently
/// from `pick_upgrade_host` so host and path choices don't correlate — picking
/// index N for both would leak that they share a key.
///
/// A path that is not rooted, or carries a query string, is rejected in favour
/// of the default: the upgrade URL must never carry per-device data, and
/// `upgrade_request` asserts that invariant.
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
/// `mix64`. Which host a device picks is not load-bearing (every entry in the
/// list is equally valid, and the server's optional allow-list accepts all of
/// them), so correcting the distribution costs nothing but a different pick on
/// the next connect.
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
    /// Upgrade header, never the URL — so it exists purely to avoid a
    /// fleet-wide constant. See `pick_upgrade_path`.
    pub upgrade_path: String,
    /// Lower is tried first (SRV semantics). Defaults come from which list the
    /// endpoint came from — see `prio` — so a config with no explicit values
    /// reproduces the historical tier order exactly.
    pub priority: u32,
    /// Tie-break within one priority. Higher = tried earlier, but the order is
    /// randomised per install (stable, keyed on the cid) so ~500K devices don't
    /// all hammer the same POP. **weight 0 disables the endpoint** — a kill
    /// switch that needs no list surgery.
    pub weight: u32,
    /// Part of the "someone is blocking us" tier (CDN fronts, distinctive
    /// names). Selection reserves budget so these are always reachable. A flag
    /// rather than an index boundary, so it survives arbitrary reordering.
    pub fallback: bool,
    /// Paid-tier pool. Kept as a flag (not an index range) for the same reason.
    pub premium: bool,
}

/// Default priorities per source list. Explicit `priority` in the config wins.
///
/// IP literals are additionally split by address family: the *preferred* family
/// keeps its base priority and the other gets `OTHER_FAMILY`. Both are still
/// well below `CDN`, so preferring a family never costs extra DNS exposure —
/// every literal is tried before the first name either way.
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
    /// CDN fronts when `cdn_first_regions` matches: ahead of ENTRY (20) and of
    /// the region tier (22), behind PREMIUM (10) so a paying user still gets
    /// reserved capacity first.
    pub const CDN_FIRST: u32 = 15;
    /// Distinctive names: last resort, one lookup enumerates the fleet.
    pub const NAMED: u32 = 50;
}

/// Which address family to try first, for this client.
///
/// v6 transit is effectively free for us, so it is preferred by default. Iran is
/// the exception: its mobile networks are largely v4-only, so a v6 dial there is
/// a guaranteed timeout rather than a cheap win — and `ipv4_first_regions` in the
/// config names the exceptions rather than hardcoding "IR" here.
///
/// The client's region is resolved from its timezone using the SAME
/// `freedom_regions` table the rest of the app uses, so the two can never drift.
/// Should this region dial the CDN fronts FIRST, ahead of the direct IPs?
///
/// Same resolution as [`prefer_v6`]: the region comes from the device timezone
/// through the `freedom_regions` table, so the two cannot drift apart and no
/// region is ever named in Rust.
///
/// 🚨 THIS INVERTS THE DNS-EXPOSURE LADDER ON PURPOSE, for the one case where
/// the ladder's assumption is wrong. The default order is IP literals first
/// because they cost no DNS at all. That is right where the IPs work. Where
/// they are systematically blocked, every one of them is an 8s timeout before
/// the fronts are even reached -- and `DIRECT_BUDGET` is 32s, so on a
/// blackholing network the user waits half a minute to arrive at the tier that
/// was always going to be the one that worked.
///
/// A CloudFront name is unremarkable cover (§12b), so the DNS it costs is the
/// cheap kind. Listing a region here is a statement that direct IPs are
/// expected to fail there.
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

/// On-device destination filtering (malware / bandwidth-consuming SDKs).
///
/// Same shape of contract as [`ProbeSettings`]: the config carries the *address
/// and the on/off flag*, and nothing else. Who is entitled to the feature
/// (premium **or** freedom region) is a decision only the app can make, so it
/// arrives through `setBlockingEnabled` — reading a URL here never turns
/// anything on.
///
/// `enabled` defaults to **false**, so an absent or malformed block leaves the
/// tunnel behaving exactly as it does today.
#[derive(Clone, Debug)]
pub struct BlocklistSettings {
    pub enabled: bool,
    /// Where the list is fetched from, through the tunnel's own SOCKS5 port.
    /// A *config value*, never a constant in the binary — this client points at
    /// no third-party list provider.
    pub url: String,
    pub refresh_secs: u64,
    pub max_bytes: usize,
    /// Hosts no list may ever block, derived from the config's own bootstrap:
    /// the update URL itself, the cashier bases, the health probe. A shipped
    /// list that blocked its own update path could not be corrected.
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
            // Floored on parse, so a bad edit degrades instead of becoming a
            // hot loop.
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

    // The app's own control plane, from the config rather than hardcoded: the
    // update URL, every API base, and the health endpoint. One bad entry in a
    // shipped list would otherwise be able to cut the app off from the very
    // channel that ships the fix.
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
    pub blocklist: BlocklistSettings,
    /// MUX2: carry many SOCKS5 streams over one Noise carrier (`crate::mux`).
    ///
    /// OTA-gated and DEFAULT FALSE, which is the kill switch. The fleet already
    /// accepts MUX2, but a POP that did not would give a client with this
    /// hardcoded on no way back short of a store release. Flipping
    /// `transport.redsocket.mux` reverts every device on its next config fetch.
    pub mux: bool,
}

/// Tier default adjusted for address family. Names are untouched — they resolve
/// to whatever the resolver returns, so family preference is meaningless there.
/// Which address family an endpoint will use, for a literal OR a
/// family-explicit hostname.
///
/// 🚨 THE NAMES CARRY THIS DELIBERATELY. §12b created `lax00-v4`/`lax00-v6`
/// (and the `-p-` premium pair) as family-EXPLICIT records precisely so one
/// name resolves to one region and one family. Reading the suffix is reading
/// that contract, not guessing from a string.
///
/// It matters because the v4/v6 preference used to be literal-only: a name fell
/// through to `_ => base` and got no family offset at all. The moment the
/// region lists moved from literals to names, `ipv4_first_regions` stopped
/// applying to them -- so Iran and Russia began dialling `-v6` FIRST, which is
/// measured at 0/50 from Russia (§12c) and is largely dead on Iranian mobile.
/// Two names at 8s each is 16 seconds of a 32s direct budget spent on
/// addresses that cannot answer, silently, in the two regions with the worst
/// networks.
///
/// A name with no `-v4`/`-v6` marker (a CDN front) returns None and is left
/// exactly where it was.
pub(crate) fn host_family_is_v6(host: &str) -> Option<bool> {
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

/// FNV-1a — used to spread which endpoint each install tries first, so ~500K
/// devices don't all hammer the same POP IP on every reconnect.
/// splitmix64 finalizer. FNV-1a's low *k* bits depend only on the low *k* bits
/// of each input byte, so reducing it with `% len` where len is a power of two
/// collapses for any cid population that is uniform in its low bits. Path lists
/// of 2, 4 or 8 entries are exactly what an operator writes, so mix before
/// reducing rather than hoping the list length stays coprime.
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

/// Build the endpoint list. `selected_region` scopes to one region ("fra"/"lax");
/// None means all regions. `cid` only affects ordering.
/// Build the endpoint list.
///
/// `premium` selects `transport.redsocket.premium_entries` — a separate POP pool
/// reserved for paying users. Premium endpoints are tried FIRST and the shared
/// pool stays as fallback, so a premium user is never worse off than a free one
/// if the premium POPs are down. Free users never see them at all.
/// An empty/absent `premium_entries` means "premium shares the free pool", which
/// is the state today.
pub fn load(
    config_json: &str,
    selected_region: Option<&str>,
    cid: &str,
    premium: bool,
    // tz: device timezone — resolves the client's region for the v4/v6 preference.
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

    // Absent or malformed means OFF. Fails closed on purpose: a config that
    // cannot be understood must not switch the transport mode.
    let mux = rs.get("mux").and_then(|m| m.as_bool()).unwrap_or(false);

    let server_pubkey = parse_pubkey(
        rs.get("pubkey")
            .and_then(|p| p.as_str())
            .ok_or_else(|| "config has no transport.redsocket.pubkey".to_string())?,
    )?;

    // ── Premium-only pool (paying users first) ────────────────────────────────
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
                // 🚨 RESERVED CAPACITY MUST NOT OVERRIDE THE REGION THE USER PICKED.
                //
                // premium_entries is one flat pool holding BOTH regions, and it
                // sits at prio::PREMIUM (10), ahead of the region tier (22). So
                // a paying user who explicitly chose Los Angeles was dialled
                // into whichever reserved POP the weighted shuffle happened to
                // put first -- half the time Frankfurt. Observed on a paid
                // device 2026-09-23: region=lax, connected 2001:db8:5::9,
                // which is fra00-06.
                //
                // It is worst where it is least acceptable: the region picker is
                // PREMIUM-GATED, so the one selection people pay for was the one
                // being ignored. Free users never read this block and were fine.
                //
                // An entry now declares its `region`. With a region selected,
                // only a matching entry keeps the premium lead; the rest are
                // DEMOTED rather than dropped, so reserved capacity still
                // rescues a user whose own region is down -- §12b's rule that a
                // premium user is never worse off than a free one.
                //
                // An UNTAGGED entry is demoted too when a region is selected:
                // it cannot prove it is the right region, and an explicit choice
                // outranks an assumption. On auto, nothing changes.
                let ep_region = e.get("region").and_then(|r| r.as_str()).unwrap_or("");
                let region_ok = match selected_region {
                    None => true,
                    Some(sel) => sel.eq_ignore_ascii_case(ep_region),
                };
                let (priority, weight) = prio_weight(e, default_prio(prio::PREMIUM, host, v6_first));
                // 🚨 The flag must go too, not just the priority. select.rs
                // hoists EVERY `premium: true` endpoint to the front of the
                // plan before priority is even consulted, so demoting by
                // priority alone changes nothing -- the wrong region still led.
                // Cleared, it becomes an ordinary endpoint in the fallback
                // band. It stays reserved: this whole block is gated on the
                // `premium` argument, so a free user never sees it either way.
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

    // ── Premium-only REGION pool ──────────────────────────────────────────────
    //
    // 🚨 THE FREE POOL AND THE PAID POOL MUST NOT BE THE SAME BOXES.
    //
    // `regions.<r>.ips` used to be the only region list, so a donor-funded
    // Iranian free user and a paying subscriber who both picked Frankfurt were
    // dialled into the same POPs. The DNS to separate them has existed since
    // 2026-09-15 and was never wired: `<r>00-f-v4/-f-v6` is the free pool and
    // `<r>00-p-v4/-p-v6` is the reserved one.
    //
    //   regions.<r>.ips          -> <r>00-f-*   free + freedom, everybody
    //   premium_regions.<r>.ips  -> <r>00-p-*   paid only, read ONLY here
    //
    // Gated on `premium`, exactly like `premium_entries`, so a free client never
    // learns these addresses exist -- not in its endpoint list, not in a DNS
    // lookup. An absent block means "premium shares the free pool", which is the
    // pre-split behaviour and what every unpublished config still gets.
    //
    // ⚠️ The free pool REMAINS underneath as fallback for a paid user. That is
    // §12b's rule -- reserved capacity going down must never strand somebody who
    // paid -- and it is the one direction sharing is still allowed. The reverse
    // never happens: nothing below adds a `-p-` endpoint to a `premium=false`
    // list, and `free_never_sees_premium_regions` pins it.
    if premium {
        if let Some(regions) = v.get("premium_regions").and_then(|r| r.as_object()) {
            for (name, region) in regions {
                // An explicit region choice filters here the same way it filters
                // `premium_entries`: a non-matching entry is DEMOTED, never
                // dropped, so picking Los Angeles cannot strand a paid user when
                // only Frankfurt's reserved POPs are up.
                let region_ok = match selected_region {
                    None => true,
                    Some(sel) => sel.eq_ignore_ascii_case(name),
                };
                let Some(ips) = region.get("ips").and_then(|i| i.as_array()) else { continue };
                let mut rank = 0u32;
                for ip in ips.iter().filter_map(|i| i.as_str()) {
                    // Position in the list decides order within the pool, and
                    // FAMILY outranks position -- same rule as `regions`, or a
                    // block that lists v6 first sends Iran and Russia to
                    // addresses that cannot answer.
                    let base = default_prio(prio::PREMIUM + rank * 2, ip, v6_first);
                    rank += 1;
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

    // ── Explicit entries win outright when present ────────────────────────────
    let mut explicit = Vec::new();
    if let Some(entries) = rs.get("entries").and_then(|e| e.as_array()) {
        for e in entries {
            // Skip entries that name a non-http protocol; the fleet is http-only.
            match e.get("protocol").and_then(|p| p.as_str()) {
                None | Some("") | Some("http") => {}
                Some(_) => continue,
            }
            let Some(host) = e.get("host").and_then(|h| h.as_str()) else { continue };
            let port = e.get("port").and_then(|p| p.as_u64()).unwrap_or(HTTP_PORT as u64) as u16;
            let sni = e.get("sni").and_then(|s| s.as_str()).unwrap_or("");
            // A hostname in entries[] costs a DNS lookup, so it belongs with the
            // named fallbacks, not with the no-DNS literals.
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

    // ── Direct POP IPs from the regions block ─────────────────────────────────
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
                        // Position in the list decides, not the shuffle -- but
                        // the FAMILY outranks position, or a config that lists
                        // v6 first sends Iran and Russia to addresses that
                        // cannot answer. Rank is doubled so it can never
                        // collide with the family offset.
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
                // A hostname has no colon and rides with v4; it resolves to
                // whatever families exist and the dialer handles both.
                if ip.contains(':') { v6.push(ep) } else { v4.push(ep) }
            }
        }
    }

    // Rotate the direct list per-install so first-choice POP varies by device.
    let mut direct = v4;
    let n_direct = direct.len();
    if n_direct > 0 {
        direct.rotate_left((fnv1a(cid) % n_direct as u64) as usize);
    }
    direct.extend(v6);

    // ── CDN fronts last: they work when direct IPs are blocked, but add a hop ──
    let cdn: Vec<Endpoint> = rs
        .get("cloudfront_domains")
        .and_then(|c| c.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|d| d.as_str())
                .map(|d| Endpoint {
                    host: d.to_string(),
                    port: HTTP_PORT,
                    // A CloudFront front must carry its own domain as Host or the
                    // CDN can't route the request to the right distribution.
                    upgrade_host: d.to_string(),
                    upgrade_path: pick_upgrade_path(&upgrade_paths, cid, d),
                    priority: if fronts_lead { prio::CDN_FIRST } else { prio::CDN },
                    // 🚨 `fallback` MUST follow the priority. `select` reserves
                    // DIRECT_BUDGET for the direct tier and only then skips to
                    // the fallback band, so a front left marked `fallback` would
                    // keep its lead priority and still be deferred behind the
                    // very budget this exists to stop paying.
                    weight: 1,
                    fallback: !fronts_lead,
                    premium: false,
                })
                .collect()
        })
        .unwrap_or_default();

    // ── Backup IPs (`transport.redsocket.backup_entries`) ─────────────────────
    //
    // Same tier and same race as the CDN fronts -- tried only once the direct
    // tier is struggling -- but dialled like an ordinary entry: an IP literal
    // with a DECOY Host from `upgrade_hosts`. They must never go through
    // `cloudfront_domains`: a front carries its own name as Host, which for an
    // IP literal is "Host: 13.56.64.180", and that is exactly what DPI on
    // Iranian networks resets (config v17, 2026-10-21, MCI). Used for the AWS
    // pfe fronts: reachable where our own prefixes are blocked, but slower, so
    // direct goes first wherever it works.
    let mut cdn = cdn;
    if let Some(arr) = rs.get("backup_entries").and_then(|b| b.as_array()) {
        for e in arr {
            let Some(host) = e.get("host").and_then(|h| h.as_str()) else { continue };
            if host.parse::<std::net::IpAddr>().is_err() {
                continue; // IP literals only: a backup must not cost a DNS lookup
            }
            let port = e.get("port").and_then(|p| p.as_u64()).unwrap_or(HTTP_PORT as u64) as u16;
            let weight = e.get("weight").and_then(|w| w.as_u64()).unwrap_or(1) as u32;
            // `sni` is the per-entry decoy Host, as on `entries`: for an AWS
            // address a `d....cloudfront.net` name looks like ordinary
            // CloudFront traffic to an AWS range. Absent = an upgrade_hosts pick.
            let sni = e.get("sni").and_then(|x| x.as_str()).unwrap_or("");
            cdn.push(Endpoint {
                host: host.to_string(),
                port,
                upgrade_host: if sni.is_empty() { pick_upgrade_host(&upgrade_hosts, cid, host) } else { sni.to_string() },
                upgrade_path: pick_upgrade_path(&upgrade_paths, cid, host),
                priority: if fronts_lead { prio::CDN_FIRST } else { prio::CDN },
                weight,
                fallback: !fronts_lead,
                premium: false,
            });
        }
    }

    // ── Ordering: escalate DNS exposure only as far as needed ─────────────────
    //
    // A distinctive hostname is the LAST resort, never the default path. One query
    // for it returns the whole fleet (12 A records, TTL 60) — that is a public
    // enumeration of every POP, and in Iran the lookup itself marks the user. IP
    // literals resolve nothing; CloudFront names are innocuous cover (millions of
    // sites resolve *.cloudfront.net). So:
    //   1. IP literals      — no DNS at all
    //   2. CDN fronts       — DNS, but unremarkable
    //   3. Named endpoints  — distinctive DNS, only if everything above failed
    //
    // Region choice still wins within tier 1: the named endpoints round-robin
    // across regions, so a user who picked Frankfurt must not silently exit via LA.
    let (mut named, literal): (Vec<Endpoint>, Vec<Endpoint>) = explicit
        .into_iter()
        .partition(|e| e.host.parse::<std::net::IpAddr>().is_err());

    let mut endpoints = Vec::new();
    endpoints.extend(premium_eps);
    let mut direct_named_selected: Vec<Endpoint> = Vec::new();
    // Region choice still wins: when the user explicitly picked a region, its
    // direct IPs must outrank the general entries, or choosing Frankfurt could
    // silently exit via LA. Expressed as priority rather than list position, so
    // there is exactly one ordering mechanism.
    // A region list may name a hostname as well as literals, so that region
    // selection survives a POP address rotation.
    //
    // 🚨 WHERE THOSE NAMES GO DEPENDS ON WHETHER THE USER ASKED.
    //
    // On AUTO they join the named tail: the DNS-exposure ladder in §12b exists
    // for the happy path, where a distinctive lookup should never happen if a
    // literal or a front can serve.
    //
    // On an EXPLICIT region choice they stay in the direct tier and lead. The
    // rule they would otherwise obey protects something the user has already
    // spent: they have named the region, and `pop-a-v4.example.net` discloses
    // one region and one family, which is precisely why those names were
    // created to replace the single fleet-wide hostname. Punting them to the
    // tail meant a region list made of names was tried AFTER every literal and
    // every CDN front -- i.e. picking Los Angeles could still exit via
    // Frankfurt, which is the exact failure the region bump above exists to
    // prevent, reintroduced by the partition.
    let (region_named, direct): (Vec<Endpoint>, Vec<Endpoint>) = direct
        .into_iter()
        .partition(|e| e.host.parse::<std::net::IpAddr>().is_err());
    if selected_region.is_some() {
        // Re-band into the region tier, preserving list order: the name's rank
        // was encoded as prio::NAMED + rank when it was built.
        // 🚨 ALL of the picked region's names must lead, not just the first.
        //
        // The old form kept the build-time rank (`prio::NAMED + rank * 2`) and
        // added it to REGION-1, so five names landed on 21/23/25/27/29 and,
        // after the -3 shift, on 18/20/22/24/26 -- interleaved with the
        // region-blind `entries` pool at 20. Picking a region made of five
        // hostnames therefore dialled ONE of them and then fell through to the
        // other region's literals, which is precisely the failure the region
        // bump exists to prevent. Invisible until a region was made entirely of
        // names; fra/lax carry one or two and the overlap never showed.
        //
        // They are now packed CONTIGUOUSLY so the whole region sits ahead of
        // `entries`, keeping list order, and floored above the premium band so
        // a long region list can never outrank reserved capacity.
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
        // Shift, don't assign: subtracting keeps the v4/v6 offset intact while
        // moving each region endpoint ahead of the matching entries tier.
        for e in &mut direct {
            e.priority = e.priority.saturating_sub(3);
        }
    }
    endpoints.extend(literal);
    endpoints.extend(direct);
    endpoints.extend(cdn);
    // Lowest priority number first, so the region the user actually picked is
    // the first name tried once every literal and every front has failed.
    named.sort_by_key(|e| e.priority);
    endpoints.extend(named);

    // Keep the earliest (lowest-priority-number) instance of each host:port.
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

    let blocklist = parse_blocklist(&v);
    // AFTER the endpoint list is final: targets are resolved against it, so a
    // target can only ever be one of the POPs this config already ships.

    Ok(Config {
        server_pubkey,
        endpoints,
        blocklist,
        mux,
    })
}

#[cfg(test)]
mod dual_stack_tests {
    use super::*;

    /// `entries[]` must accept an IPv6 literal, not just `regions.*.ips`.
    ///
    /// The shipped entries are all v4 today, so nothing exercises this path in
    /// production and a regression would surface only on a v6-only network —
    /// which is precisely where there is no v4 fallback to hide it.
    #[test]
    fn entries_accept_ipv6_literals() {
        let json = r#"{
            "version": 1,
            "transport": { "redsocket": { "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405", "entries": [
                { "host": "2001:db8:5::a", "port": 80 },
                { "host": "192.0.2.15", "port": 80 },
                { "host": "fra00.example.net", "port": 80 }
            ] } }
        }"#;
        let cfg = load(json, None, "cid0", false, "").expect("v6 entry must parse");
        let hosts: Vec<&str> = cfg.endpoints.iter().map(|e| e.host.as_str()).collect();
        assert!(hosts.contains(&"2001:db8:5::a"), "v6 entry dropped: {hosts:?}");
        assert!(hosts.contains(&"192.0.2.15"), "v4 entry dropped: {hosts:?}");
        assert!(hosts.contains(&"fra00.example.net"), "name entry dropped: {hosts:?}");
    }

    /// A bare v6 literal must survive as-is, unbracketed. protected_connect_by_name
    /// parses it with `host.parse::<IpAddr>()`, so bracketing it here would break
    /// the fast path and send it down DNS resolution instead.
    #[test]
    fn ipv6_host_is_stored_unbracketed() {
        let json = r#"{
            "version": 1,
            "transport": { "redsocket": { "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405", "entries": [
                { "host": "2001:db8:5::a", "port": 80 }
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
        "fra": { "ips": ["192.0.2.16", "2001:db8:5::2"] },
        "lax": { "ips": ["192.0.2.17"] }
      }
    }"#;

    #[test]
    fn builds_v4_first_then_v6_then_cdn() {
        let c = load(CFG, None, "abcd1234abcd1234", false, "").unwrap();
        let labels: Vec<String> = c.endpoints.iter().map(|e| e.label()).collect();
        // Two v4 direct, then the v6, then the CDN front — order is load-bearing.
        assert_eq!(labels.len(), 4);
        assert!(!labels[0].contains("::"), "v4 must come first, got {:?}", labels);
        assert!(!labels[1].contains("::"), "v4 must come first, got {:?}", labels);
        assert_eq!(labels[2], "2001:db8:5::b");
        assert_eq!(labels[3], "d1.cloudfront.net:80");
        // CDN fronts carry their own Host; direct IPs get the cover hostname.
        assert_eq!(c.endpoints[3].upgrade_host, "d1.cloudfront.net");
        assert_eq!(c.endpoints[0].upgrade_host, DEFAULT_UPGRADE_HOST);
        // No `upgrade_paths` in the config => historical path, byte-identical
        // requests for every already-deployed config.
        assert_eq!(c.endpoints[0].upgrade_path, DEFAULT_UPGRADE_PATH);
    }

    #[test]
    fn region_selection_scopes_direct_ips() {
        let c = load(CFG, Some("lax"), "cid0", false, "").unwrap();
        assert_eq!(c.endpoints[0].host, "192.0.2.17");
        // fra's v4 and v6 are gone; only lax + the CDN front remain.
        assert_eq!(c.endpoints.len(), 2);
    }

    #[test]
    fn different_cids_can_lead_with_different_pops() {
        // Rotation must actually depend on the cid.
        let leads: std::collections::HashSet<String> = ["a", "b", "c", "d", "e", "f", "g", "h"]
            .iter()
            .map(|cid| load(CFG, None, cid, false, "").unwrap().endpoints[0].host.clone())
            .collect();
        assert!(leads.len() > 1, "cid rotation never varies the lead POP");
    }

    /// `cdn_first_regions` puts the fronts at the head of the plan for the
    /// listed region ONLY, and changes nothing anywhere else.
    ///
    /// Asserted through `select::plan`, never `cfg.endpoints`: the latter is
    /// construction order, and the weighted shuffle reorders within a priority.
    /// Asserting on the wrong one has produced two confident wrong readings in
    /// this file's history.
    #[test]
    fn cdn_first_regions_leads_only_where_listed() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        // The shipped asset carries no CDN front since the CloudFront fronts
        // were disabled (2026-10-19), so the fixture adds one: this test is
        // about the mechanism, not about what is currently deployed.
        let with_front = |v: &mut serde_json::Value| {
            v["transport"]["redsocket"]["cloudfront_domains"] =
                serde_json::json!(["dtestfront000.cloudfront.net"]);
            // Backup IPs share the tier and would compete for the lead.
            v["transport"]["redsocket"]["backup_entries"] = serde_json::json!([]);
        };
        let mut v: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        with_front(&mut v);
        v["cdn_first_regions"] = serde_json::json!(["IR"]);
        let json = v.to_string();

        let lead = |tz: &str| -> String {
            let cfg = load(&json, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, false, None, "deadbeefdeadbeef");
            cfg.endpoints[p.order[0]].host.clone()
        };

        assert!(lead("Asia/Tehran").contains("cloudfront.net"),
                "Tehran must lead with a front, got {}", lead("Asia/Tehran"));

        // A second listed region works too, and RU carries 27 timezones in the
        // freedom_regions table, so the prefix match is doing real work here.
        let mut v2: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        with_front(&mut v2);
        v2["cdn_first_regions"] = serde_json::json!(["IR", "RU"]);
        let two = v2.to_string();
        // EVERY Russian zone, not a sample. RU carries 27 of them -- Kaliningrad
        // to Anadyr, plus the legacy `W-SU` alias -- and "entire Russia" is only
        // true if the table's prefix match holds for all of them.
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
        // ...and a region NOT listed still does not, with the same config.
        for tz in ["America/Los_Angeles", "Europe/Berlin"] {
            let cfg = load(&two, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, false, None, "deadbeefdeadbeef");
            assert!(!cfg.endpoints[p.order[0]].host.contains("cloudfront.net"),
                    "{tz} must not lead with a front");
        }

        // Everywhere else is untouched, and a front must NOT lead.
        for tz in ["Europe/Moscow", "America/Los_Angeles", "Europe/Berlin", ""] {
            assert!(!lead(tz).contains("cloudfront.net"),
                    "{tz} must not lead with a front, got {}", lead(tz));
        }

        // And with the key ABSENT, Tehran is unchanged -- so an older client, or
        // a brand whose config has not been republished, behaves exactly as
        // before. Constructed by REMOVING the key, not by assuming the shipped
        // config lacks it: it does carry it now, and this assertion failed the
        // moment that became true, which is the test doing its job.
        let mut off: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        with_front(&mut off);
        off.as_object_mut().unwrap().remove("cdn_first_regions");
        let base = load(&off.to_string(), None, "deadbeefdeadbeef", false, "Asia/Tehran").unwrap();
        let bp = crate::select::plan(&base, false, None, "deadbeefdeadbeef");
        assert!(!base.endpoints[bp.order[0]].host.contains("cloudfront.net"));
    }

    /// Guards the actual shipped asset: a config edit that leaves the client with
    /// no usable endpoint would otherwise only surface on a device.
    ///
    /// ⚠️ This `#[test]` and the one on `cdn_first_regions_leads_only_where_listed`
    /// had drifted apart from their functions, so neither test ran: the stray
    /// attribute stacked onto the next function and this one was a plain helper.
    /// Behind a cargo
    /// feature the cdn test had also been wrongly gated.
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
        // An IP literal must lead: the normal connect path performs NO DNS lookup.
        assert!(
            auto.endpoints[0].host.parse::<std::net::IpAddr>().is_ok(),
            "first endpoint must be an IP literal, got {}",
            auto.endpoints[0].host
        );
        // Any distinctive named endpoint is last-resort only — never before a CDN
        // front, and never in the leading half of the list.
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
        // CDN fronts must carry their own Host header or CloudFront can't route.
        for e in auto.endpoints.iter().filter(|e| e.host.ends_with(".cloudfront.net")) {
            assert_eq!(e.upgrade_host, e.host, "CDN front needs its own Host");
        }
        // Both regions the UI offers must resolve to at least one endpoint.
        for r in ["fra", "lax"] {
            let scoped = load(SHIPPED, Some(r), "abcd1234abcd1234", false, "")
                .unwrap_or_else(|e| panic!("region {} must load: {}", r, e));
            assert!(!scoped.endpoints.is_empty(), "region {} has no endpoints", r);
            // An explicit region must lead with that region's own IP.
            assert!(
                !scoped.endpoints[0].host.contains("example.net"),
                "region {} led with the roaming hostname",
                r
            );
        }
    }

    #[test]
    fn explicit_region_beats_region_agnostic_entries() {
        // The shipped config's entries include a hostname that resolves into more
        // than one region. Picking a region must not be silently overridden by it.
        const WITH_ENTRY: &str = r#"{
          "transport": { "redsocket": {
            "pubkey": "e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
            "entries": [{ "host": "any-region.example.net", "port": 80, "protocol": "http" }],
            "cloudfront_domains": []
          }},
          "regions": { "fra": { "ips": ["192.0.2.16"] }, "lax": { "ips": ["192.0.2.17"] } }
        }"#;

        // Region chosen → that region's IP leads; the roaming NAME is last resort.
        let picked = load(WITH_ENTRY, Some("fra"), "cid0", false, "").unwrap();
        assert_eq!(picked.endpoints[0].host, "192.0.2.16");
        assert_eq!(picked.endpoints.last().unwrap().host, "any-region.example.net");

        // Auto → an IP still leads. A distinctive name must never be the default
        // path, because resolving it is what exposes the user and the fleet.
        let auto = load(WITH_ENTRY, None, "cid0", false, "").unwrap();
        assert!(auto.endpoints[0].host.parse::<std::net::IpAddr>().is_ok());
        assert_eq!(auto.endpoints.last().unwrap().host, "any-region.example.net");
    }

    // ── Reachability measurement ────────────────────────────────────────────
    //
    // The point of these is one property: a target that is not already an
    // endpoint in this very config cannot survive parsing. If any of them ever
    // fails, the feature has stopped being a measurement tool and become a
    // scanner pointed by whoever can write the config.

    /// Prints the real dial order so a human can read it. Ignored: it is a
    /// window, not an assertion.
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

    /// 🚨 A FREE CLIENT MUST NEVER BE HANDED A RESERVED ADDRESS.
    ///
    /// The whole point of splitting `regions` (free, `-f-`) from
    /// `premium_regions` (paid, `-p-`) is that a donor-funded user and a paying
    /// one do not sit on the same boxes. `premium_entries` already had this
    /// property; `regions` did not, because there was only one region list.
    ///
    /// Both directions are asserted: a free plan contains no `-p-` host at all,
    /// and a paid plan LEADS with one — a premium block that is read but sorted
    /// behind the free pool would look identical to one that works.
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
        assert!(first.contains("lax00-p-"), "paid must lead on reserved capacity, got {first}");
        // §12b: reserved capacity going down must not strand somebody who paid.
        assert!(
            porder.iter().any(|&i| paid.endpoints[i].host.contains("-f-")),
            "paid has no free-pool fallback"
        );
        // The region the user picked still decides which reserved POP leads.
        assert!(!first.contains("fra00-p-"), "paid picked lax and led with fra: {first}");
    }

    /// One pool for everyone (owner's call, 2026-10-21): with hundreds of
    /// thousands of clients a reserved tier is the first thing to saturate, so
    /// every install round-robins over the same servers. With
    /// `premium_entries` empty a paying install must get exactly the free
    /// list, in exactly the same order.
    #[test]
    fn premium_shares_the_pool() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        for tz in ["", "Asia/Tehran", "Europe/Moscow"] {
            let free = load(SHIPPED, None, "deadbeefdeadbeef", false, tz).unwrap();
            let paid = load(SHIPPED, None, "deadbeefdeadbeef", true, tz).unwrap();
            let hosts = |c: &Config| {
                let p = crate::select::plan(c, true, None, "deadbeefdeadbeef");
                p.order.iter().map(|&i| c.endpoints[i].host.clone()).collect::<Vec<_>>()
            };
            assert_eq!(hosts(&free), hosts(&paid), "tz={tz}: paid and free plans differ");
            assert!(paid.endpoints.iter().all(|e| !e.premium), "tz={tz}: a premium-only endpoint");
        }
    }

    /// "Auto" must not mean "always Frankfurt". With no region picked the list
    /// carries both regions, and `select::plan` applies a per-install weighted
    /// shuffle — so the first POP tried is spread ACROSS installs and fixed FOR
    /// one install, which is what keeps a reconnect from hopping continents.
    ///
    /// Checked through `plan()`, not `cfg.endpoints`: the latter is construction
    /// order and says nothing about what is dialled first.
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
            if first.starts_with("23.128.36.") || first.starts_with("2001:db8:5::c") {
                lax += 1;
            } else {
                fra += 1;
            }
        }
        println!("auto first POP over 400 installs: lax={lax} fra={fra}");
        assert!(lax > 40 && fra > 40, "auto favoured one region: lax={lax} fra={fra}");
    }

    /// The CDN fronts are the whole answer to "the direct IPs are blocked", so
    /// they must be reachable. Now a per-endpoint flag rather than an index
    /// boundary, so it survives arbitrary priority/weight reordering.
    #[test]
    fn fallback_tier_is_flagged_and_non_empty() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let cfg = load(SHIPPED, None, "abcd1234abcd1234", false, "").unwrap();
        let fb: Vec<_> = cfg.endpoints.iter().filter(|e| e.fallback).collect();
        let direct: Vec<_> = cfg.endpoints.iter().filter(|e| !e.fallback).collect();
        assert!(!fb.is_empty(), "nothing left to try when the direct IPs are blocked");
        assert!(!direct.is_empty(), "no direct endpoints at all");
        // Fallback = a DNS name, or a backup IP from `backup_entries` (the AWS
        // pfe fronts: slower than direct, so they stay behind it and are raced
        // only once the direct tier is struggling).
        let v: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();
        let backup: Vec<&str> = v["transport"]["redsocket"]["backup_entries"]
            .as_array().map(|a| a.iter().filter_map(|x| x["host"].as_str()).collect()).unwrap_or_default();
        for e in &fb {
            if e.host.parse::<std::net::IpAddr>().is_ok() {
                assert!(e.upgrade_host != e.host, "{}: a backup IP must carry a decoy Host, not its own address", e.host);
            }
        }
        for e in &fb {
            assert!(e.host.parse::<std::net::IpAddr>().is_err() || backup.contains(&e.host.as_str()),
                    "{} is an IP literal but flagged fallback", e.host);
            assert!(e.priority >= prio::CDN, "{} fallback must sort after direct", e.host);
        }
        // Direct = IP literals only; no DNS on the happy path.
        for e in &direct {
            assert!(e.host.parse::<std::net::IpAddr>().is_ok(),
                    "{} needs DNS but is in the direct tier", e.host);
            assert!(e.priority < prio::CDN, "{} direct must sort before fallback", e.host);
        }
    }

    /// v6 transit is effectively free for us, so v6 leads everywhere EXCEPT the
    /// regions named in `ipv4_first_regions` — Iran, whose mobile networks are
    /// largely v4-only, where a v6 dial is a guaranteed timeout rather than a
    /// cheap win. The client's region comes from the SAME freedom_regions table
    /// the rest of the app uses, so the two cannot drift.
    #[test]
    /// RUSSIA MUST DIAL v4 FIRST, and this is measured, not assumed.
    ///
    /// On 2026-09-17, from Russian probes via the gateway's RU pool, our six
    /// IPv6 endpoints answered **0 of 50** while the v4 addresses on the SAME
    /// machines answered ~half. `ipv4_first_regions` named only IR, so a
    /// Moscow device sorted the four v6 literals into positions 1-4 and spent
    /// 4 x OPEN_TIMEOUT = 32s on them — exactly DIRECT_BUDGET — exhausting the
    /// direct tier before reaching a single address that works, then skipping
    /// to CDN fronts that measured 0/8 and 1/8 from the same probes.
    ///
    /// Asserted through `plan()`, never `cfg.endpoints`: construction order is
    /// not dial order, and a test on the latter passes while the fleet does
    /// something else.
    #[test]
    /// The MINIMAL fix for the live fleet: the config that is published today
    /// (v25) with exactly one key changed. Loaded with the real client loader,
    /// not a JSON lint, because that is the gate that matters.
    #[test]
    fn minimal_ru_patch_is_safe() {
        // SKIPS when the scratch file is gone rather than failing. It lived in
        // /tmp, which is cleared, so this test went permanently red and took the
        // whole suite with it — and a suite that is always red is one nobody
        // reads, which is worse than no test. Regenerate the file to re-arm it.
        let Ok(raw) = std::fs::read_to_string("/tmp/ru-minimal-config.json") else {
            eprintln!("skip minimal_ru_patch_is_safe: /tmp/ru-minimal-config.json absent");
            return;
        };
        for (tz, want_v6_first) in [("Europe/Moscow", false), ("Asia/Tehran", false),
                                    ("Europe/Berlin", true), ("America/Los_Angeles", true)] {
            let cfg = load(&raw, None, "deadbeefdeadbeef", false, tz).unwrap();
            let p = crate::select::plan(&cfg, true, None, "deadbeefdeadbeef");
            let first = &cfg.endpoints[p.order[0]].host;
            let is_v6 = first.contains(':');
            assert_eq!(is_v6, want_v6_first, "{tz} dialled {first} first");
            println!("  {tz:24} first dial: {first}");
        }
        let cfg = load(&raw, None, "deadbeefdeadbeef", false, "Europe/Moscow").unwrap();
        assert!(!cfg.endpoints.is_empty());
        println!("  endpoints parsed: {}", cfg.endpoints.len());
    }

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
        assert_eq!(v4, 0, "the FIRST thing a Moscow device dials must be v4, got position {v4}");

        // and Iran must be unchanged
        let ir = load(SHIPPED, None, "deadbeefdeadbeef", false, "Asia/Tehran").unwrap();
        let pi = crate::select::plan(&ir, true, None, "deadbeefdeadbeef");
        assert!(!ir.endpoints[pi.order[0]].host.contains(':'), "Tehran must still lead with v4");

        // and a region with no exception keeps the v6-first default
        let de = load(SHIPPED, None, "deadbeefdeadbeef", false, "Europe/Berlin").unwrap();
        let pd = crate::select::plan(&de, true, None, "deadbeefdeadbeef");
        assert!(de.endpoints[pd.order[0]].host.contains(':'), "Berlin should still lead with v6");
    }

    fn ipv6_leads_except_in_ipv4_first_regions() {
        const CFG: &str = r#"{
            "freedom_regions": {"IR": ["Asia/Tehran", "Iran"], "RU": ["Europe/Moscow"]},
            "ipv6_first": true,
            "ipv4_first_regions": ["IR"],
            "transport":{"redsocket":{
              "pubkey":"e644c8a4dbc44c56b2e2a1f2587a785fc5813aba74674e151455d51cf86dc405",
              "entries":[{"host":"1.1.1.1","port":80}]}},
            "regions":{"fra":{"ips":["9.9.9.9","2001:db8:5::d"]}}}"#;

        let first_host = |tz: &str| {
            let cfg = load(CFG, None, "abcd1234abcd1234", false, tz).unwrap();
            let p = crate::select::plan(&cfg, true, None, "abcd1234abcd1234");
            cfg.endpoints[p.order[0]].host.clone()
        };

        // Iran: v4 must lead — a v6 dial there just burns the budget.
        assert_eq!(first_host("Asia/Tehran"), "1.1.1.1");
        // Russia and everywhere else: v6 leads (free transit).
        assert!(first_host("Europe/Moscow").contains(':'), "RU should lead v6");
        assert!(first_host("America/Los_Angeles").contains(':'), "US should lead v6");
        assert!(first_host("").contains(':'), "unknown tz should lead v6");

        // And the preference must never reach past the literals into DNS: the
        // named/CDN tiers stay last regardless of family.
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

        // A CDN front must still carry its OWN domain, or CloudFront cannot route.
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
        // v6 is preferred by default, so a v4 literal sits one family band lower.
        assert_eq!(by("2.2.2.2").priority, prio::ENTRY + prio::OTHER_FAMILY,
                   "tier default not applied");
        assert_eq!(by("3.3.3.3").weight, 0);
        // The disabled one must not appear in a plan.
        let p = crate::select::plan(&cfg, true, None, "abcd1234abcd1234");
        let hosts: Vec<_> = p.order.iter().map(|&i| cfg.endpoints[i].host.clone()).collect();
        assert!(!hosts.contains(&"3.3.3.3".to_string()), "weight 0 must disable");
        // ...and the priority-99 one must sort last.
        assert_eq!(hosts.last().unwrap(), "1.1.1.1");
    }

    /// Premium endpoints are paid-only and go first, with the shared pool kept as
    /// fallback so a premium user is never stranded when a premium POP is down.
    #[test]
    /// Picking a region must decide the FIRST dial, for a paid user too.
    ///
    /// Observed in production 2026-09-23 on a `tier=paid` device: region=lax,
    /// connected 2001:db8:5::9, which is a member of fra00-p-v6. Two causes,
    /// both fixed and both pinned here:
    ///   * premium_entries is one region-blind pool at prio 10, ahead of the
    ///     region tier at 22, so reserved capacity overrode the choice;
    ///   * a region list made of HOSTNAMES was partitioned into the named tail
    ///     and tried after every literal and every CDN front.
    #[test]
    /// The REAL shipped brand config, paid user, both regions.
    #[test]
    /// IR and RU must dial v4 before v6, names included.
    #[test]
    #[test]
    fn dump_full_endpoint_list() {
        let cfg = include_str!("../../brands/configs/redvpn.json");
        for (sel, label) in [(None, "AUTO"), (Some("lax"), "LAX"), (Some("fra"), "FRA"), (Some("cdn"), "CDN")] {
            for paid in [false, true] {
                let c = load(cfg, sel, "deadbeefdeadbeef", paid, "America/Los_Angeles").unwrap();
                let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
                println!("\n=== {label}  {} ===", if paid {"PAID"} else {"free"});
                for (n, &i) in p.order.iter().enumerate() {
                    let e = &c.endpoints[i];
                    println!("  {:2}. {:34} :{}  prio {:<3}{}{}", n + 1, e.host, e.port,
                        e.priority,
                        if e.premium {" premium"} else {""},
                        if e.fallback {" fallback"} else {""});
                }
            }
        }
    }

    fn iran_and_russia_dial_v4_first_even_with_hostname_regions() {
        let cfg = include_str!("../../brands/configs/redvpn.json");
        for (tz, label) in [("Asia/Tehran", "IR"), ("Europe/Moscow", "RU"), ("Europe/Berlin", "DE")] {
            for want in ["lax", "fra"] {
                let c = load(cfg, Some(want), "deadbeefdeadbeef", false, tz).unwrap();
                let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
                let hosts: Vec<String> = p.order.iter().take(10)
                    .map(|&i| format!("{}#{}", c.endpoints[i].host, c.endpoints[i].priority))
                    .collect();
                println!("  {label:2} {tz:16} region={want} -> {hosts:?}");
            }
        }
    }

    fn shipped_redvpn_config_honours_the_picked_region_for_a_paid_user() {
        let cfg = include_str!("../../brands/configs/redvpn.json");
        for want in ["lax", "fra"] {
            let c = load(cfg, Some(want), "deadbeefdeadbeef", true, "America/Los_Angeles").unwrap();
            let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
            let hosts: Vec<&str> =
                p.order.iter().take(8).map(|&i| c.endpoints[i].host.as_str()).collect();
            println!("  region={want}: {hosts:?}");
            assert!(
                hosts[0].starts_with(want),
                "picked {want}, first dial {} -- {hosts:?}", hosts[0]
            );
        }
    }

    fn a_picked_region_leads_even_for_a_paid_user() {
        let cfg = serde_json::json!({
            "version": "t",
            "transport": {"redsocket": {
                "pubkey": "0000000000000000000000000000000000000000000000000000000000000000",
                "entries": [{"host": "203.0.113.9", "port": 80}],
                "premium_entries": [
                    {"host": "pop-b-p-v6.example.net", "port": 80, "region": "fra"},
                    {"host": "pop-a-p-v6.example.net", "port": 80, "region": "lax"}
                ]}},
            "regions": {
                "fra": {"ips": ["pop-b-v6.example.net", "pop-b-v4.example.net"]},
                "lax": {"ips": ["pop-a-v6.example.net", "pop-a-v4.example.net"]}
            }
        })
        .to_string();

        for (want, other) in [("lax", "fra"), ("fra", "lax")] {
            let c = load(&cfg, Some(want), "cid", true, "America/Los_Angeles").unwrap();
            // Assert through plan(), never cfg.endpoints: that is CONSTRUCTION
            // order, and the weighted shuffle reorders within a priority.
            let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
            let hosts: Vec<&str> = p.order.iter().map(|&i| c.endpoints[i].host.as_str()).collect();
            assert!(
                hosts[0].starts_with(want),
                "picked {want} but the first dial was {} -- full order {hosts:?}",
                hosts[0]
            );
            // The other region's reserved POP must not preempt it, and must
            // still be present as a rescue -- never dropped.
            let other_pos = hosts.iter().position(|h| h.starts_with(other));
            assert!(other_pos.is_some(), "{other} was dropped; it must remain a fallback");
            assert!(other_pos.unwrap() > 0, "{other} preempted the picked region");
        }

        // On AUTO nothing changes: names stay in the tail, premium still leads.
        let c = load(&cfg, None, "cid", true, "America/Los_Angeles").unwrap();
        let p = crate::select::plan(&c, true, None, "deadbeefdeadbeef");
        assert!(
            c.endpoints[p.order[0]].premium,
            "on auto, reserved capacity still leads"
        );
    }

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
            "entries": [ {"host": "192.0.2.18", "port": 80} ] } },
          "regions": { "lax": { "ips": ["192.0.2.19"] } }
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
        // FOUR paths on purpose: a power-of-two length is what exposes a weak
        // reduction, and it is the list length an operator is most likely to
        // write. A three-element list hides the bug this test exists for.
        let paths: Vec<String> = ["/v1/assets/app.js", "/static/css/main.css",
                                  "/api/v2/sync", "/cdn-cgi/trace"]
            .iter().map(|s| s.to_string()).collect();
        let chosen: std::collections::HashSet<String> = (0..400)
            .map(|i| pick_upgrade_path(&paths, &format!("cid{:04x}", i), "1.2.3.4"))
            .collect();
        assert_eq!(chosen.len(), paths.len(), "paths not all reachable: {:?}", chosen);

        // Cids that are uniform in their low bits — FNV-1a alone maps every one
        // of these to the same index at len 4. Real cids are random hex and do
        // not look like this, but nothing guarantees a caller's do not.
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

        // Refresh interval is floored, so a typo cannot make 500K clients hammer
        // the update host.
        assert_eq!(with(r#"{ "enabled": true, "refresh_secs": 1 }"#).refresh_secs, MIN_REFRESH_SECS);
        assert_eq!(with(r#"{ "enabled": true, "refresh_secs": 43200 }"#).refresh_secs, 43_200);
        assert_eq!(with(r#"{ "enabled": true, "max_bytes": 1 }"#).max_bytes, 4096);
        assert_eq!(with(r#"{ "enabled": true }"#).max_bytes, DEFAULT_MAX_BYTES);
    }

    /// The list must never be able to block the channel that corrects it, nor
    /// the cashier the app needs for entitlement.
    #[test]
    fn never_block_covers_the_apps_own_control_plane() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let bl = load(SHIPPED, None, "abcd1234abcd1234", false, "").unwrap().blocklist;
        assert!(!bl.enabled, "the shipped config must not ship filtering switched on");
        for expected in ["cashier.example.com", "checkout.example.com", "ip-geo.example.com"] {
            assert!(
                bl.never_block.iter().any(|h| h == expected),
                "{expected} missing from never_block: {:?}",
                bl.never_block
            );
        }
    }

    #[test]
    fn host_of_handles_the_shapes_a_config_can_carry() {
        assert_eq!(host_of("https://cashier.example.com").as_deref(), Some("cashier.example.com"));
        assert_eq!(host_of("http://x.example:8080/a/b").as_deref(), Some("x.example"));
        assert_eq!(host_of("https://[2001:db8::1]:443/x").as_deref(), Some("2001:db8::1"));
        assert_eq!(host_of("EXAMPLE.com/x").as_deref(), Some("example.com"));
        assert_eq!(host_of(""), None);
    }

    /// Same power-of-two hazard as the path picker: four hosts is a natural
    /// list length, and low-bit-uniform cids collapsed onto one entry before
    /// the reduction was mixed.
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

    /// Host and path are keyed on the same cid, so they must be salted apart or
    /// the pair leaks that one index drives both.
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

    /// OTA config is operator-controlled, but the no-identifier-in-URL
    /// invariant is load-bearing: reject anything that could carry data.
    #[test]
    fn malformed_paths_fall_back_to_default() {
        for bad in ["noise", "/x?cid=abc", "/x#frag", "/has space", ""] {
            let got = pick_upgrade_path(&[bad.to_string()], "cid", "e");
            assert_eq!(got, DEFAULT_UPGRADE_PATH, "accepted bad path {bad:?}");
        }
        // A good path alongside a bad one still gets used.
        let mixed = vec!["/x?cid=abc".to_string(), "/good".to_string()];
        assert_eq!(pick_upgrade_path(&mixed, "cid", "e"), "/good");
    }

}

#[cfg(test)]
mod mux_config_tests {
    use super::*;

    /// The shipped config must actually turn MUX2 on.
    ///
    /// `mux` defaults to false and fails closed, which is right for safety and
    /// wrong for shipping: a typo in the key name, or the field being dropped
    /// in a future edit, silently reverts every device to one handshake per
    /// destination and nothing anywhere reports it. This asserts the intent.
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
    }}

/// Every per-brand OTA config (`brands/configs/*.json`), held to the SAME bar as
/// the bundled asset.
///
/// Why this exists: `brands/configs/<id>.json` is what a brand's fleet actually
/// fetches (§16 — the route selects on `X-App-Key`), but until now nothing
/// parsed it with the client's own loader. A brand config was only ever checked
/// by eye or by a JSON linter, neither of which knows that `mux` fails closed on
/// a typo, or that a config whose first endpoint is a hostname performs
/// a DNS lookup in Iran on the normal path. Those are device-only failures, and
/// on the wrong brand they are a device-only failure for that brand alone —
/// the hardest kind to notice.
///
/// The directory is read at RUNTIME, not `include_str!`d, so brand N+1 is
/// covered the day its file lands rather than the day somebody remembers to add
/// a line here.
#[cfg(test)]
mod brand_config_tests {
    use super::*;
    use std::path::PathBuf;

    fn configs_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../brands/configs")
    }

    /// `(filename, contents)` for every brand config, newest schema and all.
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
            out.len() >= 5,
            "expected one config per brand, found {} in {} — did the directory move?",
            out.len(),
            dir.display()
        );
        out
    }

    /// Recursive hunt for `_`-prefixed keys. `tools/config/strip-notes.py` moves
    /// them to a `.notes.md` sidecar because the config ships sealed to ~750K
    /// devices on metered, censored links: prose here is paid for by a user in
    /// Iran, on every refresh. One reintroduced note key is cheap; the habit is
    /// how the file got to 62% commentary last time.
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

    /// The invariants a config must satisfy before it is safe to serve to a
    /// fleet. Applied to the brand configs AND to the bundled asset, so the two
    /// are held to one standard instead of drifting into two.
    fn assert_shippable(name: &str, raw: &str) {
        let cfg = load(raw, None, "abcd1234abcd1234", false, "")
            .unwrap_or_else(|e| panic!("{name}: must parse with the client loader: {e}"));

        assert!(
            cfg.endpoints.len() >= 10,
            "{name}: suspiciously few endpoints ({})",
            cfg.endpoints.len()
        );

        // An IP literal must lead: the normal connect path performs NO DNS lookup,
        // and in Iran the lookup itself marks the user.
        assert!(
            cfg.endpoints[0].host.parse::<std::net::IpAddr>().is_ok(),
            "{name}: first endpoint must be an IP literal, got {}",
            cfg.endpoints[0].host
        );

        // A distinctive name is last-resort only — one lookup of it returns the
        // whole fleet, so it must never sort before a CDN front.
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
        // 🚨 v4 FIRST, v6 LAST. Reversed 2026-09-23 on the owner's call: v6 was
        // put first in `Prefer IPv6 everywhere except Iran` on the argument that
        // v6 transit is effectively free for us, with IR and RU carved out as
        // exceptions. That was never the intent -- v6 is the fallback, not the
        // lead, and carving out the two regions that matter most is a sign the
        // default was wrong rather than the exceptions right. `ipv6_first` is
        // now false in every config and v6 sorts behind v4 everywhere.
        if let (Some(a), Some(b)) = (pos("-v4."), pos("-v6.")) {
            assert!(a < b, "{name}: v4 region name must be dialled before the v6 one ({a} vs {b})");
        }
        // v4 literal, then v6 literal, then CDN — the whole ladder, in the order
        // the client actually walks.
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
        // Nothing may be dialled twice — a duplicate costs a second 8s timeout
        // out of a budget that already cannot reach the end of the list.
        let mut seen = std::collections::HashSet::new();
        for &i in &p.order {
            let e = &cfg.endpoints[i];
            assert!(seen.insert((e.host.as_str(), e.port)),
                    "{name}: {} is in the dial order twice", e.host);
        }

        // CDN fronts must carry their own Host header or CloudFront can't route.
        for e in cfg.endpoints.iter().filter(|e| e.host.ends_with(".cloudfront.net")) {
            assert_eq!(e.upgrade_host, e.host, "{name}: CDN front {} needs its own Host", e.host);
        }

        // Something must remain when the direct IPs are blocked — that is the
        // entire point of the CDN tier.
        assert!(
            cfg.endpoints.iter().any(|e| e.fallback),
            "{name}: no fallback tier — nothing to try when the direct IPs are blocked"
        );

        // `mux` fails closed, which is right for safety and silent on a typo:
        // every device reverts to one handshake per destination and nothing
        // reports it.
        assert!(cfg.mux, "{name}: transport.redsocket.mux must be true");

        let v: serde_json::Value = serde_json::from_str(raw).expect("valid json");

        let mut notes = Vec::new();
        note_keys(&v, "", &mut notes);
        assert!(
            notes.is_empty(),
            "{name}: note keys must live in the .notes.md sidecar, not the shipped JSON: {notes:?}"
        );

        // The regions the UI offers must each resolve to endpoints, and must lead
        // with their own IP — otherwise picking 🇩🇪 Frankfurt can exit via LA.
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

        // Freedom regions are given the product for free. Dropping one silently
        // starts charging people the project decided not to charge — and the
        // same table drives the v4/v6 preference, so a missing IR also costs
        // every Iranian device a guaranteed v6 timeout.
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

        // Ad revenue split. A brand that silently ships 0 hands the whole
        // impression to one demand source with nobody watching the number.
        //
        // ⚠️ This used to pin the value at exactly 50, which made a routine
        // policy change fail a TRANSPORT-CORE test — the knob is meant to be
        // tunable per brand. It now checks the range and that the key exists,
        // which is what the comment above actually argues for.
        //
        // redvpn moved to 100 on 2026-09-23 because our own exchange was
        // answering `[adx] ad-request failed: timed out` and filling nothing,
        // so a 50% split was throwing away half the impressions.
        let ads = v.get("ads").and_then(|a| a.as_object()).unwrap_or_else(|| panic!("{name}: no ads block"));
        let share = ads
            .get("admob_share_pct")
            .and_then(|p| p.as_u64())
            .unwrap_or_else(|| panic!("{name}: ads.admob_share_pct missing"));
        assert!(
            share <= 100,
            "{name}: ads.admob_share_pct is {share}, which is not a percentage"
        );
    }

    /// Every brand's fleet config parses and satisfies the shipping invariants.
    #[test]
    fn every_brand_config_is_shippable() {
        for (name, raw) in brand_configs() {
            assert_shippable(&name, &raw);
        }
    }

    /// The bundled asset is a config too, and must clear the same bar. If this
    /// and the brand test ever disagree, one of them is asserting the wrong
    /// thing — not two different standards.
    #[test]
    fn the_bundled_asset_clears_the_same_bar() {
        assert_shippable(
            "default-config.json",
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json"),
        );
    }

    /// A brand config must not fall behind the bundled asset's schema. They are
    /// the same document served two ways (bundled boot config vs OTA), so a key
    /// the client learned to parse and a brand's file never grew is a feature
    /// that silently switches off for that brand alone the moment its config is
    /// published — the drift that is hardest to see, because everything still
    /// works everywhere else.
    #[test]
    fn no_brand_config_is_missing_a_block_the_shipped_asset_has() {
        const SHIPPED: &str =
            include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
        let shipped: serde_json::Value = serde_json::from_str(SHIPPED).unwrap();

        /// Paths present in `a` but absent in `b`, to a bounded depth — deep
        /// enough to catch a whole block, shallow enough that per-endpoint
        /// differences (which are legitimately per-brand) are not flagged.
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
                 Regenerate the brand configs, or delete the key from the asset if it is dead."
            );
        }
    }
}
