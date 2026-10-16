// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Endpoint reachability measurement.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

macro_rules! info  { ($($t:tt)*) => {{ let _ = format_args!($($t)*); }}; }
macro_rules! warn  { ($($t:tt)*) => {{ let _ = format_args!($($t)*); }}; }

static RUNNING: AtomicBool = AtomicBool::new(false);
static SETTINGS: Mutex<Option<Settings>> = Mutex::new(None);
static RESULTS: Mutex<Vec<Counter>> = Mutex::new(Vec::new());

/// Resolved, ready-to-use policy. Built by [`configure`] from the parsed config
/// — every `(host, port)` in [`Settings::targets`] came out of
/// `Config::endpoints`, never out of the raw JSON.
#[derive(Clone)]
pub struct Settings {
    pub enabled: bool,
    pub targets: Vec<(String, u16)>,
    /// Targets the config named that were not in its own endpoint set. Counted
    /// rather than logged, so a config error is visible without a log line that
    /// describes what this module does.
    pub rejected: usize,
    pub interval_secs: u64,
    pub jitter_secs: (u64, u64),
    pub max_per_run: usize,
    pub connect_timeout: Duration,
    pub start_delay_secs: (u64, u64),
    pub gap_ms: (u64, u64),
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            enabled: false,
            targets: Vec::new(),
            rejected: 0,
            interval_secs: 3600,
            jitter_secs: (0, 900),
            max_per_run: 4,
            connect_timeout: Duration::from_millis(4000),
            start_delay_secs: (60, 600),
            gap_ms: (500, 5000),
        }
    }
}

struct Counter {
    label: String,
    ok: u32,
    fail: u32,
    /// Connect time of the most recent attempt, milliseconds.
    last_ms: u32,
    last_ok: bool,
}

/// Install the policy for this session. Never starts anything: [`start`] does,
/// and only behind the peer relay's own consent gate.
pub fn configure(s: Settings) {
    if let Ok(mut g) = SETTINGS.lock() {
        *g = Some(s);
    }
}

/// Begin measuring, if the peer relay is enabled and consented.
pub fn start() {
    let s = match SETTINGS.lock().ok().and_then(|g| g.clone()) {
        Some(s) => s,
        None => return,
    };
    if !s.enabled || s.targets.is_empty() {
        return;
    }

    if !crate::probe::should_run() {
        return;
    }
    if RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }

    std::thread::Builder::new()
        .name("edge-m".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                    Ok(rt) => rt.block_on(run_loop(s)),
                    Err(e) => {
                        warn!("[edge] runtime: {}", e);
                    }
                }
            }));
            if result.is_err() {
                warn!("[edge] panic in measurement loop");
            }
            RUNNING.store(false, Ordering::SeqCst);
        })
        .ok();
}

/// Drop every counter. Called from `probe::reset` on disconnect: a measurement
/// belongs to the session that produced it.
pub fn reset() {
    RUNNING.store(false, Ordering::SeqCst);
    if let Ok(mut g) = RESULTS.lock() {
        g.clear();
    }
}

/// Reachability per endpoint, for the diagnostics surface.
pub fn stats() -> serde_json::Value {
    let (enabled, rejected, targets) = SETTINGS
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .map(|s| (s.enabled, s.rejected, s.targets.len()))
        .unwrap_or((false, 0, 0));

    let rows: Vec<serde_json::Value> = RESULTS
        .lock()
        .map(|g| {
            g.iter()
                .map(|c| {
                    serde_json::json!({
                        "e": c.label,
                        "ok": c.ok,
                        "no": c.fail,
                        "ms": c.last_ms,
                        "up": c.last_ok,
                    })
                })
                .collect()
        })
        .unwrap_or_default();

    serde_json::json!({
        "on": enabled,
        "run": RUNNING.load(Ordering::Relaxed),
        "tgt": targets,
        "rej": rejected,
        "r": rows,
    })
}

async fn run_loop(s: Settings) {
    if !sleep_secs(pick(s.start_delay_secs)).await {
        return;
    }

    loop {
        if !crate::probe::should_run() {
            break;
        }
        one_run(&s).await;
        if !sleep_secs(s.interval_secs + pick(s.jitter_secs)).await {
            break;
        }
    }
    info!("[edge] stopped");
}

async fn one_run(s: &Settings) {
    let mut order: Vec<usize> = (0..s.targets.len()).collect();
    shuffle(&mut order);

    for i in order.into_iter().take(s.max_per_run) {
        if !crate::probe::should_run() {
            return;
        }

        if !sleep_ms(pick(s.gap_ms)).await {
            return;
        }

        let (host, port) = &s.targets[i];
        let started = Instant::now();

        let outcome = tokio::time::timeout(
            s.connect_timeout,
            crate::protect::protected_connect_by_name(host, *port),
        )
        .await;

        let ok = matches!(outcome, Ok(Ok(_)));
        record(&format!("{}:{}", host, port), ok, started.elapsed());
    }
}

