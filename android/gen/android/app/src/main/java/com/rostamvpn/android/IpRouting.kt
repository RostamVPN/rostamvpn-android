// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import java.math.BigInteger

/** Split tunnelling by destination IP — the pure part. */
object IpRouting {
    /**
     * Ceiling on how many prefixes a user list may hold before the rest are
     * ignored.
     */
    const val MAX_USER_PREFIXES = 128

    /** Ceiling on installed routes per address family. */
    const val MAX_ROUTES_PER_FAMILY = 500

    /**
     * A normalised IP prefix: host bits are already zeroed, so two spellings of
     * the same network (`10.1.2.3/24` and `10.1.2.0/24`) are one value.
     */
    class Prefix(val bytes: ByteArray, val length: Int) {
        val isV6: Boolean get() = bytes.size == 16
        val totalBits: Int get() = bytes.size * 8

        override fun equals(other: Any?): Boolean =
            other is Prefix && length == other.length && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode() * 31 + length

        override fun toString(): String = format(this)
    }

    /** Parse `1.2.3.0/24`, `1.2.3.4` (implicit /32), `2001:db8::/32` or `::1`. */
    @JvmStatic
    fun parse(text: String): Prefix? {
        val s = text.trim()
        if (s.isEmpty() || s.length > 64) return null

        val slash = s.indexOf('/')
        val addrPart = if (slash < 0) s else s.substring(0, slash)
        val lenPart = if (slash < 0) null else s.substring(slash + 1)

        val bytes = parseAddress(addrPart) ?: return null
        val total = bytes.size * 8

        val length = when {
            lenPart == null -> total
            lenPart.isEmpty() || lenPart.length > 3 -> return null
            !lenPart.all { it in '0'..'9' } -> return null
            else -> lenPart.toIntOrNull() ?: return null
        }
        if (length < 0 || length > total) return null

        maskInPlace(bytes, length)
        return Prefix(bytes, length)
    }

    /** Literal address → bytes (4 or 16). `null` if it is not a literal. */
    private fun parseAddress(s: String): ByteArray? =
        if (s.contains(':')) parseV6(s) else parseV4(s)

    private fun parseV4(s: String): ByteArray? {
        val out = ByteArray(4)
        var idx = 0
        var start = 0
        while (idx < 4) {
            val dot = s.indexOf('.', start)
            val end = if (idx == 3) s.length else (if (dot < 0) return null else dot)
            if (idx == 3 && dot >= 0) return null
            val tok = s.substring(start, end)
            if (tok.isEmpty() || tok.length > 3) return null

            if (tok.length > 1 && tok[0] == '0') return null
            var v = 0
            for (c in tok) {
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            if (v > 255) return null
            out[idx] = v.toByte()
            start = end + 1
            idx++
        }
        return out
    }

    private fun parseV6(s: String): ByteArray? {
        if (s.length > 45) return null

        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 2) >= 0) return null

        val head: String
        val tail: String
        if (dbl >= 0) {
            head = s.substring(0, dbl)
            tail = s.substring(dbl + 2)
        } else {
            head = s
            tail = ""
        }

        val headGroups = groupsOf(head, allowV4Tail = dbl < 0) ?: return null
        val tailGroups = if (dbl < 0) emptyList() else (groupsOf(tail, allowV4Tail = true) ?: return null)

