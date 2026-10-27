//! On-device destination filtering — malware and bandwidth-consuming SDK
//! endpoints, refused before a carrier is ever dialled.
//!
//! # Where it sits
//!
//! `socks5::handle` parses the CONNECT, then asks [`blocked`] before
//! `Upstream::dial`. Nothing here touches the wire: a blocked request never
//! reaches a carrier, and an allowed one is byte-identical to what shipped
//! before this module existed. The app routes DNS through mapdns
//! (`198.18.0.2`), so hev hands us real hostnames rather than resolved
//! addresses — which is the only reason domain matching is possible at all.
//!
//! # Fail open, always
//!
//! Every failure mode — no list, unreachable URL, malformed JSON, an oversized
//! hostname, a panic in the matcher — returns "not blocked". This is
//! circumvention software: a filter that can break connectivity is strictly
//! worse than no filter. The hot path is additionally wrapped in
//! `catch_unwind`, so even a bug in here costs one allow rather than one dead
//! connection.
//!
//! # Precedence (strict, and load-bearing)
//!
//!   1. **self-protection** — hosts the core must never cut off (the update URL
//!      itself, the cashier bases, the health probe). A shipped list that
//!      blocked its own update URL could never be corrected.
//!   2. **user allow** — absolute. The false-positive escape hatch: if the user
//!      says "let this through", it goes through, over their own deny list and
//!      over the shipped list both. Anything less is not an escape hatch.
//!   3. **user deny** — the user's own additions.
//!   4. **shipped list** — the fetched JSON.
//!
//! # Privacy
//!
//! The set of hosts a user tried to reach is a record of what they were doing,
//! and these users are in Iran. Blocked hosts live in a bounded in-memory ring
//! (see [`RECENT_CAP`]), are never written to disk, never leave the device, and
//! are cleared on [`reset`] — i.e. every disconnect. Nothing here is logged
//! above `debug`, which `release_max_level_warn` compiles out of the shipped
//! binary entirely. The user's own rules DO persist (they would be useless
//! otherwise) in the app-private `redsocket` prefs, and are equally never
//! reported anywhere.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use crate::config::BlocklistSettings;

/// Longest hostname we will normalise on the stack. RFC 1035 caps a name at
/// 255 bytes; anything longer is not a real destination and is allowed through
/// rather than allocated for.
const MAX_HOST_LEN: usize = 255;
/// Label suffixes probed per lookup. `a.b.c.d.example.com` is 6. The cap bounds
/// the work a pathological hostname can cause on the hot path.
const MAX_LABELS: usize = 16;
/// Ceiling on a fetched list, so a wrong or hostile URL cannot exhaust RAM.
const MAX_RULES: usize = 200_000;
const MAX_CATEGORIES: usize = 64;
/// Per-list cap on the user's own entries. Generous for a human, bounded
/// against a runaway caller filling SharedPreferences.
const MAX_USER_RULES: usize = 500;
/// Blocked destinations retained for the UI. Memory only, session only.
const RECENT_CAP: usize = 50;
/// Category recorded for a block that came from the user's own deny list.
const USER_CATEGORY: &str = "user";
/// Pref key holding the user's own rules. Same `redsocket` file as the cid.
pub const USER_RULES_KEY: &str = "redsocket_rules";

// ============================================================================
// Rule storage
// ============================================================================

#[derive(Clone, Copy)]
struct Rule {
    cat: u16,
    /// `true` matches the hostname only; `false` (the default) also matches
    /// every subdomain of it.
    exact: bool,
}

/// An immutable, compiled set of rules. Built once per list install, then only
/// read — so lookups need no synchronisation of their own.
///
/// **Domains** are a hash map keyed on the *label suffix*, probed from the
/// right: `ads.doubleclick.net` probes `ads.doubleclick.net`, then
/// `doubleclick.net`, then `net`. Cutting only at dots is what makes
/// `notdoubleclick.net` a non-match for free — there is no substring compare
/// anywhere in here. Cost is O(labels), not O(rules), so a 100 K-entry list
/// costs the same as a 10-entry one.
///
/// **Networks** are grouped by prefix length, longest first, each group a hash
/// map of masked network → category. A lookup is one probe per *distinct
/// prefix length present* (in practice a handful), never a scan of the list.
#[derive(Default)]
pub struct RuleSet {
    domains: HashMap<Box<str>, Rule>,
    v4: Vec<(u8, HashMap<u32, u16>)>,
    v6: Vec<(u8, HashMap<u128, u16>)>,
    cats: Vec<Box<str>>,
    version: u64,
    etag: Option<String>,
    installed_at: Option<Instant>,
}

impl RuleSet {
    fn is_empty(&self) -> bool {
        self.domains.is_empty() && self.v4.is_empty() && self.v6.is_empty()
    }

    fn len(&self) -> usize {
        self.domains.len()
            + self.v4.iter().map(|(_, m)| m.len()).sum::<usize>()
            + self.v6.iter().map(|(_, m)| m.len()).sum::<usize>()
    }

    fn category(&self, id: u16) -> &str {
        self.cats.get(id as usize).map(|c| &**c).unwrap_or("blocked")
    }

    /// Does `host` match? `host` must already be lowercased and trailing-dot
    /// stripped (see [`normalize`]).
    ///
    /// Allocation-free and lock-free: every probe borrows a slice of `host`.
    fn matches(&self, host: &str) -> Option<u16> {
        if let Ok(ip) = host.parse::<IpAddr>() {
            return self.match_ip(ip);
        }
        if self.domains.is_empty() {
            return None;
        }
        // The full hostname matches an `exact` rule as well as a suffix one.
        if let Some(r) = self.domains.get(host) {
            return Some(r.cat);
        }
        let mut rest = host;
        for _ in 0..MAX_LABELS {
            let dot = rest.find('.')?;
            rest = &rest[dot + 1..];
            if rest.is_empty() {
                return None;
            }
            // A parent-label hit is a suffix match, so an `exact` rule declines.
            if let Some(r) = self.domains.get(rest) {
                if !r.exact {
                    return Some(r.cat);
                }
            }
        }
        None
    }

    fn match_ip(&self, ip: IpAddr) -> Option<u16> {
        match ip {
            IpAddr::V4(a) => self.match_v4(u32::from(a)),
            IpAddr::V6(a) => {
                // hev sends v4 as atyp 0x01, but a v4-mapped v6 literal must not
                // be a way around a v4 rule.
                if let Some(m) = a.to_ipv4_mapped() {
                    if let Some(c) = self.match_v4(u32::from(m)) {
                        return Some(c);
                    }
                }
                self.match_v6(u128::from(a))
            }
        }
    }

    fn match_v4(&self, n: u32) -> Option<u16> {
        for (len, m) in &self.v4 {
            let mask = if *len == 0 { 0 } else { u32::MAX << (32 - *len) };
            if let Some(c) = m.get(&(n & mask)) {
                return Some(*c);
            }
        }
        None
    }

    fn match_v6(&self, n: u128) -> Option<u16> {
        for (len, m) in &self.v6 {
            let mask = if *len == 0 { 0 } else { u128::MAX << (128 - *len) };
            if let Some(c) = m.get(&(n & mask)) {
                return Some(*c);
            }
        }
        None
    }
}

/// Accumulates rules, then freezes them into a [`RuleSet`].
#[derive(Default)]
struct Builder {
    domains: HashMap<Box<str>, Rule>,
    v4: BTreeMap<u8, HashMap<u32, u16>>,
    v6: BTreeMap<u8, HashMap<u128, u16>>,
    cats: Vec<Box<str>>,
    skipped: usize,
}

impl Builder {
    fn total(&self) -> usize {
        self.domains.len()
            + self.v4.values().map(|m| m.len()).sum::<usize>()
            + self.v6.values().map(|m| m.len()).sum::<usize>()
    }

    fn category(&mut self, name: &str) -> u16 {
        let n = name.trim().to_lowercase();
        let n = if n.is_empty() || n.len() > 32 { "blocked".to_string() } else { n };
        if let Some(i) = self.cats.iter().position(|c| **c == *n) {
            return i as u16;
        }
        if self.cats.len() >= MAX_CATEGORIES {
            return 0;
        }
        self.cats.push(n.into_boxed_str());
        (self.cats.len() - 1) as u16
    }

    /// Add one entry. Accepts a domain, an IP literal, or a CIDR — the shapes
    /// are told apart here rather than in the schema, so an operator putting an
    /// IP under `domains` still gets what they meant.
    ///
    /// A leading `=` means exact-only (no subdomains); `*.` and a leading dot
    /// are accepted and stripped, because every blocklist format in the world
    /// writes them and silently dropping those entries is a quiet failure.
    fn add(&mut self, raw: &str, cat: u16) {
        if self.total() >= MAX_RULES {
            self.skipped += 1;
            return;
        }
        let mut s = raw.trim();
        let exact = if let Some(r) = s.strip_prefix('=') {
            s = r;
            true
        } else {
            false
        };
        let s = s.trim_start_matches("*.").trim_matches('.').trim();
        if s.is_empty() || s.len() > MAX_HOST_LEN {
            self.skipped += 1;
            return;
        }
        if s.contains('/') {
            return self.add_cidr(s, cat);
        }
        // A bare IP is a /32 or /128, not a hostname.
        if let Ok(ip) = s.parse::<IpAddr>() {
            return self.add_net(ip, if ip.is_ipv4() { 32 } else { 128 }, cat);
        }
        let lower = s.to_lowercase();
        if lower.contains(char::is_whitespace) || !lower.is_ascii() && lower.contains(':') {
            self.skipped += 1;
            return;
        }
        self.domains.insert(lower.into_boxed_str(), Rule { cat, exact });
    }

