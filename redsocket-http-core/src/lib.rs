// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! redsocket_core — HTTP-Upgrade-only RedSocket client core for Android.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

mod cfgseal;

#[cfg(any(target_os = "ios", target_os = "macos"))]
pub mod ffi;

/// Test-only re-export so the round-trip example can reach the unwrapper
/// without making the module public to the whole crate.
#[doc(hidden)]
pub fn unseal_config_for_test(sealed: &[u8], cert: &[u8]) -> std::io::Result<String> {
    cfgseal::unseal(sealed, cert)
}
mod blocklist;
mod config;
mod noise;
#[path = "edge.rs"]
mod reach;
#[path = "peer.rs"]
pub mod probe;
mod protect;
mod select;
mod socks5;
mod throttle;
mod mux;
mod ctl;
mod tunnel;

/// Total budget for finding a working endpoint. Bounded so a fully blocked
/// network produces a definitive failure instead of an endless "Connecting…".
pub(crate) const SELECT_BUDGET: Duration = Duration::from_secs(60);
/// How much of that budget the DIRECT tiers (IP literals + region IPs) may use
/// before we skip ahead to the fallback tier (CDN fronts, then names).
pub(crate) const DIRECT_BUDGET: Duration = Duration::from_secs(32);
/// How long the direct tier gets alone before fallbacks are raced alongside it.
pub(crate) const FALLBACK_HEAD_START: Duration = Duration::from_secs(10);

/// How long Kotlin's blocking `start()` waits for a verdict before giving up.
const START_VERDICT_TIMEOUT: Duration = Duration::from_secs(90);

static RUNNING: AtomicBool = AtomicBool::new(false);

/// The SOCKS port we ACTUALLY bound, which is not always the one we were asked
/// for. 1080 is the registered SOCKS port and anything else on the device may
/// hold it, so `bind_preferring` falls back to an OS-assigned port.
static SOCKS_PORT: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

/// The port actually bound, or 0 when not running.
pub(crate) fn socks_port() -> u16 {
    SOCKS_PORT.load(Ordering::SeqCst)
}
static CONNECTIONS: AtomicU64 = AtomicU64::new(0);
static ACTIVE_LABEL: Mutex<Option<String>> = Mutex::new(None);

/// The winning endpoint's own `label()` ("host:port"), with NO carrier prefix.
static ACTIVE_ENDPOINT: Mutex<Option<String>> = Mutex::new(None);
static RACE_STATUS: Mutex<Option<String>> = Mutex::new(None);

/// The endpoint actually carrying traffic now. Called on every (re)dial, so
/// rs_connection_status / getConnectionStatus report the live carrier, not the
/// one chosen at start (§12 re-run.
pub(crate) fn set_active_carrier(label: &str) {
    if !RUNNING.load(Ordering::SeqCst) {
        return;
    }
    if let Ok(mut g) = ACTIVE_LABEL.lock() {
        *g = Some(format!("http:{}", label));
    }
    if let Ok(mut g) = ACTIVE_ENDPOINT.lock() {
        *g = Some(label.to_string());
    }
}
static CONFIG_JSON: Mutex<Option<String>> = Mutex::new(None);
static SELECTED_REGION: Mutex<Option<String>> = Mutex::new(None);
/// Paying user? Selects the premium endpoint pool (`premium_entries`). Set from
/// Kotlin via setPremium() whenever entitlement changes. Defaults to false: a
/// wrong value here must never hand free users the premium POPs.
static PREMIUM: AtomicBool = AtomicBool::new(false);
static SHUTDOWN: Mutex<Option<tokio::sync::watch::Sender<bool>>> = Mutex::new(None);

static CLIENT_ID: Mutex<Option<String>> = Mutex::new(None);

pub fn client_id() -> String {
    let mut cid = CLIENT_ID.lock().unwrap();
    if let Some(ref id) = *cid {
        return id.clone();
    }
    #[cfg(target_os = "android")]
    {
        if let Some(stored) = jni_get_pref("redsocket_cid") {
            if stored.len() == 16 {
                *cid = Some(stored.clone());
                return stored;
            }
        }
    }

    use rand::Rng;
    let id = format!("{:016x}", rand::thread_rng().gen::<u64>());
    #[cfg(target_os = "android")]
    {
        jni_set_pref("redsocket_cid", &id);
    }
    *cid = Some(id.clone());
    id
}

