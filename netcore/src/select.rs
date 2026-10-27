//! Endpoint selection — **every** decision about which endpoint to try, and in
//! what order, lives in this module. Nothing else may reorder, skip or filter
//! endpoints; `start_http` just runs what `select()` returns.
//!
//! The decisions, in priority order:
//!
//! 1. **Premium first.** Endpoints `[0, premium_count)` are the paid pool and
//!    keep absolute priority — dedicated capacity is the point of paying — so
//!    even a remembered winner is tried after them.
//! 2. **Last known good next.** The endpoint that worked last time is tried
//!    before the rest. Reconnects are the common case (the health watchdog
//!    reconnects on its own), and on a censored network the remembered winner is
//!    usually a CDN front, so this also skips straight past the blocked direct
//!    IPs on the second attempt.
//! 3. **Drop IPv6 when the device has none.** The shipped config carries 4 IPv6
//!    endpoints; on v4-only mobile (most Iranian networks) every one is a
//!    guaranteed 8s timeout, which is budget stolen from endpoints that could
//!    have worked.
//! 4. **Reserve budget for the fallback tier.** See `DIRECT_BUDGET` — without it
//!    the CDN fronts are unreachable in exactly the scenario they exist for.

use std::net::{IpAddr, SocketAddr};
use crate::FALLBACK_HEAD_START;
use std::time::{Duration, Instant};

use crate::config::{Config, Endpoint};

/// May this client use the fallback tier (CDN fronts, distinctive names)?
///
/// CloudFront egress costs real money per byte, so it is reserved for users who
/// pay for it — and for freedom regions, where it is the only thing that works
/// when the direct IPs are blocked and the mission depends on it. Metered free
/// users get the direct POPs only. Set from Kotlin via setCdnAllowed.
///
/// Defaults to TRUE: a missing call must never strand a user who needs the
/// fallback, which is the failure mode that matters.
pub static CDN_ALLOWED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

/// Pref key holding the last endpoint that completed a handshake ("host:port").
pub const LAST_OK_KEY: &str = "redsocket_last_ok";

/// Pref key holding endpoints that handshook but carried no traffic, comma
/// separated ("host:port,host:port").
///
/// 🚨 A HANDSHAKE IS NOT A WORKING TUNNEL, and only the client can tell the
/// difference. `LAST_OK_KEY` remembers what connected; this remembers what
/// connected and then failed the post-connect health check, so selection stops
/// resuming onto an endpoint that looks perfect right up until traffic has to
/// move through it.
///
/// Paired with LAST_OK by design: one pulls a proven endpoint forward, the
/// other pushes a proven-bad one out, and an endpoint that recovers is simply
/// dropped from this list again.
pub const BAD_KEY: &str = "redsocket_bad";

