package com.rostamvpn.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// Protect Mode's first-party exemption. Everything this install is configured
// to reach (the OTA config's entry points, API bases and health check, the WGTCP
// endpoints it was provisioned with, and the build's own web, store and
// account hosts) is ours: never sent to /score or /scan, never stored with a
// verdict, never an automatic or profile block, never "Needs attention".
//
// Matching: exact host for everything the config names (a CloudFront or
// workers.dev name must never exempt its whole platform); parent domain only
// for the build's own brand hosts (BuildConfig), so www.vpn.red and any
// e-sim.net subdomain are covered; IPs and CIDRs by prefix, v4 and v6.
//
// Deliberately NOT included:
//  - transport.*.upgrade_hosts: the HTTP-Upgrade cover names (other people's
//    sites). Exempting them would stop scoring the user's real traffic there.
//  - api.mirrors: third-party proxies by design (ConfigManager).
//  - DNS resolutions: the app is outside its own tun, so a lookup here would be
//    a plaintext query for our infrastructure on the censored network, and the
//    flow table carries names, not addresses (hev mapdns), so it buys nothing.
//
// Pure (no Context, no Log) so the JVM unit tests can drive it.
// ─────────────────────────────────────────────────────────────────────────────

/** An address prefix. [bytes] is 4 or 16 long. */
data class FirstPartyNet(val bytes: ByteArray, val prefix: Int) {
    fun contains(ip: ByteArray): Boolean {
        if (ip.size != bytes.size) return false
        var bits = prefix
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((ip[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
            bits -= 8; i++
        }
        return true
    }

    override fun equals(other: Any?) = other is FirstPartyNet && prefix == other.prefix && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + prefix
}

class FirstPartySet(
    /** Exact hostnames. */
    val hosts: Set<String>,
    /** Brand domains: the name itself and every subdomain. */
    val domains: Set<String>,
    val nets: List<FirstPartyNet>,
    /** Entries contributed per source, for diagnostics ("config.endpoints" -> 31). */
    val sources: Map<String, Int>,
) {
    val size: Int get() = hosts.size + domains.size + nets.size

    fun matches(raw: String): Boolean {
        val h = FirstParty.normalize(raw) ?: return false
        FirstParty.ipBytes(h)?.let { ip -> return nets.any { it.contains(ip) } }
        if (h in hosts || h in domains) return true
        var dot = h.indexOf('.')
        while (dot >= 0) {
            if (h.substring(dot + 1) in domains) return true
            dot = h.indexOf('.', dot + 1)
        }
        return false
    }

    /**
     * A profile body without the lines that name one of ours. Only lines that
     * ARE first-party go (a `vpn.red` line, a bare front address); a wider
     * entry that merely covers one of ours (a /16, a TLD) cannot be carved here
     * and stays. Returns the body and how many lines were dropped.
     */
    fun filterProfileBody(text: String): Pair<String, Int> {
        if (size == 0) return text to 0
        var dropped = 0
        val out = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val t = line.trim()
            val bare = t.removePrefix("=").removePrefix("*.").trim('.')
            val single = bare.isNotEmpty() && !t.startsWith("#") &&
                (!bare.contains('/') || bare.endsWith("/32") || bare.endsWith("/128"))
            if (single && matches(bare.substringBefore('/'))) { dropped++; continue }
            out.append(line).append('\n')
        }
        return (if (dropped == 0) text else out.toString()) to dropped
    }

    companion object {
        val EMPTY = FirstPartySet(emptySet(), emptySet(), emptyList(), emptyMap())
    }
}

object FirstParty {
    /** The build's own hosts; parent-domain matched. Overridable for tests. */
    data class BuildHosts(val brandHosts: List<String>, val exactHosts: List<String>) {
        companion object {
            fun fromBuildConfig() = BuildHosts(
                brandHosts = listOf(
                    BuildConfig.WEB_ACCOUNT_HOST, BuildConfig.CASHIER_BASE, BuildConfig.ESIM_BASE,
                    BuildConfig.PRIVACY_URL, RedShield.SCORE_URL, RedShield.SCAN_URL, RedShieldProfiles.CATALOGUE_URL,
                ) + BuildConfig.DEEP_LINK_HOSTS.split(','),
                exactHosts = listOf(ConfigManager.HealthConfig.DEFAULT.probeUrl),
            )
        }
    }

