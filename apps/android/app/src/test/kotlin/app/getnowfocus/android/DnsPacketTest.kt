package app.getnowfocus.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsPacketTest {

    private val client = byteArrayOf(10, 111, 0, 1)
    private val resolver = byteArrayOf(10, 111, 0, 2)

    /** DNS A query for [name] with an EDNS OPT additional record, like real resolvers send. */
    private fun dnsQuery(name: String): ByteArray {
        val qname = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        val header = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1)
        val question = qname.toByteArray() + byteArrayOf(0, 1, 0, 1)
        val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        return header + question + opt
    }

    private fun ipPacket(dns: ByteArray, dstPort: Int = 53): ByteArray {
        val q = DnsPacket.Query(srcIp = resolver, dstIp = client, srcPort = dstPort, dstPort = 40000, dns = dns)
        // wrapReply swaps addresses/ports, so this builds client:40000 -> resolver:dstPort.
        return DnsPacket.wrapReply(q, dns)
    }

    @Test
    fun `parses qname from an IPv4 UDP query`() {
        val packet = ipPacket(dnsQuery("m.YouTube.com"))
        val query = DnsPacket.parse(packet, packet.size)!!
        assertArrayEquals(client, query.srcIp)
        assertEquals(40000, query.srcPort)
        assertEquals("m.youtube.com", DnsPacket.qname(query.dns))
    }

    @Test
    fun `parses a hand-written wire-format packet`() {
        // 10.0.0.1:12345 -> 10.0.0.2:53, A query for example.com.
        val hex = "450000390000400040110000" + "0a0000010a000002" +
            "303900350025" + "0000" +
            "123401000001000000000000" + "076578616d706c6503636f6d00" + "00010001"
        val packet = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val query = DnsPacket.parse(packet, packet.size)!!
        assertEquals(12345, query.srcPort)
        assertEquals(29, query.dns.size)
        assertEquals("example.com", DnsPacket.qname(query.dns))
    }

    @Test
    fun `ignores non-DNS traffic`() {
        val packet = ipPacket(dnsQuery("youtube.com"), dstPort = 853)
        assertNull(DnsPacket.parse(packet, packet.size))
    }

    @Test
    fun `nxdomain reply is addressed back with a valid checksum`() {
        val packet = ipPacket(dnsQuery("youtube.com"))
        val query = DnsPacket.parse(packet, packet.size)!!
        val reply = DnsPacket.wrapReply(query, DnsPacket.nxdomain(query.dns))

        assertArrayEquals(resolver, reply.copyOfRange(12, 16))
        assertArrayEquals(client, reply.copyOfRange(16, 20))
        assertEquals(0, DnsPacket.checksum(reply, 0, 20)) // header incl. checksum sums to 0

        val dns = reply.copyOfRange(28, reply.size)
        assertEquals(0x1234, ((dns[0].toInt() and 0xFF) shl 8) or (dns[1].toInt() and 0xFF)) // same id
        assertEquals(0x80, dns[2].toInt() and 0x80) // QR = response
        assertEquals(3, dns[3].toInt() and 0x0F)    // RCODE = NXDOMAIN
        assertEquals(0, dns[11].toInt())            // OPT record dropped
        assertEquals("youtube.com", DnsPacket.qname(dns))
    }
}