/// Endpoints the client marked bad after a failed post-connect health check.
pub fn bad_load() -> Vec<String> {
    crate::pref_get(BAD_KEY)
        .map(|v| v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
        .unwrap_or_default()
}

/// Remember `label` as bad. Idempotent, and capped so a long-running install
/// cannot grow this pref without bound.
pub fn bad_add(label: &str) {
    let mut v = bad_load();
    if v.iter().any(|b| b == label) { return; }
    v.push(label.to_string());
    // Oldest out first. The cap is deliberately larger than any shipped
    // endpoint list, so the exhaustion rule in `plan_with_bad` -- not this
    // cap -- is what decides when the list starts over.
    while v.len() > 64 { v.remove(0); }
    crate::pref_put(BAD_KEY, &v.join(","));
}

/// Forget one endpoint's bad mark, on a health check it then passed.
pub fn bad_clear(label: &str) {
    let v = bad_load();
    if !v.iter().any(|b| b == label) { return; }
    let kept: Vec<String> = v.into_iter().filter(|b| b != label).collect();
    crate::pref_put(BAD_KEY, &kept.join(","));
}

/// The order to try endpoints in, plus what was left out and why (for logging).
#[derive(Debug, Default)]
pub struct Plan {
    pub order: Vec<usize>,
    pub dropped_v6: usize,
    /// Index of the remembered winner, if it survived into the plan.
    pub resumed: Option<usize>,
}

/// IPv6 literal, or a family-explicit `-v6` name (`lax00-p-v6...`). Names
/// count too: on a v4-only phone a `-v6` name resolves to nothing it can
/// reach, and dialing it costs a full 8 s attempt of a 4-attempt budget.
fn is_v6(host: &str) -> bool {
    crate::config::host_family_is_v6(host) == Some(true)
}

/// When the plan leads with IPv6, alternate the families among direct
/// endpoints (v6, v4, v6, v4...). Iranian mobile networks often hand out an
/// IPv6 route that does not actually carry traffic, and `have_ipv6` only sees
/// the route; with all-v6 first, such a phone spends its whole direct budget
/// on timeouts. Alternating bounds that to every other attempt while the
/// first attempt still goes to the preferred (cheaper) family. A plan that
/// leads with IPv4 is left alone: where v4 is preferred, v6 is the family
/// measured dead (Russia, 0/50), so mixing it in would cost attempts.
fn interleave_families(cfg: &Config, order: Vec<usize>) -> Vec<usize> {
    let fam = |i: usize| {
        let e = &cfg.endpoints[i];
        if e.fallback { None } else { crate::config::host_family_is_v6(&e.host) }
    };
    if order.iter().find_map(|&i| fam(i)) != Some(true) {
        return order;
    }
    let mut q6: std::collections::VecDeque<usize> =
        order.iter().copied().filter(|&i| fam(i) == Some(true)).collect();
    let mut q4: std::collections::VecDeque<usize> =
        order.iter().copied().filter(|&i| fam(i) == Some(false)).collect();
    if q4.is_empty() {
        return order;
    }
    let mut want_v6 = true;
    order
        .iter()
        .map(|&i| match fam(i) {
            None => i,
            Some(_) => {
                let pick = if want_v6 { q6.pop_front().or_else(|| q4.pop_front()) }
                           else { q4.pop_front().or_else(|| q6.pop_front()) };
                want_v6 = !want_v6;
                pick.expect("one slot per queued endpoint")
            }
        })
        .collect()
}

/// Direct candidates probed at once before dialling (owner decision 2026-10-21:
/// "do it in parallel like 8 at a time"). This deliberately overrides the old
/// sequential-only rule: a fan-out of SYNs is more visible, measured latency is
/// worth more. The 8 are the head of the plan, so priority still picks WHICH 8.
const PROBE_FANOUT: usize = 8;
/// Longest the race may delay a connect when nothing answers.
const PROBE_WINDOW: Duration = Duration::from_millis(1500);

/// One probe: a plain `GET /` with the endpoint's own Host header, timed to the
/// first bytes of the reply. Only our server's `404` counts, so an injected
/// block page or a middlebox that completes the handshake cannot win. For an
/// AWS front the reply comes back from the LAX back-end (DSR), so this measures
/// the whole path the tunnel will use, not just the hop to the front.
async fn probe_one(ep: Endpoint) -> Option<Duration> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let t0 = Instant::now();
    let mut s = crate::protect::protected_connect_by_name(&ep.host, ep.port).await.ok()?;
    let req = format!("GET / HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", ep.upgrade_host);
    s.write_all(req.as_bytes()).await.ok()?;
    let mut buf = [0u8; 16];
    let n = s.read(&mut buf).await.ok()?;
    buf[..n].starts_with(b"HTTP/1.1 404").then(|| t0.elapsed())
}