    fn add_cidr(&mut self, s: &str, cat: u16) {
        let Some((addr, len)) = s.split_once('/') else {
            self.skipped += 1;
            return;
        };
        let (Ok(ip), Ok(len)) = (addr.trim().parse::<IpAddr>(), len.trim().parse::<u8>()) else {
            self.skipped += 1;
            return;
        };
        let max = if ip.is_ipv4() { 32 } else { 128 };
        if len > max {
            self.skipped += 1;
            return;
        }
        self.add_net(ip, len, cat);
    }

    fn add_net(&mut self, ip: IpAddr, len: u8, cat: u16) {
        match ip {
            IpAddr::V4(a) => {
                let mask = if len == 0 { 0 } else { u32::MAX << (32 - len) };
                self.v4.entry(len).or_default().insert(u32::from(a) & mask, cat);
            }
            IpAddr::V6(a) => {
                let mask = if len == 0 { 0 } else { u128::MAX << (128 - len) };
                self.v6.entry(len).or_default().insert(u128::from(a) & mask, cat);
            }
        }
    }

    fn finish(self, version: u64, etag: Option<String>) -> RuleSet {
        // Longest prefix first: the most specific network wins, as it must.
        let mut v4: Vec<(u8, HashMap<u32, u16>)> = self.v4.into_iter().collect();
        v4.sort_by(|a, b| b.0.cmp(&a.0));
        let mut v6: Vec<(u8, HashMap<u128, u16>)> = self.v6.into_iter().collect();
        v6.sort_by(|a, b| b.0.cmp(&a.0));
        RuleSet {
            domains: self.domains,
            v4,
            v6,
            cats: self.cats,
            version,
            etag,
            installed_at: Some(Instant::now()),
        }
    }
}

/// Build a small ruleset from plain strings, all under one category.
fn ruleset_from(entries: &[String], category: &str) -> RuleSet {
    let mut b = Builder::default();
    let cat = b.category(category);
    for e in entries {
        b.add(e, cat);
    }
    b.finish(0, None)
}

// ============================================================================
// State
// ============================================================================

/// Master gate. Kotlin/Swift owns the policy (premium **or** freedom region);
/// the core cannot determine either. Defaults to false: a missing call must
/// never start filtering somebody's traffic.
static ENABLED: AtomicBool = AtomicBool::new(false);
/// `ENABLED` *and* there is something that could block. When false the hot path
/// is one relaxed atomic load and a return — no lock, no allocation, nothing.
/// That is the state for every user who is not entitled and every user whose
/// list has not arrived yet, which is the overwhelming majority of CONNECTs.
static ARMED: AtomicBool = AtomicBool::new(false);

/// The fetched list. Replaced wholesale on refresh; read via an `Arc` clone
/// taken under an uncontended read lock, which is released before matching —
/// so the match itself holds no lock. Writers run once per refresh interval.
static SHIPPED: RwLock<Option<Arc<RuleSet>>> = RwLock::new(None);
/// Compiled user allow/deny. Same shape, so user entries match by exactly the
/// same suffix rules as shipped ones and nothing about them is surprising.
static USER: RwLock<Option<Arc<UserRules>>> = RwLock::new(None);
/// Hosts no list may ever block — see the precedence note at the top.
static EXEMPT: RwLock<Option<Arc<RuleSet>>> = RwLock::new(None);
/// AUTOMATIC deny rules: hosts the app's reputation scores rated `blocked`
/// (REDSHIELD-TRAFFIC Amendment B). Exact host match, each with the score's
/// label and its own expiry. Kept apart from the user's lists: the user's
/// Allow overrides them, they are never persisted or sent upstream, and the
/// app replaces the whole set (`set_auto_rules_json`) after every score and
/// on every tunnel start.
static AUTO: RwLock<Option<Arc<AutoRules>>> = RwLock::new(None);
/// Block PROFILES (REDSHIELD-TRAFFIC Amendment C): named lists the user
/// switched on ("Adult content", a country's ranges, a custom list), each a
/// compiled RuleSet, so a lookup costs one hash probe per label (domains) or
/// per prefix length present (ranges), whatever the list size. Kept apart
/// from the user's lists, the shipped list and the automatic rules; last in
/// the precedence ladder. Memory only: the app reloads them on every start.
static PROFILES: RwLock<Option<Arc<BTreeMap<String, Arc<RuleSet>>>>> = RwLock::new(None);
/// COUNTRY-CODE profiles: a country or region profile decided by the
/// regional address map (`geo.rs`) instead of by a list body. Each is the
/// profile id and its member country codes; a literal-IP CONNECT whose
/// address the map places in one of them is refused with that id as the
/// reason. Kept apart from `PROFILES` on purpose: a country costs two bytes
/// here, not thousands of rule slots, so these never count against
/// `MAX_RULES`. Inert while no map is loaded. Memory only, like `PROFILES`.
static CC_PROFILES: RwLock<Option<Arc<BTreeMap<String, Vec<[u8; 2]>>>>> = RwLock::new(None);

/// The user's rules as authored, in order: the persisted source of truth from
/// which `USER` is compiled.
static USER_LISTS: Mutex<Option<UserLists>> = Mutex::new(None);
static USER_LOADED: AtomicBool = AtomicBool::new(false);

static BLOCKED_TOTAL: AtomicU64 = AtomicU64::new(0);
static STATS: Mutex<Option<Stats>> = Mutex::new(None);

static SETTINGS: Mutex<Option<BlocklistSettings>> = Mutex::new(None);
static REFRESHING: AtomicBool = AtomicBool::new(false);

#[derive(Default, Clone)]
struct UserLists {
    allow: Vec<String>,
    deny: Vec<String>,
}

#[derive(Default)]
struct AutoRules {
    /// host -> (label, expires)
    hosts: HashMap<String, (String, Instant)>,
}

/// Category recorded for an automatic refusal; the reason carries the label.
pub const AUTO_CATEGORY: &str = "auto";
/// Category recorded for a profile refusal; the reason carries the profile id.
pub const PROFILE_CATEGORY: &str = "profile";
const MAX_PROFILE_ID: usize = 64;
const MAX_AUTO_RULES: usize = 5_000;

#[derive(Default)]
struct UserRules {
    allow: RuleSet,
    deny: RuleSet,
}

struct Event {
    host: String,
    port: u16,
    category: String,
    at: Instant,
    /// Exit verdict detail (blk1). 0 / empty for on-device decisions.
    bits: u16,
    reason: String,
    /// false = notified and relayed, true = refused.
    refused: bool,
}

#[derive(Default)]
struct Stats {
    by_cat: BTreeMap<String, u64>,
    recent: VecDeque<Event>,
}

fn snapshot(lock: &RwLock<Option<Arc<RuleSet>>>) -> Option<Arc<RuleSet>> {
    lock.read().ok().and_then(|g| g.clone())
}

// ============================================================================
// The hot path
// ============================================================================

/// Should this CONNECT be refused? Called once per SOCKS5 CONNECT, before any
/// carrier is dialled.
///
/// Returns `false` for anything it cannot decide — see the fail-open note at
/// the top. Records the block (bounded, in memory) when it returns `true`.
pub fn blocked(host: &str, port: u16) -> bool {
    if !ARMED.load(Ordering::Relaxed) {
        return false;
    }
    // A panic in here must cost one allowed connection, never a dead one.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut buf = [0u8; MAX_HOST_LEN];
        let Some(h) = normalize(host, &mut buf) else { return false };

        let exempt = snapshot(&EXEMPT);
        let user = USER.read().ok().and_then(|g| g.clone());
        let shipped = snapshot(&SHIPPED);

        let Some(hit) = decide(exempt.as_deref(), user.as_deref(), shipped.as_deref(), h)
            .or_else(|| auto_decide(exempt.as_deref(), user.as_deref(), h, Instant::now()))
            .or_else(|| profile_decide(exempt.as_deref(), user.as_deref(), h))
            .or_else(|| cc_profile_decide(exempt.as_deref(), user.as_deref(), h))
        else {
            return false;
        };
        record(h, port, &hit);
        true
    }))
    .unwrap_or(false)
}

struct Hit {
    category: String,
    /// The automatic rule's label; empty otherwise.
    reason: String,
}

/// Step 3b of the ladder, after the user's lists and only when `decide` let
/// the host through: an unexpired automatic rule for exactly this host.
/// Exempt hosts and the user's Allow win, as they do over every list.
fn auto_decide(exempt: Option<&RuleSet>, user: Option<&UserRules>, host: &str, now: Instant) -> Option<Hit> {
    let auto = AUTO.read().ok().and_then(|g| g.clone())?;
    let (label, expires) = auto.hosts.get(host)?;
    if *expires <= now
        || exempt.is_some_and(|e| e.matches(host).is_some())
        || user.is_some_and(|u| u.allow.matches(host).is_some())
    {
        return None;
    }
    Some(Hit { category: AUTO_CATEGORY.to_string(), reason: label.clone() })
}

/// Last step: the first enabled profile (by id) that covers the host.
/// Exempt hosts and the user's Allow win, as they do over every list.
fn profile_decide(exempt: Option<&RuleSet>, user: Option<&UserRules>, host: &str) -> Option<Hit> {
    let profiles = PROFILES.read().ok().and_then(|g| g.clone())?;
    if exempt.is_some_and(|e| e.matches(host).is_some()) || user.is_some_and(|u| u.allow.matches(host).is_some()) {
        return None;
    }
    profiles
        .iter()
        .find(|(_, set)| set.matches(host).is_some())
        .map(|(id, _)| Hit { category: PROFILE_CATEGORY.to_string(), reason: id.clone() })
}

