package app.getnowfocus.android

/**
 * The decisions behind the DNS screen, kept pure so they run in plain JVM tests. The tunnel's keep-alive rule and the
 * status the screen, the health row and the Devices tab show both come from here, so they cannot disagree.
 */
object DnsPolicy {

    /** How long a failed lookup keeps the "can't reach the server" state if nothing has answered since. */
    const val UNREACHABLE_WINDOW_MS = 60_000L

    /**
     * Whether the local DNS tunnel must be up: a session or the Shield needs it to block sites, or the user keeps
     * their DNS on outside sessions (and has actually picked one - "always on" with the system DNS has nothing to keep).
     */
    fun shouldKeepUp(choice: DnsChoice, hasLiveWindow: Boolean): Boolean =
        hasLiveWindow || (choice.alwaysOn && choice.effective != DnsProvider.SYSTEM)

    fun status(
        choice: DnsChoice,
        sessionLive: Boolean,
        vpnPermitted: Boolean,
        strictHost: String?,
        tunnelUp: Boolean,
        upstream: UpstreamHealth,
        now: Long,
    ): DnsStatus {
        if (choice.effective == DnsProvider.SYSTEM) return DnsStatus.Off
        if (!(choice.alwaysOn || sessionLive)) return DnsStatus.WaitingForSession
        if (!vpnPermitted) return DnsStatus.NeedsPermission
        if (strictHost != null) return DnsStatus.StrictConflict(strictHost)
        if (!tunnelUp) return DnsStatus.Starting
        if (upstream.failAt > upstream.okAt && now - upstream.failAt < UNREACHABLE_WINDOW_MS) return DnsStatus.Unreachable
        return DnsStatus.Active
    }
}

sealed interface DnsStatus {
    /** The system DNS is chosen: nothing for NowFocus to do. */
    data object Off : DnsStatus
    /** A server is chosen but only runs during sessions, and none is live. */
    data object WaitingForSession : DnsStatus
    data object Active : DnsStatus
    /** The VPN consent is missing or was taken by another VPN app. */
    data object NeedsPermission : DnsStatus
    /** Private DNS is a hostname ([host]): Android would send every lookup to it, past the tunnel. */
    data class StrictConflict(val host: String) : DnsStatus
    /** Wanted and allowed, but the tunnel is not up yet. */
    data object Starting : DnsStatus
    /** The chosen server did not answer lately, so lookups use the network's own DNS. */
    data object Unreachable : DnsStatus
}
