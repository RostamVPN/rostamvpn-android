// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! WGTCP Android core — WireGuard over a TCP carrier, as a second protocol
//! alongside redsocket_http.

#[cfg(any(target_os = "ios", target_os = "macos"))]
pub mod ffi;

#[cfg(target_os = "android")]
use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::sys::{jint, jstring};
#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use std::ffi::c_void;
#[cfg(target_os = "android")]
use std::panic::catch_unwind;
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(target_os = "android")]
use std::sync::atomic::AtomicPtr;
use std::sync::{Arc, Mutex, OnceLock};

#[cfg(target_os = "android")]
static JVM: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
#[cfg(target_os = "android")]
static VPN_SERVICE_CLASS: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static RUNNING: AtomicBool = AtomicBool::new(false);

/// The tokio runtime driving the tunnel, plus a shutdown handle.
struct Tunnel {
    runtime: tokio::runtime::Runtime,
    shutdown: tokio::sync::watch::Sender<bool>,
}

static TUNNEL: OnceLock<Mutex<Option<Arc<Tunnel>>>> = OnceLock::new();

fn tunnel_slot() -> &'static Mutex<Option<Arc<Tunnel>>> {
    TUNNEL.get_or_init(|| Mutex::new(None))
}

/// Last error or status, readable from Kotlin without a second call path.
static STATUS: OnceLock<Mutex<String>> = OnceLock::new();

fn set_status(s: impl Into<String>) {
    let m = STATUS.get_or_init(|| Mutex::new(String::new()));
    if let Ok(mut g) = m.lock() {
        *g = s.into();
    }
}

/// The stored status, except that a running tunnel whose carrier has no
/// completed handshake reports `reconnecting` so callers never see a stale
/// `connected`.
fn status() -> String {
    if RUNNING.load(Ordering::SeqCst) && !redsocket_wgtcp::carrier::carrier_is_up() {
        return "reconnecting".to_string();
    }
    STATUS
        .get()
        .and_then(|m| m.lock().ok().map(|g| g.clone()))
        .unwrap_or_default()
}

/// Make a socket bypass the tun by calling
/// `TunnelVpnService.instance.protect(fd)`.
pub fn protect_socket(fd: i32) {
    #[cfg(not(target_os = "android"))]
    {
        let _ = fd;
    }
    #[cfg(target_os = "android")]
    {
    let vm_ptr = JVM.load(Ordering::SeqCst) as *mut jni::sys::JavaVM;
    if vm_ptr.is_null() {
        log::warn!("protect_socket: no JVM cached; socket NOT protected");
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
            return Err("VpnService class not cached".into());
        }
        let cls_ref: &jni::objects::GlobalRef =
            unsafe { &*(cls_ptr as *const jni::objects::GlobalRef) };
        let cls = unsafe { JClass::from_raw(cls_ref.as_obj().as_raw()) };

        let instance = env
            .get_static_field(&cls, "instance", "Lcom/rostamvpn/android/TunnelVpnService;")
            .map_err(|e| format!("get instance: {e}"))?
            .l()
            .map_err(|e| format!("instance not an object: {e}"))?;
        if instance.is_null() {
            return Err("TunnelVpnService.instance is null".into());
        }

        let ok = env
            .call_method(
                &instance,
                "protect",
                "(I)Z",
                &[jni::objects::JValue::Int(fd)],
            )
            .map_err(|e| format!("call protect: {e}"))?
            .z()
            .map_err(|e| format!("protect did not return boolean: {e}"))?;
        if !ok {
            return Err("protect() returned false".into());
        }
        Ok(())
    })();

    if let Err(e) = result {
        log::warn!("protect_socket({fd}) failed: {e}");
    }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: jni::JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("wgtcp"),
    );

    JVM.store(vm.get_java_vm_pointer() as *mut c_void, Ordering::SeqCst);

    if let Ok(mut env) = vm.get_env() {
        if let Ok(cls) = env.find_class("com/rostamvpn/android/TunnelVpnService") {
            if let Ok(global) = env.new_global_ref(&cls) {
                VPN_SERVICE_CLASS.store(
                    Box::into_raw(Box::new(global)) as *mut c_void,
                    Ordering::SeqCst,
                );
            }
        }
    }

    redsocket_wgtcp::carrier::set_protect_hook(protect_socket);

    jni::sys::JNI_VERSION_1_6
}

#[cfg(target_os = "android")]
fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|o| o.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Start the tunnel: parse the config, run the carrier, serve SOCKS5.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_WgtcpBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    socks_port: jint,
) -> jstring {
    let cfg_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("err:config-string:{e}");
            return jstr(&mut env, &msg);
        }
    };
    let port = socks_port as u16;

    let result = catch_unwind(move || {
        if RUNNING.load(Ordering::SeqCst) {
            return "err:already-running".to_string();
        }

        match start_tunnel(&cfg_str, port) {
            Ok(()) => {
                RUNNING.store(true, Ordering::SeqCst);
                set_status("connected");
                "ok".to_string()
            }
            Err(e) => {
                set_status(format!("failed: {e}"));
                format!("err:{e}")
            }
        }
    })
    .unwrap_or_else(|_| "err:panic".to_string());

    jstr(&mut env, &result)
}