pub fn set_client_id(id: &str) {
    *CLIENT_ID.lock().unwrap() = Some(id.to_string());
}

fn selected_region() -> Option<String> {
    SELECTED_REGION
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .filter(|r| !r.is_empty() && r != "auto")
}

fn set_race_status(s: &str) {
    if let Ok(mut g) = RACE_STATUS.lock() {
        *g = Some(s.to_string());
    }
}

/// Last-known-good endpoint, persisted so reconnects start from something
/// proven. cfg-gated: the host build (tests) has no SharedPreferences.
fn last_ok_load() -> Option<String> {
    pref_get(select::LAST_OK_KEY)
}

fn last_ok_store(label: &str) {
    pref_put(select::LAST_OK_KEY, label);
}

/// Read a string from the app-private `redsocket` prefs.
pub(crate) fn pref_get(key: &str) -> Option<String> {
    #[cfg(target_os = "android")]
    { jni_get_pref(key) }
    #[cfg(not(target_os = "android"))]
    { let _ = key; None }
}

pub(crate) fn pref_put(key: &str, value: &str) {
    #[cfg(target_os = "android")]
    { jni_set_pref(key, value); }
    #[cfg(not(target_os = "android"))]
    { let _ = (key, value); }
}

fn start_http(socks_port: u16) -> Result<String, String> {
    if RUNNING.load(Ordering::SeqCst) {
        return Err("already running".into());
    }

    let json = CONFIG_JSON
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .ok_or_else(|| "no config loaded — call loadConfig() first".to_string())?;

    let region = selected_region();
    let cid = client_id();
    let premium = PREMIUM.load(Ordering::SeqCst);

    let tz = probe::get_timezone();
    let cfg = config::load(&json, region.as_deref(), &cid, premium, &tz)?;

    log::info!(
        "[RS] http-only core: {} endpoints, region={}, tier={}",
        cfg.endpoints.len(),
        region.as_deref().unwrap_or("auto"),
        if premium { "premium" } else { "free" }
    );

    probe::set_start_delay(cfg.probe.start_delay_secs.0, cfg.probe.start_delay_secs.1);
    probe::set_relay_limits(&cfg.probe.allowed_ports, cfg.probe.max_stream_bytes);
    probe::set_stealth(cfg.probe.stream_jitter_ms, cfg.probe.max_streams_per_hour);
    if cfg.probe.enabled && !cfg.probe.gateway_host.is_empty() {
        probe::set_gateway(&cfg.probe.gateway_host, cfg.probe.gateway_port);
    } else {
        probe::set_gateway("", 0);
        log::info!("[RS] probe gateway not set (probe disabled or no host in config)");
    }

    blocklist::configure(&cfg.blocklist);

    reach::configure(reach::Settings {
        enabled: cfg.measure.enabled,
        targets: cfg
            .measure
            .targets
            .iter()
            .filter_map(|&i| cfg.endpoints.get(i).map(|e| (e.host.clone(), e.port)))
            .collect(),
        rejected: cfg.measure.rejected,
        interval_secs: cfg.measure.interval_secs,
        jitter_secs: cfg.measure.jitter_secs,
        max_per_run: cfg.measure.max_per_run,
        connect_timeout: Duration::from_millis(cfg.measure.connect_timeout_ms),
        start_delay_secs: cfg.measure.start_delay_secs,
        gap_ms: cfg.measure.gap_ms,
    });

    let (verdict_tx, verdict_rx) = std::sync::mpsc::sync_channel::<Result<String, String>>(1);
    let (sd_tx, sd_rx) = tokio::sync::watch::channel(false);
    if let Ok(mut g) = SHUTDOWN.lock() {
        *g = Some(sd_tx);
    }

    std::thread::Builder::new()
        .name("rs-http".into())
        .spawn(move || {
            let rt = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(4)
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    let _ = verdict_tx.send(Err(format!("tokio runtime: {}", e)));
                    return;
                }
            };

            rt.block_on(async move {
                let last_good = last_ok_load();
                let winner = select::select(
                    &cfg, &tz, &cid, last_good.as_deref(), SELECT_BUDGET, DIRECT_BUDGET,
                ).await;

                let Some(idx) = winner else {
                    set_race_status("failed");
                    let _ = verdict_tx.send(Err("no http endpoint reachable".into()));
                    return;
                };

                let label = cfg.endpoints[idx].label();
                set_race_status(&format!("won:{}", label));

                last_ok_store(&label);

                let (listener, bound_port) = match socks5::bind_preferring(socks_port).await {
                    Ok(pair) => pair,
                    Err(e) => {
                        let _ = verdict_tx.send(Err(format!("bind 127.0.0.1:{}: {}", socks_port, e)));
                        return;
                    }
                };
                SOCKS_PORT.store(bound_port, Ordering::SeqCst);

                if let Ok(mut g) = ACTIVE_LABEL.lock() {
                    *g = Some(format!("http:{}", label));
                }

                if let Ok(mut g) = ACTIVE_ENDPOINT.lock() {
                    *g = Some(label.clone());
                }
                RUNNING.store(true, Ordering::SeqCst);
                let _ = verdict_tx.send(Ok(format!("http:{}", label)));

                let up = Arc::new(socks5::Upstream::new(cfg.endpoints, cfg.server_pubkey, idx, cfg.mux));

                probe::start(bound_port);

                blocklist::spawn_refresh(bound_port, &cfg.blocklist);

                if let Err(e) = socks5::serve(listener, up, sd_rx).await {
                    log::error!("[RS] socks5 server stopped: {}", e);
                }

                RUNNING.store(false, Ordering::SeqCst);
    SOCKS_PORT.store(0, Ordering::SeqCst);
                if let Ok(mut g) = ACTIVE_LABEL.lock() {
                    *g = None;
                }
            });
        })
        .map_err(|e| format!("thread spawn: {}", e))?;

    match verdict_rx.recv_timeout(START_VERDICT_TIMEOUT) {
        Ok(res) => res,
        Err(_) => Err("endpoint selection timed out".into()),
    }
}

