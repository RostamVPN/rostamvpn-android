package com.rostamvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevConfigTest {
    @Test fun ipv6IsBridgedOnlyWhenTheFleetSaysSo() {
        val off = HevConfig.text("/d", 1080, false, ipv6 = false)
        assertFalse(off.contains("ipv6"))
        val on = HevConfig.text("/d", 1080, false, ipv6 = true)
        assertTrue(on.contains("tunnel:\n  mtu: 1500\n  ipv4: '10.0.0.2'\n  ipv6: 'fd00::2'\n"))
    }

    @Test fun unchangedWithoutTheFlag() {
        // Byte for byte what every brand ran before the IPv6 work.
        val expected = """
misc:
  task-stack-size: 81920
  log-file: /d/hev-socks5-tunnel.log
  log-level: debug

tunnel:
  mtu: 1500
  ipv4: '10.0.0.2'

socks5:
  address: '127.0.0.1'
  port: 1080

mapdns:
  address: '198.18.0.2'
  port: 53
  network: '100.64.0.0'
  netmask: '255.192.0.0'
  cache-size: 10000
""".trimIndent()
        assertEquals(expected, HevConfig.text("/d", 1080, false, false))
    }

    @Test fun dnsStaysOnMapdnsEitherWay() {
        listOf(false, true).forEach { v6 ->
            val c = HevConfig.text("/d", 1080, false, v6)
            assertTrue(c.contains("mapdns:\n  address: '198.18.0.2'"))
        }
    }

    @Test fun tunnelUdpIsOffUnlessTheConfigSaysTrue() {
        assertFalse(HevConfig.tunnelUdp(null))
        assertFalse(HevConfig.tunnelUdp(org.json.JSONObject("{}")))
        assertFalse(HevConfig.tunnelUdp(org.json.JSONObject("""{"tunnel":{}}""")))
        assertFalse(HevConfig.tunnelUdp(org.json.JSONObject("""{"tunnel":{"udp":false}}""")))
        assertFalse(HevConfig.tunnelUdp(org.json.JSONObject("""{"tunnel":true}""")))
        assertTrue(HevConfig.tunnelUdp(org.json.JSONObject("""{"tunnel":{"udp":true}}""")))
    }

    @Test fun udpModeIsPlainAssociate() {
        val on = HevConfig.text("/d", 1080, supportsUdp = true, ipv6 = false)
        assertTrue(on.contains("socks5:\n  address: '127.0.0.1'\n  port: 1080\n  udp: 'udp'\n"))
        assertFalse(on.contains("udp: 'tcp'"))   // FWD_UDP: the core declines it
        assertFalse(HevConfig.text("/d", 1080, false, false).contains("udp"))
    }

    // The boot config this tree ships (src/main; the internetpro asset it used
    // to read left with that brand). No `tunnel` block = UDP stays off.
    @Test fun shippedConfigKeepsUdpOff() {
        val f = listOf(java.io.File("src/main/assets/default-config.json"),
            java.io.File("app/src/main/assets/default-config.json")).first { it.exists() }
        assertFalse(HevConfig.tunnelUdp(org.json.JSONObject(f.readText())))
    }
}
