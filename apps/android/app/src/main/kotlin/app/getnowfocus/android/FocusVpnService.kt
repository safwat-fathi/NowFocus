package app.getnowfocus.android

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Local DNS-only VPN: the tunnel routes nothing but one fake resolver IP, so
 * normal traffic never touches it. Blocked names get NXDOMAIN; everything else
 * is forwarded to the real network's resolver. Nothing leaves the device
 * beyond ordinary DNS.
 *
 * Fails open: any error, a dead upstream, or an expired session means queries
 * are forwarded (or the tunnel closes) — never a black-holed network.
 *
 * ponytail: DNS-level only. Apps using DoH/hard-coded resolvers, or Private DNS
 * set to "strict", bypass it (arch doc §12.3). Upgrade path: route all traffic
 * and filter by SNI, if that bypass ever matters.
 */
class FocusVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "app.getnowfocus.android.STOP_VPN"
        private const val TAG = "FocusVpn"
        private const val VPN_ADDRESS = "10.111.0.1"
        private const val VPN_DNS = "10.111.0.2"
        private val FALLBACK_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val forwarder = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { shutdown() }

    @Volatile private var rules: ActiveRules? = null
    private var loop: TunLoop? = null
    private var collecting: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (collecting == null) {
            collecting = scope.launch {
                Enforcement.activeRulesFlow(SessionRepository(this@FocusVpnService)).collect { apply(it) }
            }
        }
        // Sticky: if the process is killed mid-session the system restarts us
        // with a null intent and the flow above re-derives whether to block.
        return START_STICKY
    }

    private fun apply(newRules: ActiveRules?) {
        rules = newRules
        handler.removeCallbacks(expire)
        if (newRules == null) {
            shutdown()
            return
        }
        // Handler time pauses in deep sleep, so this can fire late; that's fine
        // because every query re-checks endAt before blocking.
        handler.postDelayed(expire, newRules.endAt - System.currentTimeMillis())
        if (loop == null) establish()
    }

    private fun establish() {
        val builder = Builder()
            .setSession("NowFocus")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            // Without this, IPv6 would be blocked device-wide while the tunnel is up.
            .allowFamily(OsConstants.AF_INET6)
            // Our own traffic (the forwarded DNS) uses the real network directly.
            .addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val fd = builder.establish() ?: run { stopSelf(); return } // VPN consent not granted
        loop = TunLoop(fd).also { thread(name = "FocusVpn") { it.run() } }
    }

    private fun shutdown() {
        handler.removeCallbacks(expire)
        loop?.running = false
        loop = null
        rules = null
        stopSelf()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        forwarder.shutdownNow()
        super.onDestroy()
    }

    /**
     * Polls with a timeout instead of a blocking read: closing a fd doesn't
     * wake a thread already blocked reading it, which would keep the tunnel up.
     */
    private inner class TunLoop(private val tun: ParcelFileDescriptor) {
        @Volatile var running = true
        private val writeLock = Any()

        fun run() {
            val buf = ByteArray(32767)
            val pollFd = StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            try {
                while (running) {
                    if (Os.poll(arrayOf(pollFd), 1000) <= 0) continue
                    val n = try {
                        Os.read(tun.fileDescriptor, buf, 0, buf.size)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) continue else throw e
                    }
                    if (n <= 0) continue
                    try {
                        handle(buf.copyOf(n), n)
                    } catch (e: Exception) {
                        Log.w(TAG, "Dropped packet", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel loop died; failing open", e)
                handler.post { if (loop === this) shutdown() }
            } finally {
                runCatching { tun.close() }
            }
        }

        private fun handle(packet: ByteArray, length: Int) {
            val query = DnsPacket.parse(packet, length) ?: return // not DNS (e.g. DoT on 853): drop
            val name = DnsPacket.qname(query.dns)
            val active = rules
            if (name != null && active != null &&
                System.currentTimeMillis() < active.endAt &&
                DomainValidation.matches(name, active.domains)
            ) {
                write(DnsPacket.wrapReply(query, DnsPacket.nxdomain(query.dns)))
                return
            }
            forwarder.execute { forward(query) }
        }

        private fun forward(query: DnsPacket.Query) {
            for (server in listOfNotNull(upstreamDns(), FALLBACK_DNS).distinct()) {
                try {
                    DatagramSocket().use { socket ->
                        protect(socket)
                        socket.soTimeout = 2000
                        socket.send(DatagramPacket(query.dns, query.dns.size, server, 53))
                        val response = DatagramPacket(ByteArray(4096), 4096)
                        socket.receive(response)
                        write(DnsPacket.wrapReply(query, response.data.copyOf(response.length)))
                    }
                    return
                } catch (e: IOException) {
                    Log.w(TAG, "Upstream $server failed", e)
                }
            }
        }

        private fun write(packet: ByteArray) {
            if (!running) return
            synchronized(writeLock) {
                runCatching { Os.write(tun.fileDescriptor, packet, 0, packet.size) }
            }
        }
    }

    /**
     * Resolver of the real network, looked up per query so network switches
     * just work. We're excluded from our own VPN, so activeNetwork is the
     * underlying network here, not the tunnel.
     */
    private fun upstreamDns(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.dnsServers?.firstOrNull()
    }
}
