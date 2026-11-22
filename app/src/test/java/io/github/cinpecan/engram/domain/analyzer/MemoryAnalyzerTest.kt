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

    @Test
    fun stripsPromptArtifactsAndScrubsStreamingPrefixes() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val normalized = analyzer.normalizeBulletSummary(
            "• First point: Liam Chen sent the report to Priya Nair\n• Second point: Sarah aligns with Elena",
        )
        assertFalse(normalized.contains("First point", ignoreCase = true))
        assertFalse(normalized.contains("Second point", ignoreCase = true))
        assertEquals(
            "• Liam Chen sent the report to Priya Nair\n• Sarah aligns with Elena",
            normalized,
        )

        val cleanedStream = MemoryAnalyzer.cleanStreamingPreviewText(
            "• First point: Marcus Cole attached the revised quote",
        )
        assertEquals("• Marcus Cole attached the revised quote", cleanedStream)
    }

    @Test
    fun whiteBoxGuardrailsRepairBrokenSmallModelOutputsOnRelationshipNote() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val sampleNote =
            "Project sync: Liam Chen sent the latest Micro-OLED reliability report to Priya Nair for review. " +
                "Marcus Cole attached the revised quote to the Supply Agreement, so Sarah Jenkins and " +
                "Elena Vance need to align the delivery penalty clause."

        // Case 1: Single sparse sentence, invented #renewables tag, fabricated calendar, 1 relation
        val run1 = """
            {
              "summary": "First point: The revised quote is attached to the supply agreement",
              "tags": ["renewables"],
              "calendarProposal": {"title": "Review of revised quote with supply", "startTimeIso": "2026-11-24T14:00:00", "durationMinutes": 30, "location": null},
              "relations": [{"source": "Marcus Cole", "target": "Supply Agreement", "relation": "attached to", "bidirectional": false}]
            }
        """.trimIndent()
        val fixed1 = requireNotNull(analyzer.mergeModelJsonOrNull(run1, sampleNote, nowMillis))
        assertNull(fixed1.calendarProposal)
        assertFalse(fixed1.summary.contains("First point", ignoreCase = true))
        assertFalse(fixed1.tags.any { it.equals("renewables", ignoreCase = true) })
        assertTrue(fixed1.tags.contains("Supply chain"))
        assertTrue(fixed1.relations.size >= 4)

        // Case 2: Degenerate "First point: Project details" stub + generic tags
        val run2 = """
            {
              "summary": "First point: Project details",
              "tags": ["Project", "details"],
              "calendarProposal": null,
              "relations": [
                {"source": "Liam Chen", "target": "Priya Nair", "relation": "sent report", "bidirectional": false},
                {"source": "Marcus Cole", "target": "Supply Agreement", "relation": "attached", "bidirectional": false}
              ]
            }
        """.trimIndent()
        val fixed2 = requireNotNull(analyzer.mergeModelJsonOrNull(run2, sampleNote, nowMillis))
        assertNull(fixed2.calendarProposal)
        assertFalse(fixed2.summary.contains("Project details"))
        assertTrue(fixed2.summary.contains("Liam Chen"))
        assertTrue(fixed2.tags.none { it.equals("Project", ignoreCase = true) || it.equals("details", ignoreCase = true) })
        assertTrue(fixed2.relations.size >= 4)

        // Case 3: Entity typo "Marcia Cole", person-name tags, invented Dec-05 calendar
        val run3 = """
            {
              "summary": "First point: Project sync with Liam Chen and Marcia Cole",
              "tags": ["Project sync", "Liam Chen", "Marcia Cole"],
              "calendarProposal": {"title": "Project sync meeting on 12-05", "startTimeIso": "2026-12-05T14:00:00", "durationMinutes": 50, "location": null},
              "relations": [{"source": "Liam Chen", "target": "Marcia Cole", "relation": "syncs with", "bidirectional": true}]
            }
        """.trimIndent()
        val fixed3 = requireNotNull(analyzer.mergeModelJsonOrNull(run3, sampleNote, nowMillis))
        assertNull(fixed3.calendarProposal)
        assertFalse(fixed3.summary.contains("Marcia Cole"))
        assertTrue(fixed3.relations.none { it.source == "Marcia Cole" || it.target == "Marcia Cole" })
        assertTrue(fixed3.tags.none { it in listOf("Project sync", "Liam Chen", "Marcia Cole") })
        assertTrue(fixed3.relations.size >= 4)
        val attachedEdge = fixed3.relations.firstOrNull {
            setOf(it.source, it.target) == setOf("Marcus Cole", "Supply Agreement")
        }
        assertNotNull(attachedEdge)
        assertEquals("attached to", attachedEdge!!.relation)
    }

    @Test
    fun preservesValidCalendarProposalsAndRejectsPlaceholders() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val scheduledNote = "Tomorrow at 3:00 PM in Building B, Room 402: finalize addendum with Sarah Jenkins."
        val validJson = """
            {
              "summary": "• Tomorrow 3:00 PM at Building B, Room 402\n• Finalize addendum with Sarah Jenkins",
              "tags": ["Legal", "Follow-up"],
              "calendarProposal": {"title": "Finalize addendum", "startTimeIso": "2026-11-18T15:00:00", "durationMinutes": 45, "location": "Building B, Room 402"},
              "relations": []
            }
        """.trimIndent()
        val validPayload = requireNotNull(analyzer.mergeModelJsonOrNull(validJson, scheduledNote, nowMillis))
        val validCalendar = requireNotNull(validPayload.calendarProposal)
        assertEquals("Finalize addendum", validCalendar.title)
        assertEquals("2026-11-18T15:00:00", validCalendar.startTimeIso)
        assertEquals(45, validCalendar.durationMinutes)
        assertTrue(validPayload.tags.contains("Legal"))
        assertTrue(validPayload.tags.contains("Follow-up"))

        val placeholderJson = """
            {
              "summary": "• Review meeting on Dec 5 at 2:00 PM",
              "tags": [],
              "calendarProposal": {"title": "Event title", "startTimeIso": "YYYY-MM-DDTHH:MM:SS", "durationMinutes": 30, "location": null},
              "relations": []
            }
        """.trimIndent()
        val rejectedPlaceholder = requireNotNull(
            analyzer.mergeModelJsonOrNull(placeholderJson, "Review meeting on Dec 5 at 2:00 PM", nowMillis),
        )
        assertNull(rejectedPlaceholder.calendarProposal)
        assertFalse(analyzer.hasTemporalScheduleSignal("The weather is lovely today"))
        assertTrue(analyzer.hasTemporalScheduleSignal("Review with Sarah on Dec 5"))
    }

    @Test
    fun repairsLenientModelJsonWithRawNewlinesQuotesTrailingCommasAndNulls() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val rawNote = "Sarah Jenkins said \"send the updated quote\" to Marcus Cole tomorrow."
        val brokenJson = "{\"summary\":\"\u2022 Sarah said \"send the updated quote\"\n\u2022 Coordinate with Marcus Cole\",\"tags\":null,\"calendarProposal\":null,\"relations\":[{\"source\":\"Sarah Jenkins\",\"target\":\"Marcus Cole\",\"relation\":\"coordinates\",},],}"

        val parsed = requireNotNull(analyzer.mergeModelJsonOrNull(brokenJson, rawNote, nowMillis))
        assertTrue(parsed.summary.contains("Sarah said"))
        assertTrue(parsed.summary.contains("Marcus Cole"))
        assertTrue(parsed.relations.any { it.source == "Sarah Jenkins" && it.target == "Marcus Cole" })
    }

    @Test
    fun enforcesSameLanguageTagsAndScriptAwareCjkBulletThresholds() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val cjkTypoNote = "\u59293\u51B7\u4E0D\u51B7\uFF0C\u51B7\u7684\u8BDD\u627E\u55B5\u55B5\uFF0C\u6C6A\u6C6A\u73A9"
        val cjkJson = "{" +
            "\"summary\":\"\u2022 \u5929\u51B7\u65F6\u627E\u55B5\u55B5\u3001\u6C6A\u6C6A\u4E00\u8D77\u73A9\"," +
            "\"tags\":[\"Weather\",\"\u5929\u6C14\",\"\u6237\u5916\u6D3B\u52A8\",\"\u7B14\u8BB0\"]," +
            "\"calendarProposal\":null," +
            "\"relations\":[" +
            "{\"source\":\"\u6211\",\"target\":\"\u55B5\u55B5\",\"relation\":\"\u4E00\u8D77\u73A9\",\"bidirectional\":false}," +
            "{\"source\":\"\u55B5\u55B5\",\"target\":\"\u6C6A\u6C6A\",\"relation\":\"\u4E00\u8D77\u73A9\",\"bidirectional\":true}" +
            "]}"

        val cjkMerged = requireNotNull(analyzer.mergeModelJsonOrNull(cjkJson, cjkTypoNote, nowMillis))
        assertEquals("\u5929\u51B7\u65F6\u627E\u55B5\u55B5\u3001\u6C6A\u6C6A\u4E00\u8D77\u73A9", cjkMerged.summary)
        // Cross-language English tag ("Weather") and stop tag are dropped on a pure-CJK note.
        assertEquals(listOf("\u5929\u6C14", "\u6237\u5916\u6D3B\u52A8"), cjkMerged.tags)
        // Only entities grounded in the raw note are kept; synthetic subject not in source is filtered.
        assertEquals(1, cjkMerged.relations.size)
        val edge = cjkMerged.relations.single()
        assertEquals("\u55B5\u55B5", edge.source)
        assertEquals("\u6C6A\u6C6A", edge.target)
        assertTrue(edge.bidirectional)

        val mathNote = "\u7B9731\u52A082\u662F\u591A\u5C11"
        val driftedMathJson = "{\"summary\":\"\u2022 31 + 82 = 113\",\"tags\":[\"math\",\"addition\"],\"calendarProposal\":null,\"relations\":[]}"
        val driftedMerged = requireNotNull(analyzer.mergeModelJsonOrNull(driftedMathJson, mathNote, nowMillis))
        assertEquals("31 + 82 = 113", driftedMerged.summary)
        assertFalse(driftedMerged.tags.contains("math"))
        assertEquals(listOf("\u6570\u5B66\u8FD0\u7B97"), driftedMerged.tags)

        val cjkLongSummary = "\u5929\u6C14\u8F6C\u51B7\u9700\u8981\u6DFB\u8863\uFF0C\u5468\u672B\u7EA6\u670B\u53CB\u4E00\u8D77\u53BB\u6237\u5916\u6563\u6B65\uFF0C\u987A\u4FBF\u4E703\u65A4\u82F9\u679C"
        val cjkBullets = analyzer.normalizeBulletSummary(cjkLongSummary)
        assertTrue(cjkBullets.lines().size >= 2)
        assertTrue(cjkBullets.lines().all { it.startsWith("\u2022 ") })

        val edgePrompt = analyzer.buildEdgeAnalysisUserPrompt(cjkTypoNote, nowMillis)
        assertTrue(edgePrompt.contains("\"summary\""))
        assertTrue(edgePrompt.contains("\"source\""))
        assertTrue(edgePrompt.contains("\"target\""))
        assertTrue(edgePrompt.contains("\"relation\""))
        assertTrue(edgePrompt.contains("\"bidirectional\""))
        assertTrue(edgePrompt.contains(cjkTypoNote))
    }

    @Test
    fun decodesStrictClosedAstShapesAndRejectsUnterminatedPayloads() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val rawNote =
            "Meet tomorrow at 2:00 PM. Liam Chen sent the report to Priya Nair; " +
                "Marcus Cole attached the quote to Supply Agreement; Elena Vance works with Sarah Jenkins."

        val validVariantJson = "{" +
            "\"summary\":\"\u2022 Project review scheduled tomorrow\"," +
            "\"tags\":[\"Follow-up\"]," +
            "\"calendarProposal\":{\"title\":\"Project review\",\"start_time\":\"2026-11-18T14:00:00\",\"durationMinutes\":\"30\",\"location\":null}," +
            "\"relations\":[{\"source\":\"Liam Chen\",\"target\":\"Priya Nair\",\"relation\":\"meets\",\"bidirectional\":true},{\"source\":\"\",\"target\":\"Ignored\",\"relation\":\"bad\"}]" +
            "}"

        val mergedCal = requireNotNull(analyzer.mergeModelJsonOrNull(validVariantJson, rawNote, nowMillis))
        val cal = requireNotNull(mergedCal.calendarProposal)
        assertEquals("Project review", cal.title)
        assertEquals("2026-11-18T14:00:00", cal.startTimeIso)
        assertEquals(30, cal.durationMinutes)
        assertEquals(1, mergedCal.relations.count { it.source == "Liam Chen" && it.target == "Priya Nair" })

        for (emptyCal in listOf("\"\"", "\"none\"", "[]")) {
            val payload = requireNotNull(
                analyzer.parseJsonPayload("{\"summary\":\"Quick note\",\"tags\":[],\"calendarProposal\":$emptyCal,\"relations\":[]}"),
            )
            assertNull(payload.calendarProposal)
        }

        assertNull(analyzer.repairModelJsonCandidate("{\"summary\":\"unterminated string"))
        assertNull(analyzer.repairModelJsonCandidate("{\"summary\":\"Quick note\",\"tags\":[\"Follow-up\"]"))
        assertNull(analyzer.repairModelJsonCandidate("plain non-json text"))
    }

    @Test
    fun diagnoseParseFailureEmitsNonPiiStructuralCodes() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        assertEquals("blank_input", analyzer.diagnoseParseFailure("   \n  "))

        val noBraceDiag = analyzer.diagnoseParseFailure("SecretPrivateText")
        assertTrue(noBraceDiag.startsWith("repair_null(hasBrace=false"))
        assertFalse(noBraceDiag.contains("SecretPrivateText"))

        val midStringDiag = analyzer.diagnoseParseFailure("{\"summary\":\"SecretNotePayload")
        assertTrue(midStringDiag.contains("inString=true"))
        assertFalse(midStringDiag.contains("SecretNotePayload"))

        val blankSummaryDiag = analyzer.diagnoseParseFailure("{\"summary\":\"   \",\"tags\":[\"SecretTag\"]}")
        assertEquals("blank_summary(keys=summary,tags)", blankSummaryDiag)
        assertFalse(blankSummaryDiag.contains("SecretTag"))

        assertEquals("ok", analyzer.diagnoseParseFailure("{\"summary\":\"Ready summary\",\"tags\":[]}"))
    }

    @Test
    fun edgePromptTemplatesSelectScriptAndGuardrailsHardenCjkAndEnglishOutputs() {
        val analyzer = MemoryAnalyzer(zoneId = zone)

        val pureCjkNote = "\u59293\u51B7\u4E0D\u51B7\uFF0C\u51B7\u7684\u8BDD\u627E\u55B5\u55B5\uFF0C\u6C6A\u6C6A\u73A9"
        val mixedCjkNote = "\u5468\u56DB\u4E0A\u534810\u70B9\u548C Priya \u5BF9\u9F50 Micro-OLED \u91CF\u4EA7\u56FA\u4EF6\u6E05\u5355"
        val pureEnglishNote =
            "Project sync: Liam Chen sent the latest Micro-OLED reliability report to Priya Nair for review. " +
                "Marcus Cole attached the revised quote to the Supply Agreement, so Sarah Jenkins and Elena Vance " +
                "need to align the delivery penalty clause."

        assertTrue(EdgePromptTemplates.containsCjk(pureCjkNote))
        assertTrue(EdgePromptTemplates.containsCjk(mixedCjkNote))
        assertFalse(EdgePromptTemplates.containsCjk(pureEnglishNote))

        val cjkPrompt = analyzer.buildEdgeAnalysisUserPrompt(pureCjkNote, nowMillis)
        val mixedPrompt = analyzer.buildEdgeAnalysisUserPrompt(mixedCjkNote, nowMillis)
        val enPrompt = analyzer.buildEdgeAnalysisUserPrompt(pureEnglishNote, nowMillis)
        assertTrue(cjkPrompt.contains("\u5370\u8FF9"))
        assertTrue(mixedPrompt.contains("\u5370\u8FF9"))
        assertTrue(enPrompt.contains("Raw note:"))
        for (prompt in listOf(cjkPrompt, mixedPrompt, enPrompt)) {
            assertFalse(prompt.contains("<note>"))
            assertFalse(prompt.contains("First point"))
            assertFalse(prompt.contains("Rules:\n"))
            assertFalse(prompt.contains("\u2022 "))
        }

        // Plaintext password inside a model-generated CJK summary is redacted during merge.
        val secretRaw = "\u738B\u603B\u5F15\u8350\u8001\u674E\u5BF9\u63A560\u5929\u6708\u7ED3\uFF0C\u6D4B\u8BD5\u540E\u53F0\u8D26\u53F7 admin \u5BC6\u7801 Qwerty#2026"
        val unredactedModelJson = """
            {
              "summary": "\u6D4B\u8BD5\u540E\u53F0\u5BC6\u7801 Qwerty#2026",
              "tags": ["\u4F9B\u5E94\u94FE"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val redactedMerged = requireNotNull(analyzer.mergeModelJsonOrNull(unredactedModelJson, secretRaw, nowMillis))
        assertFalse(redactedMerged.summary.contains("Qwerty#2026"))
        assertTrue(redactedMerged.summary.contains("\u8BE6\u89C1\u539F\u6587"))

        // CJK relative durations pass temporal schedule validation.
        assertTrue(analyzer.hasTemporalScheduleSignal("\u534A\u5C0F\u65F6\u540E\u5F00\u4F1A"))
        assertTrue(analyzer.hasTemporalScheduleSignal("\u4E24\u5C0F\u65F6\u540E\u53D6\u5FEB\u9012"))

        // Cross-script CJK tags are dropped on pure English notes while thematic English tags remain kept.
        val enModelJson = """
            {
              "summary": "\u2022 Liam Chen sent Micro-OLED reliability report to Priya Nair\n\u2022 Marcus Cole attached revised quote to Supply Agreement",
              "tags": ["\u9879\u76EE", "Contract", "Reliability"],
              "calendarProposal": null,
              "relations": [
                {"source": "Liam Chen", "target": "Priya Nair", "relation": "sent", "bidirectional": false}
              ]
            }
        """.trimIndent()
        val enMerged = requireNotNull(analyzer.mergeModelJsonOrNull(enModelJson, pureEnglishNote, nowMillis))
        assertFalse(enMerged.tags.any { EdgePromptTemplates.containsCjk(it) })
        assertTrue(enMerged.tags.contains("Contract"))
        assertTrue(enMerged.tags.contains("Reliability"))

        // CJK relative schedule extraction computes exact local dates/clocks and venue locations.
        val cjkMeetingNote = "\u660E\u5929\u4E0B\u53483\u70B9\u5728A\u5EA7302\u4F1A\u8BAE\u5BA4\u5F00\u9700\u6C42\u8BC4\u5BA1\u4F1A"
        val cjkMeetingFallback = analyzer.fallbackAnalyze(cjkMeetingNote, nowMillis)
        assertEquals("2026-11-18T15:00:00", cjkMeetingFallback.calendarProposal?.startTimeIso)
        assertEquals("A\u5EA7302\u4F1A\u8BAE\u5BA4", cjkMeetingFallback.calendarProposal?.location)

        val cjkErrandNote = "1\u5C0F\u65F6\u540E\u63D0\u9192\u6211\u53BB\u524D\u53F0\u53D6\u5FEB\u9012"
        val cjkErrandFallback = analyzer.fallbackAnalyze(cjkErrandNote, nowMillis)
        assertEquals("2026-11-17T18:30:00", cjkErrandFallback.calendarProposal?.startTimeIso)

        val mixedFallback = analyzer.fallbackAnalyze(mixedCjkNote, nowMillis)
        assertEquals("2026-11-19T10:00:00", mixedFallback.calendarProposal?.startTimeIso)

        // Non-time idiom ("3 points of feedback") must not trigger calendar proposals.
        assertFalse(analyzer.hasTemporalScheduleSignal("\u603B\u7ED3\u4E863\u70B9\u610F\u89C1\u548C2\u6761\u5EFA\u8BAE"))

        // Model calendar reconciliation corrects stale cutoff years and spurious trailing Z suffixes,
        // while still discarding explicit placeholder calendar titles.
        val enScheduleRaw = "Tomorrow at 2:30 PM project demo review"
        val driftedYearJson = """
            {
              "summary": "Project demo review scheduled",
              "tags": ["Follow-up"],
              "calendarProposal": {"title": "Project demo review", "startTimeIso": "2024-10-05T17:00:00", "durationMinutes": 45, "location": null},
              "relations": []
            }
        """.trimIndent()
        val driftedMerged = requireNotNull(analyzer.mergeModelJsonOrNull(driftedYearJson, enScheduleRaw, nowMillis))
        assertEquals("2026-11-18T14:30:00", driftedMerged.calendarProposal?.startTimeIso)

        val trailingZJson = """
            {
              "summary": "Project demo review scheduled",
              "tags": ["Follow-up"],
              "calendarProposal": {"title": "Project demo review", "startTimeIso": "2026-11-18T17:30:00Z", "durationMinutes": 45, "location": null},
              "relations": []
            }
        """.trimIndent()
        val zMerged = requireNotNull(analyzer.mergeModelJsonOrNull(trailingZJson, enScheduleRaw, nowMillis))
        assertEquals("2026-11-18T14:30:00", zMerged.calendarProposal?.startTimeIso)

        val placeholderCalJson = """
            {
              "summary": "Project demo review scheduled",
              "tags": ["Follow-up"],
              "calendarProposal": {"title": "Event title", "startTimeIso": "2026-11-18T14:30:00", "durationMinutes": 30, "location": null},
              "relations": []
            }
        """.trimIndent()
        val placeholderMerged = requireNotNull(analyzer.mergeModelJsonOrNull(placeholderCalJson, enScheduleRaw, nowMillis))
        assertEquals(null, placeholderMerged.calendarProposal)

        // Title-less calendar objects with nested location JSON adopt the grounded heuristic title & venue.
        val titlelessCalJson = """
            {
              "summary": "$cjkMeetingNote",
              "tags": ["\u4F1A\u8BAE"],
              "calendarProposal": {"startTimeIso": "2026-11-18 17:00:00", "durationMinutes": 30, "location": {"name": "A\u5EA7302\u4F1A\u8BAE\u5BA4", "address": "\u5317\u4EAC\u5E02\u671D\u9633\u533A"}},
              "relations": []
            }
        """.trimIndent()
        val titlelessMerged = requireNotNull(analyzer.mergeModelJsonOrNull(titlelessCalJson, cjkMeetingNote, nowMillis))
        val rescuedCal = requireNotNull(titlelessMerged.calendarProposal)
        assertTrue(rescuedCal.title.isNotBlank())
        assertEquals("2026-11-18T15:00:00", rescuedCal.startTimeIso)
        assertEquals("A\u5EA7302\u4F1A\u8BAE\u5BA4", rescuedCal.location)

        // Zero-overlap hallucinated short stub falls back to grounded summary, and squashed entity tags are dropped.
        val casualTypoRaw = "rember to buy oat milk and espresso beans aftr gym"
        val stubModelJson = """
            {
              "summary": "Relaxation activities",
              "tags": ["relaxation", "activity"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val stubMerged = requireNotNull(analyzer.mergeModelJsonOrNull(stubModelJson, casualTypoRaw, nowMillis))
        assertFalse(stubMerged.summary.contains("Relaxation"))
        assertTrue(stubMerged.summary.contains("oat milk"))

        val squashedTagJson = """
            {
              "summary": "Project sync between Liam Chen and Priya Nair for Micro-OLED reliability report",
              "tags": ["priyannair", "priyan", "microoled", "supplyagreement", "deliverypenaltyclauses"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val squashedMerged = requireNotNull(analyzer.mergeModelJsonOrNull(squashedTagJson, pureEnglishNote, nowMillis))
        assertTrue(squashedMerged.tags.contains("Supply chain"))
        assertTrue(squashedMerged.tags.contains("Legal"))
        assertFalse(squashedMerged.tags.any { it in listOf("priyannair", "priyan", "microoled", "supplyagreement", "deliverypenaltyclauses") })
    }

    @Test
    fun detectsPinyinOmitsScheduleWhenUnscheduledSalvagesLoopAndExtractsCjkRelations() {
        val analyzer = MemoryAnalyzer(zoneId = zone)
        val pinyinNote = "wo yao chi niurou, xiang yao he ww, yy yi qi wan."
        val pinyinAsk = "wo yao chi shenme?"
        val cjkTypoNote = "\u59293\u51B7\u4E0D\u51B7\uFF0C\u51B7\u7684\u8BDD\u627E\u55B5\u55B5\uFF0C\u6C6A\u6C6A\u73A9"

        // Pinyin script routing & conditional schedule prompt omission.
        assertTrue(EdgePromptTemplates.isRomanizedPinyin(pinyinNote))
        assertTrue(EdgePromptTemplates.isRomanizedPinyin(pinyinAsk))
        assertFalse(EdgePromptTemplates.isRomanizedPinyin(cjkTypoNote))
        assertFalse(EdgePromptTemplates.isRomanizedPinyin("Project sync with Liam Chen tomorrow at 3 PM"))

        val unscheduledPrompt = analyzer.buildEdgeAnalysisUserPrompt(pinyinNote, nowMillis)
        assertTrue(unscheduledPrompt.contains("\u6C49\u8BED\u62FC\u97F3"))
        assertFalse(unscheduledPrompt.contains("2026-11-17"))
        assertFalse(unscheduledPrompt.contains("startTimeIso"))

        val scheduledPrompt = analyzer.buildEdgeAnalysisUserPrompt(
            "\u660E\u5929\u4E0B\u53483\u70B9\u5F00\u4F1A",
            nowMillis,
        )
        assertTrue(scheduledPrompt.contains("2026-11-17"))
        assertTrue(scheduledPrompt.contains("startTimeIso"))

        // Clean Pinyin model output retains CJK tags and grounded ww/yy/beef relations.
        val validPinyinJson = """
            {
              "summary": "\u6211\u8981\u5403\u725B\u8089\uFF1B\u60F3\u548C ww\u3001yy \u4E00\u8D77\u73A9",
              "tags": ["\u996E\u98DF", "\u5A31\u4E50"],
              "calendarProposal": null,
              "relations": [
                {"source": "\u6211", "target": "\u725B\u8089", "relation": "\u60F3\u5403", "bidirectional": false},
                {"source": "\u6211", "target": "ww", "relation": "\u4E00\u8D77\u73A9", "bidirectional": false},
                {"source": "\u6211", "target": "yy", "relation": "\u4E00\u8D77\u73A9", "bidirectional": false}
              ]
            }
        """.trimIndent()
        val cleanPinyin = requireNotNull(analyzer.mergeModelJsonOrNull(validPinyinJson, pinyinNote, nowMillis))
        assertTrue(cleanPinyin.summary.contains("\u725B\u8089"))
        assertTrue(cleanPinyin.summary.contains("ww"))
        assertTrue(cleanPinyin.summary.contains("yy"))
        assertEquals(listOf("\u996E\u98DF", "\u5A31\u4E50"), cleanPinyin.tags)
        assertTrue(cleanPinyin.relations.size >= 3)

        // Hallucinated Cloud/Edge Pinyin output (guessing milk tea / names instead of ww, yy) is repaired by guardrails.
        val hallucinatedPinyinJson = """
            {
              "summary": "\u2022 \u6211\u60F3\u5403\u725B\u8089\n\u2022 \u6211\u60F3\u559D\u5976\u8336\n\u2022 \u6211\u60F3\u548C\u60A0\u60A0\u4E00\u8D77\u73A9",
              "tags": ["\u996E\u98DF"],
              "calendarProposal": null,
              "relations": [
                {"source": "\u6211", "target": "\u5976\u8336", "relation": "\u60F3\u559D", "bidirectional": false},
                {"source": "\u6211", "target": "\u60A0\u60A0", "relation": "\u4E00\u8D77\u73A9", "bidirectional": false}
              ]
            }
        """.trimIndent()
        val repairedPinyin = requireNotNull(analyzer.mergeModelJsonOrNull(hallucinatedPinyinJson, pinyinNote, nowMillis))
        assertFalse(repairedPinyin.summary.contains("\u5976\u8336"))
        assertFalse(repairedPinyin.summary.contains("\u60A0\u60A0"))
        assertTrue(repairedPinyin.summary.contains("ww"))
        assertTrue(repairedPinyin.summary.contains("yy"))
        assertTrue(repairedPinyin.relations.none { it.target == "\u5976\u8336" || it.target == "\u60A0\u60A0" })
        assertTrue(repairedPinyin.relations.any { it.target == "ww" || it.source == "ww" })
        assertTrue(repairedPinyin.relations.any { it.target == "yy" || it.source == "yy" })

        // Empty-relation CJK companion note recovers Miaomiao <-> Wangwang without synthetic "Me" or "Day".
        val emptyRelsWeatherJson = """
            {
              "summary": "\u5929\u51B7\u4E86\uFF0C\u627E\u55B5\u55B5\u548C\u6C6A\u6C6A\u73A9",
              "tags": ["\u5929\u6C14"],
              "calendarProposal": null,
              "relations": [{"source": "\u5929", "target": "\u55B5\u55B5\u548C\u6C6A\u6C6A", "relation": "\u73A9"}]
            }
        """.trimIndent()
        val weatherMerged = requireNotNull(analyzer.mergeModelJsonOrNull(emptyRelsWeatherJson, cjkTypoNote, nowMillis))
        val petEdge = weatherMerged.relations.single()
        assertEquals("\u55B5\u55B5", petEdge.source)
        assertEquals("\u6C6A\u6C6A", petEdge.target)
        assertTrue(petEdge.bidirectional)

        // Hallucinated weather forecast & prompt-echo tags drop cleanly, and stray IME digit smooths to CJK bigrams.
        val forecastDriftJson = """
            {
              "summary": "\u5929\u6C14\u9884\u62A5\u663E\u793A\u6C14\u6E29\u8F83\u4F4E",
              "tags": ["\u767D\u76D2\u5B50", "\u8BB0\u5FC6"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val forecastRepaired = requireNotNull(analyzer.mergeModelJsonOrNull(forecastDriftJson, cjkTypoNote, nowMillis))
        assertTrue(forecastRepaired.summary.contains("\u5929\u51B7"))
        assertEquals(listOf("\u5A31\u4E50", "\u5929\u6C14"), forecastRepaired.tags)

        // Live Cloud drink-initial Pinyin misread ("he ww" as drinking ww) is rejected and repaired.
        val drinkInitialJson = """
            {
              "summary": "\u2022 \u6211\u8981\u5403\u725B\u8089\n\u2022 \u60F3\u559Dww\n\u2022 \u548Cyy\u4E00\u8D77\u73A9",
              "tags": ["\u996E\u98DF", "\u5A31\u4E50"],
              "calendarProposal": null,
              "relations": [
                {"source": "\u6211", "target": "\u725B\u8089", "relation": "\u60F3\u5403", "bidirectional": false},
                {"source": "\u6211", "target": "ww", "relation": "\u60F3\u559D", "bidirectional": false}
              ]
            }
        """.trimIndent()
        val repairedDrink = requireNotNull(analyzer.mergeModelJsonOrNull(drinkInitialJson, pinyinNote, nowMillis))
        assertFalse(repairedDrink.summary.contains("\u559D"))
        assertTrue(repairedDrink.relations.none { it.relation.contains("\u559D") })

        // Flattened root calendar JSON without "summary" key is salvaged & reconciled cleanly.
        val flatRootCal = """
            {"title":"Reminder","startTimeIso":"2026-11-23T14:00:00Z","durationMinutes":30,"location":null,"relations":[]}
        """.trimIndent()
        val flatRaw = "In 2 hours remind me to pick up the prototype lens kit from Building B, Room 108."
        val flatMerged = requireNotNull(analyzer.mergeModelJsonOrNull(flatRootCal, flatRaw, nowMillis))
        assertTrue(flatMerged.summary.contains("prototype lens kit"))
        assertEquals("2026-11-17T19:30:00", flatMerged.calendarProposal?.startTimeIso)

        // Runaway repetition loop with closed summary is salvaged while ordinary truncated JSON stays rejected.
        val errandsNote = "\u4E703\u65A4\u7EA2\u5BCC\u58EB\u82F9\u679C\uFF0C\u987A\u4FBF\u5E26\u4E24\u76D2\u4F4E\u8102\u725B\u5976\uFF0C\u518D\u53BB\u836F\u5E97\u62FF\u521B\u53EF\u8D34"
        val loopedRaw = "{\"summary\":\"\u8D2D\u4E70\u82F9\u679C\u548C\u725B\u5976\uFF1B\u53BB\u836F\u5E97\u62FF\u521B\u53EF\u8D34\",\"tags\":[\"\u4E70\",\"\u4E70\",\"\u4E70\",\"\u4E70\",\"\u4E70\""
        val salvagedErrands = requireNotNull(analyzer.mergeModelJsonOrNull(loopedRaw, errandsNote, nowMillis))
        assertTrue(salvagedErrands.summary.contains("\u82F9\u679C"))
        assertEquals(listOf("\u65E5\u5E38\u91C7\u8D2D"), salvagedErrands.tags)

        // Latin tags emitted on pure-CJK math notes fall back to CJK math tag; CJK dates do not trigger math tag.
        val cjkMathNote = "\u7B9731\u52A082\u662F\u591A\u5C11"
        val cjkMathModelJson = """
            {"summary":"\u8BA1\u7B9731\u52A0\u4E0A82\u7684\u7ED3\u679C\u3002","tags":["math","calculation"],"calendarProposal":null,"relations":[]}
        """.trimIndent()
        val cjkMathMerged = requireNotNull(analyzer.mergeModelJsonOrNull(cjkMathModelJson, cjkMathNote, nowMillis))
        assertEquals(listOf("\u6570\u5B66\u8FD0\u7B97"), cjkMathMerged.tags)

        val cjkDateNote = "\u8BB0\u5F552026-11-23\u7684\u65E5\u5E38"
        val cjkDateJson = """
            {"summary":"\u8BB0\u5F552026-11-23\u7684\u65E5\u5E38","tags":[],"calendarProposal":null,"relations":[]}
        """.trimIndent()
        val cjkDateMerged = requireNotNull(analyzer.mergeModelJsonOrNull(cjkDateJson, cjkDateNote, nowMillis))
        assertFalse(cjkDateMerged.tags.contains("\u6570\u5B66\u8FD0\u7B97"))

        // General syntactic rules: legitimate drink Pinyin keeps CJK drink claims while comitative `he` rejects ungrounded drink claims.
        val validDrinkPinyin = analyzer.mergeModelJsonOrNull(
            """{"summary":"\u6211\u60F3\u559D\u5976\u8336","tags":["\u996E\u98DF"],"calendarProposal":null,"relations":[]}""",
            "wo xiang he naicha",
            nowMillis,
        )
        assertNotNull(validDrinkPinyin)
        assertTrue(validDrinkPinyin!!.summary.contains("\u559D\u5976\u8336"))

        val hallucinatedDrinkPinyin = analyzer.mergeModelJsonOrNull(
            """{"summary":"\u6211\u60F3\u559D\u53EF\u4E50","tags":["\u996E\u98DF"],"calendarProposal":null,"relations":[]}""",
            "wo he mm, gg yi qi wan",
            nowMillis,
        )
        assertNotNull(hallucinatedDrinkPinyin)
        assertFalse(hallucinatedDrinkPinyin!!.summary.contains("\u53EF\u4E50"))
        assertTrue(hallucinatedDrinkPinyin.summary.contains("mm"))

        // Mandarin A-not-A IME stray digit smoothing applies structurally while keeping quantities intact.
        val hikeFallback = analyzer.fallbackAnalyze("\u5468\u672B\u53BB4\u4E0D\u53BB\u722C\u5C71", nowMillis)
        assertTrue(hikeFallback.summary.contains("\u53BB\u4E0D\u53BB"))
        assertFalse(hikeFallback.summary.contains("4"))

        val appleFallback = analyzer.fallbackAnalyze("\u4E703\u65A4\u82F9\u679C", nowMillis)
        assertTrue(appleFallback.summary.contains("3\u65A4"))

        // Entity-dropping CJK summary drift falls back to grounded extraction.
        val cjkWeatherNote = "\u4ECA\u5929\u5929\u6C14\u51B7\u4E0D\u51B7\uFF0C\u51B7\u7684\u8BDD\u627E\u55B5\u55B5\uFF0C\u6C6A\u6C6A\u73A9"
        val driftedWeather = analyzer.mergeModelJsonOrNull(
            """{"summary":"\u5929\u6C14\u5F88\u9002\u5408\u5728\u5BA4\u5185\u4F11\u606F","tags":["\u5929\u6C14"],"calendarProposal":null,"relations":[]}""",
            cjkWeatherNote,
            nowMillis,
        )
        assertNotNull(driftedWeather)
        assertTrue(driftedWeather!!.summary.contains("\u55B5\u55B5"))
        assertTrue(driftedWeather.summary.contains("\u6C6A\u6C6A"))
    }
}
