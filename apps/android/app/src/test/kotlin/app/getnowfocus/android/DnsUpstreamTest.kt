package app.getnowfocus.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException

class DnsUpstreamTest {

    @Test
    fun `DoT framing is a two byte big endian length then the message, and round-trips`() {
        val wire = ByteArray(300) { it.toByte() }
        val framed = DnsUpstream.frame(wire)
        assertEquals(0x01, framed[0].toInt())
        assertEquals(0x2C, framed[1].toInt())
        assertArrayEquals(wire, DnsUpstream.unframe(DataInputStream(framed.inputStream())))
    }

    @Test(expected = IOException::class)
    fun `a truncated DoT reply is an error, so the caller falls back to plain DNS`() {
        DnsUpstream.unframe(DataInputStream(byteArrayOf(0, 10, 1, 2).inputStream()))
    }

    @Test
    fun `every DoH preset is https with bootstrap addresses`() {
        DnsProvider.entries.filter { it.doh != null }.forEach {
            assertTrue("${it.name} must be https", it.doh!!.startsWith("https://"))
            assertTrue("${it.name} needs bootstrap IPs", it.bootstrap.isNotEmpty())
        }
    }

    @Test
    fun `a custom provider without a hostname behaves as the system resolver`() {
        assertEquals(DnsProvider.SYSTEM, DnsChoice(DnsProvider.CUSTOM, "").effective)
        assertEquals(DnsProvider.CUSTOM, DnsChoice(DnsProvider.CUSTOM, "dns.example.com").effective)
        assertNull(DnsUpstream.query(DnsChoice(), ByteArray(12)))
    }

    @Test
    fun `typed server names are reduced to a bare host`() {
        assertEquals("dns.example.com", DnsSetting.hostOrNull("https://DNS.example.com:853/x"))
        assertNull(DnsSetting.hostOrNull("not a host"))
    }
}
