package app.getnowfocus.android

import android.content.Context
import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Where the tunnel sends the lookups it lets through. SYSTEM is the network's own plain-DNS resolver (the
 * original behaviour); the presets are filtering or privacy resolvers reached over DoH; CUSTOM is a hostname
 * reached over DoT, e.g. the Private DNS provider the user already had.
 *
 * Why this exists: a lookup that is encrypted to the user's own provider can't be filtered by any app, so
 * while a session runs NowFocus asks the provider itself - blocklist first, then the provider's own filtering.
 * Bootstrap IPs mean the provider's name never needs resolving, so it can't loop back into the tunnel.
 */
enum class DnsProvider(val doh: String?, val bootstrap: List<String>) {
    SYSTEM(null, emptyList()),
    CLOUDFLARE_FAMILY("https://family.cloudflare-dns.com/dns-query", listOf("1.1.1.3", "1.0.0.3")),
    ADGUARD_FAMILY("https://family.adguard-dns.com/dns-query", listOf("94.140.14.15", "94.140.15.16")),
    CLEANBROWSING_FAMILY("https://doh.cleanbrowsing.org/doh/family-filter/", listOf("185.228.168.168", "185.228.169.168")),
    QUAD9("https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    CUSTOM(null, emptyList()),
}

data class DnsChoice(val provider: DnsProvider = DnsProvider.SYSTEM, val customHost: String = "") {
    /** What the tunnel will actually use: CUSTOM without a valid hostname is the same as SYSTEM. */
    val effective: DnsProvider get() = if (provider == DnsProvider.CUSTOM && customHost.isEmpty()) DnsProvider.SYSTEM else provider
}

/**
 * Kept in SharedPreferences, not the DataStores: the VPN thread reads it per query and needs the answer
 * synchronously (same reason as [AppLanguage]).
 */
object DnsSetting {
    private const val PREFS = "nowfocus_dns"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_HOST = "custom_host"

    fun read(context: Context): DnsChoice {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = prefs.getString(KEY_PROVIDER, null)?.let { n -> DnsProvider.entries.firstOrNull { it.name == n } } ?: DnsProvider.SYSTEM
        return DnsChoice(provider, prefs.getString(KEY_HOST, "") ?: "")
    }

    fun write(context: Context, choice: DnsChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, choice.provider.name).putString(KEY_HOST, choice.customHost).apply()
    }

    /** A DoT hostname from what the user typed: bare host only, so no path, port or scheme survives. */
    fun hostOrNull(raw: String): String? = DomainValidation.normalize(raw)
}

/**
 * One lookup to the chosen resolver. Our own app is excluded from the tunnel, so these sockets use the real
 * network. Returns null on any failure so the caller can fall back to plain DNS - a dead provider must never
 * black-hole the phone.
 */
object DnsUpstream {
    private const val TAG = "DnsUpstream"
    private const val TIMEOUT_MS = 3000
    private const val DOT_PORT = 853
    private val DNS_MESSAGE = "application/dns-message".toMediaType()

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            // Names resolve from the preset's bootstrap IPs; certificate and SNI still use the real hostname.
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val ips = DnsProvider.entries.firstOrNull { it.doh?.contains("//$hostname/") == true }?.bootstrap
                    return if (ips != null) ips.map { InetAddress.getByName(it) } else Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
    }

    fun query(choice: DnsChoice, wire: ByteArray): ByteArray? = try {
        when (choice.effective) {
            DnsProvider.SYSTEM -> null
            DnsProvider.CUSTOM -> dot(choice.customHost, wire)
            else -> doh(choice.effective.doh!!, wire)
        }
    } catch (e: IOException) {
        Log.w(TAG, "${choice.effective} failed", e)
        null
    }

    private fun doh(url: String, wire: ByteArray): ByteArray? {
        val request = Request.Builder().url(url).header("Accept", DNS_MESSAGE.toString()).post(wire.toRequestBody(DNS_MESSAGE)).build()
        dohClient.newCall(request).execute().use { r -> return if (r.isSuccessful) r.body?.bytes() else null }
    }

    // ponytail: one connection, queries take turns; a pool if lookups ever queue visibly behind each other.
    private val dotLock = Any()
    private var dotSocket: SSLSocket? = null
    private var dotHost: String? = null

    private fun dot(host: String, wire: ByteArray): ByteArray? = synchronized(dotLock) {
        // A reused connection may have been closed by the server: retry once on a fresh one.
        for (attempt in 0..1) {
            try {
                val socket = dotSocket?.takeIf { dotHost == host && !it.isClosed } ?: openDot(host)
                socket.getOutputStream().apply { write(frame(wire)); flush() }
                return@synchronized unframe(DataInputStream(socket.getInputStream()))
            } catch (e: IOException) {
                runCatching { dotSocket?.close() }
                dotSocket = null
                if (attempt == 1) throw e
            }
        }
        null
    }

    private fun openDot(host: String): SSLSocket {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, DOT_PORT), TIMEOUT_MS)
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, DOT_PORT, true) as SSLSocket
        tls.soTimeout = TIMEOUT_MS
        // Check the certificate against the hostname, not just that it chains to a CA.
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        dotSocket = tls
        dotHost = host
        return tls
    }

    /** RFC 7858 framing: a DNS message over TCP/TLS is prefixed with its length as 2 bytes, big endian. */
    internal fun frame(wire: ByteArray): ByteArray = byteArrayOf((wire.size shr 8).toByte(), wire.size.toByte()) + wire

    internal fun unframe(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        if (length == 0) throw IOException("empty DNS message")
        return ByteArray(length).also { input.readFully(it) }
    }
}