/// The country-code profiles: a literal-IP destination the regional map
/// places in a selected country or region. Same precedence as the list
/// profiles (after them; exempt hosts and the user's Allow win); hostnames
/// are never classified here (the exit resolves them), so they keep coming
/// through the score-driven automatic rules. `None` when no map is loaded.
fn cc_profile_decide(exempt: Option<&RuleSet>, user: Option<&UserRules>, host: &str) -> Option<Hit> {
    let profiles = CC_PROFILES.read().ok().and_then(|g| g.clone())?;
    let cc = crate::geo::lookup_host(host)?;
    if exempt.is_some_and(|e| e.matches(host).is_some()) || user.is_some_and(|u| u.allow.matches(host).is_some()) {
        return None;
    }
    profiles
        .iter()
        .find(|(_, codes)| codes.contains(&cc))
        .map(|(id, _)| Hit { category: PROFILE_CATEGORY.to_string(), reason: id.clone() })
}

/// The precedence ladder, pure and unit-tested. `host` is already normalised.
fn decide(
    exempt: Option<&RuleSet>,
    user: Option<&UserRules>,
    shipped: Option<&RuleSet>,
    host: &str,
) -> Option<Hit> {
    // 1. Never cut off the paths that carry corrections.
    if exempt.is_some_and(|e| e.matches(host).is_some()) {
        return None;
    }
    if let Some(u) = user {
        // 2. The user's word is final, over their own deny list and ours.
        if u.allow.matches(host).is_some() {
            return None;
        }
        // 3. The user's own additions.
        if u.deny.matches(host).is_some() {
            return Some(Hit { category: USER_CATEGORY.to_string(), reason: String::new() });
        }
    }
    // 4. The shipped list.
    let s = shipped?;
    let cat = s.matches(host)?;
    Some(Hit { category: s.category(cat).to_string(), reason: String::new() })
}

/// Lowercase into a caller-owned stack buffer and strip a trailing dot, so the
/// hot path never allocates. `None` for anything that cannot be a real
/// hostname, which the caller treats as "allow".
fn normalize<'a>(host: &str, buf: &'a mut [u8; MAX_HOST_LEN]) -> Option<&'a str> {
    let h = host.trim().trim_end_matches('.');
    // A bracketed v6 literal would never parse as an IpAddr.
    let h = h.strip_prefix('[').map(|s| s.trim_end_matches(']')).unwrap_or(h);
    let b = h.as_bytes();
    if b.is_empty() || b.len() > MAX_HOST_LEN {
        return None;
    }
    if b.is_ascii() {
        for (i, c) in b.iter().enumerate() {
            buf[i] = c.to_ascii_lowercase();
        }
        // Safety-free: ASCII in, ASCII out.
        return std::str::from_utf8(&buf[..b.len()]).ok();
    }
    // Non-ASCII (an IDN that was never punycoded). Copy verbatim: lowercasing
    // Unicode needs an allocation, and these do not appear in practice.
    buf[..b.len()].copy_from_slice(b);
    std::str::from_utf8(&buf[..b.len()]).ok()
}

fn record(host: &str, port: u16, hit: &Hit) {
    BLOCKED_TOTAL.fetch_add(1, Ordering::Relaxed);
    if let Ok(mut g) = STATS.lock() {
        let s = g.get_or_insert_with(Stats::default);
        *s.by_cat.entry(hit.category.clone()).or_insert(0) += 1;
        if s.recent.len() >= RECENT_CAP {
            s.recent.pop_front();
        }
        s.recent.push_back(Event {
            host: host.to_string(),
            port,
            category: hit.category.clone(),
            at: Instant::now(),
            bits: 0,
            reason: hit.reason.clone(),
            refused: true,
        });
    }
}

/// An exit verdict (blk1) for `host:port`: refused (`blocked`) or notified and
/// relayed. Same bounded, memory-only ring as on-device decisions; the reason
/// string is the feed's own one-line explanation.
pub fn record_remote(host: &str, port: u16, bits: u16, reason: &str, blocked: bool) {
    if blocked {
        BLOCKED_TOTAL.fetch_add(1, Ordering::Relaxed);
    }
    let category = reason.split(':').next().unwrap_or("").to_string();
    if let Ok(mut g) = STATS.lock() {
        let s = g.get_or_insert_with(Stats::default);
        if blocked {
            *s.by_cat.entry(category.clone()).or_insert(0) += 1;
        }
        if s.recent.len() >= RECENT_CAP {
            s.recent.pop_front();
        }
        s.recent.push_back(Event {
            host: host.chars().take(MAX_HOST_LEN).collect(),
            port,
            category,
            at: Instant::now(),
            bits,
            reason: reason.chars().take(255).collect(),
            refused: blocked,
        });
    }
}

// ============================================================================
// Arming
// ============================================================================

fn rearm() {
    let has_list = snapshot(&SHIPPED).is_some_and(|s| !s.is_empty());
    let has_user_deny = USER
        .read()
        .ok()
        .and_then(|g| g.clone())
        .is_some_and(|u| !u.deny.is_empty());
    let has_auto = AUTO.read().ok().and_then(|g| g.clone()).is_some_and(|a| !a.hosts.is_empty());
    let has_profiles = PROFILES.read().ok().and_then(|g| g.clone()).is_some_and(|p| !p.is_empty());
    // Country-code profiles can only decide with a map to ask.
    let has_cc = crate::geo::is_loaded()
        && CC_PROFILES.read().ok().and_then(|g| g.clone()).is_some_and(|p| !p.is_empty());
    let armed = ENABLED.load(Ordering::SeqCst) && (has_list || has_user_deny || has_auto || has_profiles || has_cc);
    ARMED.store(armed, Ordering::SeqCst);
}

/// Turn filtering on or off. The entitlement decision (premium **or** freedom
/// region) lives in the app — the core cannot see either — so this is the whole
/// gate and it defaults to off.
pub fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::SeqCst);
    ensure_user_loaded();
    rearm();
    log::info!("[bl] filtering {}", if on { "on" } else { "off" });
}

pub fn is_enabled() -> bool {
    ENABLED.load(Ordering::SeqCst)
}

// ============================================================================
// Installing a list
// ============================================================================

/// Parse and install a blocklist document.
///
/// # Schema
///
/// ```json
/// {
///   "version": 12,
///   "updated": 1757308800,
///   "ttl_secs": 21600,
///   "block": {
///     "malware":   { "domains": ["evil.example", "=exact-only.example"],
///                    "cidrs":   ["198.51.100.0/24"] },
///     "ads":       { "domains": ["doubleclick.net"] },
///     "telemetry": { "domains": ["metrics.example"] }
///   }
/// }
/// ```
///
/// The category is the map key, so it costs nothing per entry — which matters
/// at tens of thousands of them. A flat `{"domains": [...], "cidrs": [...]}` is
/// also accepted and lands under the category `blocked`.
///
/// Entry syntax: a bare name blocks it **and every subdomain**; `=name` blocks
/// only that exact name; `*.name` and `.name` are accepted and normalised. An
/// IP or CIDR under `domains` is understood as a network — operators mix them
/// up and silently dropping the entry is a quiet failure.
///
/// Bad entries are skipped and counted; only a document that is not JSON, or
/// that yields nothing usable, is an error — and an error leaves the previous
/// list in place.
pub fn install_json(json: &str, etag: Option<String>) -> Result<String, String> {
    let (set, skipped) = build_json(json, etag)?;
    let version = set.version;
    let (domains, nets) = (set.domains.len(), set.len() - set.domains.len());
    if let Ok(mut g) = SHIPPED.write() {
        *g = Some(Arc::new(set));
    }
    rearm();
    log::info!("[bl] list v{version}: {domains} domains, {nets} networks, {skipped} skipped");
    Ok(format!("ok:{domains}:{nets}:{skipped}"))
}

/// Parse without publishing. Split out so a failed parse provably cannot
/// disturb the list already in force — the previous one stays until a whole new
/// one has been built successfully.
fn build_json(json: &str, etag: Option<String>) -> Result<(RuleSet, usize), String> {
    let v: serde_json::Value = serde_json::from_str(json).map_err(|e| format!("parse: {e}"))?;
    let version = v.get("version").and_then(|x| x.as_u64()).unwrap_or(0);

    let mut b = Builder::default();
    let add_group = |b: &mut Builder, cat: u16, group: &serde_json::Value| {
        for key in ["domains", "hosts", "cidrs", "ips", "networks"] {
            if let Some(arr) = group.get(key).and_then(|x| x.as_array()) {
                for e in arr.iter().filter_map(|x| x.as_str()) {
                    b.add(e, cat);
                }
            }
        }
        // A category may also be a bare array of entries.
        if let Some(arr) = group.as_array() {
            for e in arr.iter().filter_map(|x| x.as_str()) {
                b.add(e, cat);
            }
        }
    };

    if let Some(block) = v.get("block").and_then(|x| x.as_object()) {
        for (name, group) in block {
            let cat = b.category(name);
            add_group(&mut b, cat, group);
        }
    }
    // Flat form, and any top-level lists alongside `block`.
    let flat = b.category("blocked");
    add_group(&mut b, flat, &v);

    let skipped = b.skipped;
    if b.total() == 0 {
        return Err("no usable entries".into());
    }
    Ok((b.finish(version, etag), skipped))
}

/// Hosts that may never be blocked, from the config's own bootstrap blocks.
///
/// Without this a single bad entry in a shipped list could block the URL that
/// ships the correction, or the cashier the app needs for entitlement — and the
/// only way out would be a store release.
pub fn configure(s: &BlocklistSettings) {
    let exempt = ruleset_from(&s.never_block, "self");
    if let Ok(mut g) = EXEMPT.write() {
        *g = Some(Arc::new(exempt));
    }
    if let Ok(mut g) = SETTINGS.lock() {
        *g = Some(s.clone());
    }
    ensure_user_loaded();
    rearm();
}

// ============================================================================
// The user's own lists
// ============================================================================

fn ensure_user_loaded() {
    if USER_LOADED.swap(true, Ordering::SeqCst) {
        return;
    }
    let lists = crate::pref_get(USER_RULES_KEY)
        .and_then(|j| parse_user_lists(&j).ok())
        .unwrap_or_default();
    install_user(lists, false);
}