/// Reorder `order` by measured latency: probe the first [`PROBE_FANOUT`] direct
/// endpoints at once, put the ones that answered first (fastest first), keep
/// everything else in plan order behind them. Once the first answer lands the
/// race waits at most as long again for a faster one, so a healthy network pays
/// one round trip, and a blocked one at most [`PROBE_WINDOW`].
pub(crate) async fn rank_by_probe(cfg: &Config, order: Vec<usize>) -> Vec<usize> {
    let cand: Vec<usize> = order.iter().copied()
        .filter(|&i| !cfg.endpoints[i].fallback).take(PROBE_FANOUT).collect();
    if cand.len() < 2 {
        return order;
    }
    let mut set = tokio::task::JoinSet::new();
    for &i in &cand {
        let ep = cfg.endpoints[i].clone();
        set.spawn(async move { (i, probe_one(ep).await) });
    }
    let start = tokio::time::Instant::now();
    let mut deadline = start + PROBE_WINDOW;
    let mut got: Vec<(usize, Duration)> = Vec::new();
    loop {
        match tokio::time::timeout_at(deadline, set.join_next()).await {
            Ok(Some(Ok((i, Some(d))))) => {
                if got.is_empty() {
                    deadline = deadline.min(start + d * 2 + Duration::from_millis(50));
                }
                got.push((i, d));
            }
            Ok(Some(_)) => {}
            _ => break,
        }
    }
    set.abort_all();
    got.sort_by_key(|&(_, d)| d);
    log::info!("[nc] probe: {}/{} answered, fastest {}", got.len(), cand.len(),
        got.iter().take(3).map(|&(i, d)| format!("{} {}ms", cfg.endpoints[i].label(), d.as_millis()))
            .collect::<Vec<_>>().join(", "));
    let mut out: Vec<usize> = got.iter().map(|&(i, _)| i).collect();
    let rest: Vec<usize> = order.into_iter().filter(|i| !out.contains(i)).collect();
    out.extend(rest);
    out
}