fn stop_http() {
    log::info!("[RS] stop requested");
    ctl::reset_session();
    RUNNING.store(false, Ordering::SeqCst);
    probe::reset();

    blocklist::reset();
    if let Ok(mut g) = ACTIVE_LABEL.lock() {
        *g = None;
    }

    if let Ok(mut g) = ACTIVE_ENDPOINT.lock() {
        *g = None;
    }
    if let Ok(mut g) = SHUTDOWN.lock() {
        if let Some(tx) = g.take() {
            let _ = tx.send(true);
        }
    }
}

static JVM: std::sync::atomic::AtomicPtr<std::ffi::c_void> =
    std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());
static VPN_SERVICE_CLASS: std::sync::atomic::AtomicPtr<std::ffi::c_void> =
    std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

#[cfg(target_os = "android")]
static APP_CONTEXT: std::sync::OnceLock<jni::objects::GlobalRef> = std::sync::OnceLock::new();

#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    vm: jni::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    JVM.store(
        vm.get_java_vm_pointer() as *mut std::ffi::c_void,
        Ordering::SeqCst,
    );

    if let Ok(mut env) = vm.get_env() {
        if let Ok(cls) = env.find_class("com/rostamvpn/android/TunnelVpnService") {
            if let Ok(global) = env.new_global_ref(&cls) {
                VPN_SERVICE_CLASS.store(
                    Box::into_raw(Box::new(global)) as *mut std::ffi::c_void,
                    Ordering::SeqCst,
                );
            }
        }
        #[cfg(target_os = "android")]
        {
            if let Ok(at) = env.find_class("android/app/ActivityThread") {
                if let Ok(app) =
                    env.call_static_method(&at, "currentApplication", "()Landroid/app/Application;", &[])
                {
                    if let Ok(obj) = app.l() {
                        if !obj.is_null() {
                            if let Ok(r) = env.new_global_ref(obj) {
                                let _ = APP_CONTEXT.set(r);
                            }
                        }
                    }
                }
            }
        }
    }

    jni::sys::JNI_VERSION_1_6
}