fn parse_user_lists(json: &str) -> Result<UserLists, String> {
    let v: serde_json::Value = serde_json::from_str(json).map_err(|e| format!("parse: {e}"))?;
    let take = |k: &str| -> Vec<String> {
        v.get(k)
            .and_then(|x| x.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str())
                    .map(|s| s.trim().to_lowercase())
                    .filter(|s| !s.is_empty())
                    .take(MAX_USER_RULES)
                    .collect()
            })
            .unwrap_or_default()
    };
    Ok(UserLists { allow: take("allow"), deny: take("deny") })
}

/// Compile and publish the user's lists, optionally persisting them.
///
/// Persisted separately from the fetched list and never touched by a refresh:
/// a user who fixed their banking app once must never have to fix it twice.
fn install_user(lists: UserLists, persist: bool) {
    let compiled = UserRules {
        allow: ruleset_from(&lists.allow, "allow"),
        deny: ruleset_from(&lists.deny, USER_CATEGORY),
    };
    if let Ok(mut g) = USER.write() {
        *g = Some(Arc::new(compiled));
    }
    if persist {
        let json = serde_json::json!({ "allow": lists.allow, "deny": lists.deny }).to_string();
        crate::pref_put(USER_RULES_KEY, &json);
    }
    if let Ok(mut g) = USER_LISTS.lock() {
        *g = Some(lists);
    }
    rearm();
}

/// Add or clear one user rule. `verdict` is `allow`, `deny` or `clear`.
///
/// Takes effect on the **next CONNECT** — no reconnect, no list refetch — which
/// is what makes "allow this" usable from the block notice itself.
pub fn set_user_rule(host: &str, verdict: &str) -> Result<String, String> {
    ensure_user_loaded();
    let h = host.trim().trim_start_matches("*.").trim_matches('.').to_lowercase();
    if h.is_empty() || h.len() > MAX_HOST_LEN || h.contains(char::is_whitespace) {
        return Err("bad host".into());
    }
    let mut lists = USER_LISTS.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    lists.allow.retain(|e| *e != h);
    lists.deny.retain(|e| *e != h);
    match verdict {
        "allow" => {
            if lists.allow.len() >= MAX_USER_RULES {
                return Err("allow list full".into());
            }
            lists.allow.push(h.clone());
        }
        "deny" => {
            if lists.deny.len() >= MAX_USER_RULES {
                return Err("deny list full".into());
            }
            lists.deny.push(h.clone());
        }
        "clear" => {}
        other => return Err(format!("unknown verdict '{other}'")),
    }
    install_user(lists, true);
    crate::ctl::push_rules();
    Ok(format!("ok:{verdict}"))
}

/// Replace both user lists wholesale — bulk edit, and the restore path on
/// platforms where the app owns persistence (iOS has no SharedPreferences).
pub fn set_user_lists_json(json: &str) -> Result<String, String> {
    USER_LOADED.store(true, Ordering::SeqCst);
    let lists = parse_user_lists(json)?;
    // Optional: the category bits the user switched off. Rides this call so
    // neither platform needs a new export; sent upstream in {rules}.
    if let Some(m) = serde_json::from_str::<serde_json::Value>(json)
        .ok()
        .and_then(|v| v.get("categories_off").and_then(|m| m.as_u64()))
    {
        crate::ctl::set_categories_off(m as u16);
    }
    let (a, d) = (lists.allow.len(), lists.deny.len());
    install_user(lists, true);
    crate::ctl::push_rules();
    Ok(format!("ok:{a}:{d}"))
}

/// Replace the automatic deny set: `{"rules":[{"host":"x","label":"Phishing","ttl_secs":3600}]}`.
/// Hosts are matched exactly (normalised); a rule with no TTL left is dropped.
/// Returns `ok:<n>` (rules installed).
pub fn set_auto_rules_json(json: &str) -> Result<String, String> {
    let v: serde_json::Value = serde_json::from_str(json).map_err(|e| format!("bad json: {e}"))?;
    let now = Instant::now();
    let mut hosts = HashMap::new();
    for r in v.get("rules").and_then(|r| r.as_array()).map(|a| a.as_slice()).unwrap_or(&[]) {
        if hosts.len() >= MAX_AUTO_RULES {
            break;
        }
        let Some(host) = r.get("host").and_then(|h| h.as_str()) else { continue };
        let ttl = r.get("ttl_secs").and_then(|t| t.as_u64()).unwrap_or(0).min(86_400);
        if ttl == 0 {
            continue;
        }
        let mut buf = [0u8; MAX_HOST_LEN];
        let Some(h) = normalize(host, &mut buf) else { continue };
        let label: String = r.get("label").and_then(|l| l.as_str()).unwrap_or("").chars().take(64).collect();
        hosts.insert(h.to_string(), (label, now + Duration::from_secs(ttl)));
    }
    let n = hosts.len();
    if let Ok(mut g) = AUTO.write() {
        *g = if n == 0 { None } else { Some(Arc::new(AutoRules { hosts })) };
    }
    rearm();
    Ok(format!("ok:{n}"))
}

/// Names a hosts file maps to itself; never a thing to block.
const HOSTS_LOCAL: [&str; 8] = [
    "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
    "0.0.0.0", "ip6-localnet",
];

/// Compile a profile body: one entry per line, `#` comments and blank lines
/// ignored, hosts-file lines (`0.0.0.0 a.example b.example`) taken by their
/// names. `kind` = `domains` (suffixes; IP and CIDR lines skipped) or
/// `ranges` (CIDRs or bare IPs; names skipped). Returns (set, skipped).
fn compile_profile(kind: &str, body: &str) -> Result<(RuleSet, usize), String> {
    let ranges = match kind {
        "domains" => false,
        "ranges" => true,
        other => return Err(format!("unknown kind '{other}'")),
    };
    let mut b = Builder::default();
    let cat = b.category(PROFILE_CATEGORY);
    let mut skipped = 0usize;
    for line in body.lines() {
        let line = line.split('#').next().unwrap_or("").trim();
        if line.is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split_whitespace().collect();
        // Hosts-file shape: an address, then one or more names.
        let entries: &[&str] = if !ranges && fields.len() >= 2 && fields[0].parse::<IpAddr>().is_ok() {
            &fields[1..]
        } else if fields.len() == 1 {
            &fields[..]
        } else {
            skipped += 1;
            continue;
        };
        for e in entries {
            let looks_net = e.contains('/') || e.trim_start_matches('=').parse::<IpAddr>().is_ok();
            if looks_net != ranges || (!ranges && HOSTS_LOCAL.contains(&e.to_ascii_lowercase().as_str())) {
                skipped += 1;
                continue;
            }
            b.add(e, cat);
        }
    }
    skipped += b.skipped;
    Ok((b.finish(0, None), skipped))
}

/// Install or replace profile `id`. `ok:<entries>:<skipped>`; refuses with
/// `limit:<would_be_total>:<MAX_RULES>` (state unchanged) when every profile
/// plus the shipped list would exceed the core's rule limit.
///
/// `kind` = `cc` installs a COUNTRY-CODE profile instead: `body` is the
/// member country codes (whitespace or comma separated; empty = derive them
/// from the id: `country:XX` -> XX, `region:<id>` -> the embedded region
/// table). Decided by the regional address map, so it takes no rule slots
/// and the limit does not apply; it replaces any list body held under the
/// same id. Returns `ok:<codes>:0`.
pub fn set_profile(id: &str, kind: &str, body: &str) -> Result<String, String> {
    let id = id.trim();
    if id.is_empty() || id.len() > MAX_PROFILE_ID || id.contains(char::is_whitespace) {
        return Err("bad id".into());
    }
    if kind == "cc" {
        return set_cc_profile(id, body);
    }
    let (set, skipped) = compile_profile(kind, body)?;
    let entries = set.len();
    let mut g = PROFILES.write().map_err(|_| "lock".to_string())?;
    let current = g.clone().unwrap_or_default();
    let others: usize = current.iter().filter(|(k, _)| k.as_str() != id).map(|(_, s)| s.len()).sum();
    let shipped = snapshot(&SHIPPED).map(|s| s.len()).unwrap_or(0);
    let total = shipped + others + entries;
    if total > MAX_RULES {
        return Err(format!("limit:{total}:{MAX_RULES}"));
    }
    let mut next = (*current).clone();
    next.insert(id.to_string(), Arc::new(set));
    *g = Some(Arc::new(next));
    drop(g);
    // One id, one meaning: a list body replaces a country-code twin.
    remove_cc_profile(id);
    rearm();
    Ok(format!("ok:{entries}:{skipped}"))
}

/// Parse a country-code list; empty derives it from the profile id.
fn cc_codes(id: &str, body: &str) -> Result<Vec<[u8; 2]>, String> {
    let mut codes: Vec<[u8; 2]> = Vec::new();
    let mut push = |c: &str| {
        let c = c.trim().to_ascii_uppercase();
        let b = c.as_bytes();
        if b.len() == 2 && b.iter().all(|x| x.is_ascii_uppercase()) {
            let cc = [b[0], b[1]];
            if !codes.contains(&cc) {
                codes.push(cc);
            }
        }
    };
    if body.trim().is_empty() {
        if let Some(cc) = id.strip_prefix("country:") {
            push(cc);
        } else if let Some(list) = crate::geo::region_countries(id) {
            list.iter().for_each(|c| push(c));
        } else {
            return Err("no codes".into());
        }
    } else {
        body.split(|c: char| c.is_whitespace() || c == ',' || c == ';').for_each(&mut push);
    }
    if codes.is_empty() {
        return Err("no codes".into());
    }
    Ok(codes)
}

