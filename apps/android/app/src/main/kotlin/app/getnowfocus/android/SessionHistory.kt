package app.getnowfocus.android

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One row per session that has ended (completed or cancelled) - never a
 * running one. SessionRepository's DataStore only ever holds the current
 * session (see its own comment: "Add Room when session history needs more
 * than one row"); this is that Room database, kept separate so the
 * current-session/policy DataStore stays untouched.
 */
@Entity(tableName = "session_history")
data class SessionHistoryRow(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val policyId: String,
    val startAt: Long,
    val endAt: Long,
    val status: FocusSessionStatus,
    val cancelledAt: Long? = null,
    // v2. defaultValue must match MIGRATION_1_2's ADD COLUMN default exactly,
    // or Room's schema validation fails to open for pre-existing users. Stats
    // filters to FOCUS (see the DAO) so bedtime sessions don't inflate it.
    @ColumnInfo(defaultValue = "FOCUS") val sessionType: SessionType = SessionType.FOCUS,
) {
    /** Real focused time: full duration if it ran to completion, elapsed-so-far if cancelled, 0 otherwise. */
    val focusedMillis: Long
        get() = when {
            status == FocusSessionStatus.COMPLETED -> endAt - startAt
            cancelledAt != null -> cancelledAt - startAt
            else -> 0L
        }
}

/** One row per accessibility-triggered block (never per DNS query - see HistoryStats.shouldLogBlockEvent). */
@Entity(tableName = "block_events")
data class BlockEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestampMillis: Long,
)

fun FocusSession.toHistoryRow() = SessionHistoryRow(
    id = id, policyId = policyId, startAt = startAt, endAt = endAt, status = status,
    cancelledAt = cancelledAt, sessionType = sessionType,
)

class HistoryConverters {
    @TypeConverter fun statusToString(s: FocusSessionStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): FocusSessionStatus = FocusSessionStatus.valueOf(s)
    @TypeConverter fun sessionTypeToString(s: SessionType): String = s.name
    @TypeConverter fun stringToSessionType(s: String): SessionType = SessionType.valueOf(s)
}

@Dao
interface SessionHistoryDao {
    // IGNORE, not the default ABORT: the same session id can genuinely be
    // offered twice (the ViewModel's flow collector and its own tick-driven
    // refreshNow can both notice the same completion), and a second attempt
    // for a row already logged should be a no-op, not a crash.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(row: SessionHistoryRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockEvent(row: BlockEventRow)

    // Focus-only (bedtime excluded) so weekly time, completion, and streak
    // aren't inflated by nightly wind-down sessions — the single place that
    // filter lives (mirrors macOS's sessionType == .focus read filter).
    @Query("SELECT * FROM session_history WHERE startAt >= :from AND startAt < :to AND sessionType = 'FOCUS' ORDER BY startAt")
    suspend fun sessionsBetween(from: Long, to: Long): List<SessionHistoryRow>

    @Query("SELECT * FROM block_events WHERE timestampMillis >= :from AND timestampMillis < :to")
    suspend fun blockEventsBetween(from: Long, to: Long): List<BlockEventRow>
}

@Database(entities = [SessionHistoryRow::class, BlockEventRow::class], version = 2, exportSchema = false)
@TypeConverters(HistoryConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): SessionHistoryDao

    companion object {
        // v1 → v2 added session_history.sessionType. The DEFAULT here must match
        // the entity's @ColumnInfo(defaultValue = "FOCUS") exactly.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_history ADD COLUMN sessionType TEXT NOT NULL DEFAULT 'FOCUS'")
            }
        }

        @Volatile private var instance: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "history.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

/** The local hour of day with the most blocked attempts, and the app tried most in that hour. */
data class Urge(val hour: Int, val count: Int, val topPackage: String)

/** One app's busiest hour of tries: [hourCount] of its [total] fell in [hour]. */
data class AppUrge(val packageName: String, val hour: Int, val hourCount: Int, val total: Int)

/** Pure aggregation over history rows - no Room/Context involved, so it's plain-JVM testable. */
object HistoryStats {

    private fun BlockEventRow.localHour(zone: ZoneId): Int = Instant.ofEpochMilli(timestampMillis).atZone(zone).hour

    /** Blocked attempts per local hour of day: always 24 entries, index = hour (0-23). */
    fun urgeByHour(events: List<BlockEventRow>, zone: ZoneId): List<Int> {
        val counts = IntArray(24)
        events.forEach { counts[it.localHour(zone)]++ }
        return counts.toList()
    }