/// Make a socket bypass the tun. MUST be called before connect() on every
/// outbound socket, or the tunnel routes into itself.
pub fn protect_socket(fd: i32) {
    #[cfg(target_os = "android")]
    {
        let vm_ptr = JVM.load(Ordering::SeqCst) as *mut jni::sys::JavaVM;
        if vm_ptr.is_null() {
            return;
        }
        let jvm = match unsafe { jni::JavaVM::from_raw(vm_ptr) } {
            Ok(v) => v,
            Err(_) => return,
        };
        let result = (|| -> Result<(), String> {
            let mut env = jvm.attach_current_thread().map_err(|e| e.to_string())?;
            let cls_ptr = VPN_SERVICE_CLASS.load(Ordering::SeqCst);
            if cls_ptr.is_null() {
                return Err("VPN class not cached".into());
            }
            let cls_ref: &jni::objects::GlobalRef = unsafe { &*(cls_ptr as *const jni::objects::GlobalRef) };
            let cls = unsafe { jni::objects::JClass::from_raw(cls_ref.as_obj().as_raw()) };

            let instance = env
                .get_static_field(&cls, "instance", "Lcom/rostamvpn/android/TunnelVpnService;")
                .map_err(|e| e.to_string())?;
            let obj = instance.l().map_err(|e| e.to_string())?;
            if obj.is_null() {
                return Err("no VPN instance yet".into());
            }
            env.call_method(&obj, "protect", "(I)Z", &[jni::objects::JValue::Int(fd)])
                .map_err(|e| e.to_string())?;
            Ok(())
        })();
        std::mem::forget(jvm);
        if let Err(e) = result {
            log::warn!("[RS] protect(fd={}) skipped: {}", fd, e);
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = fd;
}

#[cfg(target_os = "android")]
fn jni_get_pref(key: &str) -> Option<String> {
    use jni::objects::JValue;
    let ctx = APP_CONTEXT.get()?;
    let vm_ptr = JVM.load(Ordering::SeqCst) as *mut jni::sys::JavaVM;
    if vm_ptr.is_null() {
        return None;
    }
    let vm = unsafe { jni::JavaVM::from_raw(vm_ptr).ok()? };
    let out = (|| -> Option<String> {
        let mut env = vm.attach_current_thread_permanently().ok()?;
        let prefs = env
            .call_method(
                ctx.as_obj(),
                "getSharedPreferences",
                "(Ljava/lang/String;I)Landroid/content/SharedPreferences;",
                &[
                    JValue::Object(&env.new_string("redsocket").ok()?.into()),
                    JValue::Int(0),
                ],
            )
            .ok()?
            .l()
            .ok()?;
        let jkey = env.new_string(key).ok()?;
        let jdef = env.new_string("").ok()?;
        let res = env
            .call_method(
                &prefs,
                "getString",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&jkey.into()), JValue::Object(&jdef.into())],
            )
            .ok()?
            .l()
            .ok()?;
        let s: String = env.get_string((&res).into()).ok()?.into();
        if s.is_empty() { None } else { Some(s) }
    })();
    std::mem::forget(vm);
    out
}

#[cfg(target_os = "android")]
fn jni_set_pref(key: &str, value: &str) {
    use jni::objects::JValue;
    let Some(ctx) = APP_CONTEXT.get() else { return };
    let vm_ptr = JVM.load(Ordering::SeqCst) as *mut jni::sys::JavaVM;
    if vm_ptr.is_null() {
        return;
    }
    let vm = match unsafe { jni::JavaVM::from_raw(vm_ptr) } {
        Ok(v) => v,
        Err(_) => return,
    };
    let ok = (|| -> Option<()> {
        let mut env = vm.attach_current_thread_permanently().ok()?;
        let prefs = env
            .call_method(
                ctx.as_obj(),
                "getSharedPreferences",
                "(Ljava/lang/String;I)Landroid/content/SharedPreferences;",
                &[
                    JValue::Object(&env.new_string("redsocket").ok()?.into()),
                    JValue::Int(0),
                ],
            )
            .ok()?
            .l()
            .ok()?;
        let editor = env
            .call_method(&prefs, "edit", "()Landroid/content/SharedPreferences$Editor;", &[])
            .ok()?
            .l()
            .ok()?;
        let jkey = env.new_string(key).ok()?;
        let jval = env.new_string(value).ok()?;
        env.call_method(
            &editor,
            "putString",
            "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;",
            &[JValue::Object(&jkey.into()), JValue::Object(&jval.into())],
        )
        .ok()?;
        env.call_method(&editor, "apply", "()V", &[]).ok()?;
        Some(())
    })();
    std::mem::forget(vm);
    if ok.is_none() {
        log::debug!("[cid] failed to persist client id");
    }
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(o) => o.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Unwrap a sealed config: verify the signature, then decrypt.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_nativeUnsealConfig(
    mut env: JNIEnv,
    _class: JClass,
    sealed_b64: JString,
    cert_sha256_hex: JString,
) -> jstring {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let b64: String = match env_get(&mut env, &sealed_b64) {
            Some(v) => v,
            None => return "err:no-input".to_string(),
        };
        let cert: String = env_get(&mut env, &cert_sha256_hex).unwrap_or_default();
        use base64::Engine;
        let raw = match base64::engine::general_purpose::STANDARD.decode(b64.trim()) {
            Ok(v) => v,
            Err(_) => return "err:not-base64".to_string(),
        };
        let cert_bytes = hex::decode(cert.trim()).unwrap_or_default();
        match crate::cfgseal::unseal(&raw, &cert_bytes) {
            Ok(json) => json,

            Err(_) => "err:unseal".to_string(),
        }
    }))
    .unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &out)
}

