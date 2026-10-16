// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Endpoint selection — **every** decision about which endpoint to try, and in
//! what order, lives in this module. Nothing else may reorder, skip or filter
//! endpoints; `start_http` just runs what `select()` returns.

use std::net::{IpAddr, SocketAddr};
use crate::FALLBACK_HEAD_START;
use std::time::{Duration, Instant};

use crate::config::Config;

/// May this client use the fallback tier (CDN fronts, distinctive names)?
pub static CDN_ALLOWED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

/// Pref key holding the last endpoint that completed a handshake ("host:port").
pub const LAST_OK_KEY: &str = "redsocket_last_ok";

/// Pref key holding endpoints that handshook but carried no traffic, comma
/// separated ("host:port,host:port").
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

#[derive(Debug, Default)]
pub struct Plan {
    pub order: Vec<usize>,
    pub dropped_v6: usize,
    /// Index of the remembered winner, if it survived into the plan.
    pub resumed: Option<usize>,
}

fn is_v6(host: &str) -> bool {
    matches!(host.parse::<IpAddr>(), Ok(IpAddr::V6(_)))
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
#[cfg(test)]
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

    let mut enabled: Vec<usize> = (0..n).filter(|&i| cfg.endpoints[i].weight > 0).collect();

    if !CDN_ALLOWED.load(std::sync::atomic::Ordering::SeqCst) {
        let direct: Vec<usize> = enabled.iter().copied()
            .filter(|&i| !cfg.endpoints[i].fallback).collect();
        if !direct.is_empty() {
            enabled = direct;
        }
    }

    let usable: Vec<usize> = if have_v6 {
        enabled.clone()
    } else {
        let keep: Vec<usize> = enabled
            .iter().copied().filter(|&i| !is_v6(&cfg.endpoints[i].host)).collect();
        if keep.is_empty() { enabled.clone() } else { keep }
    };
    let dropped_v6 = enabled.len() - usable.len();

    let usable: Vec<usize> = if bad.is_empty() {
        usable
    } else {
        let keep: Vec<usize> = usable
            .iter().copied()
            .filter(|&i| !bad.iter().any(|b| b == &cfg.endpoints[i].label()))
            .collect();
        if keep.is_empty() { usable } else { keep }
    };

    let key = |i: usize| -> u64 {
        let e = &cfg.endpoints[i];
        fnv1a(&format!("{}|{}", cid, e.label())) / e.weight.max(1) as u64
    };

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

    let mut order = premium;
    if let Some(r) = remembered { order.push(r); }
    order.extend(rest);

    Plan { order, dropped_v6, resumed: remembered }
}

