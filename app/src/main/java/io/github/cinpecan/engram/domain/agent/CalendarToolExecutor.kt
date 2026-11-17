package io.github.cinpecan.engram.domain.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import io.github.cinpecan.engram.domain.model.CalendarProposal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Human-in-the-Loop Calendar Agent tool: only invoked after explicit user tap on confirmation card.
 */
object CalendarToolExecutor {

    fun buildInsertIntent(
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
            putExtra(CalendarContract.Events.DESCRIPTION, "Created by Engram White-Box Memory Agent")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchCalendarInsert(context: Context, proposal: CalendarProposal): Boolean {
        val intent = buildInsertIntent(proposal)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun parseStartEpochMillis(iso: String, zoneId: ZoneId): Long {
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