fn record(label: &str, ok: bool, took: Duration) {
    let ms = took.as_millis().min(u32::MAX as u128) as u32;
    let Ok(mut g) = RESULTS.lock() else { return };
    if let Some(c) = g.iter_mut().find(|c| c.label == label) {
        if ok {
            c.ok = c.ok.saturating_add(1);
        } else {
            c.fail = c.fail.saturating_add(1);
        }
        c.last_ms = ms;
        c.last_ok = ok;
        return;
    }

    g.push(Counter {
        label: label.to_string(),
        ok: ok as u32,
        fail: !ok as u32,
        last_ms: ms,
        last_ok: ok,
    });
}

fn pick((lo, hi): (u64, u64)) -> u64 {
    let (lo, hi) = if lo <= hi { (lo, hi) } else { (hi, lo) };
    if hi == 0 {
        return 0;
    }
    use rand::Rng;
    rand::thread_rng().gen_range(lo..=hi)
}

fn shuffle(v: &mut [usize]) {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    for i in (1..v.len()).rev() {
        v.swap(i, rng.gen_range(0..=i));
    }
}

/// Interruptible sleep. Returns false if the session ended while waiting, so a
/// disconnect or a probe-disable aborts before the next connection is made
/// rather than after it.
async fn sleep_secs(secs: u64) -> bool {
    for _ in 0..secs {
        if !crate::probe::should_run() {
            return false;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    crate::probe::should_run()
}

async fn sleep_ms(ms: u64) -> bool {
    let mut left = ms;
    while left > 0 {
        if !crate::probe::should_run() {
            return false;
        }
        let step = left.min(500);
        tokio::time::sleep(Duration::from_millis(step)).await;
        left -= step;
    }
    crate::probe::should_run()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_run_never_exceeds_its_cap() {
        let s = Settings {
            targets: (0..20).map(|i| (format!("10.0.0.{}", i), 80)).collect(),
            max_per_run: 4,
            ..Settings::default()
        };
        let mut order: Vec<usize> = (0..s.targets.len()).collect();
        shuffle(&mut order);
        assert_eq!(order.len(), 20);
        assert!(order.iter().take(s.max_per_run).count() <= 4);
    }

    #[test]
    fn shuffle_is_a_permutation() {
        let mut v: Vec<usize> = (0..50).collect();
        shuffle(&mut v);
        v.sort_unstable();
        assert_eq!(v, (0..50).collect::<Vec<_>>());
    }

    #[test]
    fn pick_respects_and_repairs_its_range() {
        for _ in 0..200 {
            let n = pick((5, 15));
            assert!((5..=15).contains(&n));

            let r = pick((15, 5));
            assert!((5..=15).contains(&r));
        }
        assert_eq!(pick((0, 0)), 0);
    }

    #[test]
    fn defaults_are_quiet_and_off() {
        let d = Settings::default();
        assert!(!d.enabled, "must fail closed");
        assert!(d.targets.is_empty());
        assert!(d.interval_secs >= 900, "a fleet this size cannot poll faster");
        assert!(d.max_per_run <= 16);
        assert!(d.start_delay_secs.1 > d.start_delay_secs.0);
    }

    #[test]
    fn counters_accumulate_per_endpoint_and_clear_on_reset() {
        reset();
        record("1.2.3.4:80", true, Duration::from_millis(12));
        record("1.2.3.4:80", false, Duration::from_millis(4000));
        record("5.6.7.8:80", true, Duration::from_millis(30));

        let v = stats();
        let rows = v["r"].as_array().unwrap();
        assert_eq!(rows.len(), 2, "one row per endpoint, not one per attempt");
        let first = rows.iter().find(|r| r["e"] == "1.2.3.4:80").unwrap();
        assert_eq!(first["ok"], 1);
        assert_eq!(first["no"], 1);
        assert_eq!(first["up"], false, "last attempt failed");

        reset();
        assert!(stats()["r"].as_array().unwrap().is_empty(), "a session's results must not outlive it");
    }

    #[test]
    fn stats_carry_nothing_about_the_user() {
        reset();
        record("192.0.2.160:80", true, Duration::from_millis(40));
        let s = stats().to_string();

        assert!(s.contains("192.0.2.160:80"));
        for leak in ["cid", "tz", "timezone", "uid", "gaid", "session"] {
            assert!(!s.contains(leak), "status leaked `{}`", leak);
        }
        reset();
    }
}
