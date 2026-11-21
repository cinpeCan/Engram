package io.github.cinpecan.engram.domain.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import io.github.cinpecan.engram.domain.model.CalendarProposal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Human-in-the-Loop Calendar Agent tool: only invoked after explicit user tap.
 */
object CalendarToolExecutor {

    const val ENGRAM_MARKER_PREFIX = "[engram:"

    fun buildMarker(memoryId: String): String = "$ENGRAM_MARKER_PREFIX$memoryId]"

    fun buildInsertIntent(
        memoryId: String,
        proposal: CalendarProposal,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Intent {
        val startMillis = parseStartEpochMillis(proposal.startTimeIso, zoneId)
        val endMillis = startMillis + proposal.durationMinutes.coerceIn(5, 480) * 60_000L

        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, proposal.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            proposal.location?.takeIf { it.isNotBlank() }?.let { loc ->
                putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
            }
            putExtra(CalendarContract.Events.DESCRIPTION, "Distilled by Engram · ${buildMarker(memoryId)}")
        }
    }

    fun buildViewIntent(
        eventId: Long,
        proposal: CalendarProposal,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Intent {
        val startMillis = parseStartEpochMillis(proposal.startTimeIso, zoneId)
        val endMillis = startMillis + proposal.durationMinutes.coerceIn(5, 480) * 60_000L
        return Intent(Intent.ACTION_VIEW).apply {
            data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        }
    }

    fun launchCalendarInsert(
        context: Context,
        memoryId: String,
        proposal: CalendarProposal,
    ): Boolean = startCalendarIntent(
        context = context,
        intent = buildInsertIntent(memoryId = memoryId, proposal = proposal),
    )

    fun launchCalendarView(
        context: Context,
        eventId: Long,
        proposal: CalendarProposal,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean = startCalendarIntent(
        context = context,
        intent = buildViewIntent(eventId = eventId, proposal = proposal, zoneId = zoneId),
    )

    private fun startCalendarIntent(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    internal fun parseStartEpochMillis(iso: String, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        runCatching {
            return Instant.parse(iso).toEpochMilli()
        }
        runCatching {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(iso).atZone(zoneId).toInstant().toEpochMilli()
        }
        return System.currentTimeMillis() + 24 * 60 * 60 * 1000L
    }
}