    /**
     * The busiest hour (ties go to the earlier hour), or null with no attempts.
     * Attempts are only logged while a session or shield is running, so this
     * partly mirrors when sessions run - ponytail: normalize by session minutes
     * per hour if the raw peak proves misleading.
     */
    fun peakUrge(events: List<BlockEventRow>, zone: ZoneId): Urge? {
        val peak = events.groupBy { it.localHour(zone) }.entries
            .minWithOrNull(compareBy({ -it.value.size }, { it.key })) ?: return null
        val topPackage = peak.value.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }!!.key
        return Urge(hour = peak.key, count = peak.value.size, topPackage = topPackage)
    }

    /**
     * The apps tried most, each with the hour it was tried most (ties go to the earlier hour). Apps with fewer
     * than [minTries] attempts are left out: two tries isn't a pattern worth offering to schedule against.
     */
    fun appUrges(events: List<BlockEventRow>, zone: ZoneId, minTries: Int = 3, limit: Int = 3): List<AppUrge> =
        events.groupBy { it.packageName }
            .filterValues { it.size >= minTries }
            .map { (pkg, rows) ->
                val peak = rows.groupBy { it.localHour(zone) }.entries.minWithOrNull(compareBy({ -it.value.size }, { it.key }))!!
                AppUrge(pkg, peak.key, peak.value.size, rows.size)
            }
            .sortedWith(compareBy({ -it.total }, { it.packageName }))
            .take(limit)

    fun totalFocusedMillis(rows: List<SessionHistoryRow>, from: Long, to: Long): Long =
        rows.filter { it.startAt in from until to }.sumOf { it.focusedMillis }

    fun sessionsCount(rows: List<SessionHistoryRow>, from: Long, to: Long): Int =
        rows.count { it.startAt in from until to }

    fun completedCount(rows: List<SessionHistoryRow>, from: Long, to: Long): Int =
        rows.count { it.startAt in from until to && it.status == FocusSessionStatus.COMPLETED }

    fun completionRate(rows: List<SessionHistoryRow>, from: Long, to: Long): Double {
        val total = sessionsCount(rows, from, to)
        return if (total == 0) 0.0 else completedCount(rows, from, to).toDouble() / total
    }

    /** Minutes focused per day, Monday..Sunday, for the week containing [anyDayInWeek]. */
    fun weekBucketsMinutes(rows: List<SessionHistoryRow>, anyDayInWeek: LocalDate, zone: ZoneId): List<Long> {
        val monday = anyDayInWeek.with(DayOfWeek.MONDAY)
        return (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            totalFocusedMillis(rows, from, to) / 60_000
        }
    }

    /** A day counts toward the streak with at least this much real focused time, so opening and ending a session at once doesn't. */
    const val STREAK_MIN_MILLIS = 10 * 60_000L

    /**
     * Consecutive days with at least [STREAK_MIN_MILLIS] of focused time, walking back from the most
     * recent such day. Grace of one day: if the most recent one was
     * yesterday (not today), the streak still counts - it shouldn't zero out
     * the moment a new day starts, before today's session happens.
     */
    fun currentStreakDays(rows: List<SessionHistoryRow>, today: LocalDate, zone: ZoneId): Int {
        val activeDays = rows.groupBy { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
            .filterValues { day -> day.sumOf { it.focusedMillis } >= STREAK_MIN_MILLIS }.keys
        if (activeDays.isEmpty()) return 0
        val mostRecent = activeDays.max()
        if (mostRecent.isBefore(today.minusDays(1))) return 0
        var streak = 0
        var day = mostRecent
        while (activeDays.contains(day)) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /**
     * 0-100 for [from, to): half the completion rate, 30% focused time (five hours is full marks), 20% days
     * with focused time (five is full marks). Null when there were no sessions, so a quiet week isn't a zero.
     * The Windows app computes the same formula (core/src/history_stats.rs): keep them in step.
     */
    fun focusScore(rows: List<SessionHistoryRow>, from: Long, to: Long, zone: ZoneId): Int? {
        if (sessionsCount(rows, from, to) == 0) return null
        val minutes = totalFocusedMillis(rows, from, to) / 60_000
        val days = rows.filter { it.startAt in from until to && it.focusedMillis > 0 }
            .map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.toSet().size
        val score = 0.5 * completionRate(rows, from, to) + 0.3 * minOf(minutes / 300.0, 1.0) + 0.2 * minOf(days / 5.0, 1.0)
        return Math.round(score * 100).toInt()
    }

    /** The text the "Share this week" button sends. Counts only: no app names, sites or times of day leave the phone. */
    fun weekSummaryText(rows: List<SessionHistoryRow>, events: List<BlockEventRow>, from: Long, to: Long, today: LocalDate, zone: ZoneId): UiText {
        val minutes = (totalFocusedMillis(rows, from, to) / 60_000).toInt()
        val sessions = sessionsCount(rows, from, to)
        val streak = currentStreakDays(rows, today, zone)
        val parts = buildList {
            add(UiText.Plural(R.plurals.week_share_head, sessions, listOf(minutes / 60, minutes % 60, sessions, completedCount(rows, from, to))))
            focusScore(rows, from, to, zone)?.let { add(uiText(R.string.week_share_score, it)) }
            if (streak > 0) add(UiText.Plural(R.plurals.week_share_streak, streak))
            val turnedAway = turnedAwayCount(events, from, to)
            add(UiText.Plural(R.plurals.week_share_turned_away, turnedAway))
        }
        return UiText.Joined(parts, R.string.sep_space)
    }

    /** Every blocked attempt in [from, to) across all apps (the top-3 list alone would undercount). */
    fun turnedAwayCount(events: List<BlockEventRow>, from: Long, to: Long): Int =
        events.count { it.timestampMillis in from until to }

    /** Local midnight at the start of the day containing [now]. */
    fun startOfDayMillis(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun topBlockedPackages(events: List<BlockEventRow>, from: Long, to: Long, limit: Int = 3): List<Pair<String, Int>> =
        events.filter { it.timestampMillis in from until to }
            .groupingBy { it.packageName }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /**
     * A blocked app bounced repeatedly within [windowMs] of its last logged
     * attempt is one attempt, not several - a single "try to open it" gesture
     * can fire more than one foreground-change event.
     */
    fun shouldLogBlockEvent(now: Long, lastLoggedAt: Long?, windowMs: Long = 3000): Boolean =
        lastLoggedAt == null || now - lastLoggedAt >= windowMs
}
