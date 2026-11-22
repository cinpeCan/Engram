package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.RelationTriple
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Deterministic rule-based extractor for offline summaries, topic tags, temporal schedules,
 * venue locations, and entity-relation triples across English, CJK, and romanized Pinyin notes.
 */
internal class HeuristicMemoryExtractor(
    private val zoneId: ZoneId,
) {
    fun fallbackAnalyze(
        content: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MemoryAnalysisPayload {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return MemoryAnalysisPayload(summary = "", tags = emptyList())
        }
        val calendar = extractCalendarProposal(trimmed, nowMillis)
        val summary = buildSummary(trimmed, calendar, nowMillis)
        val tags = extractTags(trimmed, hasCalendarProposal = calendar != null)
        val relations = MemoryAnalyzer.sanitizeRelations(extractRelations(trimmed))
        return MemoryAnalysisPayload(
            summary = summary,
            tags = tags,
            calendarProposal = calendar,
            relations = relations,
        )
    }

    fun extractLocation(content: String): String? {
        val structured = STRUCTURED_LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)
        val venue = structured
            ?: VENUE_LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)
            ?: CJK_ROOM_VENUE_REGEX.find(content)?.groupValues?.getOrNull(1)
            ?: CJK_BUILDING_LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)
        return venue?.trim()?.trimEnd(',', '.', ';', ':')?.takeIf { it.isNotEmpty() }
    }

    fun parseRelativeDurationMinutes(content: String): Long? {
        RELATIVE_DURATION_REGEX.find(content)?.let { match ->
            val rawAmount = match.groupValues[1].lowercase(Locale.US)
            val amount = rawAmount.toDoubleOrNull() ?: WORD_NUMBERS[rawAmount] ?: return@let
            val unit = match.groupValues[2].lowercase(Locale.US)
            val minutes = when {
                unit.startsWith("h") -> amount * 60.0
                unit.startsWith("d") -> amount * 24.0 * 60.0
                else -> amount
            }
            return minutes.roundToLong().coerceAtLeast(1L)
        }
        CJK_RELATIVE_DURATION_REGEX.find(content)?.let { match ->
            val rawAmount = match.groupValues[1].trim()
            val amount = rawAmount.toDoubleOrNull() ?: parseCjkNumber(rawAmount) ?: return@let
            val unit = match.groupValues[2]
            val minutes = when (unit) {
                "\u5C0F\u65F6" -> amount * 60.0
                "\u5929" -> amount * 24.0 * 60.0
                else -> amount
            }
            return minutes.roundToLong().coerceAtLeast(1L)
        }
        return null
    }

    fun extractCleanTitle(content: String): String {
        var cleaned = content.trim()
        cleaned = RELATIVE_DURATION_REGEX.replace(cleaned, " ")
        cleaned = CJK_RELATIVE_DURATION_REGEX.replace(cleaned, " ")
        cleaned = STRUCTURED_LOCATION_REGEX.replace(cleaned, " ")
        cleaned = VENUE_LOCATION_REGEX.replace(cleaned, " ")
        cleaned = CLOCK_STRIP_REGEX.replace(cleaned, " ")
        cleaned = CJK_CLOCK_STRIP_REGEX.replace(cleaned, " ")
        cleaned = DATE_STRIP_REGEX.replace(cleaned, " ")
        cleaned = CJK_DATE_STRIP_REGEX.replace(cleaned, " ")
        val withoutCjkLocation = CJK_LOCATION_STRIP_REGEX.replace(cleaned, " ").trim()
        if (withoutCjkLocation.isNotEmpty()) {
            cleaned = withoutCjkLocation
        }
        cleaned = cleaned.replace(MemoryAnalyzer.WHITESPACE_REGEX, " ").trim()

        val colonIdx = cleaned.indexOf(':')
        if (colonIdx >= 0 && cleaned.substring(colonIdx + 1).isNotBlank()) {
            val head = cleaned.substring(0, colonIdx).trim(' ', ',', '.', ';', '-')
            if (head.isEmpty() || head.split(' ').size <= 3) {
                cleaned = cleaned.substring(colonIdx + 1)
            }
        }
        val trimChars = charArrayOf(' ', ',', '.', ';', ':', '-', '\uFF0C', '\u3002', '\uFF1B', '\uFF1A')
        cleaned = cleaned.trim(*trimChars)
        cleaned = DANGLING_PREPOSITION_REGEX.replace(cleaned, "").trim(*trimChars)
        cleaned = LEADING_MODAL_REGEX.replace(cleaned, "").trim()
        cleaned = CJK_LEADING_REMINDER_REGEX.replace(cleaned, "").trim()

        cleaned = TITLE_CLAUSE_BREAK_REGEX.split(cleaned).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (cleaned.length > MemoryAnalyzer.MAX_TITLE_CHARS) {
            val withIdx = cleaned.indexOf(" with ")
            if (withIdx > 0) cleaned = cleaned.substring(0, withIdx)
        }
        cleaned = MemoryAnalyzer.clipAtWord(cleaned.trim(*trimChars), MemoryAnalyzer.MAX_TITLE_CHARS)
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    fun hasTemporalScheduleSignal(content: String): Boolean {
        if (findClockTime(content) != null) return true
        if (parseRelativeDurationMinutes(content) != null) return true
        if (EXPLICIT_CALENDAR_DATE_REGEX.containsMatchIn(content)) return true
        val hasDateAnchor = DATE_KEYWORD_REGEX.containsMatchIn(content) ||
            DAY_PART_REGEX.containsMatchIn(content) ||
            CJK_DATE_KEYWORD_REGEX.containsMatchIn(content)
        val hasEventCue = EVENT_KEYWORD_REGEX.containsMatchIn(content) ||
            CJK_EVENT_KEYWORD_REGEX.containsMatchIn(content)
        return hasDateAnchor && hasEventCue
    }

    fun hasClockTime(content: String): Boolean = findClockTime(content) != null

    fun hasExplicitYear(content: String): Boolean = EXPLICIT_YEAR_REGEX.containsMatchIn(content)

    fun hasExplicitNumericDate(content: String): Boolean =
        ISO_CALENDAR_DATE_REGEX.containsMatchIn(content) ||
            SLASH_MONTH_DAY_REGEX.containsMatchIn(content) ||
            CJK_MONTH_DAY_REGEX.containsMatchIn(content) ||
            EN_MONTH_DAY_REGEX.containsMatchIn(content)

    fun hasRelativeDateShift(content: String, hasExplicitNumericDate: Boolean): Boolean =
        parseRelativeDurationMinutes(content) != null ||
            (!hasExplicitNumericDate && (
                RELATIVE_ANCHOR_RECONCILE_REGEX.containsMatchIn(content) ||
                    CJK_DATE_KEYWORD_REGEX.containsMatchIn(content)
                ))

    fun findEntityNames(text: String): List<String> = findEntities(text).map { it.name }

    fun isGroundedPinyinEntity(cjkEntity: String, contentLower: String): Boolean =
        PINYIN_GROUNDED_ENTITIES[cjkEntity]?.containsMatchIn(contentLower) == true

    fun isKnownPinyinDrinkLabel(target: String): Boolean = target in PINYIN_DRINK_GLOSSARY.values

    fun hasPinyinDrinkClause(contentLower: String): Boolean =
        PINYIN_DRINK_CLAUSE_REGEX.containsMatchIn(contentLower)

    fun isKnownSceneTag(tagLower: String): Boolean = tagLower in KNOWN_SCENE_TAG_REGEXES

    fun matchSceneTag(tagLower: String, rawContent: String, hasSchedule: Boolean): Boolean? {
        if (tagLower in SCHEDULE_SCENE_TAGS) {
            return hasSchedule || hasTemporalScheduleSignal(rawContent)
        }
        if (KNOWN_SCENE_TAG_REGEXES[tagLower]?.containsMatchIn(rawContent) == true) {
            return true
        }
        return null
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
                val isCjk = EdgePromptTemplates.containsCjk(content)
                if (isCjk) {
                    val dateLabel = when (dayDiff) {
                        0L -> "\u4ECA\u5929"
                        1L -> "\u660E\u5929"
                        2L -> "\u540E\u5929"
                        else -> startDt.format(DateTimeFormatter.ofPattern("MM/dd"))
                    }
                    val timePart = startDt.format(DateTimeFormatter.ofPattern("HH:mm"))
                    val title = MemoryAnalyzer.clipAtWord(calendar.title, MemoryAnalyzer.MAX_CJK_BULLET_CHARS)
                    val location = calendar.location?.takeIf { it.isNotBlank() }
                    if (location == null && title.length <= 16) {
                        "$dateLabel $timePart \u00B7 $title"
                    } else {
                        val locPart = location?.let { " \u00B7 $it" }.orEmpty()
                        "\u2022 $dateLabel $timePart$locPart\n\u2022 $title"
                    }
                } else {
                    val dateLabel = when (dayDiff) {
                        0L -> "Today"
                        1L -> "Tomorrow"
                        2L -> "Day after tomorrow"
                        else -> startDt.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
                    }
                    val timePart = startDt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                    val title = MemoryAnalyzer.clipAtWord(calendar.title, MemoryAnalyzer.MAX_BULLET_CHARS)
                    val location = calendar.location?.takeIf { it.isNotBlank() }
                    if (location == null && title.length <= 32) {
                        "$dateLabel $timePart \u00B7 $title"
                    } else {
                        val locPart = location?.let { " \u00B7 $it" }.orEmpty()
                        "\u2022 $dateLabel $timePart$locPart\n\u2022 $title"
                    }
                }
            }.getOrNull()
            if (!formatted.isNullOrBlank()) {
                return formatted.take(MemoryAnalyzer.MAX_SUMMARY_CHARS)
            }
        }

        val redacted = MemoryAnalyzer.redactSensitiveDetails(content.trim())
            .replace('\n', ' ')
            .let { LEADING_TOPIC_PREFIX_REGEX.replace(it, "") }
            .let { text ->
                if (EdgePromptTemplates.containsCjk(text)) {
                    text.let { CJK_IME_PREFIX_STRAY_DIGIT_REGEX.replace(it, "") }
                        .let { CJK_IME_INFIX_STRAY_DIGIT_REGEX.replace(it, "$1$2") }
                } else {
                    text
                }
            }
            .trim()
        val resolvedRaw = if (EdgePromptTemplates.isRomanizedPinyin(redacted)) {
            buildPinyinFallbackSummary(redacted) ?: redacted
        } else {
            redacted
        }
        val normalized = MemoryAnalyzer.normalizeBulletSummary(resolvedRaw)
        if (normalized.isBlank()) {
            return MemoryAnalyzer.clipAtWord(resolvedRaw, MemoryAnalyzer.MAX_SINGLE_POINT_CHARS)
        }
        val lines = normalized.lines().take(3)
        return if (lines.size == 1) {
            lines[0].removePrefix("\u2022 ").trim()
        } else {
            lines.joinToString("\n")
        }
    }

    private fun buildPinyinFallbackSummary(content: String): String? {
        val points = ArrayList<String>(3)
        val hasWo = PINYIN_WO_REGEX.containsMatchIn(content)
        PINYIN_FOOD_CLAUSE_REGEX.find(content)?.let { match ->
            val foodKey = match.groupValues[2].lowercase(Locale.US)
            val foodLabel = PINYIN_FOOD_GLOSSARY[foodKey] ?: foodKey
            val verbPrefix = if (hasWo) "\u6211\u8981\u5403" else "\u60F3\u5403"
            points += "$verbPrefix$foodLabel"
        }
        PINYIN_DRINK_CLAUSE_REGEX.find(content)?.let { match ->
            val drinkKey = match.groupValues[1]
                .lowercase(Locale.US)
                .replace(MemoryAnalyzer.WHITESPACE_REGEX, "")
            val drinkLabel = PINYIN_DRINK_GLOSSARY[drinkKey] ?: drinkKey
            val verbPrefix = if (hasWo) "\u6211\u8981\u559D" else "\u60F3\u559D"
            points += "$verbPrefix$drinkLabel"
        }
        PINYIN_PLAY_CLAUSE_REGEX.find(content)?.let { match ->
            val companions = match.groupValues[1]
                .split(PINYIN_COMPANION_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
            if (companions.isNotEmpty()) {
                val joined = companions.joinToString("\u3001")
                val action = when (match.groupValues[2].lowercase(Locale.US)) {
                    "chi" -> "\u4E00\u8D77\u5403"
                    "qu" -> "\u4E00\u8D77\u53BB"
                    else -> "\u4E00\u8D77\u73A9"
                }
                val wantPrefix = if (PINYIN_XIANG_REGEX.containsMatchIn(content)) "\u60F3\u548C " else "\u548C "
                points += "$wantPrefix$joined $action"
            }
        }
        return if (points.isNotEmpty()) points.joinToString("\n") else null
    }

    private fun extractTags(content: String, hasCalendarProposal: Boolean): List<String> {
        val tags = ArrayList<String>(3)
        if (EdgePromptTemplates.isChineseIntent(content)) {
            for ((tag, keywords) in CJK_TAG_KEYWORDS) {
                if (keywords.containsMatchIn(content)) {
                    tags += tag
                }
            }
            if (hasCalendarProposal && tags.size < 2) {
                tags += CJK_TAG_SCHEDULE
            }
        } else {
            for ((tag, keywords) in TAG_KEYWORDS) {
                if (keywords.containsMatchIn(content)) {
                    tags += tag
                }
            }
            if (hasCalendarProposal && tags.size < 2) {
                tags += TAG_FOLLOW_UP
            }
        }
        return tags.distinct().take(3)
    }

    private fun extractCalendarProposal(content: String, nowMillis: Long): CalendarProposal? {
        val relativeMinutes = parseRelativeDurationMinutes(content)
        val hasClock = findClockTime(content) != null
        val hasExplicitDate = DATE_KEYWORD_REGEX.containsMatchIn(content) ||
            DAY_PART_REGEX.containsMatchIn(content) ||
            CJK_DATE_KEYWORD_REGEX.containsMatchIn(content) ||
            EXPLICIT_DATE_ONLY_REGEX.containsMatchIn(content)
        if (relativeMinutes == null && !hasClock && !hasExplicitDate) return null
        if (relativeMinutes == null && !hasClock &&
            !EVENT_KEYWORD_REGEX.containsMatchIn(content) &&
            !CJK_EVENT_KEYWORD_REGEX.containsMatchIn(content)
        ) {
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

        val location = extractLocation(content)
        val defaultTitle = if (EdgePromptTemplates.containsCjk(content)) "\u65E5\u7A0B\u63D0\u9192" else "Reminder"
        val resolvedTitle = extractCleanTitle(content).ifBlank { defaultTitle }
        val duration = if (
            SHORT_EVENT_REGEX.containsMatchIn(content) ||
            CJK_SHORT_EVENT_REGEX.containsMatchIn(content)
        ) {
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

    private fun parseCjkNumber(raw: String): Double? {
        if (raw == "\u534A") return 0.5
        val digits = mapOf(
            '\u4E00' to 1, '\u4E8C' to 2, '\u4E24' to 2, '\u4E09' to 3, '\u56DB' to 4,
            '\u4E94' to 5, '\u516D' to 6, '\u4E03' to 7, '\u516B' to 8, '\u4E5D' to 9,
        )
        val tenChar = '\u5341'
        val tenIdx = raw.indexOf(tenChar)
        if (tenIdx < 0) {
            return if (raw.length == 1) digits[raw[0]]?.toDouble() else null
        }
        val tens = if (tenIdx == 0) 1 else (digits[raw[tenIdx - 1]] ?: return null)
        val ones = if (tenIdx + 1 < raw.length) (digits[raw[tenIdx + 1]] ?: return null) else 0
        return (tens * 10 + ones).toDouble()
    }

    private fun resolveDate(content: String, baseDate: LocalDate): LocalDate {
        ISO_CALENDAR_DATE_REGEX.find(content)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mon = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && mon != null && d != null) {
                runCatching { LocalDate.of(y, mon, d) }.getOrNull()?.let { return it }
            }
        }
        SLASH_MONTH_DAY_REGEX.find(content)?.let { m ->
            val mon = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            val rawYear = m.groupValues[3].toIntOrNull()
            val y = when {
                rawYear == null -> baseDate.year
                rawYear < 100 -> 2000 + rawYear
                else -> rawYear
            }
            if (mon != null && d != null) {
                runCatching { LocalDate.of(y, mon, d) }.getOrNull()?.let { return it }
            }
        }
        CJK_MONTH_DAY_REGEX.find(content)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: baseDate.year
            val mon = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (mon != null && d != null) {
                runCatching { LocalDate.of(y, mon, d) }.getOrNull()?.let { return it }
            }
        }
        EN_MONTH_DAY_REGEX.find(content)?.let { m ->
            val mon = monthOf(m.groupValues[1])
            val d = m.groupValues[2].toIntOrNull()
            if (mon != null && d != null) {
                runCatching { LocalDate.of(baseDate.year, mon, d) }.getOrNull()?.let { return it }
            }
        }

        val lower = content.lowercase(Locale.US)
        if (CJK_THREE_DAYS_LATER_REGEX.containsMatchIn(content)) return baseDate.plusDays(3)
        if (DAY_AFTER_TOMORROW_REGEX.containsMatchIn(lower) || CJK_DAY_AFTER_TOMORROW_REGEX.containsMatchIn(content)) {
            return baseDate.plusDays(2)
        }
        if (EN_TOMORROW_REGEX.containsMatchIn(lower) || CJK_TOMORROW_REGEX.containsMatchIn(content)) {
            return baseDate.plusDays(1)
        }
        if (EN_TODAY_REGEX.containsMatchIn(lower) || CJK_TODAY_REGEX.containsMatchIn(content)) {
            return baseDate
        }
        CJK_NEXT_WEEKDAY_REGEX.find(content)?.let { match ->
            val dow = cjkWeekdayOf(match.groupValues[1])
            return baseDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays((dow.value - 1).toLong())
        }
        CJK_THIS_WEEKDAY_REGEX.find(content)?.let { match ->
            val dow = cjkWeekdayOf(match.groupValues[1])
            return baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays((dow.value - 1).toLong())
        }
        NEXT_WEEKDAY_REGEX.find(lower)?.let { match ->
            return baseDate.with(TemporalAdjusters.next(weekdayOf(match.groupValues[1])))
        }
        WEEKDAY_REGEX.find(lower)?.let { match ->
            return baseDate.with(TemporalAdjusters.nextOrSame(weekdayOf(match.groupValues[1])))
        }
        CJK_WEEKDAY_REGEX.find(content)?.let { match ->
            return baseDate.with(TemporalAdjusters.nextOrSame(cjkWeekdayOf(match.groupValues[1])))
        }
        return baseDate
    }

    private fun weekdayOf(name: String): DayOfWeek = when (name.take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun cjkWeekdayOf(charStr: String): DayOfWeek = when (charStr) {
        "\u4E00" -> DayOfWeek.MONDAY
        "\u4E8C" -> DayOfWeek.TUESDAY
        "\u4E09" -> DayOfWeek.WEDNESDAY
        "\u56DB" -> DayOfWeek.THURSDAY
        "\u4E94" -> DayOfWeek.FRIDAY
        "\u516D" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun monthOf(name: String): Int? = when (name.lowercase(Locale.US).take(3)) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> null
    }

    private data class ClockTime(val hour: Int, val minute: Int, val meridiem: String?)

    private fun findClockTime(content: String): ClockTime? {
        COLON_TIME_REGEX.find(content)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: return@let
            if (hour > 23 || minute > 59) return@let
            return ClockTime(hour, minute, m.groupValues[3].takeIf { it.isNotEmpty() })
        }
        MERIDIEM_TIME_REGEX.find(content)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            if (hour !in 1..12) return@let
            return ClockTime(hour, 0, m.groupValues[2])
        }
        BARE_AT_TIME_REGEX.find(content)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            if (hour !in 1..12) return@let
            return ClockTime(hour, 0, null)
        }
        CJK_NUMERIC_CLOCK_REGEX.find(content)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            if (hour > 23) return@let
            val minute = when {
                m.groupValues[3] == "\u534A" -> 30
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toIntOrNull() ?: return@let
                else -> 0
            }
            if (minute > 59) return@let
            return ClockTime(hour, minute, null)
        }
        CJK_WORD_CLOCK_REGEX.find(content)?.let { m ->
            val hour = parseCjkNumber(m.groupValues[1])?.toInt() ?: return@let
            if (hour > 23) return@let
            val rawMinute = m.groupValues[2]
            val minute = when {
                m.groupValues[3] == "\u534A" -> 30
                rawMinute.isNotEmpty() -> rawMinute.toIntOrNull() ?: parseCjkNumber(rawMinute)?.toInt() ?: return@let
                else -> 0
            }
            if (minute !in 0..59) return@let
            return ClockTime(hour, minute, null)
        }
        return null
    }

    private fun resolveTime(content: String): LocalTime {
        val lower = content.lowercase(Locale.US)
        val clock = findClockTime(content)
        if (clock != null) {
            var hour = clock.hour
            val meridiem = clock.meridiem?.lowercase(Locale.US)?.replace(".", "")
            val hasCjkPm = CJK_PM_HINT_REGEX.containsMatchIn(content) ||
                (hour in 1..4 && CJK_NOON_HINT_REGEX.containsMatchIn(content))
            when {
                meridiem == "pm" && hour in 1..11 -> hour += 12
                meridiem == "am" && hour == 12 -> hour = 0
                meridiem == null && hour in 1..11 && (EVENING_HINT_REGEX.containsMatchIn(lower) || hasCjkPm) -> hour += 12
            }
            return LocalTime.of(hour.coerceIn(0, 23), clock.minute.coerceIn(0, 59))
        }
        return when {
            EN_NOON_REGEX.containsMatchIn(lower) || CJK_NOON_HINT_REGEX.containsMatchIn(content) -> LocalTime.of(12, 0)
            EN_MORNING_REGEX.containsMatchIn(lower) || CJK_MORNING_HINT_REGEX.containsMatchIn(content) -> LocalTime.of(9, 0)
            EN_EVENING_REGEX.containsMatchIn(lower) || CJK_EVENING_ONLY_REGEX.containsMatchIn(content) -> LocalTime.of(19, 30)
            EN_AFTERNOON_REGEX.containsMatchIn(lower) || content.contains("\u4E0B\u5348") -> LocalTime.of(15, 0)
            else -> LocalTime.of(10, 0)
        }
    }

    private data class EntityHit(val name: String, val start: Int, val end: Int)

    private fun extractRelations(content: String): List<RelationTriple> {
        val triples = ArrayList<RelationTriple>()

        EXPLICIT_ARROW_REGEX.findAll(content).forEach { match ->
            val src = match.groupValues[1].trim()
            val rel = match.groupValues[2].trim().ifEmpty { MemoryAnalyzer.DEFAULT_RELATION }
            val tgt = match.groupValues[3].trim()
            if (src.isNotEmpty() && tgt.isNotEmpty()) {
                triples += RelationTriple(source = src, target = tgt, relation = rel)
            }
        }
        if (triples.isNotEmpty()) return triples

        val allEntities = LinkedHashSet<String>()
        var lastEntity: String? = null
        val clauses = CLAUSE_SPLIT_REGEX.split(content).map { it.trim() }.filter { it.isNotEmpty() }
        for (clause in clauses) {
            val hits = findEntities(clause)
            hits.forEach { allEntities += it.name }
            if (hits.isEmpty()) continue

            var subject: EntityHit? = null
            var startIdx = 0
            if (RELATIVE_PRONOUN_REGEX.containsMatchIn(clause) && lastEntity != null) {
                val pronounEnd = RELATIVE_PRONOUN_REGEX.find(clause)!!.range.last + 1
                val object0 = hits.first()
                triples += RelationTriple(
                    source = lastEntity,
                    target = object0.name,
                    relation = inferVerb(clause.substring(minOf(pronounEnd, object0.start), object0.start))
                        ?: MemoryAnalyzer.DEFAULT_RELATION,
                )
                subject = object0
                startIdx = 1
            }
            if (subject == null) {
                subject = hits.first()
                startIdx = 1
            }

            for (j in startIdx until hits.size) {
                val previous = hits[j - 1]
                val current = hits[j]
                val between = clause.substring(previous.end, current.start)
                val connector = between.trim().lowercase(Locale.US)
                val activeSubject = subject!!
                when {
                    connector == "and" || connector == "&" || connector == "," -> {
                        triples += RelationTriple(
                            source = previous.name,
                            target = current.name,
                            relation = inferJointLabel(clause),
                            bidirectional = true,
                        )
                    }
                    connector == "from" || connector == "of" -> {
                        triples += RelationTriple(source = previous.name, target = current.name, relation = "represents")
                    }
                    connector == "at" || connector == "in" -> {
                        triples += RelationTriple(source = previous.name, target = current.name, relation = "works at")
                    }
                    SUBORDINATOR_REGEX.containsMatchIn(between) -> {
                        subject = current
                    }
                    else -> {
                        val verbSpan = clause.substring(activeSubject.end, current.start)
                        val label = inferVerb(verbSpan) ?: MemoryAnalyzer.DEFAULT_RELATION
                        triples += RelationTriple(
                            source = activeSubject.name,
                            target = current.name,
                            relation = label,
                            bidirectional = label in BIDIRECTIONAL_LABELS,
                        )
                    }
                }
            }
            lastEntity = hits.last().name
        }

        if (triples.isEmpty() && allEntities.size >= 2) {
            val ordered = allEntities.toList().take(8)
            for (i in 0 until ordered.size - 1) {
                triples += RelationTriple(source = ordered[i], target = ordered[i + 1], relation = MemoryAnalyzer.DEFAULT_RELATION)
            }
        }
        extractCjkAndPinyinRelations(content, triples)
        return triples
    }

    private fun extractCjkAndPinyinRelations(content: String, sink: MutableList<RelationTriple>) {
        val hasCjk = EdgePromptTemplates.containsCjk(content)
        val isPinyin = EdgePromptTemplates.isRomanizedPinyin(content)
        if (!hasCjk && !isPinyin) return

        if (hasCjk) {
            CJK_COMPANION_REGEX.findAll(content).forEach { match ->
                val entity1 = match.groupValues[1].trim()
                val entity2 = match.groupValues[2].trim()
                val verb = match.groupValues[3].trim()
                if (entity1.isNotEmpty() && entity2.isNotEmpty() && entity1 != entity2 &&
                    entity1 !in MemoryAnalyzer.PLACEHOLDER_ENTITIES && entity2 !in MemoryAnalyzer.PLACEHOLDER_ENTITIES
                ) {
                    val label = if (verb == "\u73A9") "\u4E00\u8D77\u73A9" else verb
                    sink += RelationTriple(source = entity1, target = entity2, relation = label, bidirectional = true)
                    if (content.contains("\u6211")) {
                        sink += RelationTriple(source = "\u6211", target = entity1, relation = label, bidirectional = false)
                        sink += RelationTriple(source = "\u6211", target = entity2, relation = label, bidirectional = false)
                    }
                }
            }
            CJK_INTRO_REGEX.findAll(content).forEach { match ->
                val person1 = match.groupValues[1].trim()
                val relVerb = match.groupValues[2].trim()
                val person2 = match.groupValues[3].trim()
                if (person1.isNotEmpty() && person2.isNotEmpty() && person1 != person2) {
                    sink += RelationTriple(source = person1, target = person2, relation = relVerb, bidirectional = false)
                }
            }
            MIXED_CJK_LATIN_REL_REGEX.findAll(content).forEach { match ->
                val person = match.groupValues[1].trim()
                val relVerb = match.groupValues[2].trim()
                val target = match.groupValues[3].trim()
                if (person.isNotEmpty() && target.isNotEmpty() && person != target) {
                    sink += RelationTriple(source = person, target = target, relation = relVerb, bidirectional = false)
                }
            }
        }

        if (isPinyin) {
            val hasWo = PINYIN_WO_REGEX.containsMatchIn(content)
            PINYIN_FOOD_CLAUSE_REGEX.find(content)?.let { match ->
                val foodKey = match.groupValues[2].lowercase(Locale.US)
                val foodLabel = PINYIN_FOOD_GLOSSARY[foodKey] ?: foodKey
                if (hasWo) {
                    sink += RelationTriple(
                        source = "\u6211",
                        target = foodLabel,
                        relation = "\u60F3\u5403",
                        bidirectional = false,
                    )
                }
            }
            PINYIN_PLAY_CLAUSE_REGEX.find(content)?.let { match ->
                val companions = match.groupValues[1]
                    .split(PINYIN_COMPANION_SPLIT_REGEX)
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .distinct()
                val action = when (match.groupValues[2].lowercase(Locale.US)) {
                    "chi" -> "\u4E00\u8D77\u5403"
                    "qu" -> "\u4E00\u8D77\u53BB"
                    else -> "\u4E00\u8D77\u73A9"
                }
                for (i in 0 until companions.size - 1) {
                    sink += RelationTriple(
                        source = companions[i],
                        target = companions[i + 1],
                        relation = action,
                        bidirectional = true,
                    )
                }
                if (hasWo) {
                    for (comp in companions) {
                        sink += RelationTriple(
                            source = "\u6211",
                            target = comp,
                            relation = action,
                            bidirectional = false,
                        )
                    }
                }
            }
        }
    }

    private fun findEntities(clause: String): List<EntityHit> {
        val hits = ArrayList<EntityHit>()
        for (match in ENTITY_REGEX.findAll(clause)) {
            var name = match.value
            var start = match.range.first
            val leading = ENTITY_LEADING_STOPWORD_REGEX.find(name)
            if (leading != null) {
                name = name.substring(leading.range.last + 1)
                start += leading.range.last + 1
            }
            if (!name.contains(' ') && !name.contains('-')) continue
            if (name in ENTITY_BLOCKLIST) continue
            if (hits.any { it.name == name }) continue
            hits += EntityHit(name = name, start = start, end = start + name.length)
        }
        return hits
    }

    private fun inferVerb(span: String): String? {
        var best: Pair<Int, String>? = null
        for ((regex, label) in VERB_LABELS) {
            val match = regex.find(span) ?: continue
            if (best == null || match.range.first < best.first) {
                best = match.range.first to label
            }
        }
        return best?.second
    }

    private fun inferJointLabel(clause: String): String {
        val lower = clause.lowercase(Locale.US)
        return when {
            lower.contains("align") -> "aligns with"
            EN_MEET_VERB_REGEX.containsMatchIn(lower) -> "meets"
            lower.contains("review") -> "joint review"
            lower.contains("sign") -> "joint sign-off"
            else -> "works with"
        }
    }

    companion object {
        private const val TAG_FOLLOW_UP = "Follow-up"
        private const val CJK_TAG_SCHEDULE = "\u65E5\u7A0B\u63D0\u9192"

        /**
         * Removes accidental digit-key candidate selections embedded inside Mandarin A-not-A
         * (`A\u4E0DA` / `A\u6CA1A`) polar questions while leaving numeric quantities intact.
         */
        private val CJK_IME_PREFIX_STRAY_DIGIT_REGEX = Regex(
            """(?<=[\u4E00-\u9FFF])[1-9](?=([\u4E00-\u9FFF])[\u4E0D\u6CA1]\1)""",
        )
        private val CJK_IME_INFIX_STRAY_DIGIT_REGEX = Regex(
            """([\u4E00-\u9FFF])[1-9]([\u4E0D\u6CA1]\1)""",
        )
        private val LEADING_TOPIC_PREFIX_REGEX = Regex("""^[A-Z][A-Za-z\s-]{0,30}:\s+""")
        private val BIDIRECTIONAL_LABELS = setOf("aligns with", "works with", "meets")

        private fun keywords(vararg words: String): Regex =
            Regex(words.joinToString("|", prefix = """\b(?:""", postfix = """)\b""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

        private val SUPPLY_CHAIN_KEYWORDS = keywords("vendor", "supplier", "procurement", "sample", "samples", "quote", "quoted", "shipment", "factory", "mass-production", "module", "burn-in")
        private val MANUFACTURING_KEYWORDS = keywords("manufacturing", "production", "factory", "yield", "shipment", "module", "assembly", "mass-production", "burn-in")
        private val LEGAL_KEYWORDS = keywords("contract", "agreement", "clause", "legal", "indemnity", "warranty", "nda", "compliance", "addendum")
        private val CREDENTIALS_KEYWORDS = keywords("password", "passcode", "login", "pin", "token", "api key", "wifi", "wi-fi", "credentials")
        private val MATH_KEYWORDS = keywords("equation", "integral", "derivative", "theorem", "calculate", "algebra", "proof")
        private val HARDWARE_DISPLAY_KEYWORDS = keywords("hardware", "device", "module", "display", "oled", "micro-oled", "screen", "prototype", "lens", "firmware", "reliability", "yield", "quality", "burn-in", "defect")
        private val WORK_SYNC_KEYWORDS = keywords("project", "sync", "review", "deliver", "delivery", "agreement", "report", "team", "meeting", "deadline", "milestone", "align", "staging", "vpn")
        private val SECURITY_KEYWORDS = keywords("password", "passcode", "login", "pin", "token", "api key", "wifi", "wi-fi", "credentials", "vpn", "auth", "secret", "security")
        private val ARITHMETIC_KEYWORDS = keywords("equation", "integral", "derivative", "theorem", "calculate", "plus", "minus", "add", "sum", "algebra", "proof", "percent")
        private val ANALYTICS_KEYWORDS = keywords("analytics", "metrics", "data", "yield", "report", "chart", "kpi", "conversion", "score")
        private val ERRANDS_KEYWORDS = keywords("parcel", "package", "groceries", "shopping", "shop", "store", "market", "pick up", "buy", "rent", "bill", "laundry")
        private val HEALTH_KEYWORDS = keywords("doctor", "dentist", "hospital", "clinic", "medicine", "pill", "workout", "gym", "fitness", "run", "exercise", "check-up", "checkup")

        private val TAG_KEYWORDS: List<Pair<String, Regex>> = listOf(
            "Food" to keywords("lunch", "dinner", "breakfast", "brunch", "coffee", "meal", "restaurant", "takeout", "eat"),
            "Errands" to ERRANDS_KEYWORDS,
            "Travel" to keywords("flight", "train", "airport", "hotel", "taxi", "station", "trip", "boarding"),
            "Health" to HEALTH_KEYWORDS,
            "Supply chain" to SUPPLY_CHAIN_KEYWORDS,
            "Legal" to LEGAL_KEYWORDS,
            "Finance" to keywords("invoice", "payment", "deposit", "escrow", "budget", "finance", "reimbursement", "payroll", "tax"),
            "Credentials" to CREDENTIALS_KEYWORDS,
            "Math" to MATH_KEYWORDS,
        )
        private val CJK_TAG_KEYWORDS: List<Pair<String, Regex>> = listOf(
            "\u996E\u98DF" to Regex(
                """\u5403|\u559D|\u725B\u8089|\u732A\u8089|\u9E21\u8089|\u7F8A\u8089|\u5976\u8336|\u5496\u5561|\u53EF\u4E50|\u679C\u6C41|\u5564\u9152|\u706B\u9505|\u65E9\u996D|\u5348\u996D|\u665A\u996D|\u805A\u9910|\u9910\u5385|\b(?:chi|niu\s*rou|zhu\s*rou|ji\s*rou|yang\s*rou|nai\s*cha|ka\s*fei|ke\s*le|guo\s*zhi|pi\s*jiu|huo\s*guo|zaofan|wufan|wanfan)\b""",
                RegexOption.IGNORE_CASE,
            ),
            "\u5A31\u4E50" to Regex(
                """(?:\u4E00\u8D77)?\u73A9|\u627E.{1,10}\u73A9|\u805A\u4F1A|\u6E38\u620F|\u7535\u5F71|\byi\s*qi\s*wanr?\b|\bwanr?\b""",
                RegexOption.IGNORE_CASE,
            ),
            "\u5929\u6C14" to Regex(
                """\u5929\u6C14|\u5929\d?\u51B7|\u964D\u6E29|\u4E0B\u96E8|\u4E0B\u96EA|\btian\s*qi\b""",
                RegexOption.IGNORE_CASE,
            ),
            "\u65E5\u5E38\u91C7\u8D2D" to Regex(
                """\u4E70\d|\u82F9\u679C|\u725B\u5976|\u8D85\u5E02|\u836F\u5E97|\u521B\u53EF\u8D34|\u987A\u4FBF\u5E26|\u53D6.*\u5FEB\u9012""",
            ),
            "\u4F9B\u5E94\u94FE" to Regex(
                """\u62A5\u4EF7|\u6708\u7ED3|\u6837\u54C1|\u67D4\u6027.*\u5C4F|\u91CF\u4EA7|\u56FA\u4EF6""",
            ),
            "\u4F1A\u8BAE\u8BC4\u5BA1" to Regex(
                """\u4F1A\u8BAE\u5BA4|\u8BC4\u5BA1|\u5F00\u4F1A|\u5BF9\u9F50""",
            ),
            "\u8D26\u53F7\u51ED\u8BC1" to Regex(
                """\u8D26\u53F7|\u5BC6\u7801|\u53E3\u4EE4""",
            ),
            "\u6570\u5B66\u8FD0\u7B97" to Regex(
                """\d+\s*(?:\u52A0\u4E0A?|\u51CF\u53BB?|\u4E58\u4EE5?|\u9664\u4EE5?|[+*])\s*\d+|\u7B97\u672F|\u6570\u5B66|\u65B9\u7A0B|\u516C\u5F0F""",
            ),
        )
        private val PINYIN_WO_REGEX = Regex("""\bwo\b""", RegexOption.IGNORE_CASE)
        private val PINYIN_XIANG_REGEX = Regex("""\bxiang(?:\s+yao)?\b""", RegexOption.IGNORE_CASE)
        private val PINYIN_FOOD_CLAUSE_REGEX = Regex(
            """\b(?:(wo)\s+)?(?:yao|xiang(?:\s+yao)?)\s+chi\s+(niurou|zhurou|jirou|yangrou|huoguo|mifan|miantiao|pingguo)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PINYIN_DRINK_CLAUSE_REGEX = Regex(
            """\b(?:wo\s+)?(?:yao|xiang(?:\s+yao)?)\s+he\s+(nai\s*cha|ka\s*fei|niu\s*nai|ke\s*le|guo\s*zhi|pi\s*jiu|shui|cha)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PINYIN_PLAY_CLAUSE_REGEX = Regex(
            """\b(?:he|gen|yu)\s+([a-z0-9]{2,8}(?:\s*(?:,|and|he|yu)\s*[a-z0-9]{2,8})+)\s+yi\s*qi\s+(wanr?|chi|qu)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PINYIN_COMPANION_SPLIT_REGEX = Regex("""\s*(?:,|and|\bhe\b|\byu\b)\s*""", RegexOption.IGNORE_CASE)
        private val PINYIN_FOOD_GLOSSARY: Map<String, String> = mapOf(
            "niurou" to "\u725B\u8089",
            "zhurou" to "\u732A\u8089",
            "jirou" to "\u9E21\u8089",
            "yangrou" to "\u7F8A\u8089",
            "huoguo" to "\u706B\u9505",
            "mifan" to "\u7C73\u996D",
            "miantiao" to "\u9762\u6761",
            "pingguo" to "\u82F9\u679C",
        )
        private val PINYIN_DRINK_GLOSSARY: Map<String, String> = mapOf(
            "naicha" to "\u5976\u8336",
            "kafei" to "\u5496\u5561",
            "niunai" to "\u725B\u5976",
            "kele" to "\u53EF\u4E50",
            "guozhi" to "\u679C\u6C41",
            "pijiu" to "\u5564\u9152",
            "shui" to "\u6C34",
            "cha" to "\u8336",
        )
        private val PINYIN_GROUNDED_ENTITIES: Map<String, Regex> = mapOf(
            "\u6211" to Regex("""\bwo\b""", RegexOption.IGNORE_CASE),
            "\u6211\u4EEC" to Regex("""\bwomen\b""", RegexOption.IGNORE_CASE),
            "\u4F60" to Regex("""\bni\b""", RegexOption.IGNORE_CASE),
            "\u4ED6" to Regex("""\bta\b""", RegexOption.IGNORE_CASE),
            "\u725B\u8089" to Regex("""\bniu\s*rou\b""", RegexOption.IGNORE_CASE),
            "\u732A\u8089" to Regex("""\bzhu\s*rou\b""", RegexOption.IGNORE_CASE),
            "\u9E21\u8089" to Regex("""\bji\s*rou\b""", RegexOption.IGNORE_CASE),
            "\u7F8A\u8089" to Regex("""\byang\s*rou\b""", RegexOption.IGNORE_CASE),
            "\u5976\u8336" to Regex("""\bnai\s*cha\b""", RegexOption.IGNORE_CASE),
            "\u5496\u5561" to Regex("""\bka\s*fei\b""", RegexOption.IGNORE_CASE),
            "\u53EF\u4E50" to Regex("""\bke\s*le\b""", RegexOption.IGNORE_CASE),
            "\u679C\u6C41" to Regex("""\bguo\s*zhi\b""", RegexOption.IGNORE_CASE),
            "\u5564\u9152" to Regex("""\bpi\s*jiu\b""", RegexOption.IGNORE_CASE),
            "\u706B\u9505" to Regex("""\bhuo\s*guo\b""", RegexOption.IGNORE_CASE),
            "\u82F9\u679C" to Regex("""\bping\s*guo\b""", RegexOption.IGNORE_CASE),
            "\u725B\u5976" to Regex("""\bniu\s*nai\b""", RegexOption.IGNORE_CASE),
            "\u7C73\u996D" to Regex("""\bmi\s*fan\b""", RegexOption.IGNORE_CASE),
            "\u9762\u6761" to Regex("""\bmian\s*tiao\b""", RegexOption.IGNORE_CASE),
            "\u670B\u53CB" to Regex("""\bpeng\s*you\b""", RegexOption.IGNORE_CASE),
            "\u540C\u4E8B" to Regex("""\btong\s*shi\b""", RegexOption.IGNORE_CASE),
            "\u5988\u5988" to Regex("""\bma\s*ma\b""", RegexOption.IGNORE_CASE),
            "\u7238\u7238" to Regex("""\bba\s*ba\b""", RegexOption.IGNORE_CASE),
        )
        private val CJK_COMPANION_REGEX = Regex(
            """(?:\u627E|\u548C|\u8DDF|\u4E0E|\u7EA6)\s*([\u4E00-\u9FFF]{1,6}?|[A-Za-z][A-Za-z0-9-]{1,12})\s*(?:[\uFF0C,\u3001]|\u548C|\u4E0E|\u8DDF)\s*([\u4E00-\u9FFF]{1,6}?|[A-Za-z][A-Za-z0-9-]{1,12})\s*(?:\u4E00\u8D77)?(\u73A9|\u805A\u9910|\u5F00\u4F1A|\u8BC4\u5BA1|\u8BA8\u8BBA|\u5BF9\u9F50)""",
        )
        private val CJK_INTRO_REGEX = Regex(
            """([\u4E00-\u9FFF]{1,3}?(?:\u603B|\u5DE5|\u7ECF\u7406|\u8001\u5E08|\u533B\u751F)|\u8001[\u4E00-\u9FFF]|\u5C0F[\u4E00-\u9FFF])\s*(\u5F15\u8350|\u63A8\u8350|\u4EA4\u63A5\u7ED9|\u5BF9\u63A5|\u79FB\u4EA4)\s*([\u4E00-\u9FFF]{1,3}?(?:\u603B|\u5DE5|\u7ECF\u7406|\u8001\u5E08|\u533B\u751F)|\u8001[\u4E00-\u9FFF]|\u5C0F[\u4E00-\u9FFF])""",
        )
        private val MIXED_CJK_LATIN_REL_REGEX = Regex(
            """(?:\u548C|\u4E0E|\u8DDF)\s*([A-Z][A-Za-z]+(?:\s+[A-Z][A-Za-z]+)?)\s*(\u5BF9\u9F50|\u8BC4\u5BA1|\u8BA8\u8BBA|\u8DDF\u8FDB)\s*([A-Z][A-Za-z0-9-]+)""",
        )
        private val SCHEDULE_SCENE_TAGS: Set<String> = setOf(
            "follow-up", "schedule", "scheduling", "meeting", "reminder", "appointment", "calendar",
        )
        private val KNOWN_SCENE_TAG_REGEXES: Map<String, Regex> =
            TAG_KEYWORDS.associate { (tag, regex) -> tag.lowercase(Locale.US) to regex } + mapOf(
                "contract" to LEGAL_KEYWORDS,
                "contracts" to LEGAL_KEYWORDS,
                "contractual" to LEGAL_KEYWORDS,
                "procurement" to SUPPLY_CHAIN_KEYWORDS,
                "manufacturing" to MANUFACTURING_KEYWORDS,
                "production" to MANUFACTURING_KEYWORDS,
                "yield" to MANUFACTURING_KEYWORDS,
                "analytics" to ANALYTICS_KEYWORDS,
                "shopping" to ERRANDS_KEYWORDS,
                "groceries" to ERRANDS_KEYWORDS,
                "fitness" to HEALTH_KEYWORDS,
                "workout" to HEALTH_KEYWORDS,
                "hardware" to HARDWARE_DISPLAY_KEYWORDS,
                "display" to HARDWARE_DISPLAY_KEYWORDS,
                "reliability" to HARDWARE_DISPLAY_KEYWORDS,
                "quality" to HARDWARE_DISPLAY_KEYWORDS,
                "work" to WORK_SYNC_KEYWORDS,
                "sync" to WORK_SYNC_KEYWORDS,
                "collaboration" to WORK_SYNC_KEYWORDS,
                "security" to SECURITY_KEYWORDS,
                "arithmetic" to ARITHMETIC_KEYWORDS,
                "calculation" to ARITHMETIC_KEYWORDS,
            )

        private const val CJK_WEEKDAY_CHARS = """\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u65E5\u5929"""
        private const val CJK_NUMERAL_CHARS = """\u4E00\u4E8C\u4E24\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341"""
        private const val CJK_NON_TIME_AFTER_DIAN =
            """\u610F\u89C1|\u5EFA\u8BAE|\u60F3\u6CD5|\u8981\u6C42|\u8BF4\u660E|\u95EE\u9898|\u539F\u56E0|\u603B\u7ED3|\u4F53\u4F1A|\u6539\u8FDB|\u539F\u5219|\u6CE8\u610F"""
        private const val MONTHS_REGEX =
            """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""

        private val EXPLICIT_YEAR_REGEX = Regex("""\b\d{4}\b|\d{4}\u5E74""")
        private val ISO_CALENDAR_DATE_REGEX = Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b""")
        private val SLASH_MONTH_DAY_REGEX = Regex("""\b(1[0-2]|0?[1-9])/([12]?\d|3[01])(?:/(\d{2,4}))?\b""")
        private val CJK_MONTH_DAY_REGEX = Regex("""(?:(\d{4})\u5E74\s*)?(\d{1,2})\u6708(\d{1,2})[\u65E5\u53F7]?""")
        private val EN_MONTH_DAY_REGEX = Regex("""\b($MONTHS_REGEX)\s+([12]?\d|3[01])\b""", RegexOption.IGNORE_CASE)
        private val EXPLICIT_DATE_ONLY_REGEX = Regex(
            """\b\d{4}[-/]\d{1,2}[-/]\d{1,2}\b|\b(?:1[0-2]|0?[1-9])/(?:[12]?\d|3[01])(?:/\d{2,4})?\b|\b$MONTHS_REGEX\s+(?:[12]?\d|3[01])\b|\d{1,2}\u6708\d{1,2}[\u65E5\u53F7]?""",
            RegexOption.IGNORE_CASE,
        )
        private val EXPLICIT_CALENDAR_DATE_REGEX = Regex(
            """${EXPLICIT_DATE_ONLY_REGEX.pattern}|(?<!\u7B2C)(?<!\d\.)\d{1,2}\s*\u70B9(?:\s*\d{1,2}\s*\u5206|\s*\u534A)?(?!\s*(?:$CJK_NON_TIME_AFTER_DIAN))|(?:[\d$CJK_NUMERAL_CHARS]+|\u534A)\s*(?:\u4E2A)?(?:\u5C0F\u65F6|\u5206\u949F|\u5929)\u540E""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_THREE_DAYS_LATER_REGEX = Regex("""\u5927\u540E\u5929|\u5927\u540E\u65E5""")
        private val CJK_DAY_AFTER_TOMORROW_REGEX = Regex("""\u540E\u5929|\u540E\u65E5""")
        private val CJK_TOMORROW_REGEX = Regex("""\u660E\u5929|\u660E\u65E5|\u660E\u65E9|\u660E\u665A""")
        private val CJK_TODAY_REGEX = Regex("""\u4ECA\u5929|\u4ECA\u65E5|\u4ECA\u665A|\u4ECA\u65E9""")
        private val EN_TOMORROW_REGEX = Regex("""\btomorrow\b""")
        private val EN_TODAY_REGEX = Regex("""\b(?:today|tonight|this\s+(?:morning|afternoon|evening))\b""")
        private val EN_NOON_REGEX = Regex("""\bnoon\b""")
        private val EN_MORNING_REGEX = Regex("""\bmorning\b""")
        private val EN_EVENING_REGEX = Regex("""\b(?:tonight|evening)\b""")
        private val EN_AFTERNOON_REGEX = Regex("""\bafternoon\b""")
        private val EN_MEET_VERB_REGEX = Regex("""\b(?:meet|meets|meeting|met)\b""")
        private val CJK_NEXT_WEEKDAY_REGEX = Regex("""\u4E0B(?:\u4E2A)?(?:\u5468|\u661F\u671F|\u793C\u62DC)([$CJK_WEEKDAY_CHARS])""")
        private val CJK_THIS_WEEKDAY_REGEX = Regex("""\u672C(?:\u5468|\u661F\u671F|\u793C\u62DC)([$CJK_WEEKDAY_CHARS])""")
        private val CJK_WEEKDAY_REGEX = Regex("""(?:\u5468|\u661F\u671F|\u793C\u62DC)([$CJK_WEEKDAY_CHARS])""")
        private val CJK_DATE_KEYWORD_REGEX = Regex(
            """\u5927\u540E\u5929|\u5927\u540E\u65E5|\u4ECA\u5929|\u4ECA\u65E5|\u660E\u5929|\u660E\u65E5|\u660E\u65E9|\u660E\u665A|\u540E\u5929|\u540E\u65E5|\u4ECA\u665A|\u4ECA\u65E9|\u4E0B(?:\u4E2A)?(?:\u5468|\u661F\u671F|\u793C\u62DC)|\u672C(?:\u5468|\u661F\u671F|\u793C\u62DC)|(?:\u5468|\u661F\u671F|\u793C\u62DC)[$CJK_WEEKDAY_CHARS]""",
        )
        private val CJK_EVENT_KEYWORD_REGEX = Regex(
            """\u4F1A\u8BAE|\u5F00\u4F1A|\u8BC4\u5BA1|\u9762\u8BD5|\u805A\u9910|\u5403\u996D|\u770B\u75C5|\u590D\u8BCA|\u822A\u73ED|\u706B\u8F66|\u9AD8\u94C1|\u63D0\u9192|\u622A\u6B62|\u4EA4\u4ED8|\u7B7E\u5B57|\u6C47\u62A5|\u5BF9\u9F50""",
        )
        private val CJK_SHORT_EVENT_REGEX = Regex(
            """\u53D6\u5FEB\u9012|\u5403\u996D|\u5348\u996D|\u665A\u996D|\u5496\u5561|\u5403\u836F|\u63D0\u9192|\u7535\u8BDD""",
        )

        private val EVENT_KEYWORD_REGEX = keywords(
            "meeting", "meet", "review", "call", "sync", "lunch", "dinner", "breakfast", "coffee",
            "appointment", "pick up", "deadline", "interview", "demo", "standup", "stand-up", "check-up",
            "checkup", "flight", "train", "sign off", "presentation", "workshop", "remind", "dentist",
            "doctor", "visit", "catch up", "party", "class",
        )
        private val SHORT_EVENT_REGEX = keywords(
            "lunch", "dinner", "breakfast", "coffee", "meal", "parcel", "package", "pick up", "medicine", "pill", "call", "buy",
        )

        private val WORD_NUMBERS = mapOf(
            "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
            "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
            "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty-five" to 45.0,
            "half a" to 0.5, "half an" to 0.5, "a couple of" to 2.0, "a few" to 3.0,
        )
        private val RELATIVE_DURATION_REGEX = Regex(
            """\bin\s+(\d+(?:\.\d+)?|half an?|a couple of|a few|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty-five)\s*(hours?|hrs?|minutes?|mins?|days?)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_RELATIVE_DURATION_REGEX = Regex(
            """(\d+(?:\.\d+)?|\u534A|[$CJK_NUMERAL_CHARS]{1,3})\s*(?:\u4E2A)?(\u5C0F\u65F6|\u5206\u949F|\u5929)\u540E""",
        )

        private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        private val DAY_AFTER_TOMORROW_REGEX = Regex("""\bday\s+after\s+tomorrow\b""", RegexOption.IGNORE_CASE)
        private val NEXT_WEEKDAY_REGEX = Regex("""\bnext\s+($WEEKDAYS)\b""")
        private val WEEKDAY_REGEX = Regex("""\b($WEEKDAYS)\b""")
        private val DATE_KEYWORD_REGEX = Regex(
            """\b(?:today|tonight|tomorrow|day\s+after\s+tomorrow|$WEEKDAYS)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DAY_PART_REGEX = Regex("""\b(?:this\s+(?:morning|afternoon|evening)|noon)\b""", RegexOption.IGNORE_CASE)
        private val RELATIVE_ANCHOR_RECONCILE_REGEX = Regex(
            """\b(?:today|tonight|tomorrow|day\s+after\s+tomorrow|this\s+(?:morning|afternoon|evening)|next\s+(?:$WEEKDAYS)|$WEEKDAYS)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val EVENING_HINT_REGEX = Regex("""\b(?:tonight|evening|afternoon)\b""")
        private val CJK_PM_HINT_REGEX = Regex("""\u4E0B\u5348|\u665A\u4E0A|\u4ECA\u665A|\u660E\u665A|\u508D\u665A|\u591C\u91CC""")
        private val CJK_NOON_HINT_REGEX = Regex("""\u4E2D\u5348""")
        private val CJK_MORNING_HINT_REGEX = Regex("""\u4E0A\u5348|\u65E9\u4E0A|\u660E\u65E9|\u4ECA\u65E9""")
        private val CJK_EVENING_ONLY_REGEX = Regex("""\u665A\u4E0A|\u4ECA\u665A|\u660E\u665A|\u508D\u665A|\u591C\u91CC""")

        private val COLON_TIME_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?:\s*([ap]\.?m\.?)(?![a-z]))?""", RegexOption.IGNORE_CASE)
        private val MERIDIEM_TIME_REGEX = Regex("""\b(\d{1,2})\s*([ap]\.?m\.?)(?![a-z])""", RegexOption.IGNORE_CASE)
        private val BARE_AT_TIME_REGEX = Regex("""\bat\s+(\d{1,2})(?:\s*o'clock)?\b(?![:.\-]\d)(?!\s*(?:%|hours?|minutes?|mins?|days?))""", RegexOption.IGNORE_CASE)
        private val CJK_NUMERIC_CLOCK_REGEX = Regex(
            """(?<!\u7B2C)(?<!\d\.)(\d{1,2})\s*\u70B9(?:\s*(\d{1,2})\s*\u5206|\s*(\u534A)|\s*\u949F)?(?!\s*(?:$CJK_NON_TIME_AFTER_DIAN))""",
        )
        private val CJK_WORD_CLOCK_REGEX = Regex(
            """(?:\u4E0A\u5348|\u65E9\u4E0A|\u660E\u65E9|\u4ECA\u65E9|\u4E2D\u5348|\u4E0B\u5348|\u665A\u4E0A|\u4ECA\u665A|\u660E\u665A|\u508D\u665A|\u591C\u91CC|\u51CC\u6668)\s*([$CJK_NUMERAL_CHARS]{1,3})\u70B9(?:\s*(\d{1,2}|[$CJK_NUMERAL_CHARS]{1,3})\s*\u5206|\s*(\u534A)|\s*\u949F)?(?!\s*(?:$CJK_NON_TIME_AFTER_DIAN))""",
        )

        private val CLOCK_STRIP_REGEX = Regex(
            """\b(?:at\s+)?\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?(?![a-z])|\b(?:at\s+)?\d{1,2}:\d{2}|\bat\s+\d{1,2}\s*o'clock\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_CLOCK_STRIP_REGEX = Regex(
            """(?:(?:\u4E0A\u5348|\u65E9\u4E0A|\u4E2D\u5348|\u4E0B\u5348|\u665A\u4E0A|\u508D\u665A|\u591C\u91CC|\u51CC\u6668)\s*)?(?<!\u7B2C)(?<!\d\.)(?:\d{1,2}|[$CJK_NUMERAL_CHARS]{1,3})\s*\u70B9(?:\s*\d{1,2}\s*\u5206|\s*\u534A|\s*\u949F)?(?!\s*(?:$CJK_NON_TIME_AFTER_DIAN))""",
        )
        private val DATE_STRIP_REGEX = Regex(
            """\b(?:the\s+)?day\s+after\s+tomorrow\b|\b(?:(?:next|this|on|by)\s+)?(?:$WEEKDAYS)\b|\btomorrow(?:\s+(?:morning|afternoon|evening|night))?\b|\b(?:today|tonight|noon)\b|\bthis\s+(?:morning|afternoon|evening)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_DATE_STRIP_REGEX = Regex(
            """(?:\d{4}\u5E74\s*)?\d{1,2}\u6708\d{1,2}[\u65E5\u53F7]?|\u5927\u540E\u5929|\u5927\u540E\u65E5|\u540E\u5929|\u540E\u65E5|\u660E\u5929|\u660E\u65E5|\u660E\u65E9|\u660E\u665A|\u4ECA\u5929|\u4ECA\u65E5|\u4ECA\u665A|\u4ECA\u65E9|(?:\u4E0B\u4E2A?|\u672C)?(?:\u5468|\u661F\u671F|\u793C\u62DC)[$CJK_WEEKDAY_CHARS]""",
        )
        private val DANGLING_PREPOSITION_REGEX = Regex("""^(?:(?:at|on|by|in)\s+)+|(?:\s+(?:at|on|by|in))+$""", RegexOption.IGNORE_CASE)
        private val LEADING_MODAL_REGEX = Regex(
            """^(?:please\s+)?(?:remember\s+to|remind\s+me\s+to|don'?t\s+forget\s+to|i\s+(?:need|have)\s+to|need\s+to|have\s+to|gotta|must)\s+""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_LEADING_REMINDER_REGEX = Regex(
            """^(?:\u8BF7)?(?:\u63D0\u9192\u6211?|\u8BB0\u5F97|\u522B\u5FD8\u4E86)\s*""",
        )
        private val TITLE_CLAUSE_BREAK_REGEX = Regex(""",\s*then\b|;|(?<=[.!?])\s+|,\s*and then\b|\uFF1B""", RegexOption.IGNORE_CASE)

        private const val PLACE_UNIT = "(?:Building|Conference Room|Meeting Room|Room|Floor|Suite|Hall|Office|Level|Block|Gate|Terminal)"
        private val STRUCTURED_LOCATION_REGEX = Regex(
            """\b(?:in|at)\s+((?:the\s+)?$PLACE_UNIT\s+[A-Za-z0-9-]+(?:,\s*$PLACE_UNIT\s+[A-Za-z0-9-]+)*)""",
        )
        private val VENUE_LOCATION_REGEX = Regex(
            """\b(?:in|at)\s+(?:the\s+)?((?:[A-Z][\w'&-]*\s+){0,3}(?:Office|Hotel|Airport|Station|Hospital|Clinic|Cafe|Caf\u00E9|Restaurant|Center|Centre|Park|Mall|Campus|Tower|Plaza|Library|Gym))\b""",
        )
        private val CJK_ROOM_VENUE_REGEX = Regex(
            """(?:\u5728|\u5230|\u53BB)([^\s\u5728\u5230\u53BB\u70B9,;.!\?\uFF0C\u3002\uFF1B\uFF1A\uFF01\uFF1F]{0,18}?(?:\u4F1A\u8BAE\u5BA4|\u529E\u516C\u5BA4|\u5496\u5561\u5385|\u5496\u5561\u9986|\u9910\u5385|\u996D\u5E97|\u9152\u5E97|\u673A\u573A|\u8F66\u7AD9|\u9AD8\u94C1\u7AD9|\u533B\u9662|\u8BCA\u6240|\u5927\u53A6|\u5927\u5385|\u5E7F\u573A|\u524D\u53F0|\d+\u5BA4))""",
        )
        private val CJK_BUILDING_LOCATION_REGEX = Regex(
            """(?:\u5728|\u5230|\u53BB)([A-Za-z0-9\u4E00-\u9FFF-]{1,12}[\u5EA7\u680B\u5C42\u697C])""",
        )
        private val CJK_LOCATION_STRIP_REGEX = Regex(
            """${CJK_ROOM_VENUE_REGEX.pattern}|${CJK_BUILDING_LOCATION_REGEX.pattern}""",
        )

        private val EXPLICIT_ARROW_REGEX = Regex(
            """([\p{L}\p{N}_][\p{L}\p{N}_ .'-]{0,30}?)\s*-{1,2}\s*\[?([^\]\->\n]{1,24}?)\]?\s*-*>\s*([\p{L}\p{N}_][\p{L}\p{N}_ .'-]{0,30})""",
        )
        private val CLAUSE_SPLIT_REGEX = Regex("""\n+|[;\uFF1B\u3002\uFF01\uFF1F]+|(?<=[.!?])\s+|,\s+|:\s+""")
        private val RELATIVE_PRONOUN_REGEX = Regex("""^(?:who|which|that)\b""", RegexOption.IGNORE_CASE)
        private val SUBORDINATOR_REGEX = Regex(
            """\b(?:once|after|before|when|while|because|until|so|but|then|whereas|unless)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Capitalized multi-word names, e.g. `Elena Vance`, `Helios Display`, `Micro-OLED`. */
        private val ENTITY_REGEX = Regex("""\b[A-Z][a-z]+(?:(?:\s+|-)[A-Z][A-Za-z]+)+\b""")
        private val ENTITY_LEADING_STOPWORD_REGEX = Regex(
            """^(?:(?:The|A|An|This|That|Our|My|Your|Their|Next|Tomorrow|Today|Tonight|Hi|Hello|Dear|Remind|Ask|Tell|Call|Email|Meet|Ping|Send)\s+)+""",
        )
        private val ENTITY_BLOCKLIST = setOf("Conference Room", "Meeting Room")

        private val VERB_LABELS: List<Pair<Regex, String>> = listOf(
            Regex("""\bintroduc\w*""", RegexOption.IGNORE_CASE) to "introduced",
            Regex("""\breports?\s+to\b""", RegexOption.IGNORE_CASE) to "reports to",
            Regex("""\b(?:sent|sends?)\b""", RegexOption.IGNORE_CASE) to "sent",
            Regex("""\bsubmit\w*""", RegexOption.IGNORE_CASE) to "submits",
            Regex("""\bhand(?:s|ed)?\b""", RegexOption.IGNORE_CASE) to "hands off to",
            Regex("""\balign\w*""", RegexOption.IGNORE_CASE) to "aligns with",
            Regex("""\breview\w*""", RegexOption.IGNORE_CASE) to "reviews",
            Regex("""\b(?:meet|meets|meeting|met)\b""", RegexOption.IGNORE_CASE) to "meets",
            Regex("""\bcall(?:s|ed)?\b""", RegexOption.IGNORE_CASE) to "calls",
            Regex("""\bemail\w*""", RegexOption.IGNORE_CASE) to "emails",
            Regex("""\bquot(?:e|es|ed)\b""", RegexOption.IGNORE_CASE) to "quotes",
            Regex("""\bapprov\w*""", RegexOption.IGNORE_CASE) to "approves",
            Regex("""\breleas\w*""", RegexOption.IGNORE_CASE) to "releases",
            Regex("""\b(?:pays?|paid)\b""", RegexOption.IGNORE_CASE) to "pays",
            Regex("""\bhir(?:e|es|ed|ing)\b""", RegexOption.IGNORE_CASE) to "hires",
            Regex("""\bmanag\w*""", RegexOption.IGNORE_CASE) to "manages",
            Regex("""\bworks?\s+with\b""", RegexOption.IGNORE_CASE) to "works with",
            Regex("""\bhelp\w*""", RegexOption.IGNORE_CASE) to "helps",
            Regex("""\bask\w*""", RegexOption.IGNORE_CASE) to "asks",
            Regex("""\b(?:told|tells?)\b""", RegexOption.IGNORE_CASE) to "tells",
            Regex("""\binvit\w*""", RegexOption.IGNORE_CASE) to "invites",
            Regex("""\bjoin\w*""", RegexOption.IGNORE_CASE) to "joins",
            Regex("""\brepresent\w*""", RegexOption.IGNORE_CASE) to "represents",
            Regex("""\battach\w*""", RegexOption.IGNORE_CASE) to "attached to",
        )
    }
}