/// Build the runtime and spawn the carrier + SOCKS5 tasks.
fn start_tunnel(config_json: &str, socks_port: u16) -> Result<(), String> {
    let mut cfg: redsocket_wgtcp::config::Config =
        serde_json::from_str(config_json).map_err(|e| format!("config:{e}"))?;
    cfg.socks_listen = format!("127.0.0.1:{socks_port}");
    cfg.validate_public().map_err(|e| format!("config:{e}"))?;

    redsocket_wgtcp::carrier::set_tunnel_is_ipv6(cfg.address.is_ipv6());

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|e| format!("runtime:{e}"))?;

    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);

    runtime.spawn(async move {
        if let Err(e) = redsocket_wgtcp::run_tunnel(cfg, shutdown_rx).await {
            log::warn!("tunnel exited: {e:#}");
        }
    });

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
    while std::time::Instant::now() < deadline {
        if redsocket_wgtcp::carrier::carrier_is_up() {
            if let Ok(mut slot) = tunnel_slot().lock() {
                *slot = Some(Arc::new(Tunnel {
                    runtime,
                    shutdown: shutdown_tx,
                }));
            }
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }

    let _ = shutdown_tx.send(true);
    runtime.shutdown_timeout(std::time::Duration::from_secs(2));
    Err("carrier never reached the server".into())
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_WgtcpBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(stop_tunnel);
}

/// Tear the tunnel down. Shared by the JNI and C entry points so the two
/// platforms cannot diverge on shutdown ordering.
pub(crate) fn stop_tunnel() {
    if let Ok(mut slot) = tunnel_slot().lock() {
        if let Some(t) = slot.take() {
            let _ = t.shutdown.send(true);

            if let Ok(t) = Arc::try_unwrap(t) {
                t.runtime
                    .shutdown_timeout(std::time::Duration::from_secs(2));
            }
        }
    }
    RUNNING.store(false, Ordering::SeqCst);
    set_status("stopped");
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_WgtcpBridge_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if RUNNING.load(Ordering::SeqCst) {
        1
    } else {
        0
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_WgtcpBridge_nativeGetConnectionStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = catch_unwind(status).unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &s)
}

/// Generate a WireGuard keypair for this install.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_rostamvpn_android_WgtcpBridge_nativeGenerateKeypair(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = catch_unwind(|| {
        let (sk, pk) = redsocket_wgtcp::generate_keypair();
        format!("{sk}:{pk}")
    })
    .unwrap_or_else(|_| "err:panic".to_string());
    jstr(&mut env, &s)
}

#[cfg(test)]
mod tests {
    /// The exact JSON shape VpnViewModel.wgtcpConfigJson() emits must deserialize
    /// into Config. This is the seam between Kotlin and Rust.
    #[test]
    fn kotlin_config_json_deserializes() {
        let json = r#"{
            "private_key": "AHAlq7pgszzhPoY2eI+S271LpGCODEDJvw2uJ506XXk=",
            "peer_public_key": "FIaq/nh1GSXvuI9mIh2MjOFgRVs1mb6P54v0Q0bqPWs=",
            "endpoint": "203.0.113.10:8446",
            "address": "10.77.0.50",
            "gateway": "10.77.0.1",
            "prefix_len": 24,
            "mtu": 1380,
            "persistent_keepalive": 25
        }"#;
        let cfg: redsocket_wgtcp::config::Config =
            serde_json::from_str(json).expect("Kotlin's JSON must deserialize into Config");
        assert_eq!(cfg.endpoint, "203.0.113.10:8446");
        assert_eq!(cfg.prefix_len, 24);
        assert_eq!(cfg.mtu, 1380);
        assert!(cfg.address.is_ipv4());

        cfg.validate_public().expect("must validate");
    }

    /// An IPv6 tunnel config must survive the same path, since the whole point of
    /// the derived-address scheme is v6 inner addressing.
    #[test]
    fn ipv6_config_json_deserializes() {
        let json = r#"{
            "private_key": "AHAlq7pgszzhPoY2eI+S271LpGCODEDJvw2uJ506XXk=",
            "peer_public_key": "FIaq/nh1GSXvuI9mIh2MjOFgRVs1mb6P54v0Q0bqPWs=",
            "endpoint": "203.0.113.10:8446",
            "address": "fd00:5ed0:c0de::2",
            "gateway": "fd00:5ed0:c0de::1",
            "prefix_len": 64,
            "mtu": 1380
        }"#;
        let cfg: redsocket_wgtcp::config::Config = serde_json::from_str(json).unwrap();
        assert!(cfg.address.is_ipv6());
        assert_eq!(cfg.persistent_keepalive, 25, "must default when omitted");
        cfg.validate_public().expect("v6 config must validate");
    }

    /// A missing required field must be an error, not a default that produces a
    /// tunnel dialing nowhere.
    #[test]
    fn missing_required_field_is_rejected() {
        let json = r#"{"private_key": "x", "endpoint": "1.2.3.4:1"}"#;
        assert!(serde_json::from_str::<redsocket_wgtcp::config::Config>(json).is_err());
    }
}
