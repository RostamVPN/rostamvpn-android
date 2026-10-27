//! Android VPN socket protection.
//!
//! Sockets created inside a VPN app route through the tun by default. The
//! tunnel's own upstream socket MUST bypass it via `VpnService.protect(fd)` or
//! it routes into itself. Everything that dials out goes through here.

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Connect to `host:port`, protecting the socket from the tun.
///
/// Handles bare IPv6 literals correctly (`2001:db8:5::d` needs bracketing before
/// it can be parsed as a `SocketAddr`), which matters because the region IP lists
/// mix v4 and v6.
pub async fn protected_connect_by_name(host: &str, port: u16) -> io::Result<tokio::net::TcpStream> {
    // Fast path: already an IP literal (v4 or v6, unbracketed).
    if let Ok(ip) = host.parse::<IpAddr>() {
        return protected_connect(SocketAddr::new(ip, port)).await;
    }

    // Hostname → resolve. Bracket v6 so the lookup string is well-formed.
    let lookup = if host.contains(':') {
        format!("[{}]:{}", host, port)
    } else {
        format!("{}:{}", host, port)
    };
    let resolved: Vec<SocketAddr> = tokio::net::lookup_host(&lookup).await?.collect();
    if resolved.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("DNS resolution failed: {}", lookup),
        ));
    }
    // Family fallback (IPv6, 2026-10-17). This dialled only the FIRST address
    // the resolver returned, so a name with A and AAAA died with whichever
    // family came first: a broken v6 path on a dual-stack Wi-Fi, or a v4
    // address on a v6-only carrier without CLAT. Now every address is a
    // candidate, alternating families in the resolver's order (RFC 8305),
    // capped so a dead family cannot eat the selection budget.
    dial_in_turn(dial_order(resolved), |addr| async move {
    // WHICH BOX, not just which name -- DEBUG BUILDS ONLY.
    //
    // The endpoint lists moved from IP literals to hostnames, so the winner the
    // app reports is now the config entry ("ok:http:pop-b-p-v6.example.net:80")
    // rather than the address. A name fronts six boxes and the resolver picks,
    // so this is the only place that knows which one a given dial used.
    //
    // 🚨 NEVER IN A RELEASE BUILD. This is a live exit address, and the threat
    // model is someone reading logcat or decompiling on a device in Iran -- §0
    // forbids putting infrastructure on any surface they can reach, and a log
    // line is such a surface. `cfg(debug_assertions)` is a COMPILE-TIME
    // exclusion: the string never enters the release binary, so there is
    // nothing to find. Do not convert this to a runtime flag -- that is exactly
    // what shipped Dev Tools to production for months, because R8 and the
    // linker keep what a runtime branch might reach.
        #[cfg(debug_assertions)]
        log::info!("[nc] {} -> {}", host, addr.ip());
        protected_connect(addr).await
    })
    .await
}

/// At most this many addresses of one name are tried, both families together.
const MAX_ADDRS: usize = 4;

/// Resolver order, families alternated, starting with the resolver's own first
/// choice (it already applied RFC 6724), capped at [MAX_ADDRS]. Pure.
pub(crate) fn dial_order(addrs: Vec<SocketAddr>) -> Vec<SocketAddr> {
    let first_v6 = addrs.first().map(|a| a.is_ipv6()).unwrap_or(false);
    let (mut a, mut b): (Vec<_>, Vec<_>) = addrs.into_iter().partition(|x| x.is_ipv6() == first_v6);
    a.reverse();
    b.reverse();
    let mut out = Vec::new();
    while out.len() < MAX_ADDRS && (!a.is_empty() || !b.is_empty()) {
        if let Some(x) = a.pop() { out.push(x); }
        if out.len() < MAX_ADDRS { if let Some(x) = b.pop() { out.push(x); } }
    }
    out
}

/// Dial each address in turn; the first that connects wins, else the last error.
pub(crate) async fn dial_in_turn<T, F, Fut>(addrs: Vec<SocketAddr>, mut dial: F) -> io::Result<T>
where
    F: FnMut(SocketAddr) -> Fut,
    Fut: std::future::Future<Output = io::Result<T>>,
{
    let mut last = None;
    for a in addrs {
        match dial(a).await {
            Ok(s) => return Ok(s),
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no address to dial")))
}

/// Create a protected TCP connection to `addr`.
pub async fn protected_connect(addr: SocketAddr) -> io::Result<tokio::net::TcpStream> {
    let std_tcp = tokio::task::spawn_blocking(move || {
        let domain = if addr.is_ipv4() {
            socket2::Domain::IPV4
        } else {
            socket2::Domain::IPV6
        };
        let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;

        // Must happen BEFORE connect, or the SYN goes through the tun.
        #[cfg(target_os = "android")]
        {
            use std::os::unix::io::AsRawFd;
            crate::protect_socket(socket.as_raw_fd());
        }

        socket.set_nodelay(true).ok();
        socket.connect_timeout(&addr.into(), CONNECT_TIMEOUT)?;
        Ok::<std::net::TcpStream, io::Error>(std::net::TcpStream::from(socket))
    })
    .await
    .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("spawn_blocking join: {}", e)))??;

    std_tcp.set_nonblocking(true)?;
    tokio::net::TcpStream::from_std(std_tcp)
}

#[cfg(test)]
mod family_fallback_tests {
    use super::*;

    fn v4(n: u8) -> SocketAddr { SocketAddr::from(([192, 0, 2, n], 80)) }
    fn v6(n: u16) -> SocketAddr { SocketAddr::from(([0x2001, 0xdb8, 0, 0, 0, 0, 0, n], 80)) }

    #[test]
    fn alternates_families_from_the_resolvers_first_choice() {
        assert_eq!(dial_order(vec![v6(1), v6(2), v4(1), v4(2)]), vec![v6(1), v4(1), v6(2), v4(2)]);
        assert_eq!(dial_order(vec![v4(1), v6(1), v6(2)]), vec![v4(1), v6(1), v6(2)]);
        assert_eq!(dial_order(vec![v4(1), v4(2)]), vec![v4(1), v4(2)]);
    }

    #[test]
    fn is_capped() {
        let many: Vec<_> = (1..=6).map(v6).chain((1..=6).map(v4)).collect();
        assert_eq!(dial_order(many).len(), MAX_ADDRS);
    }

    #[tokio::test]
    async fn a_dead_first_family_falls_back_to_the_other() {
        // v6 path broken (the dual-stack Wi-Fi case): the v4 address connects.
        let got = dial_in_turn(dial_order(vec![v6(1), v4(1)]), |a| async move {
            if a.is_ipv6() { Err(io::Error::new(io::ErrorKind::Other, "v6 unreachable")) } else { Ok(a) }
        }).await.unwrap();
        assert_eq!(got, v4(1));
        // v6-only carrier without CLAT: v4 fails, v6 connects.
        let got = dial_in_turn(dial_order(vec![v4(1), v6(1)]), |a| async move {
            if a.is_ipv4() { Err(io::Error::new(io::ErrorKind::Other, "no v4 route")) } else { Ok(a) }
        }).await.unwrap();
        assert_eq!(got, v6(1));
    }

    #[tokio::test]
    async fn all_dead_returns_the_last_error() {
        let e = dial_in_turn(vec![v6(1), v4(1)], |_| async { Err::<(), _>(io::Error::new(io::ErrorKind::TimedOut, "t")) })
            .await.unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::TimedOut);
    }
}
