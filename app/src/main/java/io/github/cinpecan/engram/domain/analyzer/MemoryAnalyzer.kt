package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.RelationTriple
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.StreamChunk
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json

/**
 * Extracts White-Box Memory metadata (summary, tags, calendar proposal, and sanitized 2D triples)
 * using the on-device [EdgeAiEngine] with deterministic heuristic normalization fallback.
 */
class MemoryAnalyzer(
    private val engine: EdgeAiEngine,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun analyze(
        content: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MemoryAnalysisPayload {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return MemoryAnalysisPayload(summary = "", tags = emptyList())
        }

        val prompt = buildAnalysisPrompt(trimmed)
        val rawModelText = runCatching {
            val terminal = engine.streamGenerate(prompt, maxTokens = 384).last()
            (terminal as? StreamChunk.Completed)?.fullText.orEmpty()
        }.getOrDefault("")

        val parsedFromModel = parseJsonPayload(rawModelText)
        val heuristic = heuristicAnalyze(trimmed, nowMillis)

        // Merge model JSON output with heuristic safety nets so Deponder invariants and rich cards always hold.
        return if (parsedFromModel != null && isSubstantiveModelOutput(parsedFromModel)) {
            val cleanedRelations = sanitizeRelations(
                if (parsedFromModel.relations.isNotEmpty()) parsedFromModel.relations else heuristic.relations,
            )
            val mergedTags = (parsedFromModel.tags + heuristic.tags)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
            parsedFromModel.copy(
                summary = parsedFromModel.summary.ifBlank { heuristic.summary }.take(160),
                tags = mergedTags,
                calendarProposal = parsedFromModel.calendarProposal ?: heuristic.calendarProposal,
                relations = cleanedRelations,
            )
        } else {
            heuristic.copy(relations = sanitizeRelations(heuristic.relations))
        }
    }

    internal fun parseJsonPayload(rawText: String): MemoryAnalysisPayload? {
        if (rawText.isBlank()) return null
        val cleaned = rawText
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = cleaned.substring(start, end + 1)
        return runCatching { json.decodeFromString<MemoryAnalysisPayload>(candidate) }.getOrNull()
    }

    internal fun heuristicAnalyze(content: String, nowMillis: Long): MemoryAnalysisPayload {
        val summary = buildSummary(content)
        val tags = extractTags(content)
        val calendar = extractCalendarProposal(content, nowMillis)
        val relations = sanitizeRelations(extractRelations(content))
        return MemoryAnalysisPayload(
            summary = summary,
            tags = tags,
            calendarProposal = calendar,
            relations = relations,
        )
    }

    private fun isSubstantiveModelOutput(payload: MemoryAnalysisPayload): Boolean {
        val hasRealSummary = payload.summary.isNotBlank() && payload.summary != "端侧结构化解析已完成"
        return hasRealSummary || payload.relations.isNotEmpty() || payload.calendarProposal != null
    }

    private fun buildSummary(content: String): String {
        val firstClause = content
            .replace('\n', ' ')
            .split('。', '！', '？', ';', '；')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: content.trim()
        return if (firstClause.length > 52) firstClause.take(52) + "…" else firstClause
    }

    private fun extractTags(content: String): List<String> {
        val tags = ArrayList<String>(3)
        if (containsAny(content, listOf("密码", "账号", "token", "key", "口令", "Admin"))) {
            tags += "凭证备份"
        }
        if (containsAny(content, listOf("合同", "法务", "条款", "审批", "账期", "预付款", "财务"))) {
            tags += "法务商务"
        }
        if (containsAny(content, listOf("屏", "模组", "供应商", "报价", "测试", "样品", "验收"))) {
            tags += "供应链"
        }
        if (containsAny(content, listOf("会议", "明天", "后天", "下周", "点", ":00", "讨论", "沟通", "评审"))) {
            tags += "日程跟进"
        }
        if (tags.isEmpty()) {
            tags += "闪念笔记"
        }
        if (tags.size == 1) {
            tags += "白盒记忆"
        }
        return tags.distinct().take(3)
    }

    private fun extractCalendarProposal(content: String, nowMillis: Long): CalendarProposal? {
        val hasTimeHint = containsAny(
            content,
            listOf("明天", "后天", "今天", "下周", "周一", "周二", "周三", "周四", "周五", "点", ":00", ":30"),
        )
        val hasActionHint = containsAny(
            content,
            listOf("会议", "碰头", "评审", "对齐", "沟通", "面聊", "约见", "提醒", "汇报", "确认", "过一遍"),
        )
        if (!hasTimeHint || !hasActionHint) return null

        val baseDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val targetDate = resolveDate(content, baseDate)
        val targetTime = resolveTime(content)
        val startDateTime = LocalDateTime.of(targetDate, targetTime)

        val locationMatch = LOCATION_REGEX.find(content)
        val location = locationMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        val title = when {
            content.contains("合同") -> "合同条款确认沟通"
            content.contains("评审") -> "项目方案评审会"
            content.contains("对接") -> "业务对接沟通"
            else -> buildSummary(content).take(22)
        }

        return CalendarProposal(
            title = title,
            startTimeIso = startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            durationMinutes = 60,
            location = location,
        )
    }

    private fun resolveDate(content: String, baseDate: LocalDate): LocalDate = when {
        content.contains("后天") -> baseDate.plusDays(2)
        content.contains("明天") -> baseDate.plusDays(1)
        content.contains("今天") -> baseDate
        content.contains("下周一") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        content.contains("下周二") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
        content.contains("下周三") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        content.contains("下周四") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
        content.contains("下周五") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        content.contains("周一") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        content.contains("周二") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY))
        content.contains("周三") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
        content.contains("周四") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY))
        content.contains("周五") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        else -> baseDate.plusDays(1)
    }

    private fun resolveTime(content: String): LocalTime {
        val colonMatch = COLON_TIME_REGEX.find(content)
        if (colonMatch != null) {
            var hour = colonMatch.groupValues[1].toIntOrNull() ?: 10
            val minute = colonMatch.groupValues[2].toIntOrNull() ?: 0
            if (content.contains("下午") && hour in 1..11) hour += 12
            if (content.contains("晚上") && hour in 1..11) hour += 12
            return LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }
        val zhTimeMatch = ZH_TIME_REGEX.find(content)
        if (zhTimeMatch != null) {
            var hour = zhTimeMatch.groupValues[1].toIntOrNull() ?: 10
            val half = zhTimeMatch.groupValues.getOrNull(2) == "半"
            if (content.contains("下午") && hour in 1..11) hour += 12
            if (content.contains("晚上") && hour in 1..11) hour += 12
            return LocalTime.of(hour.coerceIn(0, 23), if (half) 30 else 0)
        }
        return if (content.contains("下午")) LocalTime.of(15, 0) else LocalTime.of(10, 0)
    }

    private fun extractRelations(content: String): List<RelationTriple> {
        val triples = ArrayList<RelationTriple>()

        // 1. Explicit arrow notation: A -> B (rel) or A -[rel]-> B
        EXPLICIT_ARROW_REGEX.findAll(content).forEach { match ->
            val src = match.groupValues[1].trim()
            val rel = match.groupValues[2].trim().ifEmpty { "关联" }
            val tgt = match.groupValues[3].trim()
            if (src.isNotEmpty() && tgt.isNotEmpty()) {
                triples += RelationTriple(source = src, target = tgt, relation = rel)
            }
        }

        // 2. Domain entity detection for natural Chinese project / relationship notes
        val entities = KNOWN_ENTITY_PATTERNS
            .flatMap { regex -> regex.findAll(content).map { it.value.trim() } }
            .distinct()
            .take(8)

        if (triples.isEmpty() && entities.size >= 2) {
            val clauses = content.split('，', '。', '；', ';', '\n')
            for (clause in clauses) {
                val present = entities.filter { clause.contains(it) }
                if (present.size >= 2) {
                    val relationLabel = inferRelationVerb(clause)
                    for (i in 0 until present.size - 1) {
                        triples += RelationTriple(
                            source = present[i],
                            target = present[i + 1],
                            relation = relationLabel,
                            bidirectional = clause.contains("对齐") || clause.contains("配合") || clause.contains("协同"),
                        )
                    }
                }
            }
            if (triples.isEmpty()) {
                for (i in 0 until entities.size - 1) {
                    triples += RelationTriple(
                        source = entities[i],
                        target = entities[i + 1],
                        relation = "关联",
                    )
                }
            }
        }
        return triples
    }

    private fun inferRelationVerb(clause: String): String = when {
        clause.contains("引荐") || clause.contains("介绍") -> "引荐"
        clause.contains("负责") || clause.contains("主责") -> "负责"
        clause.contains("卡在") || clause.contains("停在") -> "卡点审批"
        clause.contains("提醒") || clause.contains("审核") -> "法务审查"
        clause.contains("确认") || clause.contains("验收") -> "待确认"
        clause.contains("放行") || clause.contains("付款") || clause.contains("账期") -> "付款前置"
        clause.contains("交接") || clause.contains("测试") -> "交付测试"
        clause.contains("对齐") -> "条款对齐"
        else -> "协同推进"
    }

    private fun buildAnalysisPrompt(content: String): String = """
        请作为 Engram 端侧白盒记忆解析器，从文本中提取 JSON_SCHEMA，严格输出 JSON：
        {"summary":"不超过40字摘要","tags":["标签1","标签2"],"calendarProposal":{"title":"","startTimeIso":"YYYY-MM-DDTHH:MM:SS","durationMinutes":60,"location":null},"relations":[{"source":"","target":"","relation":"","bidirectional":false}]}
        原文：$content
    """.trimIndent()

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    companion object {
        private val COLON_TIME_REGEX = Regex("""(\d{1,2}):(\d{2})""")
        private val ZH_TIME_REGEX = Regex("""(\d{1,2})\s*点(半)?""")
        private val LOCATION_REGEX = Regex("""(?:在|于)([\u4E00-\u9FFFA-Za-z0-9]{2,20}(?:会议室|大厦|园区|中心|楼))""")
        private val EXPLICIT_ARROW_REGEX = Regex("""([\u4E00-\u9FFFA-Za-z0-9_]{1,16})\s*[-—]{1,2}\s*\[?([^\]\->\n]{1,12})\]?\s*[-—]*>\s*([\u4E00-\u9FFFA-Za-z0-9_]{1,16})""")

        private val KNOWN_ENTITY_PATTERNS = listOf(
            Regex("""[王张李刘陈赵周吴孙郑钱孙][总姐哥工敏涛杰明]"""),
            Regex("""老[王张李陈赵刘周]"""),
            Regex("""(?:法务|财务|研发|采购|供应链|产品|技术)(?:[\u4E00-\u9FFF]{1,3})?"""),
            Regex("""[\u4E00-\u9FFF]{2,6}(?:合同|模组|账号|样品|条款|预付款|验收单|方案|系统)"""),
        )

        /**
         * Enforces Deponder 0.5.0 invariants:
         * 1. Zero blank endpoints or self-loops (`source != target`).
         * 2. At most one edge per unordered pair `(min(a,b), max(a,b))`, merging opposing pairs into `bidirectional = true`.
         * 3. Bounded to 15 links / 12 entities so 2D physics layout remains legible on mobile viewports.
         */
        fun sanitizeRelations(raw: List<RelationTriple>): List<RelationTriple> {
            val byUndirectedKey = LinkedHashMap<Pair<String, String>, RelationTriple>()
            val uniqueNodes = LinkedHashSet<String>()

            for (item in raw) {
                val src = item.source.trim().take(24)
                val tgt = item.target.trim().take(24)
                val rel = item.relation.trim().ifEmpty { "关联" }.take(16)
                if (src.isEmpty() || tgt.isEmpty() || src == tgt) continue

                val key = if (src <= tgt) src to tgt else tgt to src
                val existing = byUndirectedKey[key]
                if (existing == null) {
                    if (uniqueNodes.size >= 12 && (!uniqueNodes.contains(src) || !uniqueNodes.contains(tgt))) {
                        continue
                    }
                    uniqueNodes.add(src)
                    uniqueNodes.add(tgt)
                    byUndirectedKey[key] = item.copy(
                        source = src,
                        target = tgt,
                        relation = rel,
                    )
                } else if (existing.source != src && !existing.bidirectional) {
                    val combinedLabel = if (existing.relation == rel) {
                        rel
                    } else {
                        "${existing.relation}/${rel}".take(18)
                    }
                    byUndirectedKey[key] = existing.copy(
                        relation = combinedLabel,
                        bidirectional = true,
                    )
                }
                if (byUndirectedKey.size >= 15) break
            }
            return byUndirectedKey.values.toList()
        }
    }
}