        val out = ByteArray(16)
        if (dbl < 0) {
            if (headGroups.size != 8) return null
            writeGroups(out, 0, headGroups)
        } else {
            if (headGroups.size + tailGroups.size > 7) return null
            writeGroups(out, 0, headGroups)
            writeGroups(out, 8 - tailGroups.size, tailGroups)
        }
        return out
    }

    /** Split one side of a v6 address into 16-bit groups. `null` if malformed. */
    private fun groupsOf(part: String, allowV4Tail: Boolean): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val toks = part.split(':')
        val out = ArrayList<Int>(8)
        for ((i, t) in toks.withIndex()) {
            if (t.contains('.')) {
                if (!allowV4Tail || i != toks.size - 1) return null
                val v4 = parseV4(t) ?: return null
                out.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                out.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
            } else {
                if (t.isEmpty() || t.length > 4) return null
                var v = 0
                for (c in t) {
                    val d = when (c) {
                        in '0'..'9' -> c - '0'
                        in 'a'..'f' -> c - 'a' + 10
                        in 'A'..'F' -> c - 'A' + 10
                        else -> return null
                    }
                    v = (v shl 4) or d
                }
                out.add(v)
            }
        }
        return if (out.size > 8) null else out
    }

    private fun writeGroups(out: ByteArray, groupOffset: Int, groups: List<Int>) {
        for ((i, g) in groups.withIndex()) {
            val at = (groupOffset + i) * 2
            if (at + 1 >= out.size) return
            out[at] = ((g ushr 8) and 0xff).toByte()
            out[at + 1] = (g and 0xff).toByte()
        }
    }

    /** Zero every bit below [length]. */
    private fun maskInPlace(b: ByteArray, length: Int) {
        for (i in b.indices) {
            val bitsBefore = i * 8
            val keep = (length - bitsBefore).coerceIn(0, 8)
            if (keep == 8) continue
            val mask = if (keep == 0) 0 else (0xff shl (8 - keep)) and 0xff
            b[i] = (b[i].toInt() and mask).toByte()
        }
    }

    /** Canonical `address/length`. v6 uses the longest-run `::` compression. */
    @JvmStatic
    fun format(p: Prefix): String {
        val addr = if (p.isV6) formatV6(p.bytes) else formatV4(p.bytes)
        return "$addr/${p.length}"
    }

    private fun formatV4(b: ByteArray): String =
        "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}." +
            "${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"

    private fun formatV6(b: ByteArray): String {
        val g = IntArray(8) { ((b[it * 2].toInt() and 0xff) shl 8) or (b[it * 2 + 1].toInt() and 0xff) }

        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] != 0) { i++; continue }
            var j = i
            while (j < 8 && g[j] == 0) j++
            if (j - i > bestLen) { bestLen = j - i; bestStart = i }
            i = j
        }
        if (bestLen < 2) { bestStart = -1; bestLen = 0 }

        val sb = StringBuilder(39)
        var k = 0
        while (k < 8) {
            if (k == bestStart) {
                sb.append("::")
                k += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith("::")) sb.append(':')
            sb.append(Integer.toHexString(g[k]))
            k++
        }
        return if (sb.isEmpty()) "::" else sb.toString()
    }

    private class Range(val start: BigInteger, val end: BigInteger)

    private fun prefixToRange(p: Prefix): Range {
        val start = BigInteger(1, p.bytes)
        val hostBits = p.totalBits - p.length
        val size = BigInteger.ONE.shiftLeft(hostBits)
        return Range(start, start.add(size).subtract(BigInteger.ONE))
    }

    /**
     * Every prefix needed to cover everything *except* [excluded], within one
     * address family.
     */
    @JvmStatic
    fun complement(excluded: List<Prefix>, v6: Boolean): List<Prefix> {
        val totalBits = if (v6) 128 else 32
        val byteLen = if (v6) 16 else 4
        val max = BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE)

        val ranges = excluded
            .filter { it.isV6 == v6 }
            .map { prefixToRange(it) }
            .sortedWith(compareBy({ it.start }, { it.end }))

        val merged = ArrayList<Range>(ranges.size)
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end.add(BigInteger.ONE)) {
                if (r.end > last.end) merged[merged.size - 1] = Range(last.start, r.end)
            } else {
                merged.add(r)
            }
        }

        val out = ArrayList<Prefix>()
        var cursor = BigInteger.ZERO
        for (r in merged) {
            if (r.start > cursor) {
                rangeToPrefixes(cursor, r.start.subtract(BigInteger.ONE), totalBits, byteLen, out)
            }
            val next = r.end.add(BigInteger.ONE)
            if (next > cursor) cursor = next
        }
        if (cursor <= max) {
            rangeToPrefixes(cursor, max, totalBits, byteLen, out)
        }
        return out
    }

    /** Minimal CIDR cover of the inclusive range `[start, end]`. */
    private fun rangeToPrefixes(
        start: BigInteger,
        end: BigInteger,
        totalBits: Int,
        byteLen: Int,
        out: MutableList<Prefix>
    ) {
        var s = start
        while (s <= end) {
            val alignBits = if (s.signum() == 0) totalBits else s.lowestSetBit
            val remaining = end.subtract(s).add(BigInteger.ONE)
            val sizeBits = remaining.bitLength() - 1
            val hostBits = minOf(alignBits, sizeBits, totalBits)

            out.add(Prefix(toBytes(s, byteLen), totalBits - hostBits))

            s = s.add(BigInteger.ONE.shiftLeft(hostBits))
        }
    }

    /** Fixed-width big-endian bytes; `BigInteger.toByteArray` is neither. */
    private fun toBytes(v: BigInteger, byteLen: Int): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(byteLen)

        val copy = minOf(raw.size, byteLen)
        System.arraycopy(raw, raw.size - copy, out, byteLen - copy, copy)
        return out
    }

    /** What [TunnelVpnService] should actually install, and what it could not. */
    class RoutePlan(
        val v4Routes: List<Prefix>,
        val v6Routes: List<Prefix>,
        val applied: List<Prefix>,
        val dropped: List<Prefix>,
        val v4Requested: Boolean,
        val v6Requested: Boolean
    )

    /** Fit a user's exclusion list under [maxRoutesPerFamily], honestly. */
    @JvmStatic
    @JvmOverloads
    fun planRoutes(
        excluded: List<Prefix>,
        maxRoutesPerFamily: Int = MAX_ROUTES_PER_FAMILY
    ): RoutePlan {
        val applied = ArrayList<Prefix>()
        val dropped = ArrayList<Prefix>()

        val considered = if (excluded.size > MAX_USER_PREFIXES) {
            dropped.addAll(excluded.subList(MAX_USER_PREFIXES, excluded.size))
            excluded.subList(0, MAX_USER_PREFIXES)
        } else {
            excluded
        }

        val seen = HashSet<Prefix>()
        for (p in considered) {
            if (!seen.add(p)) continue
            val trial = ArrayList(applied)
            trial.add(p)
            val size = complement(trial, p.isV6).size
            if (size <= maxRoutesPerFamily) applied.add(p) else dropped.add(p)
        }

        val v4Requested = applied.any { !it.isV6 }
        val v6Requested = applied.any { it.isV6 }

        return RoutePlan(
            v4Routes = if (v4Requested) complement(applied, v6 = false) else emptyList(),
            v6Routes = if (v6Requested) complement(applied, v6 = true) else emptyList(),
            applied = applied,
            dropped = dropped,
            v4Requested = v4Requested,
            v6Requested = v6Requested
        )
    }
}
