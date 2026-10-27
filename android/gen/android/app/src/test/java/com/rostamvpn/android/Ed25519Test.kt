package com.rostamvpn.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519Test {
    private fun h(s: String) = Ed25519.hex(s)!!

    // RFC 8032 section 7.1, TEST 1 (empty message) and TEST 2 (one byte).
    private val pub1 = h("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val sig1 = h("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    private val pub2 = h("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val sig2 = h("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")

    @Test fun rfcVectorsVerify() {
        assertTrue(Ed25519.verify(pub1, ByteArray(0), sig1))
        assertTrue(Ed25519.verify(pub2, byteArrayOf(0x72), sig2))
    }

    @Test fun tamperingFails() {
        assertFalse(Ed25519.verify(pub2, byteArrayOf(0x73), sig2))
        assertFalse(Ed25519.verify(pub1, ByteArray(0), sig1.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }))
        assertFalse(Ed25519.verify(pub2, ByteArray(0), sig1))
        assertFalse(Ed25519.verify(pub1, ByteArray(0), sig1.copyOf(63)))
    }

    @Test fun compiledInKeyDecodes() {
        assertTrue(Ed25519.hex(RedShieldProfiles.PUBLIC_KEY_HEX)!!.size == 32)
    }
}
