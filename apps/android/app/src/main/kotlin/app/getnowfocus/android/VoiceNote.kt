package app.getnowfocus.android

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * A 10-second note to yourself, recorded at Setup and played back before a
 * STRICT session can be ended early.
 *
 * Files live in noBackupFilesDir: the manifest has allowBackup="true", and
 * that directory is excluded from auto-backup without any extra rules.
 */
object VoiceNote {
    const val MAX_MS = 10_000

    private fun dir(context: Context) = File(context.noBackupFilesDir, "voice").apply { mkdirs() }

    /** Where Setup records to, before there is a session id to name the file after. */
    fun pending(context: Context) = File(dir(context), "pending.m4a")

    /**
     * The session's note if it is set and still on disk. The one check the Play
     * button, the Unlock screen and cancelSession all share, so the UI and the
     * ViewModel can't disagree about whether a note exists.
     */
    fun playableFile(session: FocusSession): File? = session.voiceNotePath?.let(::File)?.takeIf { it.exists() }

    /** Moves the just-recorded note under the session's id; null if nothing was recorded. */
    fun adoptPending(context: Context, sessionId: String): String? {
        val pending = pending(context)
        if (!pending.exists()) return null
        val target = File(dir(context), "$sessionId.m4a")
        return if (pending.renameTo(target)) target.absolutePath else null
    }

    /** Deletes every note except [keepId]'s (and any stale pending one). Run at each session start, so ended sessions' notes can't pile up. */
    fun purgeExcept(context: Context, keepId: String) {
        dir(context).listFiles()?.filter { it.name != "$keepId.m4a" }?.forEach { it.delete() }
    }

    /** Whether the file really plays. A note that can't is discarded at record time: a playback error counts as "listened", so a corrupt file would silently remove the lock. */
    fun isPlayable(file: File): Boolean {
        val p = MediaPlayer()
        return try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.duration > 0
        } catch (_: Exception) {
            false
        } finally {
            p.release()
        }
    }
}

/**
 * Records up to [VoiceNote.MAX_MS] to [file]. [onStopped] fires exactly once
 * per successful [start] - on [stop] or when the cap is hit - with whether the
 * result is a playable file (an unplayable one is already deleted).
 */
class VoiceRecorder(private val context: Context, private val file: File, private val onStopped: (usable: Boolean) -> Unit) {
    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())

    // Our own timer ends the note at MAX_MS with an ordinary stop(). MediaRecorder's
    // max-duration auto-stop is asynchronous and calling stop() on top of it can
    // leave a half-written file, so it is only a backstop, set a second later.
    private val autoStop = Runnable { stop() }

    /** False if the recorder couldn't start (mic busy, another app recording); then nothing is left behind. */
    fun start(): Boolean {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setMaxDuration(VoiceNote.MAX_MS + 1_000)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
            r.prepare()
            r.start()
            recorder = r
            handler.postDelayed(autoStop, VoiceNote.MAX_MS.toLong())
            true
        } catch (_: Exception) {
            r.release()
            file.delete()
            false
        }
    }

    fun stop() {
        val r = recorder ?: return
        recorder = null
        handler.removeCallbacks(autoStop)
        // stop() throws if nothing was captured, and can throw right after the
        // cap auto-stopped; either way the playability check below is the real verdict.
        try { r.stop() } catch (_: RuntimeException) { }
        r.release()
        val usable = VoiceNote.isPlayable(file)
        if (!usable) file.delete()
        onStopped(usable)
    }

    /** Abandons an in-progress recording (screen left mid-record). A finished note is untouched. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        handler.removeCallbacks(autoStop)
        try { r.stop() } catch (_: RuntimeException) { }
        r.release()
        file.delete()
    }
}

/**
 * Plays [file] once. [onListened] fires only when it plays to its natural end
 * (never on a tap or a release) - or when playback fails outright, so a broken
 * file can't trap you: STRICT is not LOCKED.
 */
class VoiceNotePlayer(private val file: File, private val onListened: () -> Unit) {
    private var player: MediaPlayer? = null

    fun play() {
        release()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { release(); onListened() }
            p.setOnErrorListener { _, _, _ -> release(); onListened(); true }
            p.prepare()
            p.start()
        } catch (_: Exception) {
            release()
            onListened()
        }
    }

    fun release() {
        val p = player ?: return
        player = null
        p.release()
    }
}
