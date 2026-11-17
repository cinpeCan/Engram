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

    @Test
    fun parsesFencedModelJsonAndEnforcesDeponderInvariants() {
        val jsonOutput = """
            ```json
            {
              "summary": "法务对齐合同条款",
              "tags": ["商务合同"],
              "calendarProposal": null,
              "relations": [
                {"source": "张姐", "target": "张姐", "relation": "自环应过滤", "bidirectional": false},
                {"source": "老李", "target": "张姐", "relation": "提交条款", "bidirectional": false},
                {"source": "张姐", "target": "老李", "relation": "反馈修改", "bidirectional": false}
              ]
            }
            ```
        """.trimIndent()

        val analyzer = MemoryAnalyzer()
        val payload = analyzer.mergeModelJsonOrNull(jsonOutput, "老李和法务张姐对接合同条款")
        assertNotNull(payload)
        val resolved = payload!!

        assertEquals("法务对齐合同条款", resolved.summary)
        assertEquals(listOf("商务合同"), resolved.tags)
        assertEquals(1, resolved.relations.size)
        val edge = resolved.relations.single()
        assertEquals("老李", edge.source)
        assertEquals("张姐", edge.target)
        assertTrue(edge.bidirectional)
        assertNull(analyzer.mergeModelJsonOrNull("not-valid-json", "测试"))
    }

    @Test
    fun normalizesMultiClauseModelSummariesIntoConciseBullets() {
        val analyzer = MemoryAnalyzer()
        val rawOutput = """
            {
              "summary": "法务张姐审核违约金条款；财务刘敏等待陈工提交验收单后放行预付款；王总周五前跟进。",
              "tags": ["商务合同"],
              "calendarProposal": null,
              "relations": []
            }
        """.trimIndent()
        val parsed = analyzer.mergeModelJsonOrNull(rawOutput, "测试多条纪要")!!
        val lines = parsed.summary.lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("• ") })
        assertTrue(lines.none { it.contains('；') })
    }

    @Test
    fun extractsDistilledSummaryAndExactTimeForRelativeDurationNotes() {
        val zone = ZoneId.of("Asia/Shanghai")
        val nowMillis = LocalDateTime.of(2026, 11, 17, 17, 30, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val analyzer = MemoryAnalyzer(zoneId = zone)

        val mealPayload = analyzer.fallbackAnalyze("1小时后吃饭", nowMillis = nowMillis)
        assertTrue(mealPayload.summary.contains("18:30"))
        assertTrue(mealPayload.summary.contains("吃饭"))
        assertTrue(mealPayload.summary != "吃饭")
        assertEquals(listOf("饮食", "日程提醒"), mealPayload.tags)

        val mealCal = requireNotNull(mealPayload.calendarProposal)
        assertEquals("吃饭", mealCal.title)
        assertEquals("2026-11-17T18:30:00", mealCal.startTimeIso)
        assertEquals(30, mealCal.durationMinutes)

        val parcelPayload = analyzer.fallbackAnalyze("半小时后去拿快递", nowMillis = nowMillis)
        val parcelCal = requireNotNull(parcelPayload.calendarProposal)
        assertEquals("拿快递", parcelCal.title)
        assertEquals("2026-11-17T18:00:00", parcelCal.startTimeIso)
        assertEquals(listOf("生活待办", "日程提醒"), parcelPayload.tags)

        val credPayload = analyzer.fallbackAnalyze(
            "调试后台账号 test_panel，初始密码 Admin@9527，下周交接给研发陈工做烧屏测试。",
            nowMillis = nowMillis,
        )
        assertFalse(credPayload.summary.contains("Admin@9527"))
        assertFalse(credPayload.summary.contains("test_panel"))
        assertTrue(credPayload.summary.contains("陈工"))
    }

    @Test
    fun fallbackExtractsCalendarLocationAndLeavesCasualNotesUntagged() {
        val analyzer = MemoryAnalyzer()

        val meetingPayload = analyzer.fallbackAnalyze(
            "明天下午15:00在软件园三期B区7楼会议室，和法务张姐、陈工过一遍采购合同补充条款。",
        )
        val proposal = requireNotNull(meetingPayload.calendarProposal)
        assertTrue(proposal.startTimeIso.contains("15:00"))
        assertEquals("软件园三期B区7楼会议室", proposal.location)
        assertTrue(meetingPayload.relations.isNotEmpty())
        assertTrue(meetingPayload.relations.none { it.source == it.target })

        val casualPayload = analyzer.fallbackAnalyze("今天阳光很好")
        assertTrue(casualPayload.tags.isEmpty())
    }

    @Test
    fun sanitizeRelationsDropsSelfLoopsAndMergesReversePairs() {
        val cleaned = MemoryAnalyzer.sanitizeRelations(
            listOf(
                RelationTriple(source = "A", target = "A", relation = "loop"),
                RelationTriple(source = "A", target = "B", relation = "引荐"),
                RelationTriple(source = "B", target = "A", relation = "回访"),
            ),
        )
        assertEquals(1, cleaned.size)
        assertTrue(cleaned.single().bidirectional)
    }
}
