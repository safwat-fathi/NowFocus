package app.getnowfocus.android.sync

import org.json.JSONObject

/**
 * What the app knows about one synced record. Kept next to the data itself (same DataStore, same edit), so a
 * local write and its bookkeeping can never be seen apart. See [SyncLogic].
 */
data class Meta(
    /** The server's last `data` for this record, as JSON text; null = this record never reached the server. */
    val rawJson: String? = null,
    val revision: Int = 0,
    /** Epoch ms of the latest local change that made the record differ from [rawJson]; becomes the push `updatedAt`. */
    val dirtyAt: Long? = null,
    /** The user deleted it locally and the server hasn't been told yet. */
    val deleted: Boolean = false,
    /** False for server records this app can't represent (allowlist profiles): kept in [rawJson], never shown, never deleted. */
    val imported: Boolean = true,
    /** Fingerprint of a payload the server rejected, so it isn't resent until the user changes something. */
    val rejected: String? = null,
) {
    val raw: JSONObject? get() = rawJson?.let { JSONObject(it) }

    companion object
}

data class SyncState(
    /** The account this device is linked to; null = never signed in, so no bookkeeping is done at all. */
    val userId: String? = null,
    val cursor: Long = 0,
    /** False until the first full pull after linking has been applied; nothing is uploaded before that. */
    val initialPullDone: Boolean = false,
    val policies: Map<String, Meta> = emptyMap(),
    val bedtime: Meta? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("userId", userId ?: JSONObject.NULL)
        put("cursor", cursor)
        put("initialPullDone", initialPullDone)
        put("policies", JSONObject().also { o -> policies.forEach { (id, m) -> o.put(id, m.encode()) } })
        put("bedtime", bedtime?.encode() ?: JSONObject.NULL)
    }.toString()

    companion object {
        /** Unreadable state degrades to "never linked": the next sign-in re-links and re-merges instead of crashing. */
        fun decode(text: String?): SyncState = runCatching {
            if (text.isNullOrEmpty()) return@runCatching SyncState()
            val o = JSONObject(text)
            val ps = o.obj("policies")
            SyncState(
                userId = o.s("userId"),
                cursor = o.l("cursor") ?: 0,
                initialPullDone = o.b("initialPullDone", false),
                policies = ps?.keys()?.asSequence()?.mapNotNull { id -> ps.obj(id)?.let { id to Meta.decode(it) } }?.toMap() ?: emptyMap(),
                bedtime = o.obj("bedtime")?.let { Meta.decode(it) },
            )
        }.getOrDefault(SyncState())
    }
}

private fun Meta.encode(): JSONObject = JSONObject().apply {
    put("raw", rawJson ?: JSONObject.NULL)
    put("revision", revision)
    put("dirtyAt", dirtyAt ?: JSONObject.NULL)
    put("deleted", deleted)
    put("imported", imported)
    put("rejected", rejected ?: JSONObject.NULL)
}

private fun Meta.Companion.decode(o: JSONObject) = Meta(
    rawJson = o.s("raw"),
    revision = o.i("revision") ?: 0,
    dirtyAt = o.l("dirtyAt"),
    deleted = o.b("deleted", false),
    imported = o.b("imported", true),
    rejected = o.s("rejected"),
)
