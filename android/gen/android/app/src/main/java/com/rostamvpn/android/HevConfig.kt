package com.rostamvpn.android

/** hev-socks5-tunnel settings, apart from TunnelVpnService so the JVM tests can load them. */
object HevConfig {
    /** The tun's own IPv6 address (addAddress below); hev answers on it when IPv6 is bridged. */
    const val TUN_V6 = "fd00::2"

    /**
     * hev-socks5-tunnel's config. Pure, so the IPv6 gate is tested.
     * Without [ipv6] the tunnel section has no `ipv6:` and hev drops v6
     * packets (the tun still captures `::/0`, so nothing leaks and apps
     * fall back to v4). With it hev bridges them as SOCKS ATYP 4.
     * DNS stays on mapdns (v4 198.18.0.2): names go to the exit as names
     * (ATYP 3) and the exit resolves AAAA and A, so nothing is resolved here.
     */
    fun text(dir: String, socksPort: Int, supportsUdp: Boolean, ipv6: Boolean, origin: Boolean = false): String {
        // 'udp' = plain SOCKS5 UDP ASSOCIATE, the only UDP the core accepts
        // (socks5.rs handle_udp_associate, carried over MUX2). hev's 'tcp'
        // mode is FWD_UDP (0x05), which the core declines, and so is hev's
        // default: without the key UDP stays off, as it always was.
        val udpConfig = if (supportsUdp) "\n  udp: 'udp'" else ""
        val v6 = if (ipv6) "\n  ipv6: '$TUN_V6'" else ""
        // BuildConfig.APP_ATTRIBUTION: the brand ships the patched bridge
        // (tools/hev), which then sends each TCP session's app-side socket as
        // the SOCKS5 username; the core records it for getConnectionOwnerUid.
        // The stock bridge would send "@origin" literally, so never without it.
        val origin = if (origin) "\n  username: '@origin'\n  password: '-'" else ""
        return """
misc:
  task-stack-size: 81920
  log-file: $dir/hev-socks5-tunnel.log
  log-level: debug

tunnel:
  mtu: 1500
  ipv4: '10.0.0.2'$v6

socks5:
  address: '127.0.0.1'
  port: $socksPort$udpConfig$origin

mapdns:
  address: '198.18.0.2'
  port: 53
  network: '100.64.0.0'
  netmask: '255.192.0.0'
  cache-size: 10000
""".trimIndent()
    }

    /**
     * OTA `tunnel.udp` (default false; absent = false, so no brand changes
     * until the fleet config says so). When true, UDP rides the tunnel: QUIC
     * and HTTP/3 instead of their TCP fallback. Pure, so the default is tested.
     */
    fun tunnelUdp(cfg: org.json.JSONObject?): Boolean =
        cfg?.optJSONObject("tunnel")?.optBoolean("udp", false) ?: false
}
