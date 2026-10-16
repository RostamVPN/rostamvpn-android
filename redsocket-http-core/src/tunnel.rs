// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! One carrier: HTTP-Upgrade → Noise NK → META → stripped-SOCKS5 dest header.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::io::AsyncWriteExt;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};

use crate::config::Endpoint;
use crate::noise::{self, NoiseSession};

/// Whole-handshake budget. `protect` already bounds the TCP connect, but a
/// censor can accept the SYN and then stall forever — without this, a
/// blackholed POP hangs the dial instead of failing over.
pub const OPEN_TIMEOUT: Duration = Duration::from_secs(8);

/// TCP keepalive for a MUX2 carrier: idle time, probe interval, probe count.
const CARRIER_KEEPALIVE: (Duration, Duration, u32) =
    (Duration::from_secs(45), Duration::from_secs(15), 4);

/// Apply [`CARRIER_KEEPALIVE`] to a carrier socket. Best-effort: a kernel that
/// refuses one of these options is no reason to fail the dial.
fn set_carrier_keepalive(tcp: &tokio::net::TcpStream) {
    let (time, interval, retries) = CARRIER_KEEPALIVE;
    let ka = socket2::TcpKeepalive::new()
        .with_time(time)
        .with_interval(interval)
        .with_retries(retries);
    if let Err(e) = socket2::SockRef::from(tcp).set_tcp_keepalive(&ka) {
        log::warn!("carrier keepalive not set: {}", e);
    }
}

pub struct Tunnel {
    pub reader: OwnedReadHalf,
    pub writer: OwnedWriteHalf,
    pub session: NoiseSession,
}

/// Build the cleartext upgrade request.
fn upgrade_request(upgrade_host: &str, upgrade_path: &str) -> String {
    format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n",
        upgrade_path, upgrade_host
    )
}

/// [`open`] with a hard upper bound. Always prefer this over calling `open`.
pub async fn open_with_timeout(
    ep: &Endpoint,
    server_pubkey: &[u8; 32],
    tz: &str,
    cid: &str,
    mux: bool,
) -> io::Result<Tunnel> {
    match tokio::time::timeout(OPEN_TIMEOUT, open(ep, server_pubkey, tz, cid, mux)).await {
        Ok(res) => res,
        Err(_) => Err(io::Error::new(
            io::ErrorKind::TimedOut,
            format!("{} handshake timed out", ep.label()),
        )),
    }
}

/// Open and identify a tunnel. On return the Noise session is live and META has
/// been sent, but no destination is set yet — call [`send_dest`] next.
pub async fn open(
    ep: &Endpoint,
    server_pubkey: &[u8; 32],
    tz: &str,
    cid: &str,

    mux: bool,
) -> io::Result<Tunnel> {
    let tcp = crate::protect::protected_connect_by_name(&ep.host, ep.port).await?;

    if mux {
        set_carrier_keepalive(&tcp);
    }
    let (mut reader, mut writer) = tcp.into_split();

    let req = upgrade_request(&ep.upgrade_host, &ep.upgrade_path);
    writer.write_all(req.as_bytes()).await?;
    writer.flush().await?;

    let mut resp = [0u8; 1024];
    let n = reader.read(&mut resp).await?;
    let head = std::str::from_utf8(&resp[..n]).unwrap_or("");
    if !head.starts_with("HTTP/1.1 101") {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!(
                "upgrade rejected: {}",
                head.lines().next().unwrap_or("<no response>")
            ),
        ));
    }

    let mut session = noise::handshake_initiator(&mut reader, &mut writer, server_pubkey).await?;

    let hello = if mux {
        let (blk, msg) = crate::ctl::caps();
        crate::ctl::mux_hello(tz, cid, blk, msg)
    } else {
        format!("META|{}|||{}", tz, cid)
    };
    let ct = session.encrypt(hello.as_bytes())?;
    noise::write_frame(&mut writer, &ct).await?;

    Ok(Tunnel { reader, writer, session })
}

/// Write the stripped-SOCKS5 destination header. After this the tunnel is ready
/// for raw relay. Do not wait for a reply.
pub async fn send_dest(t: &mut Tunnel, host: &str, port: u16) -> io::Result<()> {
    let mut hdr = Vec::with_capacity(host.len() + 8);
    hdr.push(0x05);
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        hdr.push(0x01);
        hdr.extend_from_slice(&ip.octets());
    } else if let Ok(ip) = host.parse::<Ipv6Addr>() {
        hdr.push(0x04);
        hdr.extend_from_slice(&ip.octets());
    } else {
        if host.is_empty() || host.len() > 255 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "destination hostname length out of range",
            ));
        }
        hdr.push(0x03);
        hdr.push(host.len() as u8);
        hdr.extend_from_slice(host.as_bytes());
    }
    hdr.extend_from_slice(&port.to_be_bytes());

    let ct = t.session.encrypt(&hdr)?;
    noise::write_frame(&mut t.writer, &ct).await
}

