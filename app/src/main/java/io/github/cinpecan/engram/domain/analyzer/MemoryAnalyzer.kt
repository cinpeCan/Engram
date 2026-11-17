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
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json

/**
 * Extracts practical, user-facing metadata (clean title/summary, domain tags, calendar proposal,
 * and sanitized 2D triples) using [EdgeAiEngine] with deterministic Chinese/English heuristics.
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

        val prompt = buildAnalysisPrompt(trimmed, nowMillis)
        val rawModelText = runCatching {
            val terminal = engine.streamGenerate(prompt, maxTokens = 384).last()
            (terminal as? StreamChunk.Completed)?.fullText.orEmpty()
        }.getOrDefault("")

        val parsedFromModel = parseJsonPayload(rawModelText)
        val heuristic = heuristicAnalyze(trimmed, nowMillis)

        return if (parsedFromModel != null) {
            val cleanedRelations = sanitizeRelations(
                if (parsedFromModel.relations.isNotEmpty()) parsedFromModel.relations else heuristic.relations,
            )
            val modelTags = parsedFromModel.tags
                .map { it.trim().removePrefix("#") }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
            val resolvedTags = modelTags.ifEmpty { heuristic.tags }
            parsedFromModel.copy(
                summary = parsedFromModel.summary.ifBlank { heuristic.summary }.take(160),
                tags = resolvedTags,
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
        val calendar = extractCalendarProposal(content, nowMillis)
        val summary = buildSummary(content, calendar, nowMillis)
        val tags = extractTags(content, hasCalendarProposal = calendar != null)
        val relations = sanitizeRelations(extractRelations(content))
        return MemoryAnalysisPayload(
            summary = summary,
            tags = tags,
            calendarProposal = calendar,
            relations = relations,
        )
    }

    private fun buildSummary(
        content: String,
        calendar: CalendarProposal?,
        nowMillis: Long,
    ): String {
        if (calendar != null) {
            val formatted = runCatching {
                val startDt = LocalDateTime.parse(calendar.startTimeIso)
                val baseDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
                val dayDiff = ChronoUnit.DAYS.between(baseDate, startDt.toLocalDate())
                val dateLabel = when (dayDiff) {
                    0L -> "今日"
                    1L -> "明日"
                    2L -> "后日"
                    else -> startDt.format(DateTimeFormatter.ofPattern("MM-dd"))
                }
                val timePart = startDt.format(DateTimeFormatter.ofPattern("HH:mm"))
                val locPart = calendar.location?.takeIf { it.isNotBlank() }?.let { "于$it" }.orEmpty()
                val actionCore = if (calendar.title.length <= 4) "${calendar.title}安排" else calendar.title
                "$dateLabel $timePart$locPart，$actionCore。".take(100)
            }.getOrNull()
            if (!formatted.isNullOrBlank()) {
                return formatted
            }
        }

        val redacted = redactSensitiveDetails(content.trim())
            .replace('\n', ' ')
            .let { LEADING_TOPIC_PREFIX_REGEX.replace(it, "") }
            .trim()
        val clauses = redacted
            .split('。', '！', '？', ';', '；')
            .map { LEADING_TOPIC_PREFIX_REGEX.replace(it.trim(), "").trim('，', ',', ' ') }
            .filter { it.isNotBlank() }

        if (clauses.isEmpty()) {
            return if (redacted.length > 96) redacted.take(96) + "…" else redacted
        }

        val sb = StringBuilder()
        for (clause in clauses) {
            val nextLen = if (sb.isEmpty()) clause.length else sb.length + 1 + clause.length
            if (sb.isNotEmpty() && nextLen > 92) break
            if (sb.isNotEmpty()) sb.append('；')
            sb.append(clause)
            if (sb.length >= 44) break
        }
        val joined = sb.toString().trim()
        return when {
            joined.length > 96 -> joined.take(96) + "…"
            joined.endsWith("。") || joined.endsWith("！") || joined.endsWith("？") -> joined
            else -> "$joined。"
        }
    }

    internal fun redactSensitiveDetails(text: String): String {
        var result = ACCOUNT_SECRET_PAIR_REGEX.replace(text, "已留存相关账号凭证（详见原文）")
        result = PASSWORD_SECRET_REGEX.replace(result, "已留存相关凭证（详见原文）")
        return result
    }

    private fun extractTags(content: String, hasCalendarProposal: Boolean): List<String> {
        val tags = ArrayList<String>(3)
        if (containsAny(content, listOf("吃", "饭", "餐", "午饭", "晚饭", "早饭", "外卖", "咖啡", "奶茶", "聚餐", "火锅", "烧烤"))) {
            tags += "饮食"
        }
        if (containsAny(content, listOf("快递", "取件", "买", "超市", "下单", "退货", "缴费", "房租", "水电", "物业"))) {
            tags += "生活待办"
        }
        if (containsAny(content, listOf("高铁", "飞机", "航班", "打车", "车票", "酒店", "出发", "机场", "车站"))) {
            tags += "出行"
        }
        if (containsAny(content, listOf("吃药", "医院", "看牙", "体检", "复查", "跑步", "健身", "运动"))) {
            tags += "健康"
        }
        if (containsAny(content, listOf("密码", "账号", "token", "key", "口令", "验证码", "wifi", "宽带", "Admin"))) {
            tags += "账号凭证"
        }
        if (containsAny(content, listOf("合同", "法务", "条款", "审批", "账期", "预付款", "付款", "财务", "发票", "报价"))) {
            tags += "商务合同"
        }
        if (containsAny(content, listOf("屏", "模组", "供应商", "测试", "样品", "验收", "研发", "固件", "联调", "方案"))) {
            tags += "项目推进"
        }
        if (hasCalendarProposal && tags.size < 2) {
            tags += "日程提醒"
        }
        return tags.distinct().take(3)
    }

    private fun extractCalendarProposal(content: String, nowMillis: Long): CalendarProposal? {
        val relativeMinutes = parseRelativeDurationMinutes(content)
        val hasExplicitClockOrDate = hasExplicitTimeExpression(content)
        if (relativeMinutes == null && !hasExplicitClockOrDate) return null

        val cleanTitle = extractCleanTitle(content)
        if (relativeMinutes == null && !hasScheduleVerbOrObject(content, cleanTitle)) {
            return null
        }

        val startDateTime = if (relativeMinutes != null) {
            Instant.ofEpochMilli(nowMillis)
                .plusSeconds(relativeMinutes * 60L)
                .atZone(zoneId)
                .toLocalDateTime()
                .withSecond(0)
                .withNano(0)
        } else {
            val baseDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            LocalDateTime.of(resolveDate(content, baseDate), resolveTime(content))
        }

        val location = LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedTitle = cleanTitle.ifBlank { "待办提醒" }.take(24)
        val duration = if (containsAny(content, listOf("吃", "饭", "餐", "外卖", "快递", "取件", "药", "买", "电话"))) {
            30
        } else {
            60
        }

        return CalendarProposal(
            title = resolvedTitle,
            startTimeIso = startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            durationMinutes = duration,
            location = location,
        )
    }

    private fun hasExplicitTimeExpression(content: String): Boolean =
        COLON_TIME_REGEX.containsMatchIn(content) ||
            ZH_TIME_REGEX.containsMatchIn(content) ||
            containsAny(
                content,
                listOf("今天", "今晚", "今早", "明天", "明早", "明晚", "后天", "下周", "周一", "周二", "周三", "周四", "周五", "周六", "周日", "中午", "下午", "晚上"),
            )

    private fun hasScheduleVerbOrObject(content: String, cleanTitle: String): Boolean {
        if (containsAny(
                content,
                listOf("会议", "碰头", "评审", "对齐", "沟通", "面聊", "约见", "提醒", "汇报", "确认", "过一遍", "吃饭", "午饭", "晚饭", "聚餐", "快递", "取件", "体检", "吃药", "出发", "开会"),
            )
        ) {
            return true
        }
        return COLON_TIME_REGEX.containsMatchIn(content) ||
            (ZH_TIME_REGEX.containsMatchIn(content) && cleanTitle.length >= 2)
    }

    internal fun parseRelativeDurationMinutes(content: String): Long? {
        ARABIC_HOURS_AFTER_REGEX.find(content)?.let { m ->
            val hours = m.groupValues[1].toDoubleOrNull() ?: return@let
            return (hours * 60.0).roundToLong().coerceAtLeast(1L)
        }
        ZH_HOURS_AFTER_REGEX.find(content)?.let { m ->
            return when (m.groupValues[1]) {
                "半", "半个" -> 30L
                "一个半" -> 90L
                "一" -> 60L
                "两", "二" -> 120L
                "三" -> 180L
                "四" -> 240L
                "五" -> 300L
                "六" -> 360L
                else -> 60L
            }
        }
        if (content.contains("一刻钟后") || content.contains("一刻钟之后")) {
            return 15L
        }
        ARABIC_MINUTES_AFTER_REGEX.find(content)?.let { m ->
            val mins = m.groupValues[1].toLongOrNull() ?: return@let
            return mins.coerceAtLeast(1L)
        }
        ZH_MINUTES_AFTER_REGEX.find(content)?.let { m ->
            return ZH_NUMBER_MAP[m.groupValues[1]]?.toLong() ?: 15L
        }
        DAYS_AFTER_REGEX.find(content)?.let { m ->
            val raw = m.groupValues[1]
            val days = raw.toLongOrNull() ?: ZH_NUMBER_MAP[raw]?.toLong() ?: return@let
            return (days * 24L * 60L).coerceAtLeast(1L)
        }
        return null
    }

    internal fun extractCleanTitle(content: String): String {
        var cleaned = content.trim()
        cleaned = RELATIVE_STRIP_REGEX.replace(cleaned, " ")
        cleaned = CALENDAR_DATE_STRIP_REGEX.replace(cleaned, " ")
        cleaned = DAY_PART_CLOCK_STRIP_REGEX.replace(cleaned, " ")
        cleaned = LOCATION_REGEX.replace(cleaned, " ")
        cleaned = cleaned
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('，', ',', '。', '；', ';', '、', ':', '：', '-', ' ')
        cleaned = LEADING_MODAL_REGEX.replace(cleaned, "").trim()
        cleaned = cleaned.trim('，', ',', '。', '；', ';', '、', ':', '：', '-', ' ')

        if (cleaned.length > 22) {
            val firstClause = cleaned.split('，', '。', '；', ',', ';').firstOrNull { it.isNotBlank() }?.trim()
            cleaned = if (!firstClause.isNullOrBlank() && firstClause.length <= 22) {
                firstClause
            } else {
                cleaned.take(22).trimEnd()
            }
        }
        return cleaned
    }

    private fun resolveDate(content: String, baseDate: LocalDate): LocalDate = when {
        content.contains("后天") -> baseDate.plusDays(2)
        content.contains("明天") || content.contains("明早") || content.contains("明晚") -> baseDate.plusDays(1)
        content.contains("今天") || content.contains("今晚") || content.contains("今早") -> baseDate
        content.contains("下周一") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        content.contains("下周二") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
        content.contains("下周三") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        content.contains("下周四") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
        content.contains("下周五") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        content.contains("下周六") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        content.contains("下周日") || content.contains("下周天") -> baseDate.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        content.contains("周一") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        content.contains("周二") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY))
        content.contains("周三") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
        content.contains("周四") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY))
        content.contains("周五") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        content.contains("周六") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        content.contains("周日") || content.contains("周天") -> baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        else -> baseDate
    }

    private fun resolveTime(content: String): LocalTime {
        val colonMatch = COLON_TIME_REGEX.find(content)
        if (colonMatch != null) {
            var hour = colonMatch.groupValues[1].toIntOrNull() ?: 10
            val minute = colonMatch.groupValues[2].toIntOrNull() ?: 0
            if ((content.contains("下午") || content.contains("晚上") || content.contains("明晚") || content.contains("今晚")) && hour in 1..11) {
                hour += 12
            }
            return LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }
        val zhTimeMatch = ZH_TIME_REGEX.find(content)
        if (zhTimeMatch != null) {
            var hour = zhTimeMatch.groupValues[1].toIntOrNull() ?: 10
            val half = zhTimeMatch.groupValues.getOrNull(2) == "半"
            if ((content.contains("下午") || content.contains("晚上") || content.contains("明晚") || content.contains("今晚")) && hour in 1..11) {
                hour += 12
            }
            return LocalTime.of(hour.coerceIn(0, 23), if (half) 30 else 0)
        }
        return when {
            content.contains("中午") -> LocalTime.of(12, 0)
            content.contains("早") -> LocalTime.of(8, 30)
            content.contains("晚") -> LocalTime.of(19, 30)
            content.contains("下午") -> LocalTime.of(15, 0)
            else -> LocalTime.of(10, 0)
        }
    }

    private fun extractRelations(content: String): List<RelationTriple> {
        val triples = ArrayList<RelationTriple>()

        EXPLICIT_ARROW_REGEX.findAll(content).forEach { match ->
            val src = match.groupValues[1].trim()
            val rel = match.groupValues[2].trim().ifEmpty { "关联" }
            val tgt = match.groupValues[3].trim()
            if (src.isNotEmpty() && tgt.isNotEmpty()) {
                triples += RelationTriple(source = src, target = tgt, relation = rel)
            }
        }

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

    private fun buildAnalysisPrompt(content: String, nowMillis: Long): String {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val formattedNow = nowLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"))
        return """
            你是印迹（Engram）白盒记忆助手。当前本地时间：$formattedNow。
            请将用户的松散原始记录提炼为凝练完整的摘要（1~2句话，保留关键人物、时间、事项与核心结论，不要写成简短标题，也不要直接暴露明文密码口令；20~100字），并提取场景标签（无合适分类返回 []）、日程提议（若含"1小时后"等相对时间请换算为精确ISO时间）及实体关系三元组，仅输出 JSON_SCHEMA：
            {"summary":"AI提炼的完整核心摘要","tags":["场景标签"],"calendarProposal":{"title":"日程事项","startTimeIso":"YYYY-MM-DDTHH:MM:SS","durationMinutes":30,"location":null},"relations":[{"source":"","target":"","relation":"","bidirectional":false}]}
            原文：$content
        """.trimIndent()
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    companion object {
        private val ARABIC_HOURS_AFTER_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:个)?小时(?:之)?后""")
        private val ZH_HOURS_AFTER_REGEX = Regex("""(一个半|半个?|一|两|二|三|四|五|六)\s*(?:个)?小时(?:之)?后""")
        private val ARABIC_MINUTES_AFTER_REGEX = Regex("""(\d+)\s*分钟?(?:之)?后""")
        private val ZH_MINUTES_AFTER_REGEX = Regex("""(半|一|两|二|三|五|十|十五|二十|三十|四十五)\s*分钟?(?:之)?后""")
        private val DAYS_AFTER_REGEX = Regex("""(\d+|一|两|二|三|四|五|六|七)\s*天(?:之)?后""")

        private val RELATIVE_STRIP_REGEX = Regex(
            """(?:\d+(?:\.\d+)?|一个半|半个?|一|两|二|三|四|五|六|十|十五|二十|三十|四十五|七)\s*(?:(?:个)?小时|分钟?|天)(?:之)?后|一刻钟(?:之)?后""",
        )
        private val CALENDAR_DATE_STRIP_REGEX = Regex(
            """(?:大后|今|明|后)(?:天|早|晚|午)|下?周[一二三四五六日天]""",
        )
        private val DAY_PART_CLOCK_STRIP_REGEX = Regex(
            """(?:早上|上午|中午|下午|晚上|傍晚)?\s*\d{1,2}\s*(?::|：|点)\s*(?:\d{1,2}|半)?|早上|上午|中午|下午|晚上|傍晚""",
        )
        private val LEADING_MODAL_REGEX = Regex("""^(?:记得|提醒我|别忘了|待会儿?|等会儿?|顺便|准备|去(?=吃饭|拿|取|买|开会|见))""")
        private val LEADING_TOPIC_PREFIX_REGEX = Regex("""^(?:供应商对接|采购推进卡点复盘|项目同步|会议纪要|备忘|记录一下|随手记)[:：]\s*""")
        private val ACCOUNT_SECRET_PAIR_REGEX = Regex(
            """(?:调试后台|测试后台|后台|登录)?账号\s*[:：]?\s*[A-Za-z0-9_.-]{3,}(?:\s*[，,、\s]+\s*(?:初始)?(?:密码|口令|token|key)\s*[:：]?\s*[^\s，。,；;]+)?""",
            RegexOption.IGNORE_CASE,
        )
        private val PASSWORD_SECRET_REGEX = Regex(
            """(?:初始)?(?:密码|口令|验证码)\s*[:：]?\s*[A-Za-z0-9@#$%^&*_.-]{4,}""",
            RegexOption.IGNORE_CASE,
        )

        private val ZH_NUMBER_MAP = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "十" to 10, "十五" to 15, "二十" to 20, "三十" to 30, "四十五" to 45,
        )

        private val COLON_TIME_REGEX = Regex("""(\d{1,2})[:：](\d{2})""")
        private val ZH_TIME_REGEX = Regex("""(\d{1,2})\s*点(半)?""")
        private val LOCATION_REGEX = Regex("""(?:在|于)([\u4E00-\u9FFFA-Za-z0-9]{2,20}(?:会议室|大厦|园区|中心|楼))""")
        private val EXPLICIT_ARROW_REGEX = Regex("""([\u4E00-\u9FFFA-Za-z0-9_]{1,16})\s*[-—]{1,2}\s*\[?([^\]\->\n]{1,12})\]?\s*[-—]*>\s*([\u4E00-\u9FFFA-Za-z0-9_]{1,16})""")

        private val KNOWN_ENTITY_PATTERNS = listOf(
            Regex("""[王张李刘陈赵周吴孙郑钱孙][总姐哥工敏涛杰明]"""),
            Regex("""老[王张李陈赵刘周]"""),
            Regex("""(?:法务|财务|研发|采购|供应链|产品|技术)(?:[\u4E00-\u9FFF]{1,3})?"""),
            Regex("""[\u4E00-\u9FFF]{2,6}(?:合同|模组|账号|样品|条款|预付款|验收单|方案|系统)"""),
        )

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
