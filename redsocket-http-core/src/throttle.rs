// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Client-side speed limit for the free tier.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tokio::sync::Mutex;
use tokio::time::Instant;

/// 0 = unlimited. Bytes per second, derived from the kbps the app sets.
static RATE_BPS: AtomicU64 = AtomicU64::new(0);

/// Burst allowance: how much unused budget can accumulate. One second's worth,
/// so a short idle period doesn't hand out an unbounded burst afterwards, but
/// bursty traffic (a page load) still feels responsive rather than sawtoothed.
const BURST_SECS: u64 = 1;

struct Bucket {
    tokens: f64,
    last: Instant,
}

static BUCKET: Mutex<Option<Bucket>> = Mutex::const_new(None);

/// Set the limit in kilobits per second; 0 (or negative from JNI) disables it.
pub fn set_kbps(kbps: i32) {
    let bps = if kbps <= 0 { 0 } else { (kbps as u64) * 1000 / 8 };
    let prev = RATE_BPS.swap(bps, Ordering::SeqCst);
    if prev != bps {
        log::info!(
            "[throttle] {} -> {}",
            if prev == 0 { "unlimited".to_string() } else { format!("{} B/s", prev) },
            if bps == 0 { "unlimited".to_string() } else { format!("{} B/s", bps) }
        );
    }
}

#[cfg(test)]
pub fn is_limited() -> bool {
    RATE_BPS.load(Ordering::SeqCst) > 0
}

/// Wait until `n` bytes may be sent. Returns immediately when unlimited, so the
/// paid/freedom path costs one relaxed atomic load per chunk and nothing else.
pub async fn consume(n: usize) {
    let rate = RATE_BPS.load(Ordering::SeqCst);
    if rate == 0 || n == 0 {
        return;
    }
    let need = n as f64;
    let burst = (rate * BURST_SECS) as f64;

    loop {
        let wait = {
            let mut g = BUCKET.lock().await;
            let now = Instant::now();
            let b = g.get_or_insert_with(|| Bucket { tokens: burst, last: now });

            let dt = now.saturating_duration_since(b.last).as_secs_f64();
            b.last = now;
            b.tokens = (b.tokens + dt * rate as f64).min(burst);

            if b.tokens >= need {
                b.tokens -= need;
                return;
            }

            if need > burst {
                b.tokens = 0.0;
                (need - burst) / rate as f64
            } else {
                (need - b.tokens) / rate as f64
            }
        };

        tokio::time::sleep(Duration::from_secs_f64(wait.clamp(0.0, 5.0))).await;
        if need > burst {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One test, run sequentially: RATE_BPS and BUCKET are process-global, so
    /// separate #[test] fns race each other and produce phantom passes/failures.
    #[tokio::test]
    async fn rate_limiting_behaves() {
        set_kbps(0);
        let t = Instant::now();
        for _ in 0..200 {
            consume(64 * 1024).await;
        }
        assert!(t.elapsed() < Duration::from_millis(200), "unlimited path is not free");

        set_kbps(800);
        let t = Instant::now();
        for _ in 0..300 {
            consume(1000).await;
        }
        let el = t.elapsed();
        assert!(el >= Duration::from_millis(1200), "no throttling happened: {:?}", el);
        assert!(el < Duration::from_secs(6), "throttled far too hard: {:?}", el);

        set_kbps(8);
        let t = Instant::now();
        consume(64 * 1024).await;
        assert!(t.elapsed() < Duration::from_secs(6), "oversized chunk stalled the relay");

        set_kbps(0);
        assert!(!is_limited());
    }
}
