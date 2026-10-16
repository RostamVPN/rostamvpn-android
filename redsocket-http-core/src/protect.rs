// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

//! Android VPN socket protection.

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Connect to `host:port`, protecting the socket from the tun.
pub async fn protected_connect_by_name(host: &str, port: u16) -> io::Result<tokio::net::TcpStream> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return protected_connect(SocketAddr::new(ip, port)).await;
    }

    let lookup = if host.contains(':') {
        format!("[{}]:{}", host, port)
    } else {
        format!("{}:{}", host, port)
    };
    let mut addrs = tokio::net::lookup_host(&lookup).await?;
    let addr = addrs.next().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("DNS resolution failed: {}", lookup),
        )
    })?;

    #[cfg(debug_assertions)]
    log::info!("[RS] {} -> {}", host, addr.ip());
    protected_connect(addr).await
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
    .map_err(|e| io::Error::other(format!("spawn_blocking join: {}", e)))??;

    std_tcp.set_nonblocking(true)?;
    tokio::net::TcpStream::from_std(std_tcp)
}
