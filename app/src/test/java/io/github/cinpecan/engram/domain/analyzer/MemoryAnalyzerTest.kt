package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.RelationTriple
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAnalyzerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis = LocalDateTime.of(2026, 11, 17, 17, 30, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun parsesFencedModelJsonAndEnforcesDeponderInvariants() {
        val jsonOutput = """
            ```json
            {
              "summary": "Marcus aligns contract clauses with Sarah",
              "tags": ["Legal"],
              "calendarProposal": null,
              "relations": [
                {"source": "Sarah Jenkins", "target": "Sarah Jenkins", "relation": "self loop", "bidirectional": false},
                {"source": "Marcus Cole", "target": "Sarah Jenkins", "relation": "sends clauses", "bidirectional": false},
                {"source": "Sarah Jenkins", "target": "Marcus Cole", "relation": "requests edits", "bidirectional": false}
              ]
            }
            ```
        """.trimIndent()

        val analyzer = MemoryAnalyzer()
        val payload = analyzer.mergeModelJsonOrNull(jsonOutput, "Marcus Cole and Sarah Jenkins align the contract clauses")
        assertNotNull(payload)
        val resolved = payload!!

        assertEquals("Marcus aligns contract clauses with Sarah", resolved.summary)
        assertEquals(listOf("Legal"), resolved.tags)
        assertEquals(1, resolved.relations.size)
        val edge = resolved.relations.single()
        assertEquals("Marcus Cole", edge.source)
        assertEquals("Sarah Jenkins", edge.target)
        assertTrue(edge.bidirectional)
        assertTrue(edge.relation.length <= 24)
        assertNull(analyzer.mergeModelJsonOrNull("not-valid-json", "test"))
    }

    @Test
    fun normalizesMultiClauseModelSummariesIntoConciseBullets() {
        val analyzer = MemoryAnalyzer()
        val rawOutput = """
            {
              "summary": "Sarah audits the indemnity clause; Priya releases the deposit after Liam's sign-off; Elena follows up by Friday.",
              "tags": ["Legal"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val parsed = analyzer.mergeModelJsonOrNull(rawOutput, "multi-point meeting notes")!!
        val lines = parsed.summary.lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("• ") })
        assertTrue(lines.none { it.contains(';') })
        assertEquals("• Elena follows up by Friday", lines[2])
    }

    @Test
    fun normalizeKeepsClockTimesAndDecimalsIntact() {
        val analyzer = MemoryAnalyzer()
        val normalized = analyzer.normalizeBulletSummary(
            "Review starts at 3:00 PM. Budget is 10.5k. Bring the signed addendum!",
        )
        val lines = normalized.lines()
        assertEquals(3, lines.size)
        assertEquals("• Review starts at 3:00 PM", lines[0])
        assertEquals("• Budget is 10.5k", lines[1])
        assertTrue(normalized.length <= 300)

        val single = analyzer.normalizeBulletSummary("• " + "word ".repeat(40))
        assertTrue(single.length <= 90)
        assertFalse(single.startsWith("•"))
    }

    @Test
    fun extractsDistilledSummaryAndExactTimeForRelativeDurationNotes() {
        val analyzer = MemoryAnalyzer(zoneId = zone)

        val mealPayload = analyzer.fallbackAnalyze("Dinner in 1 hour", nowMillis = nowMillis)
        assertEquals("Today 6:30 PM · Dinner", mealPayload.summary)
        assertEquals(listOf("Food", "Follow-up"), mealPayload.tags)
        val mealCal = requireNotNull(mealPayload.calendarProposal)
        assertEquals("Dinner", mealCal.title)
        assertEquals("2026-11-17T18:30:00", mealCal.startTimeIso)
        assertEquals(30, mealCal.durationMinutes)

        val parcelPayload = analyzer.fallbackAnalyze("Pick up the parcel in half an hour", nowMillis = nowMillis)
        val parcelCal = requireNotNull(parcelPayload.calendarProposal)
        assertEquals("Pick up the parcel", parcelCal.title)
        assertEquals("2026-11-17T18:00:00", parcelCal.startTimeIso)
        assertEquals(listOf("Errands", "Follow-up"), parcelPayload.tags)

        val callPayload = analyzer.fallbackAnalyze("Remind me to call Priya in 30 minutes", nowMillis = nowMillis)
        val callCal = requireNotNull(callPayload.calendarProposal)
        assertEquals("Call Priya", callCal.title)
        assertEquals("2026-11-17T18:00:00", callCal.startTimeIso)
    }

    @Test
    fun redactsCredentialsFromFallbackSummary() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val credPayload = analyzer.fallbackAnalyze(
            "Test portal login is test_panel / Admin@9527. Hand it to Liam Chen next week for burn-in testing.",
            nowMillis = nowMillis,
        )
        assertFalse(credPayload.summary.contains("Admin@9527"))
        assertFalse(credPayload.summary.contains("test_panel"))
        assertTrue(credPayload.summary.contains("see source"))
        assertTrue(credPayload.summary.contains("Liam Chen"))
        assertTrue(credPayload.tags.contains("Credentials"))
        assertNull(credPayload.calendarProposal)

        val pinRedacted = analyzer.redactSensitiveDetails("The door PIN is 4821, password: hunter2!")
        assertFalse(pinRedacted.contains("4821"))
        assertFalse(pinRedacted.contains("hunter2"))
    }

    @Test
    fun fallbackExtractsCalendarLocationAndLeavesCasualNotesUntagged() {
        val analyzer = MemoryAnalyzer(zoneId = zone)

        val meetingPayload = analyzer.fallbackAnalyze(
            "Tomorrow at 3:00 PM in Building B, Conference Room 402: final review of the supply agreement " +
                "addendum with Sarah Jenkins and Liam Chen, then lock the signing date.",
            nowMillis = nowMillis,
        )
        val proposal = requireNotNull(meetingPayload.calendarProposal)
        assertEquals("2026-11-18T15:00:00", proposal.startTimeIso)
        assertEquals("Building B, Conference Room 402", proposal.location)
        assertEquals("Final review of the supply agreement addendum", proposal.title)
        assertEquals(
            "• Tomorrow 3:00 PM · Building B, Conference Room 402\n• Final review of the supply agreement addendum",
            meetingPayload.summary,
        )
        val edge = meetingPayload.relations.single()
        assertEquals(setOf("Sarah Jenkins", "Liam Chen"), setOf(edge.source, edge.target))
        assertTrue(edge.bidirectional)

        val sampleCalendar = analyzer.fallbackAnalyze(
            "The day after tomorrow at 10:30 AM in Building A, Room 501: sign off the Micro-OLED " +
                "mass-production firmware test with Liam Chen and Marcus Cole.",
            nowMillis = nowMillis,
        )
        val sampleProposal = requireNotNull(sampleCalendar.calendarProposal)
        assertEquals("2026-11-19T10:30:00", sampleProposal.startTimeIso)
        assertEquals("Building A, Room 501", sampleProposal.location)
        assertEquals("Sign off the Micro-OLED mass-production firmware test", sampleProposal.title)

        val casualPayload = analyzer.fallbackAnalyze("The weather is lovely today", nowMillis = nowMillis)
        assertTrue(casualPayload.tags.isEmpty())
        assertNull(casualPayload.calendarProposal)
        assertEquals("The weather is lovely today", casualPayload.summary)
    }

    @Test
    fun parsesClockTimesWithMeridiemAndWeekdays() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        // 2026-11-17 is a Tuesday.
        val lunch = requireNotNull(analyzer.fallbackAnalyze("Lunch with Priya next Monday at 12pm", nowMillis).calendarProposal)
        assertEquals("2026-11-23T12:00:00", lunch.startTimeIso)

        val dinner = requireNotNull(analyzer.fallbackAnalyze("Dinner tonight at 7", nowMillis).calendarProposal)
        assertEquals("2026-11-17T19:00:00", dinner.startTimeIso)

        val friday = requireNotNull(analyzer.fallbackAnalyze("Vendor call on Friday 9:15 am", nowMillis).calendarProposal)
        assertEquals("2026-11-20T09:15:00", friday.startTimeIso)
    }

    @Test
    fun fallbackRelationsComeFromNamedEntitiesAndVerbs() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val seedLike = analyzer.fallbackAnalyze(
            "Vendor intro: Elena Vance introduced Marcus Cole from Helios Display, who quoted the next-gen " +
                "Micro-OLED module at Net-60 terms. Samples passed the brightness spec.",
            nowMillis = nowMillis,
        )
        val edges = seedLike.relations.map { Triple(it.source, it.target, it.relation) }
        assertTrue(edges.contains(Triple("Elena Vance", "Marcus Cole", "introduced")))
        assertTrue(edges.contains(Triple("Marcus Cole", "Helios Display", "represents")))
        assertTrue(edges.contains(Triple("Helios Display", "Micro-OLED", "quotes")))
        assertTrue(seedLike.tags.contains("Supply chain"))

        val sample = analyzer.fallbackAnalyze(
            "Project sync: Liam Chen sent the latest Micro-OLED reliability report to Priya Nair for review. " +
                "Marcus Cole says the revised quote is attached to the supply agreement, so Sarah Jenkins and " +
                "Elena Vance need to align the delivery penalty clause.",
            nowMillis = nowMillis,
        )
        val sampleEdges = sample.relations.associateBy { setOf(it.source, it.target) }
        assertEquals("sent", sampleEdges[setOf("Liam Chen", "Priya Nair")]?.relation)
        val aligned = requireNotNull(sampleEdges[setOf("Sarah Jenkins", "Elena Vance")])
        assertEquals("aligns with", aligned.relation)
        assertTrue(aligned.bidirectional)
        assertNull(sample.calendarProposal)
        assertTrue(sample.relations.none { it.source == it.target })
    }

    @Test
    fun sanitizeRelationsDropsSelfLoopsAndMergesReversePairs() {
        val cleaned = MemoryAnalyzer.sanitizeRelations(
            listOf(
                RelationTriple(source = "A", target = "A", relation = "loop"),
                RelationTriple(source = "A", target = "B", relation = "introduced"),
                RelationTriple(source = "B", target = "A", relation = "follows up"),
                RelationTriple(source = "C".repeat(40), target = "D", relation = "x".repeat(40)),
            ),
        )
        assertEquals(2, cleaned.size)
        assertTrue(cleaned.first().bidirectional)
        assertTrue(cleaned.first().relation.length <= 24)
        assertEquals(32, cleaned.last().source.length)
        assertEquals(24, cleaned.last().relation.length)
    }
}