    @Volatile var current: FirstPartySet = FirstPartySet.EMPTY
        private set

    /** Distinct first-party destinations seen in the flow table (so never sent to /score or /scan). Ours, not the user's. */
    private val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    val notSent: Int get() = seen.size

    /** [host] is ours: remember it for the count. */
    fun noteExempt(host: String) { if (seen.size < 1_000) seen += host.lowercase() }

    /** Verdicts for one of ours that came back anyway and were dropped. */
    val verdictsDropped = AtomicLong()
    /** Block records (and profile lines) for one of ours that were dropped. */
    val blocksDropped = AtomicLong()

    /** The config the app runs on (ConfigManager), and a newer one stored by a refresh. */
    @Volatile private var running: String? = null
    @Volatile private var stored: String? = null
    @Volatile private var wgtcp: String? = null

    fun matches(host: String): Boolean = current.matches(host)

    @Synchronized
    private fun rebuild(): FirstPartySet =
        build(listOfNotNull(running, stored), wgtcp, BuildHosts.fromBuildConfig()).also { current = it }

    /** Every config parse (launch, QR) lands here via ConfigManager.loadFromJson. */
    fun onConfig(configJson: String) = runCatching { running = configJson; stored = null; rebuild() }

    /**
     * An OTA refresh stored a newer config. The app keeps running on the old one
     * until the next launch (VpnViewModel.refreshConfigInBackground) and the core
     * picks the new endpoints up on the next connect, so both count until then.
     */
    fun onStoredConfig(configJson: String) = runCatching { stored = configJson; rebuild() }

    /** A freshly provisioned WGTCP profile. */
    fun onWgtcpProfile(profileJson: String) = runCatching { wgtcp = profileJson; rebuild() }

    /** Tunnel start: the parsed config plus the stored WGTCP profile. */
    fun refresh(ctx: android.content.Context) = runCatching {
        wgtcp = ctx.getSharedPreferences(WgtcpProvisioner.PREFS, android.content.Context.MODE_PRIVATE)
            .getString(WgtcpProvisioner.KEY_PROFILE, null)
        if (running == null) ConfigManager.rawJson.takeIf { it.length > 2 }?.let { running = it }
        rebuild()
    }

    /** One line for logs and the debug report: counts only, never a user destination. */
    fun summary(): String {
        val s = current
        return "first-party ${s.hosts.size} host(s), ${s.domains.size} domain(s), ${s.nets.size} net(s) " +
            "[${s.sources.entries.joinToString { "${it.key}=${it.value}" }}]; " +
            "exempted: $notSent not sent, ${verdictsDropped.get()} verdict(s), ${blocksDropped.get()} block(s)"
    }

