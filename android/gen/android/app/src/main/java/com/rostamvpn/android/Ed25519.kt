package com.rostamvpn.android

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature VERIFICATION only (RFC 8032 section 5.1.7), for the
 * RedShield block-profile bodies. Pure BigInteger: minSdk is 23 and the
 * platform has no Ed25519 below API 33, and a core export for it would be one
 * more name on a decompiled .so (CLAUDE.md, the JNI surface rule).
 *
 * Slow by crypto standards (a few tens of ms) and that is fine: it runs once
 * per downloaded list, never on a hot path. Tested against RFC 8032 vectors
 * in Ed25519Test.
 */
internal object Ed25519 {
    // Declared first: object properties initialise in order. (BigInteger.TWO is API 33.)
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** Extended coordinates (X, Y, Z, T). */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val G: Pt = run {
        val gy = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val gx = recoverX(gy, 0)!!
        Pt(gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P))
    }

    private fun inv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)

    private fun add(p: Pt, q: Pt): Pt {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P)
        val d = TWO.multiply(p.z).multiply(q.z).mod(P)
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s: BigInteger, p: Pt): Pt {
        var q = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var pp = p
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = add(q, pp)
            pp = add(pp, pp)
            k = k.shiftRight(1)
        }
        return q
    }

    private fun same(p: Pt, q: Pt): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    /** Little-endian unsigned. */
    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun decompress(s: ByteArray): Pt? {
        if (s.size != 32) return null
        var y = le(s)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = le(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = MessageDigest.getInstance("SHA-512").run {
            update(rBytes); update(publicKey); update(message); digest()
        }.let { le(it).mod(L) }
        same(mul(s, G), add(r, mul(h, a)))
    }.getOrDefault(false)

    fun hex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0 || t.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(t.length / 2) { ((Character.digit(t[2 * it], 16) shl 4) or Character.digit(t[2 * it + 1], 16)).toByte() }
    }
}