fn env_get(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|v| v.into())
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_start(
    mut env: JNIEnv,
    _class: JClass,
    socks_port: jint,
) -> jstring {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("RedSocket"),
    );
    let port = socks_port as u16;
    log::info!("[RS] start, SOCKS5 port={}", port);

    let msg = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_http(port))) {
        Ok(Ok(entry)) => format!("ok:{}", entry),
        Ok(Err(e)) => format!("err:{}", e),
        Err(p) => {
            let m = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            log::error!("[RS] PANIC in start: {}", m);
            format!("err:panic:{}", m)
        }
    };
    jstr(&mut env, &msg)
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_stop(_env: JNIEnv, _class: JClass) {
    stop_http();
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_isRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    RUNNING.load(Ordering::SeqCst) as jint
}

/// The port actually bound, or 0 when not running.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getSocksPort(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    socks_port() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setClientId(
    mut env: JNIEnv,
    _class: JClass,
    cid: JString,
) {
    if let Ok(s) = env.get_string(&cid) {
        let id: String = s.into();
        if !id.is_empty() {
            set_client_id(&id);
            log::info!("[RS] client id injected ({} chars)", id.len());
        }
    }
}

/// Inject an opaque per-session tag used by the relay subsystem. Empty clears
/// it. Independent of setClientId.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setPeerTag(
    mut env: JNIEnv,
    _class: JClass,
    probe_id: JString,
) {
    if let Ok(s) = env.get_string(&probe_id) {
        let id: String = s.into();
        probe::set_probe_id(&id);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_loadConfig(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) {
    if let Ok(s) = env.get_string(&config_json) {
        let json: String = s.into();
        log::info!("[RS] loadConfig: {} bytes", json.len());
        ctl::configure(&json);
        if let Ok(mut g) = CONFIG_JSON.lock() {
            *g = Some(json);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getConnectionStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let running = RUNNING.load(Ordering::SeqCst);
    let carrier = ACTIVE_LABEL.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    let s = serde_json::json!({
        "status": if running { "connected" } else { "disconnected" },
        "carrier": carrier,
    })
    .to_string();
    jstr(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getActiveCarrier(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = ACTIVE_LABEL.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    jstr(&mut env, &s)
}

/// Record whether the endpoint now carrying traffic actually works.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_markActiveCarrier(
    _env: JNIEnv,
    _class: JClass,
    ok: jni::sys::jboolean,
) {
    let label = match ACTIVE_ENDPOINT.lock().ok().and_then(|g| g.clone()) {
        Some(l) if !l.is_empty() => l,

        _ => return,
    };
    if ok != 0 {
        select::bad_clear(&label);
    } else {
        log::info!("[RS] endpoint failed the post-connect check, skipping it next");
        select::bad_add(&label);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getRaceStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = RACE_STATUS.lock().ok().and_then(|g| g.clone()).unwrap_or_default();
    jstr(&mut env, &s)
}

/// The carrier menu, **derived from the loaded config** — never hardcoded — so
/// the UI can only offer a carrier this `.so` builds AND the config actually
/// populates.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_rsCarriers(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let region = selected_region();
    let configured = CONFIG_JSON
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .and_then(|j| config::load(&j, region.as_deref(), &client_id(),
                                   PREMIUM.load(Ordering::SeqCst), &probe::get_timezone()).ok())
        .is_some_and(|c| !c.endpoints.is_empty());

    let json = if configured {
        serde_json::json!([{
            "id": "http",
            "name": "HTTP",
            "region": region.unwrap_or_default(),
            "transport": "http",
            "desc": "Web (port 80)",
        }])
    } else {
        log::warn!("[RS] rsCarriers: no usable config — returning empty carrier list");
        serde_json::json!([])
    };
    jstr(&mut env, &json.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_runDiagnostics(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let region = selected_region();
    let endpoints = CONFIG_JSON
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .and_then(|j| config::load(&j, region.as_deref(), &client_id(),
                                   PREMIUM.load(Ordering::SeqCst), &probe::get_timezone()).ok())
        .map(|c| c.endpoints.iter().map(|e| e.label()).collect::<Vec<_>>())
        .unwrap_or_default();
    let s = serde_json::json!({
        "core": "http-only",
        "running": RUNNING.load(Ordering::SeqCst),
        "carrier": ACTIVE_LABEL.lock().ok().and_then(|g| g.clone()).unwrap_or_default(),
        "race_status": RACE_STATUS.lock().ok().and_then(|g| g.clone()).unwrap_or_default(),
        "region": region.unwrap_or_else(|| "auto".into()),
        "timezone": probe::get_timezone(),
        "connections": CONNECTIONS.load(Ordering::Relaxed),
        "endpoints": endpoints,
        "px_on": probe::is_enabled(),
        "px_run": probe::is_running(),

        "lm": reach::stats(),
    })
    .to_string();
    jstr(&mut env, &s)
}

/// Kept for ABI compatibility: with one carrier there is nothing to filter.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setProtocolFilter(
    mut env: JNIEnv,
    _class: JClass,
    proto: JString,
) {
    if let Ok(s) = env.get_string(&proto) {
        let p: String = s.into();
        if !p.is_empty() && p != "auto" && p != "http" {
            log::info!("[RS] protocol filter '{}' ignored — this core is http-only", p);
        }
    }
}

/// Tell the core whether this install is entitled to the premium endpoint pool.
/// Call it whenever entitlement changes (launch, purchase, restore) and BEFORE
/// start() — it is read when the endpoint list is built.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setCdnAllowed(
    _env: JNIEnv,
    _class: JClass,
    allowed: jni::sys::jboolean,
) {
    let on = allowed != 0;
    select::CDN_ALLOWED.store(on, Ordering::SeqCst);
    log::info!("[RS] cdn fallback {}", if on { "allowed" } else { "denied (free tier)" });
}

/// Free-tier speed limit in kbps; 0 = unlimited. The APP decides who is limited
/// (paid and freedom regions never are) — the core only enforces it.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setRateLimitKbps(
    _env: JNIEnv,
    _class: JClass,
    kbps: jint,
) {
    throttle::set_kbps(kbps);
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setPremium(
    _env: JNIEnv,
    _class: JClass,
    premium: jni::sys::jboolean,
) {
    let on = premium != 0;
    PREMIUM.store(on, Ordering::SeqCst);
    log::info!("[RS] tier set to {}", if on { "premium" } else { "free" });
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setRegion(
    mut env: JNIEnv,
    _class: JClass,
    region: JString,
) {
    if let Ok(s) = env.get_string(&region) {
        let r: String = s.into();
        log::info!("[RS] region set to '{}'", r);
        if let Ok(mut g) = SELECTED_REGION.lock() {
            *g = if r.is_empty() { None } else { Some(r) };
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_enablePeer(
    _env: JNIEnv,
    _class: JClass,
) {
    probe::enable();
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_disablePeer(
    _env: JNIEnv,
    _class: JClass,
) {
    probe::disable();
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setPeerZone(
    mut env: JNIEnv,
    _class: JClass,
    tz: JString,
) {
    if let Ok(s) = env.get_string(&tz) {
        let t: String = s.into();
        probe::set_timezone(&t);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setPeerApp(
    mut env: JNIEnv,
    _class: JClass,
    app: JString,
) {
    if let Ok(s) = env.get_string(&app) {
        let a: String = s.into();
        probe::set_app(&a);
        ctl::set_app(&a);
    }
}

/// Queued downstream control-stream messages as a JSON array, oldest first.
/// Drains. "[]" when none (or no msg1 tunnel has been up).
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_drainNotes(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = ctl::drain_notes();
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setPeerRoute(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
) {
    if let Ok(s) = env.get_string(&host) {
        let h: String = s.into();
        probe::set_gateway(&h, port as u16);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_startPeer(
    _env: JNIEnv,
    _class: JClass,
    socks_port: jint,
) {
    probe::start(socks_port as u16);
}

/// Master gate. Premium **or** freedom region — a decision only the app can
/// make, because the core sees neither entitlement nor SIM country. Defaults to
/// off, so a missing call can never start filtering somebody's traffic.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setBlockingEnabled(
    _env: JNIEnv,
    _class: JClass,
    enabled: jni::sys::jboolean,
) {
    let _ = std::panic::catch_unwind(|| blocklist::set_enabled(enabled != 0));
}

/// Install a blocklist document directly, bypassing the core's own fetch.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setBlocklist(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match env_get(&mut env, &json) {
            Some(j) => match blocklist::install_json(&j, None) {
                Ok(m) => m,
                Err(e) => format!("err:{e}"),
            },
            None => "err:no-input".to_string(),
        }
    }))
    .unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &out)
}

/// Counts plus the recent blocks with their category, so the app can tell the
/// user something true and offer "allow this" on the spot.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getBlockStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = std::panic::catch_unwind(blocklist::stats_json).unwrap_or_else(|_| "{}".to_string());
    jstr(&mut env, &out)
}

/// Add or clear one of the user's own rules. `verdict` is `allow`, `deny` or
/// `clear`. Takes effect on the next CONNECT — no reconnect, no refetch — which
/// is what makes "allow this" work from the block notice itself.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setUserRule(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    verdict: JString,
) -> jstring {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Some(h), Some(v)) = (env_get(&mut env, &host), env_get(&mut env, &verdict)) else {
            return "err:no-input".to_string();
        };
        match blocklist::set_user_rule(&h, &v) {
            Ok(m) => m,
            Err(e) => format!("err:{e}"),
        }
    }))
    .unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &out)
}

/// Replace both user lists wholesale: `{"allow":[…],"deny":[…]}`.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_setUserRules(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match env_get(&mut env, &json) {
            Some(j) => match blocklist::set_user_lists_json(&j) {
                Ok(m) => m,
                Err(e) => format!("err:{e}"),
            },
            None => "err:no-input".to_string(),
        }
    }))
    .unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &out)
}