/// Stable per-install jitter for the weighted tie-break. Same FNV as config.rs.
fn fnv1a(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.as_bytes() {
        h ^= *b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// Pure ordering decision — no I/O, so it is unit-testable.
///
/// Order: premium → remembered winner → everything else by `priority` ascending,
/// ties broken by a weighted, per-install-stable shuffle so ~500K devices don't
/// converge on one POP. `weight: 0` removes an endpoint entirely.
pub fn plan(cfg: &Config, have_v6: bool, last_good: Option<&str>, cid: &str) -> Plan {
    plan_with_bad(cfg, have_v6, last_good, cid, &[])
}

/// [`plan`], plus endpoints to skip because they failed the post-connect check.
pub fn plan_with_bad(
    cfg: &Config,
    have_v6: bool,
    last_good: Option<&str>,
    cid: &str,
    bad: &[String],
) -> Plan {
    let n = cfg.endpoints.len();

    // weight 0 = disabled. A kill switch that needs no list surgery.
    let mut enabled: Vec<usize> = (0..n).filter(|&i| cfg.endpoints[i].weight > 0).collect();

    // Paid-only fallback tier. Never applied if it would empty the list — a
    // config that is all-CDN must still connect somebody.
    if !CDN_ALLOWED.load(std::sync::atomic::Ordering::SeqCst) {
        let direct: Vec<usize> = enabled.iter().copied()
            .filter(|&i| !cfg.endpoints[i].fallback).collect();
        if !direct.is_empty() {
            enabled = direct;
        }
    }

    // Drop v6 only if something would remain; never strand the client because
    // its network happens to be v6-only or the config is all-v6.
    let usable: Vec<usize> = if have_v6 {
        enabled.clone()
    } else {
        let keep: Vec<usize> = enabled
            .iter().copied().filter(|&i| !is_v6(&cfg.endpoints[i].host)).collect();
        if keep.is_empty() { enabled.clone() } else { keep }
    };
    let dropped_v6 = enabled.len() - usable.len();

    // Skip what has already handshook and failed to carry traffic -- UNLESS that
    // would leave nothing. Exhausting the list is exactly when to start over:
    // every endpoint is now known-bad, so the least-stale choice is the whole
    // list again rather than no connection at all. Same never-strand rule the
    // CDN gate and the v6 drop above follow, and the reason a bad endpoint that
    // has since recovered is reachable again without any expiry logic.
    let usable: Vec<usize> = if bad.is_empty() {
        usable
    } else {
        let keep: Vec<usize> = usable
            .iter().copied()
            .filter(|&i| !bad.iter().any(|b| b == &cfg.endpoints[i].label()))
            .collect();
        if keep.is_empty() { usable } else { keep }
    };

    // Weighted tie-break: higher weight => smaller key => tried earlier, but
    // which of two equal-weight endpoints leads varies per install.
    let key = |i: usize| -> u64 {
        let e = &cfg.endpoints[i];
        fnv1a(&format!("{}|{}", cid, e.label())) / e.weight.max(1) as u64
    };

    // A remembered winner that is ALSO known-bad must not be pulled forward:
    // last_ok and bad are written by different events (handshake vs health), so
    // one endpoint can legitimately be in both, and resuming onto it would
    // defeat the skip above on every reconnect.
    let remembered = last_good.and_then(|label| {
        usable.iter().copied().find(|&i| {
            cfg.endpoints[i].label() == label
                && !cfg.endpoints[i].premium
                && !bad.iter().any(|b| b == label)
        })
    });

    let mut rest: Vec<usize> = usable
        .iter().copied()
        .filter(|&i| !cfg.endpoints[i].premium && Some(i) != remembered)
        .collect();
    rest.sort_by(|&a, &b| {
        cfg.endpoints[a].priority.cmp(&cfg.endpoints[b].priority)
            .then_with(|| key(a).cmp(&key(b)))
    });

    let mut premium: Vec<usize> = usable.iter().copied()
        .filter(|&i| cfg.endpoints[i].premium).collect();
    premium.sort_by(|&a, &b| {
        cfg.endpoints[a].priority.cmp(&cfg.endpoints[b].priority)
            .then_with(|| key(a).cmp(&key(b)))
    });

    let premium = interleave_families(cfg, premium);
    let rest = interleave_families(cfg, rest);

    let mut order = premium;
    if let Some(r) = remembered { order.push(r); }
    order.extend(rest);

    Plan { order, dropped_v6, resumed: remembered }
}

/// Does this device have routable IPv6?
///
/// A UDP `connect()` performs a route lookup and sends **nothing** on the wire,
/// so this costs one syscall and leaks no traffic. The socket is protected, so
/// the answer describes the *underlying* network rather than whatever routes the
/// VpnService tun happens to install.
pub fn have_ipv6(cfg: &Config) -> bool {
    // Probe against one of our own v6 endpoints — no third party involved.
    let Some(target) = cfg
        .endpoints
        .iter()
        .find_map(|e| e.host.parse::<IpAddr>().ok().filter(|ip| ip.is_ipv6()))
    else {
        return false; // nothing v6 to reach; treat as no-v6 so nothing is dropped
    };

    let sock = match socket2::Socket::new(
        socket2::Domain::IPV6,
        socket2::Type::DGRAM,
        Some(socket2::Protocol::UDP),
    ) {
        Ok(s) => s,
        Err(_) => return false,
    };
    #[cfg(target_os = "android")]
    {
        use std::os::fd::AsRawFd;
        crate::protect_socket(sock.as_raw_fd());
    }
    sock.connect(&SocketAddr::new(target, 80).into()).is_ok()
}

/// Run the plan against the network. Returns the winning endpoint index.
///
/// `budget` is the whole-selection cap; `direct_budget` is how much of it the
/// direct tiers may consume before the remaining direct entries are skipped so
/// the fallback tier (CDN fronts, names) is still reachable.
pub async fn select(
    cfg: &Config,
    tz: &str,
    cid: &str,
    last_good: Option<&str>,
    budget: Duration,
    direct_budget: Duration,
) -> Option<usize> {
    let have_v6 = have_ipv6(cfg);
    // Read here rather than at the call site: every ordering/skip decision is
    // supposed to live in this module, and a caller that forgot to pass the bad
    // set would silently reselect an endpoint the client already rejected.
    let bad = bad_load();
    let p = plan_with_bad(cfg, have_v6, last_good, cid, &bad);
    log::info!(
        "[nc] selection: {} endpoints, v6={} (dropped {}), resume={}",
        p.order.len(),
        have_v6,
        p.dropped_v6,
        p.resumed
            .map(|i| cfg.endpoints[i].label())
            .unwrap_or_else(|| "none".into())
    );

    // Race the first direct candidates in parallel and dial the fastest first.
    let order = rank_by_probe(cfg, p.order.clone()).await;

    let started = Instant::now();
    let deadline = started + budget;
    let direct_deadline = started + direct_budget;

    // Fallback entries, in plan order, consumed as the race needs them.
    let mut fallbacks = p.order.iter().copied()
        .filter(|&i| cfg.endpoints[i].fallback)
        .collect::<std::collections::VecDeque<_>>();
    let mut announced_race = false;

    for &i in &order {
        if Instant::now() >= deadline {
            log::warn!("[nc] selection budget exhausted before endpoint {}", i);
            break;
        }
        // Fallbacks are driven by the race below, not walked here, or they would
        // be attempted twice.
        if cfg.endpoints[i].fallback {
            continue;
        }
        // Direct tier has had its share and is still failing — leave the rest of
        // it alone so the fallback tier gets its turn.
        if Instant::now() >= direct_deadline {
            // No dedup guard needed: the `break` below means this can be
            // reached at most once per selection.
            log::info!("[nc] direct tier exhausted — trying fallback tier only");
            break;
        }

        let ep = &cfg.endpoints[i];
        crate::set_race_status(&format!("trying:{}", ep.label()));

        // The direct endpoint gets the head start ALONE: direct IPs are the
        // cheap, unremarkable path and usually win outright, so racing from the
        // first instant would double our connection rate on every healthy
        // network for no benefit.
        let direct_try = crate::tunnel::open_with_timeout(ep, &cfg.server_pubkey, tz, cid, false);
        tokio::pin!(direct_try);

        if fallbacks.is_empty() {
            match direct_try.await {
                Ok(t) => { drop(t); return Some(i); }
                Err(e) => log::info!("[nc] {} unusable: {}", ep.label(), e),
            }
            continue;
        }

        // If the head start runs out while this attempt is still pending --
        // the common case on a blocked network, where a direct dial hangs for
        // the full OPEN_TIMEOUT -- the race starts NOW, mid-attempt. It used to
        // start only at the next attempt, which cost two full direct timeouts
        // (~16 s) before the first fallback was even dialled.
        let head_start_ends = tokio::time::Instant::from_std(started + FALLBACK_HEAD_START);
        if tokio::time::Instant::now() < head_start_ends {
            tokio::select! {
                biased;
                r = &mut direct_try => {
                    match r {
                        Ok(t) => { drop(t); return Some(i); }
                        Err(e) => log::info!("[nc] {} unusable: {}", ep.label(), e),
                    }
                    continue;
                }
                _ = tokio::time::sleep_until(head_start_ends) => {}
            }
        }

        // Past the head start the direct tier is evidently struggling, so pair
        // the pending direct attempt (and each one after it) with ONE fallback
        // attempt and take whichever answers first.
        //
        // Exactly two sockets, never a fan-out: a burst of simultaneous SYNs to
        // many POPs is itself the signature a censor looks for, and it is the
        // reason this selector was sequential to begin with. Two in flight is
        // what an ordinary Happy Eyeballs client already does.
        let j = fallbacks.pop_front().unwrap();
        let fb = &cfg.endpoints[j];
        if !announced_race {
            announced_race = true;
            log::info!("[nc] direct tier slow — racing fallbacks alongside it");
        }
        crate::set_race_status(&format!("racing:{}|{}", ep.label(), fb.label()));

        let fallback_try = crate::tunnel::open_with_timeout(fb, &cfg.server_pubkey, tz, cid, false);
        tokio::pin!(fallback_try);

        let mut direct_done = false;
        let mut fallback_done = false;
        loop {
            tokio::select! {
                // Biased so a finished direct attempt is taken first when both
                // are ready: the direct IP costs no DNS lookup and is the
                // quieter path, so it should win a tie.
                biased;
                r = &mut direct_try, if !direct_done => {
                    direct_done = true;
                    match r {
                        Ok(t) => { drop(t); return Some(i); }
                        Err(e) => log::info!("[nc] {} unusable: {}", ep.label(), e),
                    }
                }
                r = &mut fallback_try, if !fallback_done => {
                    fallback_done = true;
                    match r {
                        Ok(t) => { drop(t); return Some(j); }
                        Err(e) => log::info!("[nc] {} unusable: {}", fb.label(), e),
                    }
                }
                else => break,
            }
            if direct_done && fallback_done {
                break;
            }
        }
    }

    // Whatever fallbacks the race did not consume, tried sequentially. This is
    // the path when the direct tier ran out early, or the head start never
    // elapsed because everything failed fast.
    while let Some(j) = fallbacks.pop_front() {
        if Instant::now() >= deadline {
            log::warn!("[nc] selection budget exhausted before fallback {}", j);
            break;
        }
        let fb = &cfg.endpoints[j];
        crate::set_race_status(&format!("trying:{}", fb.label()));
        match crate::tunnel::open_with_timeout(fb, &cfg.server_pubkey, tz, cid, false).await {
            Ok(t) => { drop(t); return Some(j); }
            Err(e) => log::info!("[nc] {} unusable: {}", fb.label(), e),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{BlocklistSettings, Endpoint};

    fn ep(host: &str, priority: u32, weight: u32, premium: bool, fallback: bool) -> Endpoint {
        Endpoint {
            host: host.into(), port: 80, upgrade_host: "h".into(), upgrade_path: "/noise".into(),
            priority, weight, premium, fallback,
        }
    }

    /// A local server that answers `GET /` with `reply` after `delay_ms`.
    async fn fake_pop(reply: &'static [u8], delay_ms: u64) -> u16 {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = l.accept().await {
                tokio::spawn(async move {
                    let mut b = [0u8; 256];
                    let _ = s.read(&mut b).await;
                    tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                    let _ = s.write_all(reply).await;
                });
            }
        });
        port
    }

    /// The race puts the fastest real POP first, then the slower one, then the
    /// rest in plan order; an injected non-404 page and a dead port never win.
    #[tokio::test]
    async fn probe_race_dials_the_fastest_real_pop_first() {
        let slow = fake_pop(b"HTTP/1.1 404 Not Found\r\n\r\n", 300).await;
        let fast = fake_pop(b"HTTP/1.1 404 Not Found\r\n\r\n", 20).await;
        let fake = fake_pop(b"HTTP/1.1 403 Forbidden\r\n\r\n", 0).await;
        let mut c = cfg(&["127.0.0.1", "127.0.0.1", "127.0.0.1", "127.0.0.1"]);
        for (e, p) in c.endpoints.iter_mut().zip([fake, 1, slow, fast]) { e.port = p; }
        let order = rank_by_probe(&c, vec![0, 1, 2, 3]).await;
        // slow answers at 300 ms, more than twice the fast one, so it is cut off
        // and keeps its plan slot behind the winner.
        assert_eq!(order, vec![3, 0, 1, 2]);
        c.endpoints[2].port = fake_pop(b"HTTP/1.1 404 Not Found\r\n\r\n", 30).await;
        assert_eq!(rank_by_probe(&c, vec![0, 1, 2, 3]).await[..2], [3, 2]);
    }

    /// Direct entries plus CDN fallbacks — the shape the race exists for.
    pub(super) fn cfg_mixed() -> Config {
        let mut c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c.endpoints.push(ep("front-a.example", 40, 1, false, true));
        c.endpoints.push(ep("front-b.example", 40, 1, false, true));
        c
    }

    /// Plain endpoints, all equal priority/weight, none premium or fallback.
    fn cfg(hosts: &[&str]) -> Config {
        Config {
            server_pubkey: [0u8; 32],
            endpoints: hosts.iter().map(|h| ep(h, 20, 1, false, false)).collect(),
            blocklist: BlocklistSettings::default(),
            mux: false,
        }
    }

    const CID: &str = "abcd1234abcd1234";

    #[test]
    fn v6_is_dropped_without_v6_connectivity() {
        let c = cfg(&["1.1.1.1", "2001:db8:5::d", "2.2.2.2", "cdn.example"]);
        let hosts: Vec<_> = plan(&c, false, None, CID)
            .order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert!(!hosts.iter().any(|h| h.contains(':')), "v6 survived: {:?}", hosts);
        assert_eq!(hosts.len(), 3);
        // ...and kept when the device can route it.
        assert_eq!(plan(&c, true, None, CID).order.len(), 4);
    }

    /// Carrier pinning (Android `withPreferredHosts`) sets explicit priorities
    /// alternating v4/v6; the plan must dial them in exactly that order, ahead
    /// of the untouched entries, and skip the v6 ones on a v4-only phone.
    #[test]
    fn carrier_pinned_priorities_dial_in_order() {
        let mut c = cfg(&["192.0.2.21", "2001:db8:5::e", "192.0.2.22"]);
        c.endpoints.push(ep("192.0.2.10", 1, 10, false, false));
        c.endpoints.push(ep("2001:db8:5::1", 2, 10, false, false));
        c.endpoints.push(ep("192.0.2.11", 3, 10, false, false));
        c.endpoints.push(ep("2001:db8:5::2", 4, 10, false, false));
        let hosts = |v6| plan(&c, v6, None, CID).order.iter().map(|&i| c.endpoints[i].host.clone()).collect::<Vec<_>>();
        assert_eq!(hosts(true)[..4], ["192.0.2.10", "2001:db8:5::1", "192.0.2.11", "2001:db8:5::2"]);
        assert_eq!(hosts(false)[..2], ["192.0.2.10", "192.0.2.11"]);
    }

    #[test]
    fn never_strands_a_v6_only_config() {
        let c = cfg(&["2001:db8:5::d", "2001:db8:5::f"]);
        assert_eq!(plan(&c, false, None, CID).order.len(), 2, "dropped every endpoint");
    }

    #[test]
    fn last_known_good_is_tried_first() {
        let c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        let p = plan(&c, true, Some("3.3.3.3:80"), CID);
        assert_eq!(p.order[0], 2);
        assert_eq!(p.resumed, Some(2));
        // and it is not tried twice
        assert_eq!(p.order.len(), 3);
        let mut sorted = p.order.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, vec![0, 1, 2]);
    }

    #[test]
    fn premium_outranks_the_remembered_winner() {
        // 0 is premium; the remembered winner is a shared endpoint.
        let mut c = cfg(&["9.9.9.9", "1.1.1.1", "2.2.2.2"]);
        c.endpoints[0].premium = true;
        c.endpoints[0].priority = crate::config::prio::PREMIUM;
        let p = plan(&c, true, Some("2.2.2.2:80"), CID);
        assert_eq!(p.order[0], 0, "premium must stay first");
        assert_eq!(p.order[1], 2, "remembered winner comes next");
    }

    #[test]
    fn a_stale_remembered_endpoint_is_ignored() {
        let c = cfg(&["1.1.1.1", "2.2.2.2"]);
        let p = plan(&c, true, Some("5.5.5.5:80"), CID);   // no longer in the config
        assert_eq!(p.resumed, None);
        assert_eq!(p.order.len(), 2);
    }

    #[test]
    fn lower_priority_number_is_tried_first() {
        let mut c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c.endpoints[2].priority = 5;    // promote the last one
        c.endpoints[0].priority = 99;   // demote the first
        let hosts: Vec<_> = plan(&c, true, None, CID)
            .order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert_eq!(hosts.first().unwrap(), "3.3.3.3");
        assert_eq!(hosts.last().unwrap(), "1.1.1.1");
    }

    #[test]
    fn weight_biases_ties_and_zero_disables() {
        let mut c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c.endpoints[1].weight = 0;      // disabled
        let p = plan(&c, true, None, CID);
        let hosts: Vec<_> = p.order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert_eq!(hosts.len(), 2);
        assert!(!hosts.contains(&"2.2.2.2".to_string()));

        // A heavily weighted endpoint leads its priority band for most installs.
        let mut c2 = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c2.endpoints[2].weight = 1000;
        let lead_count = (0..40)
            .filter(|n| {
                let cid = format!("cid{:016x}", n);
                let p = plan(&c2, true, None, &cid);
                c2.endpoints[p.order[0]].host == "3.3.3.3"
            })
            .count();
        assert!(lead_count > 30, "weight barely mattered: led {}/40", lead_count);
    }

    /// CloudFront costs money per byte, so the free tier is held to direct POPs
    /// — but the gate must never empty the list.
    #[test]
    fn cdn_gate_excludes_the_fallback_tier_but_never_strands() {
        use std::sync::atomic::Ordering;
        let mut c = cfg(&["1.1.1.1", "cdn.example", "name.example"]);
        c.endpoints[1].fallback = true;
        c.endpoints[2].fallback = true;

        CDN_ALLOWED.store(true, Ordering::SeqCst);
        assert_eq!(plan(&c, true, None, CID).order.len(), 3, "paid should see everything");

        CDN_ALLOWED.store(false, Ordering::SeqCst);
        let hosts: Vec<_> = plan(&c, true, None, CID)
            .order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert_eq!(hosts, vec!["1.1.1.1"], "free tier must not reach the CDN");

        // All-fallback config: denying would leave nothing, so the gate yields.
        let mut only_cdn = cfg(&["cdn1.example", "cdn2.example"]);
        for e in &mut only_cdn.endpoints { e.fallback = true; }
        assert_eq!(plan(&only_cdn, true, None, CID).order.len(), 2, "gate stranded the client");
        CDN_ALLOWED.store(true, Ordering::SeqCst);
    }

    /// A handshake is not a working tunnel, so an endpoint that connected and
    /// then failed the health check has to leave the plan -- and the list has to
    /// come back once every endpoint has been burned, or the user is left with
    /// nothing at all rather than something worth one more try.
    #[test]
    fn bad_endpoints_are_skipped_until_the_list_is_exhausted() {
        let c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        let label = |i: usize| c.endpoints[i].label();

        let hosts = |p: &Plan| -> Vec<String> {
            p.order.iter().map(|&i| c.endpoints[i].host.clone()).collect()
        };

        // One bad endpoint is simply gone.
        let one = vec![label(0)];
        let got = hosts(&plan_with_bad(&c, true, None, CID, &one));
        assert!(!got.contains(&"1.1.1.1".to_string()), "known-bad endpoint was still planned");
        assert_eq!(got.len(), 2);

        // EXHAUSTED: every endpoint burned, so the whole list returns rather
        // than planning nothing. This is the "retry once all are tried" rule.
        let all: Vec<String> = (0..3).map(label).collect();
        assert_eq!(
            plan_with_bad(&c, true, None, CID, &all).order.len(),
            3,
            "an all-bad list must start over, not strand the client"
        );

        // last_ok pulls a winner forward; being bad must override that, or a
        // reconnect resumes straight back onto the endpoint that just failed.
        let resumed = plan_with_bad(&c, true, Some(&label(1)), CID, &[]);
        assert_eq!(hosts(&resumed)[0], "2.2.2.2", "last_ok should lead");
        let vetoed = plan_with_bad(&c, true, Some(&label(1)), CID, &[label(1)]);
        assert!(
            !hosts(&vetoed).contains(&"2.2.2.2".to_string()),
            "a remembered winner that is known-bad must not be resumed"
        );
    }

    /// Load spreading must survive the change: equal-weight endpoints must not
    /// all lead on the same POP across installs.
    #[test]
    fn equal_weights_still_spread_across_installs() {
        let c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"]);
        let leads: std::collections::HashSet<String> = (0..40)
            .map(|n| {
                let cid = format!("cid{:016x}", n);
                c.endpoints[plan(&c, true, None, &cid).order[0]].host.clone()
            })
            .collect();
        assert!(leads.len() > 1, "every install would hammer the same POP");
    }
}

