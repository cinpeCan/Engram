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
import kotlinx.serialization.json.Json

/**
 * Pure structured prompt builder, resilient JSON parser, Deponder triple sanitizer,
 * and deterministic rule fallback for offline memory distillation.
 */
class MemoryAnalyzer(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun buildAnalysisPrompt(content: String, nowMillis: Long = System.currentTimeMillis()): String {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val formattedNow = nowLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)", Locale.US))
        return """
            You are Engram, a white-box memory assistant. Current local time: $formattedNow.
            Distill the user's note and output JSON only, matching this schema exactly:
            {"summary":"• First point\n• Second point","tags":["Tag"],"calendarProposal":{"title":"Event title","startTimeIso":"YYYY-MM-DDTHH:MM:SS","durationMinutes":30,"location":null},"relations":[{"source":"","target":"","relation":"","bidirectional":false}]}
            Rules:
            - summary: 1-3 bullets, each starting with "• " and separated by \n; each bullet at most 12 words, concise subject-verb-object.
            - Never reveal plaintext passwords, PINs or secrets; write "see source" instead.
            - tags: at most 3 short scene tags, or [] if none fit.
            - calendarProposal: null unless the note describes a schedulable event. Convert relative times ("in 2 hours", "tomorrow 3pm") into an exact ISO local datetime.
            - relations: at most 15 entity triples; source and target must differ.
            - Write the summary, tags and relation labels in the same language as the note.
            Note: $content
        """.trimIndent()
    }

    fun mergeModelJsonOrNull(
        rawText: String,
        rawContent: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MemoryAnalysisPayload? {
        val parsedFromModel = parseJsonPayload(rawText) ?: return null
        val heuristic by lazy { fallbackAnalyze(rawContent, nowMillis) }
        val cleanedRelations = sanitizeRelations(
            if (parsedFromModel.relations.isNotEmpty()) parsedFromModel.relations else heuristic.relations,
        )
        val modelTags = parsedFromModel.tags
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
        val resolvedTags = modelTags.ifEmpty { heuristic.tags }
        val normalizedSummary = normalizeBulletSummary(parsedFromModel.summary).ifBlank { heuristic.summary }
        return parsedFromModel.copy(
            summary = normalizedSummary,
            tags = resolvedTags,
            calendarProposal = parsedFromModel.calendarProposal ?: heuristic.calendarProposal,
            relations = cleanedRelations,
        )
    }

    /**
     * Normalizes free-form model or rule output into at most four concise points.
     * Splits on newlines and sentence punctuation while keeping decimals and clock
     * times such as `3:00` or `10.5` intact.
     */
    internal fun normalizeBulletSummary(rawSummary: String): String {
        if (rawSummary.isBlank()) return ""
        val rawSegments = SENTENCE_SPLIT_REGEX.split(rawSummary.replace("\r\n", "\n"))
            .map { segment ->
                BULLET_PREFIX_REGEX.replace(segment.trim(), "")
                    .trim(' ', '\t', ',', '，', '、', '•', '·')
                    .trimEnd('.')
                    .trim()
            }
            .filter { it.isNotBlank() }

        val concisePoints = ArrayList<String>(4)
        for (segment in rawSegments) {
            if (segment.length > MAX_BULLET_CHARS && (segment.contains(", ") || segment.contains('，'))) {
                val subClauses = segment.split(", ", "，")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                var carry = ""
                for (clause in subClauses) {
                    val combined = if (carry.isEmpty()) clause else "$carry, $clause"
                    if (combined.length < MIN_CLAUSE_CHARS) {
                        carry = combined
                    } else {
                        concisePoints += combined
                        carry = ""
                    }
                }
                if (carry.isNotBlank()) {
                    if (concisePoints.isNotEmpty() && carry.length < MIN_CLAUSE_CHARS) {
                        val lastIdx = concisePoints.lastIndex
                        concisePoints[lastIdx] = "${concisePoints[lastIdx]}, $carry"
                    } else {
                        concisePoints += carry
                    }
                }
            } else {
                concisePoints += segment
            }
        }

        val capped = concisePoints
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)

        return when {
            capped.isEmpty() -> ""
            capped.size == 1 -> clipAtWord(capped[0], MAX_SINGLE_POINT_CHARS)
            else -> capped.joinToString("\n") { "• ${clipAtWord(it, MAX_BULLET_CHARS)}" }.take(MAX_SUMMARY_CHARS)
        }
    }

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
        val relations = sanitizeRelations(extractRelations(trimmed))
        return MemoryAnalysisPayload(
            summary = summary,
            tags = tags,
            calendarProposal = calendar,
            relations = relations,
        )
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
                    0L -> "Today"
                    1L -> "Tomorrow"
                    2L -> "Day after tomorrow"
                    else -> startDt.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
                }
                val timePart = startDt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                val title = clipAtWord(calendar.title, MAX_BULLET_CHARS)
                val location = calendar.location?.takeIf { it.isNotBlank() }
                if (location == null && title.length <= 32) {
                    "$dateLabel $timePart · $title"
                } else {
                    val locPart = location?.let { " · $it" }.orEmpty()
                    "• $dateLabel $timePart$locPart\n• $title"
                }
            }.getOrNull()
            if (!formatted.isNullOrBlank()) {
                return formatted.take(MAX_SUMMARY_CHARS)
            }
        }

        val redacted = redactSensitiveDetails(content.trim())
            .replace('\n', ' ')
            .let { LEADING_TOPIC_PREFIX_REGEX.replace(it, "") }
            .trim()
        val normalized = normalizeBulletSummary(redacted)
        if (normalized.isBlank()) {
            return clipAtWord(redacted, MAX_SINGLE_POINT_CHARS)
        }
        val lines = normalized.lines().take(3)
        return if (lines.size == 1) {
            lines[0].removePrefix("• ").trim()
        } else {
            lines.joinToString("\n")
        }
    }

    internal fun redactSensitiveDetails(text: String): String {
        var result = ACCOUNT_SECRET_PAIR_REGEX.replace(text) { match ->
            "${match.groupValues[1]} saved (see source)"
        }
        result = PASSWORD_SECRET_REGEX.replace(result) { match ->
            "${match.groupValues[1]} saved (see source)"
        }
        return result
    }

    private fun extractTags(content: String, hasCalendarProposal: Boolean): List<String> {
        val tags = ArrayList<String>(3)
        for ((tag, keywords) in TAG_KEYWORDS) {
            if (keywords.containsMatchIn(content)) {
                tags += tag
            }
        }
        if (hasCalendarProposal && tags.size < 2) {
            tags += TAG_FOLLOW_UP
        }
        return tags.distinct().take(3)
    }

    private fun extractCalendarProposal(content: String, nowMillis: Long): CalendarProposal? {
        val relativeMinutes = parseRelativeDurationMinutes(content)
        val hasClock = findClockTime(content) != null
        val hasExplicitDate = DATE_KEYWORD_REGEX.containsMatchIn(content) || DAY_PART_REGEX.containsMatchIn(content)
        if (relativeMinutes == null && !hasClock && !hasExplicitDate) return null
        if (relativeMinutes == null && !hasClock && !EVENT_KEYWORD_REGEX.containsMatchIn(content)) {
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
        val resolvedTitle = extractCleanTitle(content).ifBlank { "Reminder" }
        val duration = if (SHORT_EVENT_REGEX.containsMatchIn(content)) 30 else 60

        return CalendarProposal(
            title = resolvedTitle,
            startTimeIso = startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            durationMinutes = duration,
            location = location,
        )
    }

    internal fun extractLocation(content: String): String? {
        val structured = STRUCTURED_LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)
        val venue = structured ?: VENUE_LOCATION_REGEX.find(content)?.groupValues?.getOrNull(1)
        return venue?.trim()?.trimEnd(',', '.', ';', ':')?.takeIf { it.isNotEmpty() }
    }

    internal fun parseRelativeDurationMinutes(content: String): Long? {
        val match = RELATIVE_DURATION_REGEX.find(content) ?: return null
        val rawAmount = match.groupValues[1].lowercase(Locale.US)
        val amount = rawAmount.toDoubleOrNull() ?: WORD_NUMBERS[rawAmount] ?: return null
        val unit = match.groupValues[2].lowercase(Locale.US)
        val minutes = when {
            unit.startsWith("h") -> amount * 60.0
            unit.startsWith("d") -> amount * 24.0 * 60.0
            else -> amount
        }
        return minutes.roundToLong().coerceAtLeast(1L)
    }

    internal fun extractCleanTitle(content: String): String {
        var cleaned = content.trim()
        cleaned = RELATIVE_DURATION_REGEX.replace(cleaned, " ")
        cleaned = STRUCTURED_LOCATION_REGEX.replace(cleaned, " ")
        cleaned = VENUE_LOCATION_REGEX.replace(cleaned, " ")
        cleaned = CLOCK_STRIP_REGEX.replace(cleaned, " ")
        cleaned = DATE_STRIP_REGEX.replace(cleaned, " ")
        cleaned = cleaned.replace(WHITESPACE_REGEX, " ").trim()

        val colonIdx = cleaned.indexOf(':')
        if (colonIdx >= 0 && cleaned.substring(colonIdx + 1).isNotBlank()) {
            val head = cleaned.substring(0, colonIdx).trim(' ', ',', '.', ';', '-')
            if (head.isEmpty() || head.split(' ').size <= 3) {
                cleaned = cleaned.substring(colonIdx + 1)
            }
        }
        cleaned = cleaned.trim(' ', ',', '.', ';', ':', '-')
        cleaned = DANGLING_PREPOSITION_REGEX.replace(cleaned, "").trim(' ', ',', '.', ';', ':', '-')
        cleaned = LEADING_MODAL_REGEX.replace(cleaned, "").trim()

        cleaned = TITLE_CLAUSE_BREAK_REGEX.split(cleaned).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (cleaned.length > MAX_TITLE_CHARS) {
            val withIdx = cleaned.indexOf(" with ")
            if (withIdx > 0) cleaned = cleaned.substring(0, withIdx)
        }
        cleaned = clipAtWord(cleaned.trim(' ', ',', '.', ';', ':', '-'), MAX_TITLE_CHARS)
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    private fun resolveDate(content: String, baseDate: LocalDate): LocalDate {
        val lower = content.lowercase(Locale.US)
        if (DAY_AFTER_TOMORROW_REGEX.containsMatchIn(lower)) return baseDate.plusDays(2)
        if (Regex("""\btomorrow\b""").containsMatchIn(lower)) return baseDate.plusDays(1)
        if (Regex("""\b(?:today|tonight|this\s+(?:morning|afternoon|evening))\b""").containsMatchIn(lower)) {
            return baseDate
        }
        NEXT_WEEKDAY_REGEX.find(lower)?.let { match ->
            return baseDate.with(TemporalAdjusters.next(weekdayOf(match.groupValues[1])))
        }
        WEEKDAY_REGEX.find(lower)?.let { match ->
            return baseDate.with(TemporalAdjusters.nextOrSame(weekdayOf(match.groupValues[1])))
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
        return null
    }

    private fun resolveTime(content: String): LocalTime {
        val lower = content.lowercase(Locale.US)
        val clock = findClockTime(content)
        if (clock != null) {
            var hour = clock.hour
            val meridiem = clock.meridiem?.lowercase(Locale.US)?.replace(".", "")
            when {
                meridiem == "pm" && hour in 1..11 -> hour += 12
                meridiem == "am" && hour == 12 -> hour = 0
                meridiem == null && hour in 1..11 && EVENING_HINT_REGEX.containsMatchIn(lower) -> hour += 12
            }
            return LocalTime.of(hour.coerceIn(0, 23), clock.minute.coerceIn(0, 59))
        }
        return when {
            Regex("""\bnoon\b""").containsMatchIn(lower) -> LocalTime.of(12, 0)
            Regex("""\bmorning\b""").containsMatchIn(lower) -> LocalTime.of(9, 0)
            Regex("""\b(?:tonight|evening)\b""").containsMatchIn(lower) -> LocalTime.of(19, 30)
            Regex("""\bafternoon\b""").containsMatchIn(lower) -> LocalTime.of(15, 0)
            else -> LocalTime.of(10, 0)
        }
    }

    private data class EntityHit(val name: String, val start: Int, val end: Int)

    private fun extractRelations(content: String): List<RelationTriple> {
        val triples = ArrayList<RelationTriple>()

        EXPLICIT_ARROW_REGEX.findAll(content).forEach { match ->
            val src = match.groupValues[1].trim()
            val rel = match.groupValues[2].trim().ifEmpty { DEFAULT_RELATION }
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
                    relation = inferVerb(clause.substring(minOf(pronounEnd, object0.start), object0.start)) ?: DEFAULT_RELATION,
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
                        val label = inferVerb(verbSpan) ?: DEFAULT_RELATION
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
                triples += RelationTriple(source = ordered[i], target = ordered[i + 1], relation = DEFAULT_RELATION)
            }
        }
        return triples
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
            Regex("""\b(?:meet|meets|meeting|met)\b""").containsMatchIn(lower) -> "meets"
            lower.contains("review") -> "joint review"
            lower.contains("sign") -> "joint sign-off"
            else -> "works with"
        }
    }

    companion object {
        private const val MAX_BULLET_CHARS = 70
        private const val MAX_SINGLE_POINT_CHARS = 90
        private const val MAX_SUMMARY_CHARS = 300
        private const val MAX_TITLE_CHARS = 56
        private const val MIN_CLAUSE_CHARS = 20
        private const val DEFAULT_RELATION = "related to"
        private const val TAG_FOLLOW_UP = "Follow-up"

        private val BIDIRECTIONAL_LABELS = setOf("aligns with", "works with", "meets")

        /** Splits on newlines, semicolons, CJK sentence marks, and `.!?` followed by whitespace or end. */
        private val SENTENCE_SPLIT_REGEX = Regex("""\n+|[;；。！？]+|(?<=[.!?])(?=\s|$)""")
        private val BULLET_PREFIX_REGEX = Regex("""^(?:[•·\-*]|\d+[.)])\s*""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val LEADING_TOPIC_PREFIX_REGEX = Regex("""^[A-Z][A-Za-z\s-]{0,30}:\s+""")

        private val ACCOUNT_SECRET_PAIR_REGEX = Regex(
            """\b(login|credentials?|account|username)\s*(?:is|:|=)?\s*[^\s,;/]+\s*/\s*[^\s,;]*[^\s,;.]""",
            RegexOption.IGNORE_CASE,
        )
        private val PASSWORD_SECRET_REGEX = Regex(
            """\b(password|passcode|pin|pwd|otp|verification code|api key|token)(?:\s*(?:is|:|=)\s*|\s+)(?=\S*[\d@#$%^&*!])[^\s,;]*[^\s,;.]""",
            RegexOption.IGNORE_CASE,
        )

        private fun keywords(vararg words: String): Regex =
            Regex(words.joinToString("|", prefix = """\b(?:""", postfix = """)\b""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

        private val TAG_KEYWORDS: List<Pair<String, Regex>> = listOf(
            "Food" to keywords("lunch", "dinner", "breakfast", "brunch", "coffee", "meal", "restaurant", "takeout", "eat"),
            "Errands" to keywords("parcel", "package", "groceries", "pick up", "buy", "rent", "bill", "laundry"),
            "Travel" to keywords("flight", "train", "airport", "hotel", "taxi", "station", "trip", "boarding"),
            "Health" to keywords("doctor", "dentist", "hospital", "clinic", "medicine", "pill", "workout", "gym", "check-up", "checkup"),
            "Supply chain" to keywords("vendor", "supplier", "procurement", "sample", "samples", "quote", "quoted", "shipment", "factory", "mass-production", "module", "burn-in"),
            "Legal" to keywords("contract", "agreement", "clause", "legal", "indemnity", "warranty", "nda", "compliance", "addendum"),
            "Finance" to keywords("invoice", "payment", "deposit", "escrow", "budget", "finance", "reimbursement", "payroll", "tax"),
            "Credentials" to keywords("password", "passcode", "login", "pin", "token", "api key", "wifi", "wi-fi", "credentials"),
            "Math" to keywords("equation", "integral", "derivative", "theorem", "calculate", "algebra", "proof"),
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

        private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        private val DAY_AFTER_TOMORROW_REGEX = Regex("""\bday\s+after\s+tomorrow\b""", RegexOption.IGNORE_CASE)
        private val NEXT_WEEKDAY_REGEX = Regex("""\bnext\s+($WEEKDAYS)\b""")
        private val WEEKDAY_REGEX = Regex("""\b($WEEKDAYS)\b""")
        private val DATE_KEYWORD_REGEX = Regex(
            """\b(?:today|tonight|tomorrow|day\s+after\s+tomorrow|$WEEKDAYS)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DAY_PART_REGEX = Regex("""\b(?:this\s+(?:morning|afternoon|evening)|noon)\b""", RegexOption.IGNORE_CASE)
        private val EVENING_HINT_REGEX = Regex("""\b(?:tonight|evening|afternoon)\b""")

        private val COLON_TIME_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?:\s*([ap]\.?m\.?)(?![a-z]))?""", RegexOption.IGNORE_CASE)
        private val MERIDIEM_TIME_REGEX = Regex("""\b(\d{1,2})\s*([ap]\.?m\.?)(?![a-z])""", RegexOption.IGNORE_CASE)
        private val BARE_AT_TIME_REGEX = Regex("""\bat\s+(\d{1,2})(?:\s*o'clock)?\b(?![:.\-]\d)(?!\s*(?:%|hours?|minutes?|mins?|days?))""", RegexOption.IGNORE_CASE)

        private val CLOCK_STRIP_REGEX = Regex(
            """\b(?:at\s+)?\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?(?![a-z])|\b(?:at\s+)?\d{1,2}:\d{2}|\bat\s+\d{1,2}\s*o'clock\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DATE_STRIP_REGEX = Regex(
            """\b(?:the\s+)?day\s+after\s+tomorrow\b|\b(?:(?:next|this|on|by)\s+)?(?:$WEEKDAYS)\b|\btomorrow(?:\s+(?:morning|afternoon|evening|night))?\b|\b(?:today|tonight|noon)\b|\bthis\s+(?:morning|afternoon|evening)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DANGLING_PREPOSITION_REGEX = Regex("""^(?:(?:at|on|by|in)\s+)+|(?:\s+(?:at|on|by|in))+$""", RegexOption.IGNORE_CASE)
        private val LEADING_MODAL_REGEX = Regex(
            """^(?:please\s+)?(?:remember\s+to|remind\s+me\s+to|don'?t\s+forget\s+to|i\s+(?:need|have)\s+to|need\s+to|have\s+to|gotta|must)\s+""",
            RegexOption.IGNORE_CASE,
        )
        private val TITLE_CLAUSE_BREAK_REGEX = Regex(""",\s*then\b|;|(?<=[.!?])\s+|,\s*and then\b""", RegexOption.IGNORE_CASE)

        private const val PLACE_UNIT = "(?:Building|Conference Room|Meeting Room|Room|Floor|Suite|Hall|Office|Level|Block|Gate|Terminal)"
        private val STRUCTURED_LOCATION_REGEX = Regex(
            """\b(?:in|at)\s+((?:the\s+)?$PLACE_UNIT\s+[A-Za-z0-9-]+(?:,\s*$PLACE_UNIT\s+[A-Za-z0-9-]+)*)""",
        )
        private val VENUE_LOCATION_REGEX = Regex(
            """\b(?:in|at)\s+(?:the\s+)?((?:[A-Z][\w'&-]*\s+){0,3}(?:Office|Hotel|Airport|Station|Hospital|Clinic|Cafe|Café|Restaurant|Center|Centre|Park|Mall|Campus|Tower|Plaza|Library|Gym))\b""",
        )

        private val EXPLICIT_ARROW_REGEX = Regex(
            """([\p{L}\p{N}_][\p{L}\p{N}_ .'-]{0,30}?)\s*-{1,2}\s*\[?([^\]\->\n]{1,24}?)\]?\s*-*>\s*([\p{L}\p{N}_][\p{L}\p{N}_ .'-]{0,30})""",
        )
        private val CLAUSE_SPLIT_REGEX = Regex("""\n+|[;；。！？]+|(?<=[.!?])\s+|,\s+|:\s+""")
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
        )

        internal fun clipAtWord(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val hardCut = text.take(maxChars - 1)
            val lastSpace = hardCut.lastIndexOf(' ')
            val cut = if (lastSpace >= maxChars / 2) hardCut.substring(0, lastSpace) else hardCut
            return cut.trimEnd(' ', ',', ';', ':', '-') + "…"
        }

        fun sanitizeRelations(raw: List<RelationTriple>): List<RelationTriple> {
            val byUndirectedKey = LinkedHashMap<Pair<String, String>, RelationTriple>()
            val uniqueNodes = LinkedHashSet<String>()

            for (item in raw) {
                val src = item.source.trim().take(32)
                val tgt = item.target.trim().take(32)
                val rel = item.relation.trim().ifEmpty { DEFAULT_RELATION }.take(24)
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
                        "${existing.relation}/$rel".take(24)
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