fn set_cc_profile(id: &str, body: &str) -> Result<String, String> {
    let codes = cc_codes(id, body)?;
    let n = codes.len();
    if let Ok(mut g) = CC_PROFILES.write() {
        let mut next = g.clone().map(|c| (*c).clone()).unwrap_or_default();
        next.insert(id.to_string(), codes);
        *g = Some(Arc::new(next));
    }
    if let Ok(mut g) = PROFILES.write() {
        if let Some(cur) = g.clone() {
            if cur.contains_key(id) {
                let mut next = (*cur).clone();
                next.remove(id);
                *g = if next.is_empty() { None } else { Some(Arc::new(next)) };
            }
        }
    }
    rearm();
    Ok(format!("ok:{n}:0"))
}

fn remove_cc_profile(id: &str) {
    if let Ok(mut g) = CC_PROFILES.write() {
        if let Some(cur) = g.clone() {
            if cur.contains_key(id) {
                let mut next = (*cur).clone();
                next.remove(id);
                *g = if next.is_empty() { None } else { Some(Arc::new(next)) };
            }
        }
    }
}

/// Remove profile `id` (list body or country-code); no-op when not loaded.
pub fn clear_profile(id: &str) {
    if let Ok(mut g) = PROFILES.write() {
        if let Some(cur) = g.clone() {
            let mut next = (*cur).clone();
            next.remove(id.trim());
            *g = if next.is_empty() { None } else { Some(Arc::new(next)) };
        }
    }
    remove_cc_profile(id.trim());
    rearm();
}

/// Does a user ALLOW rule cover `host`? Sets the stream's "user allowed" flag
/// on blk1 tunnels, so the exit skips protection for it.
pub fn user_allows(host: &str) -> bool {
    ensure_user_loaded();
    let mut buf = [0u8; MAX_HOST_LEN];
    let Some(h) = normalize(host, &mut buf) else { return false };
    USER.read().ok().and_then(|g| g.clone()).is_some_and(|u| u.allow.matches(h).is_some())
}

/// The user's rules as authored: `{"allow":[…],"deny":[…]}`.
pub fn user_lists_json() -> String {
    ensure_user_loaded();
    let lists = USER_LISTS.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    serde_json::json!({ "allow": lists.allow, "deny": lists.deny }).to_string()
}

// ============================================================================
// Alerting
// ============================================================================

/// What the app needs to tell the user something true: the counts, and the
/// recent blocks with their category so "allow this" can be offered in place.
///
/// `recent` is capped, in memory, and gone on disconnect.
pub fn stats_json() -> String {
    let shipped = snapshot(&SHIPPED);
    let (version, domains, nets, age) = match shipped.as_deref() {
        Some(s) => (
            s.version,
            s.domains.len(),
            s.len() - s.domains.len(),
            s.installed_at.map(|t| t.elapsed().as_secs()),
        ),
        None => (0, 0, 0, None),
    };
    let (by_cat, recent): (BTreeMap<String, u64>, Vec<serde_json::Value>) = STATS
        .lock()
        .ok()
        .and_then(|g| {
            g.as_ref().map(|s| {
                let recent = s
                    .recent
                    .iter()
                    .rev()
                    .map(|e| {
                        serde_json::json!({
                            "host": e.host,
                            "port": e.port,
                            "category": e.category,
                            "secs_ago": e.at.elapsed().as_secs(),
                            "bits": e.bits,
                            "reason": e.reason,
                            "refused": e.refused,
                        })
                    })
                    .collect::<Vec<_>>();
                (s.by_cat.clone(), recent)
            })
        })
        .unwrap_or_default();
    serde_json::json!({
        "enabled": ENABLED.load(Ordering::SeqCst),
        "armed": ARMED.load(Ordering::SeqCst),
        "list_version": version,
        "domains": domains,
        "networks": nets,
        "list_age_secs": age,
        "blocked_total": BLOCKED_TOTAL.load(Ordering::Relaxed),
        "by_category": by_cat,
        "recent": recent,
    })
    .to_string()
}

/// Forget which hosts were blocked. The user's rules are untouched.
pub fn clear_history() {
    if let Ok(mut g) = STATS.lock() {
        *g = None;
    }
}

/// Called on tunnel stop. Drops the block history — it must not outlive the
/// session — and stops the refresh loop. The user's rules and the installed
/// list survive, so a reconnect does not refetch or re-ask.
pub fn reset() {
    clear_history();
    // Automatic rules are the app's to rebuild from its score cache on the
    // next start; none outlives the session in the core.
    if let Ok(mut g) = AUTO.write() {
        *g = None;
    }
    if let Ok(mut g) = PROFILES.write() {
        *g = None;
    }
    // Country-code profiles go with them; the regional map itself stays
    // (immutable, a few MB, re-sent by the app only when it changes).
    if let Ok(mut g) = CC_PROFILES.write() {
        *g = None;
    }
    rearm();
    REFRESHING.store(false, Ordering::SeqCst);
}

// ============================================================================
// Fetch — through the tunnel's own SOCKS5, after it is up
// ============================================================================

/// Start the refresh loop. No-op unless the config enables it and names a URL.
///
/// The request goes out through `127.0.0.1:<socks_port>` — the tunnel we just
/// brought up — so it is neither censorable nor a plaintext signal on the local
/// network that this device runs this app. It carries no cid, no device
/// identifier and no cookie: just `GET`, `Host` and an `If-None-Match`.
pub fn spawn_refresh(socks_port: u16, s: &BlocklistSettings) {
    if !s.enabled || s.url.is_empty() {
        log::info!("[bl] list refresh disabled (no url or not enabled)");
        return;
    }
    if REFRESHING.swap(true, Ordering::SeqCst) {
        return;
    }
    let s = s.clone();
    tokio::spawn(async move {
        refresh_loop(socks_port, s).await;
        REFRESHING.store(false, Ordering::SeqCst);
    });
}

async fn refresh_loop(socks_port: u16, s: BlocklistSettings) {
    // Spread the fleet: every client whose tunnel comes up in the same window
    // would otherwise hit the update host at once.
    {
        use rand::Rng;
        let jitter = rand::thread_rng().gen_range(2..=10);
        if !sleep_secs(jitter).await {
            return;
        }
    }
    let mut failures: u32 = 0;
    loop {
        if !crate::RUNNING.load(Ordering::SeqCst) || !REFRESHING.load(Ordering::SeqCst) {
            return;
        }
        match fetch_once(socks_port, &s).await {
            Ok(msg) => {
                failures = 0;
                log::info!("[bl] refresh: {msg}");
            }
            Err(e) => {
                failures = failures.saturating_add(1);
                // Warn, never fail the tunnel: the previous list (or none)
                // stays in force and traffic is unaffected either way.
                log::warn!("[bl] refresh failed ({failures}): {e}");
            }
        }
        let wait = if failures == 0 {
            s.refresh_secs
        } else {
            // 60s, doubling, capped at the normal interval.
            (60u64 << failures.min(8)).min(s.refresh_secs)
        };
        if !sleep_secs(wait).await {
            return;
        }
    }
}

/// Sleep in one-second ticks so a disconnect ends the loop promptly instead of
/// leaving a task parked for hours. `false` means "stop".
async fn sleep_secs(secs: u64) -> bool {
    for _ in 0..secs {
        if !crate::RUNNING.load(Ordering::SeqCst) || !REFRESHING.load(Ordering::SeqCst) {
            return false;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    true
}

async fn fetch_once(socks_port: u16, s: &BlocklistSettings) -> Result<String, String> {
    let url = parse_url(&s.url)?;
    let etag = snapshot(&SHIPPED).and_then(|r| r.etag.clone());
    let resp = tokio::time::timeout(
        Duration::from_secs(30),
        http_get(socks_port, &url, etag.as_deref(), s.max_bytes),
    )
    .await
    .map_err(|_| "timeout".to_string())??;

    match resp.status {
        // Unchanged — the whole point of keeping the etag.
        304 => Ok("not modified".into()),
        200 => {
            let body = String::from_utf8_lossy(&resp.body);
            install_json(&body, resp.etag)
        }
        c => Err(format!("http {c}")),
    }
}

struct Url {
    host: String,
    port: u16,
    path: String,
}

/// `http://host[:port][/path]` only.
///
/// This crate has **no TLS stack** — deliberately, see CLAUDE.md §6 — so an
/// `https://` URL is refused here rather than quietly half-implemented. The
/// transport is untrusted by design anyway: the request rides inside the Noise
/// tunnel, and a list served over plain HTTP should be authenticated the way
/// the config is (`cfgseal`) before it is trusted with anything more than the
/// advisory filtering it does today. Until then an operator who wants TLS
/// fetches the list in the app and pushes it in via `setBlocklist`.
fn parse_url(u: &str) -> Result<Url, String> {
    let rest = u
        .strip_prefix("http://")
        .ok_or_else(|| "only http:// is fetched by the core (no TLS stack)".to_string())?;
    let (hostport, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    if hostport.is_empty() {
        return Err("no host".into());
    }
    // `[v6]:port`
    let (host, port) = if let Some(end) = hostport.strip_prefix('[').and_then(|h| h.find(']').map(|i| (h, i))) {
        let (h, i) = end;
        let p = h[i + 1..].strip_prefix(':').and_then(|p| p.parse().ok()).unwrap_or(80);
        (h[..i].to_string(), p)
    } else {
        match hostport.rsplit_once(':') {
            Some((h, p)) => (h.to_string(), p.parse().map_err(|_| "bad port".to_string())?),
            None => (hostport.to_string(), 80),
        }
    };
    Ok(Url { host, port, path: path.to_string() })
}

struct Resp {
    status: u16,
    etag: Option<String>,
    body: Vec<u8>,
}

async fn http_get(
    socks_port: u16,
    url: &Url,
    etag: Option<&str>,
    max_bytes: usize,
) -> Result<Resp, String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut sock = socks_connect(socks_port, &url.host, url.port).await?;

    let mut req = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nAccept: application/json\r\n\
         Accept-Encoding: identity\r\nUser-Agent: {}\r\nConnection: close\r\n",
        url.path, url.host, FETCH_UA
    );
    if let Some(e) = etag {
        // Cheap refetch: unchanged lists cost one 304 and no body.
        req.push_str(&format!("If-None-Match: {e}\r\n"));
    }
    req.push_str("\r\n");
    sock.write_all(req.as_bytes()).await.map_err(|e| format!("write: {e}"))?;

    let mut raw = Vec::with_capacity(16 * 1024);
    let mut chunk = [0u8; 16 * 1024];
    loop {
        let n = sock.read(&mut chunk).await.map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        raw.extend_from_slice(&chunk[..n]);
        if raw.len() > max_bytes {
            return Err(format!("response over {max_bytes} bytes"));
        }
    }

    let sep = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| "no header terminator".to_string())?;
    let head = String::from_utf8_lossy(&raw[..sep]).to_string();
    let body = raw[sep + 4..].to_vec();

    let mut lines = head.lines();
    let status: u16 = lines
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|c| c.parse().ok())
        .ok_or_else(|| "bad status line".to_string())?;

    let mut etag_out = None;
    let mut chunked = false;
    for l in lines {
        let Some((k, v)) = l.split_once(':') else { continue };
        match k.trim().to_ascii_lowercase().as_str() {
            "etag" => etag_out = Some(v.trim().to_string()),
            "transfer-encoding" if v.to_ascii_lowercase().contains("chunked") => chunked = true,
            _ => {}
        }
    }
    let body = if chunked { dechunk(&body)? } else { body };
    Ok(Resp { status, etag: etag_out, body })
}