#[cfg(test)]
mod live {
    //! End-to-end wire-compat check against a live POP.

    use super::*;

    /// The real shipped config — so this exercises the actual endpoint list, not a
    /// hand-picked IP that may not match what devices are told to dial.
    const REAL_CONFIG: &str =
        include_str!("../../android/gen/android/app/src/main/assets/default-config.json");
    /// Obviously synthetic so this run is identifiable as a test in the cid feed.
    const TEST_CID: &str = "deadbeefdeadbeef";
    /// The app's own site: first-party, so a live run tells no third party about
    /// the exit.
    const ECHO_HOST: &str = "rostam.app";

    /// The CDN path is the fallback when direct POP IPs are blocked, so it needs
    /// its own check: a 101 from CloudFront says nothing about whether the Noise
    /// handshake survives the CDN hop.
    #[tokio::test]
    #[ignore]
    async fn handshakes_through_cloudfront_front() {
        let cfg = crate::config::load(REAL_CONFIG, None, TEST_CID, false, "").expect("config must parse");
        let front = cfg
            .endpoints
            .iter()
            .find(|e| e.host.ends_with(".cloudfront.net"))
            .expect("config has no CDN front");

        let mut t = open_with_timeout(front, &cfg.server_pubkey, "", TEST_CID, false)
            .await
            .unwrap_or_else(|e| panic!("CDN front {} failed: {}", front.label(), e));
        println!("[live] Noise handshake OK via CDN front {}", front.label());

        send_dest(&mut t, ECHO_HOST, 80).await.expect("dest header");
        let req = format!("GET / HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", ECHO_HOST);
        let ct = t.session.encrypt(req.as_bytes()).unwrap();
        noise::write_frame(&mut t.writer, &ct).await.unwrap();

        let frame = tokio::time::timeout(Duration::from_secs(20), noise::read_frame(&mut t.reader))
            .await
            .expect("no response within 20s")
            .expect("frame read failed");
        let pt = t.session.decrypt(&frame).expect("decrypt failed");
        let body = String::from_utf8_lossy(&pt);
        println!("[live] {} bytes via CDN:\n{}", pt.len(), body.lines().next().unwrap_or(""));
        assert!(body.starts_with("HTTP/"), "expected HTTP through CDN, got {:?}", body);
    }

    /// Point the client at ONE named POP, to verify a specific node rather than
    /// whatever the shipped config happens to pick. Endpoint and key come from the
    /// environment so a node can be checked without editing code.
    #[tokio::test]
    #[ignore]
    async fn relays_through_a_named_pop() {
        let target = std::env::var("RS_POP").expect("set RS_POP=host:port");
        let pubkey = std::env::var("RS_PUB").expect("set RS_PUB=<server pubkey hex>");
        let (host, port) = target.rsplit_once(':').expect("RS_POP must be host:port");
        let port: u16 = port.parse().expect("port");

        let ep = crate::config::Endpoint {
            host: host.to_string(),
            port,
            upgrade_host: host.to_string(),
            upgrade_path: "/noise".to_string(),
            priority: 0,
            weight: 1,
            fallback: false,
            premium: false,
        };
        let mut key = [0u8; 32];
        let raw = hex::decode(pubkey.trim()).expect("RS_PUB must be hex");
        assert_eq!(raw.len(), 32, "RS_PUB must be 32 bytes");
        key.copy_from_slice(&raw);

        let mut t = open_with_timeout(&ep, &key, "", TEST_CID, false)
            .await
            .unwrap_or_else(|e| panic!("handshake against {target} failed: {e}"));
        println!("[live] Noise handshake OK against {target}");

        let host = std::env::var("RS_HOST").unwrap_or_else(|_| ECHO_HOST.to_string());
        let path = std::env::var("RS_URL").unwrap_or_else(|_| "/".to_string());
        send_dest(&mut t, &host, 80).await.expect("dest header");
        let req = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
        let ct = t.session.encrypt(req.as_bytes()).unwrap();
        noise::write_frame(&mut t.writer, &ct).await.unwrap();

        let t0 = std::time::Instant::now();
        let mut total = 0usize;
        let mut frames = 0usize;
        let mut first: Option<String> = None;
        while let Ok(Ok(frame)) =
            tokio::time::timeout(Duration::from_secs(20), noise::read_frame(&mut t.reader)).await
        {
            let pt = t.session.decrypt(&frame).expect("decrypt failed");
            if first.is_none() {
                first = Some(String::from_utf8_lossy(&pt).lines().next().unwrap_or("").to_string());
            }
            total += pt.len();
            frames += 1;
        }
        let secs = t0.elapsed().as_secs_f64();
        println!("[live] {} first-line={:?}", target, first.unwrap_or_default());
        println!("[live] RESULT bytes={total} secs={secs:.3} MBps={:.3} frames={frames} avg_frame={}",
                 total as f64 / 1e6 / secs, if frames > 0 { total / frames } else { 0 });
        assert!(total > 0, "nothing relayed");
    }

