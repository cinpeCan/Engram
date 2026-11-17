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
            content = "供应商对接：王总引荐了供应链老李负责新一代柔性护眼屏模组报价，给到 60 天月结账期，样品亮度测通标准。调试后台账号 test_panel，初始密码 Admin@9527，下周交接给研发陈工做烧屏测试。",
            summary = "• 王总引荐老李对接柔性屏报价（60天月结）\n• 样品达标，下周交研发陈工做烧屏测试\n• 已留存调试后台账号凭证（详见原文）",
            tags = listOf("供应链", "凭证备份"),
            createdAtMillis = nowMillis - 2 * 24 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-1_r0", "seed-1", "王总", "老李", "引荐", bidirectional = false),
                RelationTriple("seed-1_r1", "seed-1", "老李", "柔性屏模组", "报价供样", bidirectional = false),
                RelationTriple("seed-1_r2", "seed-1", "陈工", "柔性屏模组", "烧屏测试", bidirectional = false),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        val day2 = MemoryItem(
            id = "seed-2",
            content = "采购推进卡点复盘：目前采购合同走到了法务张姐手里，她提醒老李提供的违约金与质保账期条款还要再对齐；同时财务刘敏需要先确认陈工的柔性屏模组测试验收单才能放行预付款，王总周五前要看最终进展。",
            summary = "• 法务张姐与老李对齐违约金及质保账期\n• 财务刘敏待陈工验收单后放行预付款\n• 王总要求周五前同步最终进展",
            tags = listOf("法务商务", "供应链"),
            createdAtMillis = nowMillis - 24 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-2_r0", "seed-2", "王总", "老李", "引荐供应商", bidirectional = false),
                RelationTriple("seed-2_r1", "seed-2", "老李", "柔性屏模组", "提供样品", bidirectional = false),
                RelationTriple("seed-2_r2", "seed-2", "陈工", "柔性屏模组", "烧屏验收", bidirectional = false),
                RelationTriple("seed-2_r3", "seed-2", "陈工", "财务刘敏", "提交验收单", bidirectional = false),
                RelationTriple("seed-2_r4", "seed-2", "财务刘敏", "采购合同", "放行预付款", bidirectional = false),
                RelationTriple("seed-2_r5", "seed-2", "采购合同", "法务张姐", "卡点审查", bidirectional = false),
                RelationTriple("seed-2_r6", "seed-2", "法务张姐", "老李", "条款对齐", bidirectional = true),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        val day3 = MemoryItem(
            id = "seed-3",
            content = "明天下午15:00在软件园三期B区7楼会议室，和法务张姐、陈工过一遍柔性屏采购合同补充条款并确认签字节点。",
            summary = "• 明日 15:00 · 软件园三期B区7楼会议室\n• 与法务张姐、陈工评审补充条款并确认签字",
            tags = listOf("日程跟进", "法务商务"),
            createdAtMillis = nowMillis - 2 * 60 * 60 * 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = CalendarProposal(
                title = "柔性屏采购合同补充条款评审",
                startTimeIso = tomorrow15,
                durationMinutes = 60,
                location = "软件园三期B区7楼会议室",
            ),
            calendarSaved = false,
            relations = listOf(
                RelationTriple("seed-3_r0", "seed-3", "法务张姐", "采购合同", "过补充条款", bidirectional = false),
                RelationTriple("seed-3_r1", "seed-3", "陈工", "采购合同", "确认签字节点", bidirectional = false),
                RelationTriple("seed-3_r2", "seed-3", "法务张姐", "陈工", "联合评审", bidirectional = true),
            ),
            analysisSource = AnalysisSource.SEED,
            telemetryBadge = "",
        )

        return listOf(day1, day2, day3)
    }
}