/// Plausible-but-unremarkable UA. The request rides inside the tunnel, so this
/// is cover against the origin's logs and any CDN in front of it, not a censor.
const FETCH_UA: &str = "okhttp/4.12.0";

/// A CDN in front of the origin may chunk the response; without this the JSON
/// parse would fail on the length prefixes and the feature would silently never
/// update.
fn dechunk(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(body.len());
    let mut i = 0;
    while i < body.len() {
        let Some(nl) = body[i..].windows(2).position(|w| w == b"\r\n") else { break };
        let line = std::str::from_utf8(&body[i..i + nl]).map_err(|_| "bad chunk header")?;
        let size = usize::from_str_radix(line.split(';').next().unwrap_or("").trim(), 16)
            .map_err(|_| "bad chunk size")?;
        i += nl + 2;
        if size == 0 {
            break;
        }
        if i + size > body.len() {
            return Err("truncated chunk".into());
        }
        out.extend_from_slice(&body[i..i + size]);
        i += size + 2;
    }
    Ok(out)
}

/// Dial `host:port` through the tunnel's own SOCKS5 front end — the same route
/// the blocklist fetch uses, in async form.
async fn socks_connect(
    socks_port: u16,
    host: &str,
    port: u16,
) -> Result<tokio::net::TcpStream, String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut s = tokio::time::timeout(
        Duration::from_secs(10),
        tokio::net::TcpStream::connect(("127.0.0.1", socks_port)),
    )
    .await
    .map_err(|_| "socks connect timeout".to_string())?
    .map_err(|e| format!("socks connect: {e}"))?;

    s.write_all(&[0x05, 0x01, 0x00]).await.map_err(|e| format!("socks greet: {e}"))?;
    let mut r = [0u8; 2];
    s.read_exact(&mut r).await.map_err(|e| format!("socks greet reply: {e}"))?;
    if r[0] != 0x05 || r[1] != 0x00 {
        return Err("socks auth rejected".into());
    }
    if host.len() > 255 {
        return Err("host too long".into());
    }
    let mut cmd = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
    cmd.extend_from_slice(host.as_bytes());
    cmd.extend_from_slice(&port.to_be_bytes());
    s.write_all(&cmd).await.map_err(|e| format!("socks cmd: {e}"))?;

    let mut reply = [0u8; 10];
    s.read_exact(&mut reply).await.map_err(|e| format!("socks reply: {e}"))?;
    if reply[1] != 0x00 {
        return Err(format!("socks refused: rep={}", reply[1]));
    }
    Ok(s)
}

