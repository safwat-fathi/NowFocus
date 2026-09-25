package app.getnowfocus.android

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    id = id, policyId = policyId, startAt = startAt, endAt = endAt, status = status, cancelledAt = cancelledAt,
)

class HistoryConverters {
    @TypeConverter fun statusToString(s: FocusSessionStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): FocusSessionStatus = FocusSessionStatus.valueOf(s)
}

@Dao
interface SessionHistoryDao {
    @Insert
    suspend fun insertSession(row: SessionHistoryRow)

    @Insert
    suspend fun insertBlockEvent(row: BlockEventRow)

    @Query("SELECT * FROM session_history WHERE startAt >= :from AND startAt < :to ORDER BY startAt")
    suspend fun sessionsBetween(from: Long, to: Long): List<SessionHistoryRow>

    @Query("SELECT * FROM block_events WHERE timestampMillis >= :from AND timestampMillis < :to")
    suspend fun blockEventsBetween(from: Long, to: Long): List<BlockEventRow>
}

@Database(entities = [SessionHistoryRow::class, BlockEventRow::class], version = 1, exportSchema = false)
@TypeConverters(HistoryConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): SessionHistoryDao

    companion object {
        @Volatile private var instance: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "history.db")
                .build().also { instance = it }
        }
    }
}

/** Pure aggregation over history rows - no Room/Context involved, so it's plain-JVM testable. */
object HistoryStats {

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

    /**
     * Consecutive days with at least one session, walking back from the most
     * recent active day. Grace of one day: if the most recent session was
     * yesterday (not today), the streak still counts - it shouldn't zero out
     * the moment a new day starts, before today's session happens.
     */
    fun currentStreakDays(rows: List<SessionHistoryRow>, today: LocalDate, zone: ZoneId): Int {
        val activeDays = rows.map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.toSet()
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
