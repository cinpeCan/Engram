package io.github.cinpecan.engram.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.cinpecan.engram.domain.agent.CalendarToolExecutor
import io.github.cinpecan.engram.domain.model.CalendarProposal
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class CalendarAnchorTarget(
    val memoryId: String,
    val proposal: CalendarProposal,
)

data class CalendarProviderEventRow(
    val eventId: Long,
    val title: String,
    val description: String,
    val dtStartMillis: Long,
)

interface SystemCalendarObserver {
    fun hasReadCalendarPermission(): Boolean
    fun notifyPermissionChanged()
    fun observeSyncedEvents(targetsFlow: Flow<List<CalendarAnchorTarget>>): Flow<Map<String, Long>>
}

private val ENGRAM_MARKER_REGEX = Regex("""\[engram:([^\]]+)]""")
private const val FALLBACK_WINDOW_MILLIS = 12L * 60L * 60L * 1000L

/**
 * Deterministic matcher aligning Room calendar proposals against System [CalendarContract.Events] rows:
 * 1) Tier 1 matches `[engram:<memoryId>]` markers embedded in event descriptions (survives title/time edits).
 * 2) Tier 2 falls back to normalized title + ±12h window matching if a user cleared the description note.
 */
internal fun matchSyncedCalendarEvents(
    targets: List<CalendarAnchorTarget>,
    rows: List<CalendarProviderEventRow>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Map<String, Long> {
    if (targets.isEmpty() || rows.isEmpty()) return emptyMap()

    val matched = LinkedHashMap<String, Long>()
    val claimedEventIds = HashSet<Long>()

    val rowsByMarker = HashMap<String, MutableList<CalendarProviderEventRow>>()
    for (row in rows) {
        ENGRAM_MARKER_REGEX.findAll(row.description).forEach { hit ->
            val id = hit.groupValues[1].trim()
            if (id.isNotEmpty()) {
                rowsByMarker.getOrPut(id) { mutableListOf() }.add(row)
            }
        }
    }

    for (target in targets) {
        val bestRow = rowsByMarker[target.memoryId]
            ?.maxByOrNull { it.eventId }
            ?: continue
        matched[target.memoryId] = bestRow.eventId
        claimedEventIds += bestRow.eventId
    }

    for (target in targets) {
        if (target.memoryId in matched) continue
        val expectedTitle = target.proposal.title.trim()
        if (expectedTitle.isEmpty()) continue
        val expectedStartMillis = CalendarToolExecutor.parseStartEpochMillis(
            iso = target.proposal.startTimeIso,
            zoneId = zoneId,
        )
        val fallbackRow = rows.asSequence()
            .filter { it.eventId !in claimedEventIds }
            .filter { it.title.trim().equals(expectedTitle, ignoreCase = true) }
            .map { row -> row to abs(row.dtStartMillis - expectedStartMillis) }
            .filter { (_, delta) -> delta <= FALLBACK_WINDOW_MILLIS }
            .minWithOrNull(
                compareBy<Pair<CalendarProviderEventRow, Long>> { it.second }
                    .thenByDescending { it.first.eventId },
            )
            ?.first
        if (fallbackRow != null) {
            matched[target.memoryId] = fallbackRow.eventId
            claimedEventIds += fallbackRow.eventId
        }
    }

    return matched
}

/**
 * Reactive subscription to Android [CalendarContract] backed by a cold [callbackFlow] + [ContentObserver].
 * Registers the observer only while collectors are active and tears it down automatically on cancel.
 */
class AndroidSystemCalendarObserver(
    context: Context,
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : SystemCalendarObserver {

    private val appContext = context.applicationContext
    private val permissionSignal = MutableStateFlow(0)

    override fun hasReadCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED

    override fun notifyPermissionChanged() {
        permissionSignal.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSyncedEvents(
        targetsFlow: Flow<List<CalendarAnchorTarget>>,
    ): Flow<Map<String, Long>> =
        permissionSignal
            .map { hasReadCalendarPermission() }
            .distinctUntilChanged()
            .flatMapLatest { granted ->
                if (!granted) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        targetsFlow.distinctUntilChanged(),
                        calendarChangesFlow(),
                    ) { targets, _ ->
                        queryAndMatch(targets)
                    }.flowOn(Dispatchers.IO)
                }
            }
            .distinctUntilChanged()

    @OptIn(FlowPreview::class)
    private fun calendarChangesFlow(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val registered = try {
            appContext.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                true,
                observer,
            )
            true
        } catch (_: SecurityException) {
            false
        }
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose {
            appContext.contentResolver.unregisterContentObserver(observer)
        }
    }
        .conflate()
        .debounce(80L)
        .onStart { emit(Unit) }

    private fun queryAndMatch(targets: List<CalendarAnchorTarget>): Map<String, Long> {
        if (targets.isEmpty() || !hasReadCalendarPermission()) return emptyMap()

        val zoneId = zoneIdProvider()
        val expectedTimes = targets.map {
            CalendarToolExecutor.parseStartEpochMillis(it.proposal.startTimeIso, zoneId)
        }
        val minWindow = (expectedTimes.minOrNull() ?: 0L) - FALLBACK_WINDOW_MILLIS
        val maxWindow = (expectedTimes.maxOrNull() ?: 0L) + FALLBACK_WINDOW_MILLIS

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
        )
        val selection = buildString {
            append("${CalendarContract.Events.DELETED} = 0")
            append(" AND (${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ?)")
            append(" AND (${CalendarContract.Events.DESCRIPTION} LIKE ?")
            append(" OR (${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?))")
        }
        val selectionArgs = arrayOf(
            CalendarContract.Events.STATUS_CANCELED.toString(),
            "%${CalendarToolExecutor.ENGRAM_MARKER_PREFIX}%",
            minWindow.toString(),
            maxWindow.toString(),
        )

        val rows = ArrayList<CalendarProviderEventRow>()
        try {
            appContext.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext()) {
                    rows += CalendarProviderEventRow(
                        eventId = cursor.getLong(idIdx),
                        title = cursor.getString(titleIdx).orEmpty(),
                        description = cursor.getString(descIdx).orEmpty(),
                        dtStartMillis = if (cursor.isNull(startIdx)) 0L else cursor.getLong(startIdx),
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyMap()
        }

        return matchSyncedCalendarEvents(
            targets = targets,
            rows = rows,
            zoneId = zoneId,
        )
    }
}
