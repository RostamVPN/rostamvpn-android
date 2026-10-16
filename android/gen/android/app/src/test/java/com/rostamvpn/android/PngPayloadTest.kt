// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** The PNG container walk that reads a config off a public image proxy. */
class PngPayloadTest {
    private val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(kind: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(),
            (len ushr 8).toByte(), len.toByte(),
        ))
        val k = kind.toByteArray(Charsets.US_ASCII)
        out.write(k); out.write(data)
        val crc = CRC32().apply { update(k); update(data) }.value
        out.write(byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(),
            (crc ushr 8).toByte(), crc.toByte(),
        ))
        return out.toByteArray()
    }

    private fun ihdr() = chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0))

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sig)
        chunks.forEach { out.write(it) }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `finds the payload in a well formed file`() {
        val payload = "sealed-config-base64-goes-here"
        val file = png(ihdr(), chunk("msDt", payload.toByteArray(Charsets.US_ASCII)))
        assertEquals(payload, RemoteConfig.pngPayload(file))
    }

    @Test
    fun `carries a full sized config`() {
        val payload = "x".repeat(7484)
        val file = png(ihdr(), chunk("msDt", payload.toByteArray(Charsets.US_ASCII)))
        assertEquals(payload, RemoteConfig.pngPayload(file))
    }

    @Test
    fun `an ordinary image yields nothing`() {
        assertNull(RemoteConfig.pngPayload(png(ihdr())))
    }

    @Test
    fun `a file that is not a PNG yields nothing`() {
        assertNull(RemoteConfig.pngPayload("this is not a png".toByteArray()))
        assertNull(RemoteConfig.pngPayload(ByteArray(0)))
    }

    @Test
    fun `a truncated file yields nothing`() {
        val file = png(ihdr(), chunk("msDt", "payload".toByteArray()))
        assertNull(RemoteConfig.pngPayload(file.copyOf(file.size / 2)))
    }

    @Test
    fun `a chunk claiming more than the file holds stops the walk`() {
        val file = png(ihdr(), chunk("msDt", "payload".toByteArray()))
        val lying = file.copyOf()
        lying[8] = 0x7F; lying[9] = 0xFF.toByte()
        lying[10] = 0xFF.toByte(); lying[11] = 0xFF.toByte()
        assertNull(RemoteConfig.pngPayload(lying))
    }

    @Test
    fun `skips chunks before ours`() {
        val payload = "after-some-other-chunks"
        val file = png(
            ihdr(),
            chunk("tEXt", "Comment\u0000nothing to see".toByteArray(Charsets.US_ASCII)),
            chunk("msDt", payload.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(payload, RemoteConfig.pngPayload(file))
    }
}
