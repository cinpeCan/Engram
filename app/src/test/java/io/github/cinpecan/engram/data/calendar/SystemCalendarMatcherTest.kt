package io.github.cinpecan.engram.data.calendar

import io.github.cinpecan.engram.domain.model.CalendarProposal
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarMatcherTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun tier1MarkerMatchesEvenAfterUserEditsTitleAndTimeInSystemCalendar() {
        val target = CalendarAnchorTarget(
            memoryId = "seed-3",
            proposal = CalendarProposal(
                title = "Supply Agreement Addendum Review",
                startTimeIso = "2026-11-19T15:00:00",
                durationMinutes = 60,
                location = "Building B, Conference Room 402",
            ),
        )
        val rescheduledStart = LocalDateTime.of(2026, 11, 22, 10, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val rows = listOf(
            CalendarProviderEventRow(
                eventId = 42L,
                title = "Rescheduled final review",
                description = "Notes updated [engram:seed-3]",
                dtStartMillis = rescheduledStart,
            ),
        )

        val matched = matchSyncedCalendarEvents(listOf(target), rows, zone)
        assertEquals(mapOf("seed-3" to 42L), matched)
    }

    @Test
    fun tier2TitleAndWindowFallbackMatchesWhenDescriptionMarkerWasCleared() {
        val target = CalendarAnchorTarget(
            memoryId = "mem-2",
            proposal = CalendarProposal(
                title = "Firmware sign-off",
                startTimeIso = "2026-11-19T10:30:00",
                durationMinutes = 60,
            ),
        )
        val nearbyStart = LocalDateTime.of(2026, 11, 19, 14, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val rows = listOf(
            CalendarProviderEventRow(
                eventId = 88L,
                title = "  Firmware sign-off ",
                description = "",
                dtStartMillis = nearbyStart,
            ),
        )

        val matched = matchSyncedCalendarEvents(listOf(target), rows, zone)
        assertEquals(mapOf("mem-2" to 88L), matched)
    }

    @Test
    fun nonMatchingOrOutOfWindowEventsStayUnmatched() {
        val target = CalendarAnchorTarget(
            memoryId = "mem-3",
            proposal = CalendarProposal(
                title = "Legal clause alignment",
                startTimeIso = "2026-11-19T15:00:00",
                durationMinutes = 60,
            ),
        )
        val distantStart = LocalDateTime.of(2026, 11, 22, 15, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val rows = listOf(
            CalendarProviderEventRow(
                eventId = 91L,
                title = "Legal clause alignment",
                description = "",
                dtStartMillis = distantStart,
            ),
            CalendarProviderEventRow(
                eventId = 92L,
                title = "Unrelated event",
                description = "[engram:other-id]",
                dtStartMillis = distantStart,
            ),
        )

        val matched = matchSyncedCalendarEvents(listOf(target), rows, zone)
        assertTrue(matched.isEmpty())
    }
}