/// The user's own rules as authored, for the settings screen.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_getUserRules(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = std::panic::catch_unwind(blocklist::user_lists_json)
        .unwrap_or_else(|_| r#"{"allow":[],"deny":[]}"#.to_string());
    jstr(&mut env, &out)
}

/// Forget which destinations were blocked. The user's rules are untouched.
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_RedSocketBridge_clearBlockHistory(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(blocklist::clear_history);
}

#[cfg(test)]
mod active_carrier_tests {
    use super::*;

    #[test]
    fn a_redial_updates_the_reported_carrier_only_while_running() {
        RUNNING.store(true, Ordering::SeqCst);
        if let Ok(mut g) = ACTIVE_LABEL.lock() { *g = Some("http:127.0.0.1:8081".into()); }
        set_active_carrier("127.0.0.1:8082");
        assert_eq!(ACTIVE_LABEL.lock().unwrap().as_deref(), Some("http:127.0.0.1:8082"));
        assert_eq!(ACTIVE_ENDPOINT.lock().unwrap().as_deref(), Some("127.0.0.1:8082"));
        RUNNING.store(false, Ordering::SeqCst);
        set_active_carrier("10.0.0.1:80");
        assert_eq!(ACTIVE_LABEL.lock().unwrap().as_deref(), Some("http:127.0.0.1:8082"), "no label after stop");
    }
}