/// Does this device have routable IPv6?
pub fn have_ipv6(cfg: &Config) -> bool {
    let Some(target) = cfg
        .endpoints
        .iter()
        .find_map(|e| e.host.parse::<IpAddr>().ok().filter(|ip| ip.is_ipv6()))
    else {
        return false;
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
pub async fn select(
    cfg: &Config,
    tz: &str,
    cid: &str,
    last_good: Option<&str>,
    budget: Duration,
    direct_budget: Duration,
) -> Option<usize> {
    let have_v6 = have_ipv6(cfg);

    let bad = bad_load();
    let p = plan_with_bad(cfg, have_v6, last_good, cid, &bad);
    log::info!(
        "[RS] selection: {} endpoints, v6={} (dropped {}), resume={}",
        p.order.len(),
        have_v6,
        p.dropped_v6,
        p.resumed
            .map(|i| cfg.endpoints[i].label())
            .unwrap_or_else(|| "none".into())
    );

    let started = Instant::now();
    let deadline = started + budget;
    let direct_deadline = started + direct_budget;

    let mut fallbacks = p.order.iter().copied()
        .filter(|&i| cfg.endpoints[i].fallback)
        .collect::<std::collections::VecDeque<_>>();
    let mut announced_race = false;

    for &i in &p.order {
        if Instant::now() >= deadline {
            log::warn!("[RS] selection budget exhausted before endpoint {}", i);
            break;
        }

        if cfg.endpoints[i].fallback {
            continue;
        }

        if Instant::now() >= direct_deadline {
            log::info!("[RS] direct tier exhausted — trying fallback tier only");
            break;
        }

        let ep = &cfg.endpoints[i];
        crate::set_race_status(&format!("trying:{}", ep.label()));

        if Instant::now() < started + FALLBACK_HEAD_START || fallbacks.is_empty() {
            match crate::tunnel::open_with_timeout(ep, &cfg.server_pubkey, tz, cid, false).await {
                Ok(t) => { drop(t); return Some(i); }
                Err(e) => log::info!("[RS] {} unusable: {}", ep.label(), e),
            }
            continue;
        }

        let j = fallbacks.pop_front().unwrap();
        let fb = &cfg.endpoints[j];
        if !announced_race {
            announced_race = true;
            log::info!("[RS] direct tier slow — racing fallbacks alongside it");
        }
        crate::set_race_status(&format!("racing:{}|{}", ep.label(), fb.label()));

        let direct_try = crate::tunnel::open_with_timeout(ep, &cfg.server_pubkey, tz, cid, false);
        let fallback_try = crate::tunnel::open_with_timeout(fb, &cfg.server_pubkey, tz, cid, false);
        tokio::pin!(direct_try);
        tokio::pin!(fallback_try);

        let mut direct_done = false;
        let mut fallback_done = false;
        loop {
            tokio::select! {
                biased;
                r = &mut direct_try, if !direct_done => {
                    direct_done = true;
                    match r {
                        Ok(t) => { drop(t); return Some(i); }
                        Err(e) => log::info!("[RS] {} unusable: {}", ep.label(), e),
                    }
                }
                r = &mut fallback_try, if !fallback_done => {
                    fallback_done = true;
                    match r {
                        Ok(t) => { drop(t); return Some(j); }
                        Err(e) => log::info!("[RS] {} unusable: {}", fb.label(), e),
                    }
                }
                else => break,
            }
            if direct_done && fallback_done {
                break;
            }
        }
    }

    while let Some(j) = fallbacks.pop_front() {
        if Instant::now() >= deadline {
            log::warn!("[RS] selection budget exhausted before fallback {}", j);
            break;
        }
        let fb = &cfg.endpoints[j];
        crate::set_race_status(&format!("trying:{}", fb.label()));
        match crate::tunnel::open_with_timeout(fb, &cfg.server_pubkey, tz, cid, false).await {
            Ok(t) => { drop(t); return Some(j); }
            Err(e) => log::info!("[RS] {} unusable: {}", fb.label(), e),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{BlocklistSettings, Endpoint, MeasureSettings, ProbeSettings};

    fn ep(host: &str, priority: u32, weight: u32, premium: bool, fallback: bool) -> Endpoint {
        Endpoint {
            host: host.into(), port: 80, upgrade_host: "h".into(), upgrade_path: "/noise".into(),
            priority, weight, premium, fallback,
        }
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
            probe: ProbeSettings::default(),
            blocklist: BlocklistSettings::default(),
            measure: MeasureSettings::default(),
            mux: false,
        }
    }

    const CID: &str = "abcd1234abcd1234";

    #[test]
    fn v6_is_dropped_without_v6_connectivity() {
        let c = cfg(&["1.1.1.1", "2001:db8::1", "2.2.2.2", "cdn.example"]);
        let hosts: Vec<_> = plan(&c, false, None, CID)
            .order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert!(!hosts.iter().any(|h| h.contains(':')), "v6 survived: {:?}", hosts);
        assert_eq!(hosts.len(), 3);

        assert_eq!(plan(&c, true, None, CID).order.len(), 4);
    }

    #[test]
    fn never_strands_a_v6_only_config() {
        let c = cfg(&["2001:db8::1", "2001:db8::2"]);
        assert_eq!(plan(&c, false, None, CID).order.len(), 2, "dropped every endpoint");
    }

    #[test]
    fn last_known_good_is_tried_first() {
        let c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        let p = plan(&c, true, Some("3.3.3.3:80"), CID);
        assert_eq!(p.order[0], 2);
        assert_eq!(p.resumed, Some(2));

        assert_eq!(p.order.len(), 3);
        let mut sorted = p.order.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, vec![0, 1, 2]);
    }

    #[test]
    fn premium_outranks_the_remembered_winner() {
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
        let p = plan(&c, true, Some("5.5.5.5:80"), CID);
        assert_eq!(p.resumed, None);
        assert_eq!(p.order.len(), 2);
    }

    #[test]
    fn lower_priority_number_is_tried_first() {
        let mut c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c.endpoints[2].priority = 5;
        c.endpoints[0].priority = 99;
        let hosts: Vec<_> = plan(&c, true, None, CID)
            .order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert_eq!(hosts.first().unwrap(), "3.3.3.3");
        assert_eq!(hosts.last().unwrap(), "1.1.1.1");
    }

    #[test]
    fn weight_biases_ties_and_zero_disables() {
        let mut c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        c.endpoints[1].weight = 0;
        let p = plan(&c, true, None, CID);
        let hosts: Vec<_> = p.order.iter().map(|&i| c.endpoints[i].host.clone()).collect();
        assert_eq!(hosts.len(), 2);
        assert!(!hosts.contains(&"2.2.2.2".to_string()));

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

    /// CloudFront costs money per byte, so the free tier is held to direct POPs —
    /// but the gate must never empty the list.
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

        let mut only_cdn = cfg(&["cdn1.example", "cdn2.example"]);
        for e in &mut only_cdn.endpoints { e.fallback = true; }
        assert_eq!(plan(&only_cdn, true, None, CID).order.len(), 2, "gate stranded the client");
        CDN_ALLOWED.store(true, Ordering::SeqCst);
    }

    /// A handshake is not a working tunnel, so an endpoint that connected and then
    /// failed the health check has to leave the plan -- and the list has to come
    /// back once every endpoint has been burned, or the user is left with nothing
    /// at all rather than something worth one more try.
    #[test]
    fn bad_endpoints_are_skipped_until_the_list_is_exhausted() {
        let c = cfg(&["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
        let label = |i: usize| c.endpoints[i].label();

        let hosts = |p: &Plan| -> Vec<String> {
            p.order.iter().map(|&i| c.endpoints[i].host.clone()).collect()
        };

        let one = vec![label(0)];
        let got = hosts(&plan_with_bad(&c, true, None, CID, &one));
        assert!(!got.contains(&"1.1.1.1".to_string()), "known-bad endpoint was still planned");
        assert_eq!(got.len(), 2);

        let all: Vec<String> = (0..3).map(label).collect();
        assert_eq!(
            plan_with_bad(&c, true, None, CID, &all).order.len(),
            3,
            "an all-bad list must start over, not strand the client"
        );

        let resumed = plan_with_bad(&c, true, Some(&label(1)), CID, &[]);
        assert_eq!(hosts(&resumed)[0], "2.2.2.2", "last_ok should lead");
        let vetoed = plan_with_bad(&c, true, Some(&label(1)), CID, &[label(1)]);
        assert!(
            !hosts(&vetoed).contains(&"2.2.2.2".to_string()),
            "a remembered winner that is known-bad must not be resumed"
        );
    }

    /// Load spreading must survive the change: equal-weight endpoints must not all
    /// lead on the same POP across installs.
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
    /// sequential tail, never both. Attempting one twice doubles the traffic to a
    /// CDN front and makes a retry look like a burst.
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
    /// DIRECT_BUDGET the pairing never happens and this silently reverts to the old
    /// sequential behaviour — passing tests, unchanged logs, no race.
    #[test]
    fn head_start_leaves_room_to_race() {
        assert!(crate::FALLBACK_HEAD_START < crate::DIRECT_BUDGET,
                "head start must be shorter than the direct budget");
        assert!(crate::DIRECT_BUDGET < crate::SELECT_BUDGET,
                "direct budget must leave time for the fallback tier");
    }
}