    fun build(configJsons: List<String>, wgtcpProfileJson: String?, build: BuildHosts): FirstPartySet {
        val hosts = LinkedHashSet<String>()
        val domains = LinkedHashSet<String>()
        val nets = LinkedHashSet<FirstPartyNet>()
        val sources = LinkedHashMap<String, Int>()

        /** Add one entry (URL, host, host:port, IP or CIDR). Returns true when it added something. */
        fun add(raw: String?, parent: Boolean = false): Boolean {
            val e = raw?.trim().orEmpty()
            if (e.isEmpty()) return false
            if (!e.contains("://") && e.contains('/')) {
                val net = cidr(e) ?: return false
                return nets.add(net)
            }
            val h = hostOf(e) ?: return false
            ipBytes(h)?.let { return nets.add(FirstPartyNet(it, it.size * 8)) }
            if (!h.contains('.')) return false
            return if (parent) domains.add(h) else hosts.add(h)
        }
        fun count(source: String, entries: Iterable<String?>, parent: Boolean = false) {
            var n = 0
            for (e in entries) if (add(e, parent)) n++
            if (n > 0) sources[source] = (sources[source] ?: 0) + n
        }

        count("build.brand", build.brandHosts, parent = true)
        count("build.exact", build.exactHosts)

        for (json in configJsons) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            o.optJSONObject("api")?.let { a ->
                count("config.api", listOf(a.optString("base")) + a.optJSONArray("fallback_bases").strings())
            }
            // Every transport's entry lists: objects with a host (entries,
            // premium_entries, backup_entries...) and string endpoint lists.
            // upgrade_hosts is a list of cover names, never ours.
            val t = o.optJSONObject("transport")
            val ep = ArrayList<String?>()
            t?.keys()?.forEach { name ->
                val tr = t.optJSONObject(name) ?: return@forEach
                tr.keys().forEach { k ->
                    if (k == "upgrade_hosts") return@forEach
                    val arr = tr.optJSONArray(k) ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val item = arr.opt(i)
                        when {
                            item is JSONObject -> {
                                ep += item.optString("host").ifEmpty { null }
                                ep += item.optString("endpoint").ifEmpty { null }
                            }
                            item is String && k.contains("endpoint") -> ep += item
                        }
                    }
                }
                ep += tr.optString("endpoint").ifEmpty { null }
            }
            count("config.endpoints", ep)
            val regions = ArrayList<String?>()
            for (key in listOf("regions", "premium_regions")) {
                val r = o.optJSONObject(key) ?: continue
                r.keys().forEach { k ->
                    regions += (r.optJSONArray(k) ?: r.optJSONObject(k)?.optJSONArray("ips")).strings()
                }
            }
            count("config.regions", regions)
            count("config.health", listOf(o.optJSONObject("health")?.optString("probe_url")))
            // Optional operator list (hosts, IPs, CIDRs; ".example" or
            // "*.example" = the domain and its subdomains). Client-only key:
            // absent = nothing, an old client ignores it.
            o.optJSONObject("redshield")?.optJSONArray("first_party").strings().let { l ->
                count("config.first_party", l.filter { !it.startsWith(".") && !it.startsWith("*.") })
                count("config.first_party", l.filter { it.startsWith(".") || it.startsWith("*.") }
                    .map { it.removePrefix("*").removePrefix(".") }, parent = true)
            }
        }

        wgtcpProfileJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { p ->
            count("wgtcp.endpoints", p.optJSONArray("endpoints").strings() + listOf(p.optString("endpoint")))
        }

        return FirstPartySet(hosts, domains, nets.toList(), sources)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    /** Lowercase, trimmed, no trailing dot, no brackets, no v6 zone. Null for blank. */
    fun normalize(raw: String): String? {
        var h = raw.trim().lowercase().trimEnd('.')
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        if (h.contains(':')) h = h.substringBefore('%')
        return h.ifEmpty { null }
    }

    /**
     * The host of a URL, `host:port`, `[v6]:port`, bare v6 or bare name.
     * Never resolves anything.
     */
    fun hostOf(entry: String): String? {
        var s = entry.trim()
        s.indexOf("://").takeIf { it >= 0 }?.let { s = s.substring(it + 3) }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) return normalize(s.substringBefore(']') + "]")
        // Two or more colons: a bare v6 address, no port.
        if (s.count { it == ':' } >= 2) return normalize(s)
        return normalize(s.substringBefore(':'))
    }

    fun cidr(s: String): FirstPartyNet? {
        val (addr, len) = s.trim().split('/', limit = 2).let { it[0] to it.getOrNull(1) }
        val ip = ipBytes(normalize(addr) ?: return null) ?: return null
        val bits = len?.toIntOrNull() ?: return null
        if (bits < 0 || bits > ip.size * 8) return null
        return FirstPartyNet(ip, bits)
    }

    /**
     * 4 or 16 bytes for a literal address, null for a name. An IPv4-mapped v6
     * address comes back as its v4 bytes, so either spelling matches a v4 net.
     * Only literals reach InetAddress (it never sees a name, so no lookup).
     */
    fun ipBytes(h: String): ByteArray? {
        if (!h.contains(':')) {
            if (!isLiteralIp(h)) return null
            return h.split('.').map { it.toInt().toByte() }.toByteArray()
        }
        if (h.count { it == ':' } < 2 || !h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        val b = runCatching { java.net.InetAddress.getByName(h).address }.getOrNull() ?: return null
        if (b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1)
            return b.copyOfRange(12, 16)
        return b
    }
}