/// Serialises every test that touches the process-global filter state, here and
/// in `socks5`. Without it those tests race and produce phantom results.
#[cfg(test)]
pub(crate) static TEST_GUARD: Mutex<()> = Mutex::new(());

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn profile_guard() -> std::sync::MutexGuard<'static, ()> {
        let g = TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        set_user_lists_json(r#"{"allow":[],"deny":[]}"#).unwrap();
        set_enabled(true);
        g
    }

    fn profile_teardown() {
        set_enabled(false);
        reset();
    }

    #[test]
    fn profile_domains_match_suffixes_and_record_the_id() {
        let _g = profile_guard();
        let r = set_profile("adult", "domains", "# comment\n\nporn.example\n*.tube.example\n10.0.0.0/8\nbad line here\n").unwrap();
        assert_eq!(r, "ok:2:2", "two names; the CIDR and the 3-field line skipped");
        assert!(blocked("porn.example", 443));
        assert!(blocked("www.porn.example", 443));
        assert!(blocked("a.b.tube.example", 443));
        assert!(!blocked("notporn.example", 443));
        assert!(!blocked("10.1.2.3", 443), "a domains profile has no ranges");
        let stats = stats_json();
        assert!(stats.contains("\"reason\":\"adult\"") && stats.contains(PROFILE_CATEGORY), "{stats}");
        profile_teardown();
    }

    #[test]
    fn profile_ranges_match_literal_ips_only() {
        let _g = profile_guard();
        assert_eq!(set_profile("country:cn", "ranges", "1.0.1.0/24\n2001:db8::/32\n203.0.113.7\nexample.cn\n").unwrap(), "ok:3:1");
        assert!(blocked("1.0.1.200", 443));
        assert!(!blocked("1.0.2.1", 443));
        assert!(blocked("2001:db8:1::5", 443));
        assert!(blocked("203.0.113.7", 80));
        assert!(!blocked("example.cn", 443), "names never match a ranges profile");
        profile_teardown();
    }

    #[test]
    fn profile_parses_hosts_files() {
        let _g = profile_guard();
        let body = "127.0.0.1 localhost\n::1 ip6-localhost ip6-loopback\n0.0.0.0 ads.example tracker.example # inline\n0.0.0.0 0.0.0.0\n";
        assert_eq!(set_profile("custom:mine", "domains", body).unwrap(), "ok:2:4");
        assert!(blocked("ads.example", 443) && blocked("x.tracker.example", 443));
        assert!(!blocked("localhost", 443));
        profile_teardown();
    }

    #[test]
    fn profile_limit_refuses_and_keeps_state() {
        let _g = profile_guard();
        // Another test may have left a shipped list installed: it counts too.
        let shipped = snapshot(&SHIPPED).map(|s| s.len()).unwrap_or(0);
        let big: String = (0..MAX_RULES - 10 - shipped).map(|i| format!("h{i}.example\n")).collect();
        set_profile("big", "domains", &big).unwrap();
        let err = set_profile("more", "domains", &(0..20).map(|i| format!("m{i}.example\n")).collect::<String>()).unwrap_err();
        assert_eq!(err, format!("limit:{}:{MAX_RULES}", MAX_RULES + 10));
        assert!(!blocked("m1.example", 443), "nothing installed");
        assert!(blocked("h5.example", 443), "the existing profile is untouched");
        // Replacing the same id counts the new body only.
        assert_eq!(set_profile("big", "domains", "small.example\n").unwrap(), "ok:1:0");
        assert!(!blocked("h5.example", 443));
        profile_teardown();
    }

    #[test]
    fn profile_precedence_and_clear() {
        let _g = profile_guard();
        set_profile("social", "domains", "social.example\nmine.example\nauto.example\n").unwrap();
        set_auto_rules_json(r#"{"rules":[{"host":"auto.example","label":"Phishing","ttl_secs":600}]}"#).unwrap();
        set_user_rule("mine.example", "deny").unwrap();
        set_user_rule("social.example", "allow").unwrap();
        assert!(!blocked("social.example", 443), "Trust beats a profile");
        assert!(blocked("mine.example", 443));
        assert!(blocked("auto.example", 443));
        let stats = stats_json();
        let v: serde_json::Value = serde_json::from_str(&stats).unwrap();
        let recent = v["recent"].as_array().unwrap();
        let cat = |h: &str| recent.iter().find(|e| e["host"] == h).map(|e| e["category"].as_str().unwrap().to_string());
        assert_eq!(cat("mine.example").as_deref(), Some(USER_CATEGORY));
        assert_eq!(cat("auto.example").as_deref(), Some(AUTO_CATEGORY));
        set_user_rule("mine.example", "clear").unwrap();
        set_user_rule("social.example", "clear").unwrap();
        clear_profile("social");
        assert!(!blocked("social.example", 443));
        set_profile("p", "domains", "x.example\n").unwrap();
        reset();
        set_enabled(true);
        assert!(!blocked("x.example", 443), "reset drops profiles");
        profile_teardown();
    }

    #[test]
    fn cc_profiles_decide_by_the_regional_map_without_rule_slots() {
        let _g = profile_guard();
        crate::geo::clear();
        // No map: a cc profile installs, takes no slots and decides nothing.
        assert_eq!(set_profile("country:DE", "cc", "").unwrap(), "ok:1:0");
        assert!(!blocked("10.0.1.1", 443));
        // With the sample map (10.0.0.0/23 DE, 10.0.2.0/25 FR, 2001:db8::/32 NL, 2a00::/48 SE).
        crate::geo::install(&crate::geo::tests::sample()).unwrap();
        set_profile("country:DE", "cc", "").unwrap();
        assert!(blocked("10.0.1.1", 443), "DE address refused by cc");
        assert!(!blocked("10.0.2.1", 443), "FR address passes");
        assert!(!blocked("de.example", 443), "a hostname is never classified by the map");
        let v: serde_json::Value = serde_json::from_str(&stats_json()).unwrap();
        let e = v["recent"].as_array().unwrap().iter().find(|e| e["host"] == "10.0.1.1").unwrap();
        assert_eq!(e["category"], PROFILE_CATEGORY);
        assert_eq!(e["reason"], "country:DE");
        // A region from the embedded table, and an explicit code list from the catalogue.
        assert_eq!(set_profile("region:eu", "cc", "").unwrap(), "ok:27:0");
        assert!(blocked("10.0.2.1", 443), "FR is in the EU");
        assert!(blocked("2001:db8::1", 443), "NL is in the EU (v6)");
        assert!(blocked("2a00::1", 443), "SE is in the EU (v6)");
        clear_profile("region:eu");
        assert!(!blocked("10.0.2.1", 443));
        assert!(!blocked("2a00::1", 443));
        assert_eq!(set_profile("region:nordics", "cc", "se, no\nfi").unwrap(), "ok:3:0");
        assert!(blocked("2a00::1", 443));
        assert!(set_profile("region:mars", "cc", "").is_err(), "unknown region, no codes");
        assert!(set_profile("x", "cc", "GERMANY").is_err(), "not two-letter codes");
        // Exempt hosts and the user's Allow win, as over every list.
        set_user_rule("10.0.1.1", "allow").unwrap();
        assert!(!blocked("10.0.1.1", 443));
        set_user_rule("10.0.1.1", "clear").unwrap();
        assert!(blocked("10.0.1.1", 443));
        // No rule slots: a cc profile next to a full list profile is not refused.
        let shipped = snapshot(&SHIPPED).map(|s| s.len()).unwrap_or(0);
        let big: String = (0..MAX_RULES - shipped).map(|i| format!("h{i}.example\n")).collect();
        assert!(set_profile("big", "domains", &big).unwrap().starts_with("ok:"));
        assert_eq!(set_profile("country:FR", "cc", "").unwrap(), "ok:1:0");
        assert!(set_profile("more", "domains", "m.example\n").unwrap_err().starts_with("limit:"));
        clear_profile("big");
        // A list body under the same id replaces the cc twin, and back.
        assert_eq!(set_profile("country:DE", "ranges", "192.0.2.0/24\n").unwrap(), "ok:1:0");
        assert!(!blocked("10.0.1.1", 443), "the cc twin is gone");
        assert!(blocked("192.0.2.9", 443));
        assert_eq!(set_profile("country:DE", "cc", "").unwrap(), "ok:1:0");
        assert!(blocked("10.0.1.1", 443));
        assert!(!blocked("192.0.2.9", 443), "the list body is gone");
        // The map survives reset; the profiles do not.
        reset();
        set_enabled(true);
        assert!(crate::geo::is_loaded());
        assert!(!blocked("10.0.1.1", 443));
        // Clearing the map disarms a cc profile without removing it.
        set_profile("country:DE", "cc", "").unwrap();
        assert!(blocked("10.0.1.1", 443));
        crate::geo::clear();
        assert!(!blocked("10.0.1.1", 443));
        crate::geo::install(&crate::geo::tests::sample()).unwrap();
        set_enabled(true);
        assert!(blocked("10.0.1.1", 443), "re-armed once a map is back");
        crate::geo::clear();
        profile_teardown();
    }

    #[test]
    fn profile_rejects_bad_kind_and_id() {
        assert!(set_profile("a", "words", "x").is_err());
        assert!(set_profile("", "domains", "x").is_err());
        assert!(set_profile("a b", "domains", "x").is_err());
    }

    /// Per-CONNECT cost with 200 000 suffixes in one profile. Run with
    /// `cargo test --release --lib profile_cost -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn profile_cost_with_200k_suffixes() {
        let _g = profile_guard();
        let body: String = (0..200_000).map(|i| format!("site{i}.example{}\n", i % 97)).collect();
        let t = Instant::now();
        assert!(set_profile("adult", "domains", &body).unwrap().starts_with("ok:200000:"));
        println!("compile 200k: {:?}", t.elapsed());
        let n = 100_000u32;
        let t = Instant::now();
        for i in 0..n {
            assert!(!blocked(if i % 2 == 0 { "cdn.images.unrelated.example.com" } else { "www.apple.com" }, 443));
        }
        let miss = t.elapsed() / n;
        let t = Instant::now();
        for _ in 0..n {
            assert!(blocked("img.cdn.site123.example26", 443));
        }
        let hit = t.elapsed() / n;
        println!("per blocked(): miss {miss:?}, hit (incl. recording the event) {hit:?}");
        profile_teardown();
    }

    #[test]
    fn automatic_rules_refuse_record_the_label_and_yield_to_allow() {
        let _g = TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        set_user_lists_json(r#"{"allow":[],"deny":[]}"#).unwrap();
        set_enabled(true);
        assert!(!blocked("evil.example", 443), "nothing armed yet");
        set_auto_rules_json(
            r#"{"rules":[{"host":"Evil.Example","label":"Phishing","ttl_secs":600},
                         {"host":"gone.example","label":"Malware","ttl_secs":0}]}"#,
        )
        .unwrap();
        assert!(blocked("evil.example", 443));
        assert!(!blocked("sub.evil.example", 443), "exact host only");
        assert!(!blocked("gone.example", 443), "no TTL, no rule");
        let stats = stats_json();
        assert!(stats.contains("\"reason\":\"Phishing\"") && stats.contains(AUTO_CATEGORY), "{stats}");
        // The user's Allow wins.
        set_user_rule("evil.example", "allow").unwrap();
        assert!(!blocked("evil.example", 443));
        set_user_rule("evil.example", "clear").unwrap();
        assert!(blocked("evil.example", 443));
        // Replaced wholesale; an empty set disarms.
        set_auto_rules_json(r#"{"rules":[]}"#).unwrap();
        assert!(!blocked("evil.example", 443));
        set_enabled(false);
        reset();
    }

    fn shipped(json: &str) -> RuleSet {
        let v: serde_json::Value = serde_json::from_str(json).unwrap();
        let mut b = Builder::default();
        if let Some(block) = v.get("block").and_then(|x| x.as_object()) {
            for (name, group) in block {
                let cat = b.category(name);
                for key in ["domains", "cidrs"] {
                    if let Some(arr) = group.get(key).and_then(|x| x.as_array()) {
                        for e in arr.iter().filter_map(|x| x.as_str()) {
                            b.add(e, cat);
                        }
                    }
                }
            }
        }
        b.finish(1, None)
    }

    fn set(entries: &[&str]) -> RuleSet {
        let owned: Vec<String> = entries.iter().map(|s| s.to_string()).collect();
        ruleset_from(&owned, "ads")
    }

    /// Suffix matching must cut at label boundaries and NOWHERE else. The
    /// near-miss is the whole test: a substring matcher blocks
    /// `notdoubleclick.net` and takes a real site off the internet.
    #[test]
    fn domain_matching_is_suffix_wise_at_label_boundaries() {
        let r = set(&["doubleclick.net"]);
        assert!(r.matches("doubleclick.net").is_some(), "exact host must match");
        assert!(r.matches("ads.doubleclick.net").is_some(), "subdomain must match");
        assert!(r.matches("a.b.c.doubleclick.net").is_some(), "deep subdomain must match");

        // Near misses, every one of which a naive `contains`/`ends_with` breaks.
        assert!(r.matches("notdoubleclick.net").is_none());
        assert!(r.matches("doubleclick.net.evil.example").is_none());
        assert!(r.matches("xdoubleclick.net").is_none());
        assert!(r.matches("doubleclick.network").is_none());
        assert!(r.matches("net").is_none());
        assert!(r.matches("example.com").is_none());
    }

    #[test]
    fn exact_entries_do_not_take_subdomains() {
        let mut b = Builder::default();
        let c = b.category("ads");
        b.add("=metrics.example.com", c);
        let r = b.finish(1, None);
        assert!(r.matches("metrics.example.com").is_some());
        assert!(r.matches("a.metrics.example.com").is_none(), "= means exact only");
    }

    #[test]
    fn wildcard_and_dot_prefixes_normalise() {
        let r = set(&["*.tracker.example", ".other.example"]);
        assert!(r.matches("tracker.example").is_some());
        assert!(r.matches("a.tracker.example").is_some());
        assert!(r.matches("b.other.example").is_some());
    }

    #[test]
    fn hosts_are_matched_case_insensitively() {
        let r = set(&["DoubleClick.NET"]);
        let mut buf = [0u8; MAX_HOST_LEN];
        let h = normalize("ADS.DoubleClick.Net.", &mut buf).unwrap();
        assert_eq!(h, "ads.doubleclick.net");
        assert!(r.matches(h).is_some());
    }

    #[test]
    fn cidr_and_bare_ips_match_including_near_misses() {
        let r = set(&["198.51.100.0/24", "203.0.113.7", "2001:db8::/32"]);
        assert!(r.matches("198.51.100.1").is_some());
        assert!(r.matches("198.51.100.255").is_some());
        assert!(r.matches("198.51.101.1").is_none(), "adjacent /24 must not match");
        assert!(r.matches("203.0.113.7").is_some());
        assert!(r.matches("203.0.113.8").is_none(), "bare IP is a /32");
        assert!(r.matches("2001:db8::1").is_some());
        assert!(r.matches("2001:db9::1").is_none());
        // A v4-mapped v6 literal must not walk around a v4 rule.
        assert!(r.matches("::ffff:198.51.100.5").is_some());
    }

    #[test]
    fn longest_prefix_wins() {
        let mut b = Builder::default();
        let broad = b.category("ads");
        let narrow = b.category("malware");
        b.add("10.0.0.0/8", broad);
        b.add("10.1.2.0/24", narrow);
        let r = b.finish(1, None);
        assert_eq!(r.category(r.matches("10.1.2.3").unwrap()), "malware");
        assert_eq!(r.category(r.matches("10.9.9.9").unwrap()), "ads");
    }

    #[test]
    fn categories_survive_the_round_trip() {
        let r = shipped(
            r#"{"block":{"malware":{"domains":["evil.example"]},
                          "ads":{"domains":["ads.example"]}}}"#,
        );
        assert_eq!(r.category(r.matches("evil.example").unwrap()), "malware");
        assert_eq!(r.category(r.matches("x.ads.example").unwrap()), "ads");
    }

    // ── Precedence ──────────────────────────────────────────────────────────

    fn user(allow: &[&str], deny: &[&str]) -> UserRules {
        let a: Vec<String> = allow.iter().map(|s| s.to_string()).collect();
        let d: Vec<String> = deny.iter().map(|s| s.to_string()).collect();
        UserRules { allow: ruleset_from(&a, "allow"), deny: ruleset_from(&d, USER_CATEGORY) }
    }

    #[test]
    fn user_allow_beats_everything() {
        let s = set(&["example.com"]);
        let u = user(&["example.com"], &["example.com"]);
        // Over the shipped list AND over the user's own deny entry.
        assert!(decide(None, Some(&u), Some(&s), "example.com").is_none());
        assert!(decide(None, Some(&u), Some(&s), "ads.example.com").is_none());
    }

    /// The interaction that usually goes wrong: a narrow allow inside a broad
    /// shipped block must free exactly its own subtree and nothing else.
    #[test]
    fn narrow_user_allow_carves_out_of_a_broad_block() {
        let s = set(&["example.com"]);
        let u = user(&["foo.example.com"], &[]);
        assert!(decide(None, Some(&u), Some(&s), "foo.example.com").is_none());
        assert!(decide(None, Some(&u), Some(&s), "a.foo.example.com").is_none());
        // Siblings and the parent stay blocked.
        assert!(decide(None, Some(&u), Some(&s), "bar.example.com").is_some());
        assert!(decide(None, Some(&u), Some(&s), "example.com").is_some());
    }

    #[test]
    fn user_deny_blocks_what_the_list_does_not() {
        let s = set(&["ads.example"]);
        let u = user(&[], &["timewaster.example"]);
        let hit = decide(None, Some(&u), Some(&s), "app.timewaster.example").unwrap();
        assert_eq!(hit.category, USER_CATEGORY);
    }

    #[test]
    fn exempt_hosts_can_never_be_blocked() {
        let s = set(&["cashier.example.com"]);
        let u = user(&[], &["cashier.example.com"]);
        let e = ruleset_from(&["cashier.example.com".to_string()], "self");
        assert!(
            decide(Some(&e), Some(&u), Some(&s), "cashier.example.com").is_none(),
            "a list must never be able to block its own correction path"
        );
    }

    #[test]
    fn nothing_installed_blocks_nothing() {
        assert!(decide(None, None, None, "anything.example").is_none());
        let empty = RuleSet::default();
        assert!(decide(Some(&empty), None, Some(&empty), "anything.example").is_none());
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    #[test]
    fn malformed_documents_are_rejected() {
        for bad in ["", "not json", "[]", "{}", r#"{"block":{}}"#, r#"{"block":{"ads":{}}}"#] {
            assert!(build_json(bad, None).is_err(), "accepted {bad:?}");
        }
    }

    #[test]
    fn bad_entries_are_skipped_not_fatal() {
        let (r, skipped) = build_json(
            r#"{"version":4,"block":{"ads":{
                 "domains":["good.example","", "  ", "has space", "1.2.3.4/99"],
                 "cidrs":["198.51.100.0/24","not-a-cidr"]}}}"#,
            None,
        )
        .expect("a few bad entries must not lose the whole list");
        assert!(skipped >= 4, "bad entries not counted: {skipped}");
        assert!(r.matches("good.example").is_some());
        assert!(r.matches("198.51.100.9").is_some());
    }

    #[test]
    fn flat_schema_is_accepted() {
        let (r, _) = build_json(r#"{"domains":["flat.example"],"cidrs":["10.0.0.0/8"]}"#, None)
            .unwrap();
        assert_eq!(r.category(r.matches("a.flat.example").unwrap()), "blocked");
        assert!(r.matches("10.5.5.5").is_some());
    }

    #[test]
    fn an_ip_written_under_domains_is_understood_as_a_network() {
        let (r, _) = build_json(r#"{"block":{"malware":{"domains":["203.0.113.0/24"]}}}"#, None)
            .unwrap();
        assert!(r.matches("203.0.113.9").is_some());
    }

    // ── Fail-open, gating and the live globals ──────────────────────────────

    /// ONE test, run sequentially — the same reason `throttle` has one:
    /// ENABLED/ARMED/SHIPPED/USER are process-global, so separate `#[test]` fns
    /// race each other and produce phantom passes and failures.
    #[test]
    fn live_state_behaves() {
        let _g = TEST_GUARD.lock().unwrap_or_else(|e| e.into_inner());
        // 1. Off by default: a list may be installed and still block nothing.
        set_enabled(false);
        set_user_lists_json(r#"{"allow":[],"deny":[]}"#).unwrap();
        install_json(r#"{"version":9,"block":{"ads":{"domains":["blocked.example"]}}}"#, None)
            .unwrap();
        assert!(!blocked("blocked.example", 443), "disabled must never block");

        // 2. Enabled: the list bites, and only where it should.
        set_enabled(true);
        assert!(blocked("blocked.example", 443), "enabled must block a listed host");
        assert!(blocked("ads.blocked.example", 80), "subdomains too");
        assert!(!blocked("notblocked.example", 443), "near miss must pass");
        assert!(!blocked("allowed.example", 443));

        // 3. Fail open on anything undecidable.
        assert!(!blocked(&"a".repeat(300), 443));
        assert!(!blocked("", 443));

        // 4. The escape hatch: effective on the next connection, no reconnect.
        set_user_rule("blocked.example", "allow").unwrap();
        assert!(!blocked("blocked.example", 443), "user allow must take effect at once");
        assert!(!blocked("ads.blocked.example", 443), "allow covers the subtree");
        set_user_rule("blocked.example", "clear").unwrap();
        assert!(blocked("blocked.example", 443), "clearing must restore the list verdict");

        // 5. A user deny blocks what no list does.
        set_user_rule("mine.example", "deny").unwrap();
        assert!(blocked("app.mine.example", 443));
        let stats = stats_json();
        assert!(stats.contains("mine.example") && stats.contains(USER_CATEGORY), "{stats}");

        // 6. A failed refresh must leave the live list exactly as it was.
        assert!(install_json("garbage", None).is_err());
        assert!(blocked("blocked.example", 443), "a bad refresh dropped the live list");

        // 7. Input validation, and rules that survive a list replacement.
        assert!(set_user_rule("", "allow").is_err());
        assert!(set_user_rule("has space", "allow").is_err());
        assert!(set_user_rule("x.example", "nonsense").is_err());
        install_json(r#"{"version":10,"block":{"ads":{"domains":["other.example"]}}}"#, None)
            .unwrap();
        assert!(
            user_lists_json().contains("mine.example"),
            "a list refresh must never touch the user's own rules"
        );
        assert!(blocked("app.mine.example", 443), "user deny lost across a refresh");

        // 8. History is clearable and does not survive a stop; rules do.
        clear_history();
        assert!(!stats_json().contains("mine.example"));
        assert!(blocked("app.mine.example", 443));
        reset();
        assert!(!stats_json().contains("mine.example"), "history outlived the session");
        assert!(user_lists_json().contains("mine.example"), "reset ate the user's rules");

        // 9. A malformed bulk restore must not wipe what is there.
        assert!(set_user_lists_json("nope").is_err());
        assert!(user_lists_json().contains("mine.example"));

        // 10. Back to a clean slate for anything that runs after.
        set_user_lists_json(r#"{"allow":[],"deny":[]}"#).unwrap();
        set_enabled(false);
        assert!(!blocked("other.example", 443));
    }

    // ── Fetch plumbing ──────────────────────────────────────────────────────

    #[test]
    fn urls_parse_and_https_is_refused() {
        let u = parse_url("http://example.com/lists/v1.json").unwrap();
        assert_eq!((u.host.as_str(), u.port, u.path.as_str()), ("example.com", 80, "/lists/v1.json"));
        let u = parse_url("http://example.com:8080").unwrap();
        assert_eq!((u.host.as_str(), u.port, u.path.as_str()), ("example.com", 8080, "/"));
        let u = parse_url("http://[2001:db8::1]:81/x").unwrap();
        assert_eq!((u.host.as_str(), u.port), ("2001:db8::1", 81));
        // No TLS stack in this crate — refuse rather than half-implement.
        assert!(parse_url("https://example.com/x").is_err());
        assert!(parse_url("example.com/x").is_err());
    }

    /// A CDN in front of the origin may chunk the response. Without this the
    /// JSON parse would choke on the length prefixes and the list would
    /// silently never update.
    #[test]
    fn chunked_bodies_are_reassembled() {
        let json = br#"{"domains":["c.example"]}"#;
        let (a, b) = json.split_at(10);
        let mut body = Vec::new();
        for part in [a, b] {
            body.extend_from_slice(format!("{:x}\r\n", part.len()).as_bytes());
            body.extend_from_slice(part);
            body.extend_from_slice(b"\r\n");
        }
        body.extend_from_slice(b"0\r\n\r\n");
        assert_eq!(dechunk(&body).unwrap(), json.to_vec());

        // A truncated chunk is an error, not a silently short list.
        assert!(dechunk(b"ff\r\nshort\r\n").is_err());
    }
}
