package app.getnowfocus.android

/**
 * Minimal IPv4/UDP/DNS handling for FocusVpnService. Only DNS queries to the
 * VPN's fake resolver ever reach the tunnel, so this never sees real traffic.
 * Pure Kotlin so it runs in plain JVM unit tests.
 */
object DnsPacket {

    class Query(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val dns: ByteArray)

    /** Returns the query if [packet] is an IPv4 UDP datagram to port 53, else null. */
    fun parse(packet: ByteArray, length: Int): Query? {
        if (length < 20 || (packet[0].toInt() ushr 4) != 4 || packet[9].toInt() != 17) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8 + 12) return null
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        val totalLength = minOf(u16(packet, 2), length)
        return Query(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = u16(packet, ihl),
            dstPort = dstPort,
            dns = packet.copyOfRange(ihl + 8, totalLength),
        )
    }

    /** First question's name, lowercase, or null if malformed. */
    fun qname(dns: ByteArray): String? = questionEnd(dns)?.let { end ->
        val labels = mutableListOf<String>()
        var i = 12
        while (i < end - 4) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) break
            labels += String(dns, i + 1, len, Charsets.US_ASCII)
            i += len + 1
        }
        labels.joinToString(".").lowercase()
    }

    /** NXDOMAIN answer to [dns]: header + question only, QR/RA set, RCODE=3. */
    fun nxdomain(dns: ByteArray): ByteArray {
        val end = questionEnd(dns) ?: 12
        val reply = dns.copyOf(end)
        reply[2] = (reply[2].toInt() or 0x80).toByte() // QR = response, keep opcode/RD
        reply[3] = (0x80 or 3).toByte()                // RA, RCODE = NXDOMAIN
        for (i in 6 until 12) reply[i] = 0                // no answer/authority/additional
        if (end == 12) { reply[4] = 0; reply[5] = 0 }
        return reply
    }

    /** Wraps a DNS [reply] in IPv4/UDP addressed back to whoever sent [query]. */
    fun wrapReply(query: Query, reply: ByteArray): ByteArray {
        val total = 20 + 8 + reply.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[8] = 64          // TTL
        p[9] = 17          // UDP
        query.dstIp.copyInto(p, 12)
        query.srcIp.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, query.dstPort)
        put16(p, 22, query.srcPort)
        put16(p, 24, 8 + reply.size) // UDP checksum left 0: optional over IPv4
        reply.copyInto(p, 28)
        return p
    }

    fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) { sum += u16(buf, i); i += 2 }
        if (length % 2 == 1) sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    /** Offset just past the first question (name + qtype + qclass), or null if malformed. */
    private fun questionEnd(dns: ByteArray): Int? {
        if (dns.size < 12 || u16(dns, 4) < 1) return null
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) return (i + 5).takeIf { it <= dns.size }
            if (len and 0xC0 != 0) return null // compression pointers don't appear in queries
            i += len + 1
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