    #[tokio::test]
    #[ignore]
    async fn handshakes_and_relays_through_live_pop() {
        let cfg = crate::config::load(REAL_CONFIG, None, TEST_CID, false, "").expect("real config must parse");
        println!("[live] {} endpoints from shipped config", cfg.endpoints.len());

        let mut tunnel = None;
        for ep in &cfg.endpoints {
            match open_with_timeout(ep, &cfg.server_pubkey, "", TEST_CID, false).await {
                Ok(t) => {
                    println!("[live] handshake OK against {}", ep.label());
                    tunnel = Some(t);
                    break;
                }
                Err(e) => println!("[live] {} unusable: {}", ep.label(), e),
            }
        }
        let mut t = tunnel.expect("no endpoint in the shipped config completed a handshake");

        send_dest(&mut t, ECHO_HOST, 80)
            .await
            .expect("dest header write failed");
        let req = format!(
            "GET / HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
            ECHO_HOST
        );
        let ct = t.session.encrypt(req.as_bytes()).unwrap();
        noise::write_frame(&mut t.writer, &ct).await.unwrap();

        let frame = tokio::time::timeout(
            Duration::from_secs(15),
            noise::read_frame(&mut t.reader),
        )
        .await
        .expect("no response within 15s")
        .expect("frame read failed");
        let pt = t.session.decrypt(&frame).expect("decrypt failed");
        let body = String::from_utf8_lossy(&pt);
        println!("[live] {} bytes back from origin:\n{}", pt.len(), body);
        assert!(
            body.starts_with("HTTP/"),
            "expected an HTTP response through the tunnel, got: {:?}",
            body
        );
    }
}

#[cfg(test)]
mod carrier {
    use super::*;

    /// A silently-refused `setsockopt` is the realistic failure here: the code
    /// looks right, nothing errors at runtime because we swallow it on purpose, and
    /// the carrier still dies behind NAT with nobody the wiser.
    #[tokio::test]
    async fn keepalive_is_really_applied_to_the_socket() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let accept = tokio::spawn(async move { listener.accept().await.unwrap() });
        let tcp = tokio::net::TcpStream::connect(addr).await.unwrap();
        let _server = accept.await.unwrap();

        let sock = socket2::SockRef::from(&tcp);
        assert!(!sock.keepalive().unwrap(), "keepalive is off by default");

        set_carrier_keepalive(&tcp);
        let (time, interval, retries) = CARRIER_KEEPALIVE;
        assert!(sock.keepalive().unwrap(), "SO_KEEPALIVE never got enabled");
        assert_eq!(sock.keepalive_time().unwrap(), time);
        assert_eq!(sock.keepalive_interval().unwrap(), interval);
        assert_eq!(sock.keepalive_retries().unwrap(), retries);

        assert!(time < Duration::from_secs(60));
    }
}

#[cfg(test)]
mod cleartext {
    use super::*;

    /// The cleartext half of the handshake must carry nothing that identifies the
    /// device. Regression guard.
    #[test]
    fn upgrade_request_leaks_no_identifier() {
        let cid = "deadbeefdeadbeef";
        let req = upgrade_request("login.microsoftonline.com", "/v1/assets/app.js");

        assert!(!req.contains(cid), "cid appears in the cleartext request");
        assert!(!req.contains('?'), "query string in the upgrade URL: {:?}", req);

        assert!(
            req.starts_with("GET /v1/assets/app.js HTTP/1.1\r\n"),
            "path is not bare: {:?}",
            req
        );
        assert!(
            upgrade_request("h", "/noise").starts_with("GET /noise HTTP/1.1\r\n"),
            "default path must still produce the historical request line"
        );

        let headers: Vec<&str> = req.trim_end().lines().skip(1).collect();
        assert_eq!(headers.len(), 3, "unexpected headers: {:?}", headers);
        for h in headers {
            let name = h.split(':').next().unwrap().to_ascii_lowercase();
            assert!(
                matches!(name.as_str(), "host" | "upgrade" | "connection"),
                "unexpected header {:?} — anything device-specific here is cleartext",
                h
            );
        }
    }
}
