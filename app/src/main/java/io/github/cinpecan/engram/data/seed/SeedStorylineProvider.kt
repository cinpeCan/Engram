package io.github.cinpecan.engram.data.seed

import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Provides a cohesive 3-part narrative showcasing White-Box Memory, 2D Deponder relationships,
 * and Human-in-the-loop calendar actions.
 */
object SeedStorylineProvider {

    fun createInitialStoryline(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<MemoryItem> {
        val tomorrow15 = LocalDateTime.of(
            Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().plusDays(1),
            LocalTime.of(15, 0),
        ).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val day1 = MemoryItem(
            id = "seed-1",
            content = "Vendor intro: Elena Vance introduced Marcus Cole from Helios Display, who quoted the next-gen Micro-OLED module at Net-60 terms. Samples passed the brightness spec. Test portal login is test_panel / Admin@9527. Hand the module to Liam Chen next week for burn-in testing.",
            summary = "• Elena introduced Marcus (Helios Display) for a Micro-OLED quote\n• Net-60 terms; samples passed the brightness spec\n• Portal login saved (see source); Liam runs burn-in next week",
            tags = listOf("Supply chain", "Credentials"),
            createdAtMillis = nowMillis - 2 * 24 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-1_r0", "seed-1", "Elena Vance", "Marcus Cole", "introduced", bidirectional = false),
                RelationTriple("seed-1_r1", "seed-1", "Marcus Cole", "Helios Display", "represents", bidirectional = false),
                RelationTriple("seed-1_r2", "seed-1", "Helios Display", "Micro-OLED Module", "quotes Net-60", bidirectional = false),
                RelationTriple("seed-1_r3", "seed-1", "Liam Chen", "Micro-OLED Module", "burn-in test", bidirectional = false),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        val day2 = MemoryItem(
            id = "seed-2",
            content = "Procurement blockers: the supply agreement is with Legal. Sarah Jenkins still needs to align the warranty indemnity clause with Marcus Cole. Priya Nair in Finance will only release the escrow deposit once Liam Chen submits the Micro-OLED burn-in sign-off. Elena Vance wants an update from Sarah by Friday.",
            summary = "• Sarah is aligning the indemnity clause with Marcus\n• Priya releases the deposit after Liam's burn-in sign-off\n• Elena expects Sarah's update by Friday",
            tags = listOf("Legal", "Supply chain"),
            createdAtMillis = nowMillis - 24 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-2_r0", "seed-2", "Liam Chen", "Micro-OLED Module", "burn-in sign-off", bidirectional = false),
                RelationTriple("seed-2_r1", "seed-2", "Liam Chen", "Priya Nair", "submits sign-off", bidirectional = false),
                RelationTriple("seed-2_r2", "seed-2", "Priya Nair", "Supply Agreement", "releases deposit", bidirectional = false),
                RelationTriple("seed-2_r3", "seed-2", "Sarah Jenkins", "Supply Agreement", "audits indemnity", bidirectional = false),
                RelationTriple("seed-2_r4", "seed-2", "Sarah Jenkins", "Marcus Cole", "aligns clauses", bidirectional = true),
                RelationTriple("seed-2_r5", "seed-2", "Elena Vance", "Sarah Jenkins", "Friday update", bidirectional = false),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        val day3 = MemoryItem(
            id = "seed-3",
            content = "Tomorrow at 3:00 PM in Building B, Conference Room 402: final review of the supply agreement addendum with Sarah Jenkins and Liam Chen, then lock the signing date.",
            summary = "• Tomorrow 3:00 PM · Building B, Room 402\n• Final addendum review with Sarah and Liam",
            tags = listOf("Follow-up", "Legal"),
            createdAtMillis = nowMillis - 2 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = CalendarProposal(
                title = "Supply Agreement Addendum Review",
                startTimeIso = tomorrow15,
                durationMinutes = 60,
                location = "Building B, Conference Room 402",
            ),
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-3_r0", "seed-3", "Sarah Jenkins", "Supply Agreement", "reviews addendum", bidirectional = false),
                RelationTriple("seed-3_r1", "seed-3", "Liam Chen", "Supply Agreement", "locks sign-off", bidirectional = false),
                RelationTriple("seed-3_r2", "seed-3", "Sarah Jenkins", "Liam Chen", "joint review", bidirectional = true),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        return listOf(day1, day2, day3)
    }
}