#[cfg(test)]
mod race_tests {
    use super::*;

    /// Every fallback must be reachable exactly once — via the race or the
    /// sequential tail, never both. Attempting one twice doubles the traffic to
    /// a CDN front and makes a retry look like a burst.
    #[test]
    fn fallbacks_are_not_attempted_twice() {
        let cfg = super::tests::cfg_mixed();
        let p = plan(&cfg, true, None, "cid");
        let fallbacks: Vec<usize> = p.order.iter().copied()
            .filter(|&i| cfg.endpoints[i].fallback).collect();
        let mut seen = std::collections::HashSet::new();
        for i in &fallbacks {
            assert!(seen.insert(*i), "fallback {i} appears twice in the plan");
        }
        assert!(!fallbacks.is_empty(), "fixture must contain fallbacks");
    }

    /// The head start has to leave room for the race. If it ever reaches
    /// DIRECT_BUDGET the pairing never happens and this silently reverts to the
    /// old sequential behaviour — passing tests, unchanged logs, no race.
    #[test]
    fn head_start_leaves_room_to_race() {
        assert!(crate::FALLBACK_HEAD_START < crate::DIRECT_BUDGET,
                "head start must be shorter than the direct budget");
        assert!(crate::DIRECT_BUDGET < crate::SELECT_BUDGET,
                "direct budget must leave time for the fallback tier");
    }
}
