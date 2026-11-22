package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.RelationTriple
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json

/**
 * Coordinates prompt construction, white-box model output reconciliation, and grounded offline
 * fallback extraction by delegating JSON repair to [LenientAnalysisJsonParser] and deterministic
 * rule extraction to [HeuristicMemoryExtractor].
 */
class MemoryAnalyzer(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val lenientJson: Json = Json(json) {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val jsonParser = LenientAnalysisJsonParser(lenientJson)
    private val heuristicExtractor = HeuristicMemoryExtractor(zoneId)

    fun buildEdgeAnalysisUserPrompt(content: String, nowMillis: Long = System.currentTimeMillis()): String {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val formattedNow = nowLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)", Locale.US))
        return EdgePromptTemplates.buildDistillationUserPrompt(
            content = content,
            formattedNow = formattedNow,
            hasScheduleSignal = heuristicExtractor.hasTemporalScheduleSignal(content),
        )
    }

    fun mergeModelJsonOrNull(
        rawText: String,
        rawContent: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MemoryAnalysisPayload? {
        val parsedFromModel = parseJsonPayload(rawText) ?: return null
        val heuristic by lazy { fallbackAnalyze(rawContent, nowMillis) }

        val isPinyinInput = EdgePromptTemplates.isRomanizedPinyin(rawContent)
        val rawContentLower = rawContent.lowercase(Locale.US)
        val hasPinyinDrinkClause = isPinyinInput && heuristicExtractor.hasPinyinDrinkClause(rawContentLower)
        val keptModelRelations = ArrayList<RelationTriple>()
        for (triple in parsedFromModel.relations) {
            val srcOk = isEntityGroundedInContent(triple.source, rawContent)
            val tgtOk = isEntityGroundedInContent(triple.target, rawContent)
            val badPinyinDrink = isPinyinInput &&
                triple.relation.contains("\u559D") &&
                (!hasPinyinDrinkClause || !heuristicExtractor.isKnownPinyinDrinkLabel(triple.target))
            if (srcOk && tgtOk && !badPinyinDrink) {
                keptModelRelations += triple
            }
        }
        val groundedModelRelations = sanitizeRelations(keptModelRelations)
        val cleanedRelations = when {
            groundedModelRelations.isEmpty() -> heuristic.relations
            groundedModelRelations.size < heuristic.relations.size -> {
                val presentKeys = groundedModelRelations
                    .map { undirectedLowerKey(it.source, it.target) }
                    .toHashSet()
                val supplemented = groundedModelRelations + heuristic.relations.filter { edge ->
                    undirectedLowerKey(edge.source, edge.target) !in presentKeys
                }
                sanitizeRelations(supplemented)
            }
            else -> groundedModelRelations
        }

        val resolvedCalendar = validateCalendarProposal(
            proposal = parsedFromModel.calendarProposal,
            rawContent = rawContent,
            nowMillis = nowMillis,
            heuristicProposal = heuristic.calendarProposal,
        ) ?: if (parsedFromModel.calendarProposal == null) heuristic.calendarProposal else null

        val heuristicEntities = buildSet {
            heuristic.relations.forEach { edge ->
                add(edge.source)
                add(edge.target)
            }
            heuristicExtractor.findEntityNames(rawContent).forEach { add(it) }
        }
        val cjkRelationEntities = buildSet {
            heuristic.relations.forEach { edge ->
                if (CJK_RUN_REGEX.containsMatchIn(edge.source)) add(edge.source)
                if (CJK_RUN_REGEX.containsMatchIn(edge.target)) add(edge.target)
            }
        }
        val normalizedModelSummary = normalizeBulletSummary(
            redactSensitiveDetails(parsedFromModel.summary),
        )
        val degenerateSummary = isDegenerateModelSummary(
            summary = normalizedModelSummary,
            rawContent = rawContent,
            heuristicEntities = heuristicEntities,
            cjkRelationEntities = cjkRelationEntities,
        )
        val resolvedSummary = if (degenerateSummary) {
            heuristic.summary
        } else {
            normalizedModelSummary
        }

        val resolvedTags = if (degenerateSummary) {
            heuristic.tags
        } else {
            filterAndNormalizeTags(
                rawTags = parsedFromModel.tags,
                rawContent = rawContent,
                cleanedRelations = cleanedRelations,
                heuristicEntities = heuristicEntities,
                hasResolvedCalendar = resolvedCalendar != null,
            ).ifEmpty { heuristic.tags }
        }
        return parsedFromModel.copy(
            summary = resolvedSummary,
            tags = resolvedTags,
            calendarProposal = resolvedCalendar,
            relations = cleanedRelations,
        )
    }

    fun fallbackAnalyze(
        content: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MemoryAnalysisPayload = heuristicExtractor.fallbackAnalyze(content, nowMillis)

    internal fun normalizeBulletSummary(rawSummary: String): String =
        Companion.normalizeBulletSummary(rawSummary)

    internal fun redactSensitiveDetails(text: String): String =
        Companion.redactSensitiveDetails(text)

    internal fun parseJsonPayload(rawText: String): MemoryAnalysisPayload? =
        jsonParser.parseJsonPayload(rawText)

    /** Returns a structural, non-PII diagnostic tag explaining why [rawText] failed to parse. */
    internal fun diagnoseParseFailure(rawText: String): String =
        jsonParser.diagnoseParseFailure(rawText)

    internal fun repairModelJsonCandidate(rawText: String): String? =
        jsonParser.repairModelJsonCandidate(rawText)

    internal fun extractLocation(content: String): String? =
        heuristicExtractor.extractLocation(content)

    internal fun parseRelativeDurationMinutes(content: String): Long? =
        heuristicExtractor.parseRelativeDurationMinutes(content)

    internal fun extractCleanTitle(content: String): String =
        heuristicExtractor.extractCleanTitle(content)

    internal fun hasTemporalScheduleSignal(content: String): Boolean =
        heuristicExtractor.hasTemporalScheduleSignal(content)

    internal fun validateCalendarProposal(
        proposal: CalendarProposal?,
        rawContent: String,
        nowMillis: Long = System.currentTimeMillis(),
        heuristicProposal: CalendarProposal? = null,
    ): CalendarProposal? {
        if (proposal == null || !hasTemporalScheduleSignal(rawContent)) return null
        val rawTitle = proposal.title.trim()
        if (rawTitle.lowercase(Locale.US) in PLACEHOLDER_CALENDAR_TITLES) return null
        val title = rawTitle.ifEmpty { heuristicProposal?.title.orEmpty() }.trim()
        if (title.isEmpty() || title.lowercase(Locale.US) in PLACEHOLDER_CALENDAR_TITLES) return null
        val normalizedIso = proposal.startTimeIso.trim().replace(' ', 'T')
        val wallClockIso = if (normalizedIso.endsWith("Z", ignoreCase = true)) {
            normalizedIso.dropLast(1)
        } else {
            normalizedIso
        }
        var parsedDt = runCatching { LocalDateTime.parse(wallClockIso) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(normalizedIso).atZoneSameInstant(zoneId).toLocalDateTime() }.getOrNull()
            ?: heuristicProposal?.let { runCatching { LocalDateTime.parse(it.startTimeIso) }.getOrNull() }
            ?: return null

        if (heuristicProposal != null) {
            val heuristicDt = runCatching { LocalDateTime.parse(heuristicProposal.startTimeIso) }.getOrNull()
            if (heuristicDt != null) {
                val baseYear = Instant.ofEpochMilli(nowMillis).atZone(zoneId).year
                val hasExplicitYear = heuristicExtractor.hasExplicitYear(rawContent)
                val hasExplicitNumericDate = heuristicExtractor.hasExplicitNumericDate(rawContent)
                val hasRelativeShift = heuristicExtractor.hasRelativeDateShift(rawContent, hasExplicitNumericDate)
                val staleYear = !hasExplicitYear && parsedDt.year < baseYear
                if (staleYear || hasRelativeShift || hasExplicitNumericDate) {
                    val hasUnambiguousClock = heuristicExtractor.hasClockTime(rawContent) ||
                        parseRelativeDurationMinutes(rawContent) != null
                    parsedDt = if (hasUnambiguousClock) {
                        heuristicDt
                    } else {
                        LocalDateTime.of(heuristicDt.toLocalDate(), parsedDt.toLocalTime())
                    }
                }
            }
        }

        val cleanLocation = proposal.location
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() &&
                    !it.equals("null", ignoreCase = true) &&
                    (heuristicProposal == null || isEntityGroundedInContent(it, rawContent))
            }
            ?: heuristicProposal?.location
        return proposal.copy(
            title = clipAtWord(title, MAX_TITLE_CHARS),
            startTimeIso = parsedDt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            durationMinutes = proposal.durationMinutes.coerceIn(5, 480),
            location = cleanLocation,
        )
    }

    internal fun isEntityGroundedInContent(entity: String, rawContent: String): Boolean {
        val clean = entity.trim()
        if (clean.isEmpty()) return false
        val hasCjkEntity = CJK_RUN_REGEX.containsMatchIn(clean)
        if (CJK_SENTENCE_PUNCT_REGEX.containsMatchIn(clean)) return false
        if (hasCjkEntity && (clean.contains(',') || clean.length > 10)) return false
        if (!hasCjkEntity && clean.length < 2) return false
        val lower = clean.lowercase(Locale.US)
        if (lower in PLACEHOLDER_ENTITIES) return false
        val contentLower = rawContent.lowercase(Locale.US)
        if (!hasCjkEntity && lower.length <= 3) {
            return hasWholeLatinToken(contentLower, lower)
        }
        if (contentLower.contains(lower)) return true
        if (hasCjkEntity && EdgePromptTemplates.isRomanizedPinyin(rawContent)) {
            return heuristicExtractor.isGroundedPinyinEntity(clean, contentLower)
        }
        val latinTokens = ENTITY_TOKEN_REGEX.findAll(lower).map { it.value }.toList()
        val cjkTokens = CJK_RUN_REGEX.findAll(clean).map { it.value }.toList()
        if (latinTokens.isEmpty() && cjkTokens.isEmpty()) return false
        return latinTokens.all { contentLower.contains(it) } &&
            cjkTokens.all { rawContent.contains(it) }
    }

    private fun filterAndNormalizeTags(
        rawTags: List<String>,
        rawContent: String,
        cleanedRelations: List<RelationTriple>,
        heuristicEntities: Set<String>,
        hasResolvedCalendar: Boolean,
    ): List<String> {
        val entityNamesLower = buildSet {
            cleanedRelations.forEach { edge ->
                val sLower = edge.source.lowercase(Locale.US)
                val tLower = edge.target.lowercase(Locale.US)
                add(sLower)
                add(tLower)
                add(NON_ALNUM_ASCII_REGEX.replace(sLower, ""))
                add(NON_ALNUM_ASCII_REGEX.replace(tLower, ""))
            }
            heuristicEntities.forEach { entity ->
                val eLower = entity.lowercase(Locale.US)
                add(eLower)
                add(NON_ALNUM_ASCII_REGEX.replace(eLower, ""))
            }
        }
        val personEntityTokens = buildSet {
            for (entity in heuristicEntities) {
                val parts = entity.trim().lowercase(Locale.US).split(WHITESPACE_REGEX)
                if (parts.size in 2..3 && parts.none { it in NON_PERSON_ENTITY_WORDS }) {
                    parts.filter { it.length >= 4 }.forEach { add(it) }
                }
            }
        }
        val isPinyinNote = EdgePromptTemplates.isRomanizedPinyin(rawContent)
        val hasCjkNote = CJK_RUN_REGEX.containsMatchIn(rawContent)
        val hasCjkIntent = hasCjkNote || isPinyinNote
        val isPureCjkNote = hasCjkNote && !LATIN_LETTER_REGEX.containsMatchIn(rawContent)
        val seenTags = HashSet<String>()
        val modelTags = ArrayList<String>(3)
        for (rawTag in rawTags) {
            val tag = rawTag.trim().removePrefix("#").replace(WHITESPACE_REGEX, " ").trim()
            if (tag.isEmpty() || tag.length > MAX_TAG_CHARS) continue
            if ((isPureCjkNote || isPinyinNote) && LATIN_LETTER_REGEX.containsMatchIn(tag)) continue
            if (!hasCjkIntent && CJK_RUN_REGEX.containsMatchIn(tag)) continue
            val lower = tag.lowercase(Locale.US)
            val lowerCollapsed = NON_ALNUM_ASCII_REGEX.replace(lower, "")
            val matchesCollapsedEntity = lowerCollapsed.length >= 4 && lowerCollapsed in entityNamesLower
            val matchesPersonToken = !heuristicExtractor.isKnownSceneTag(lower) && (
                lower in personEntityTokens ||
                    (!lower.contains(' ') && lower.length >= 5 && personEntityTokens.any { pt -> lower.startsWith(pt) })
                )
            if (lower in GENERIC_STOP_TAGS || lower in entityNamesLower || matchesCollapsedEntity || matchesPersonToken) {
                continue
            }
            if (!hasCjkIntent &&
                rawContent.trim().length >= 40 &&
                !isTagGrounded(lower, rawContent, hasResolvedCalendar)
            ) {
                continue
            }
            if (seenTags.add(lower)) {
                modelTags += tag
            }
            if (modelTags.size >= 3) break
        }
        return modelTags
    }

    private fun extractPinyinLatinInitials(rawContent: String): List<String> =
        PINYIN_INITIAL_TOKEN_REGEX.findAll(rawContent.lowercase(Locale.US))
            .map { it.groupValues[1] }
            .filter { tok -> tok !in EdgePromptTemplates.GENERAL_PINYIN_SYLLABLES && tok !in ENGLISH_SHORT_CONNECTORS }
            .distinct()
            .toList()

    private fun hasWholeLatinToken(textLower: String, tokenLower: String): Boolean =
        Regex("""(?:^|[^a-z0-9])${Regex.escape(tokenLower)}(?:[^a-z0-9]|$)""").containsMatchIn(textLower)

    private fun extractCjkBigrams(text: String): Set<String> {
        val out = HashSet<String>()
        for (match in CJK_RUN_REGEX.findAll(text)) {
            val run = match.value
            if (run.length < 2) continue
            for (i in 0 until run.length - 1) {
                out += run.substring(i, i + 2)
            }
        }
        return out
    }

    private fun isDegenerateModelSummary(
        summary: String,
        rawContent: String,
        heuristicEntities: Set<String>,
        cjkRelationEntities: Set<String>,
    ): Boolean {
        if (summary.isBlank()) return true
        val lower = summary.lowercase(Locale.US)
        if (PROMPT_PLACEHOLDER_PHRASES.any { lower.contains(it) }) return true
        val trimmedContent = rawContent.trim()
        if (trimmedContent.length >= 80 && summary.length < 28) return true
        if (trimmedContent.length >= 60) {
            val summaryEntities = heuristicExtractor.findEntityNames(summary)
            if (summaryEntities.any { !isEntityGroundedInContent(it, rawContent) }) {
                return true
            }
        }
        if (trimmedContent.length >= 120 &&
            heuristicEntities.size >= 4 &&
            summary.lines().size == 1 &&
            summary.length < 75
        ) {
            val coveredCount = heuristicEntities.count { entity ->
                val entityLower = entity.lowercase(Locale.US)
                lower.contains(entityLower) ||
                    ENTITY_TOKEN_REGEX.findAll(entityLower).any { tok -> lower.contains(tok.value) }
            }
            if (coveredCount < 2) return true
        }
        val isPinyinNote = EdgePromptTemplates.isRomanizedPinyin(rawContent)
        if (isPinyinNote) {
            if (!CJK_RUN_REGEX.containsMatchIn(summary)) return true
            val contentLower = rawContent.lowercase(Locale.US)
            if (PINYIN_DRINK_INITIAL_REGEX.containsMatchIn(lower)) {
                return true
            }
            // Reject hallucinated drink claims when romanized `he` functions as comitative preposition ("with").
            if (!heuristicExtractor.hasPinyinDrinkClause(contentLower) &&
                CJK_DRINK_CLAIM_REGEX.containsMatchIn(summary)
            ) {
                return true
            }
            val expectedInitials = extractPinyinLatinInitials(rawContent)
            if (expectedInitials.isNotEmpty() &&
                expectedInitials.any { tok -> !hasWholeLatinToken(lower, tok) }
            ) {
                return true
            }
        }
        val hasCjkRaw = CJK_RUN_REGEX.containsMatchIn(rawContent)
        if (hasCjkRaw) {
            val rawBigrams = extractCjkBigrams(rawContent)
            val summaryWithoutRedaction = summary.replace(CJK_REDACTION_SUFFIX, "")
            val summaryBigrams = extractCjkBigrams(summaryWithoutRedaction)
            if (rawBigrams.size >= 3 && summaryBigrams.isNotEmpty() && summaryBigrams.none { it in rawBigrams }) {
                return true
            }
            // Reject topical drift that drops every extracted CJK entity and shares <1/3 of bigrams.
            if (cjkRelationEntities.size >= 2 &&
                cjkRelationEntities.none { ent -> summary.contains(ent) } &&
                summaryBigrams.size >= 4 &&
                summaryBigrams.count { it in rawBigrams } * 3 < summaryBigrams.size
            ) {
                return true
            }
        }
        if (!hasCjkRaw &&
            !isPinyinNote &&
            trimmedContent.length >= 40 &&
            summary.lines().size == 1 &&
            summary.length < 32
        ) {
            val rawStems = WORD_STEM_REGEX.findAll(rawContent.lowercase(Locale.US))
                .map { it.value }
                .filter { it !in COMMON_STOP_STEMS }
                .toSet()
            val summaryStems = WORD_STEM_REGEX.findAll(lower)
                .map { it.value }
                .filter { it !in COMMON_STOP_STEMS }
                .toSet()
            if (rawStems.size >= 4 &&
                summaryStems.isNotEmpty() &&
                summaryStems.none { sw ->
                    rawStems.any { rw -> rw.take(3) == sw.take(3) || rw.contains(sw) || sw.contains(rw) }
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun isTagGrounded(
        tagLower: String,
        rawContent: String,
        hasSchedule: Boolean,
    ): Boolean {
        heuristicExtractor.matchSceneTag(tagLower, rawContent, hasSchedule)?.let { return it }
        val contentLower = rawContent.lowercase(Locale.US)
        if (contentLower.contains(tagLower)) return true
        val contentWords = WORD_STEM_REGEX.findAll(contentLower).map { it.value }.toSet()
        val tagWords = WORD_STEM_REGEX.findAll(tagLower).map { it.value }.toList()
        return tagWords.isNotEmpty() && tagWords.all { tw ->
            val singular = tw.removeSuffix("s")
            contentLower.contains(tw) ||
                (singular.length >= 4 && contentLower.contains(singular)) ||
                (tw.length >= 5 && contentWords.any { cw -> abs(cw.length - tw.length) <= 4 && cw.startsWith(tw.take(5)) })
        }
    }

    private fun undirectedLowerKey(a: String, b: String): Pair<String, String> {
        val la = a.trim().lowercase(Locale.US)
        val lb = b.trim().lowercase(Locale.US)
        return if (la <= lb) la to lb else lb to la
    }

    companion object {
        internal const val MAX_BULLET_CHARS = 70
        internal const val MAX_CJK_BULLET_CHARS = 28
        internal const val MAX_SINGLE_POINT_CHARS = 90
        private const val MAX_CJK_SINGLE_POINT_CHARS = 42
        internal const val MAX_SUMMARY_CHARS = 300
        internal const val MAX_TITLE_CHARS = 56
        private const val MAX_TAG_CHARS = 28
        private const val MIN_CLAUSE_CHARS = 20
        private const val MIN_CJK_CLAUSE_CHARS = 8
        internal const val DEFAULT_RELATION = "related to"
        private const val CJK_REDACTION_SUFFIX = "\u5DF2\u4FDD\u5B58\uFF08\u8BE6\u89C1\u539F\u6587\uFF09"

        private val LATIN_LETTER_REGEX = Regex("""[a-zA-Z]""")
        private val CJK_SENTENCE_PUNCT_REGEX = Regex("""[\uFF0C\u3002\uFF1B\uFF1F\uFF01;!?]""")
        private val PINYIN_INITIAL_TOKEN_REGEX = Regex("""\b([a-z]{2,3})\b""")
        private val PINYIN_DRINK_INITIAL_REGEX = Regex("""\u559D\s*[a-z]{2,3}(?:[^a-z]|$)""")
        private val CJK_DRINK_CLAIM_REGEX = Regex("""\u559D[\u4E00-\u9FFF]{1,4}""")
        private val ENGLISH_SHORT_CONNECTORS = setOf("to", "in", "on", "at", "by", "of", "is", "it", "as", "or", "so", "my", "we")

        /** Splits on newlines, semicolons, CJK sentence marks, and `.!?` followed by whitespace or end. */
        private val SENTENCE_SPLIT_REGEX = Regex("""\n+|[;\uFF1B\u3002\uFF01\uFF1F]+|(?<=[.!?])(?=\s|$)""")
        private val BULLET_PREFIX_REGEX = Regex("""^(?:[\u2022\u00B7\-*]|\d+[.)])\s*""")
        private val SUMMARY_ARTIFACT_PREFIX_REGEX = Regex(
            """^(?:(?:first|second|third|fourth|next|final)\s+point|point\s*\d+|bullet\s*\d+|key\s+point\s*\d*|summary|note|\u8981\u70B9[\u4E00\u4E8C\u4E09\u56DB1-4]?|\u6458\u8981)\s*[:\uFF1A\-\u2013\u2014]\s*""",
            RegexOption.IGNORE_CASE,
        )
        internal val WHITESPACE_REGEX = Regex("""\s+""")

        private val GENERIC_STOP_TAGS = setOf(
            "tag", "tags", "note", "notes", "detail", "details", "point",
            "first point", "second point", "summary", "topic", "project",
            "project sync", "general", "info", "misc", "other", "none", "n/a", "memo",
            "\u7B14\u8BB0", "\u5907\u5FD8", "\u5185\u5BB9", "\u8BE6\u60C5",
            "\u6458\u8981", "\u6807\u7B7E", "\u573A\u666F\u6807\u7B7E",
            "\u5176\u4ED6", "\u9879\u76EE", "\u7B14\u8BB0\u5185\u5BB9",
            "\u767D\u76D2", "\u767D\u76D2\u5B50", "\u8BB0\u5FC6\u52A9\u624B",
            "\u8BB0\u5FC6", "\u5370\u8FF9", "\u52A9\u624B", "\u65E0", "\u65E0\u610F\u4E49",
        )
        private val PLACEHOLDER_CALENDAR_TITLES = setOf(
            "event title", "title", "untitled", "placeholder", "<event>", "event", "none", "null",
            "\u65E5\u7A0B\u4E8B\u9879",
        )
        internal val PLACEHOLDER_ENTITIES = setOf(
            "source", "target", "entity", "entity a", "entity b", "person", "item", "n/a", "none", "null",
            "\u539F\u6587", "\u5929", "\u65E5", "\u6708", "\u5E74", "\u4EBA", "\u4E8B", "\u65F6",
        )
        private val PROMPT_PLACEHOLDER_PHRASES = listOf(
            "<concise fact>", "concise fact", "concise bullet", "short fact", "event title", "first point", "second point",
            "\u8981\u70B9\u4E00", "\u8981\u70B9\u4E8C", "\u767D\u76D2\u8BB0\u5FC6", "\u8BB0\u5FC6\u52A9\u624B",
        )
        private val ENTITY_TOKEN_REGEX = Regex("""[\p{L}\p{N}][\p{L}\p{N}-]{2,}""")
        private val WORD_STEM_REGEX = Regex("""[a-z0-9]{3,}""")
        internal val CJK_RUN_REGEX = Regex("""[\u4e00-\u9fff]+""")
        private val NON_ALNUM_ASCII_REGEX = Regex("""[^a-z0-9\u4e00-\u9fff]""")
        private val COMMON_STOP_STEMS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "into",
            "have", "need", "will", "after", "before", "tomorrow", "today",
        )
        private val NON_PERSON_ENTITY_WORDS = setOf(
            "agreement", "contract", "supply", "display", "oled", "micro",
            "room", "building", "cafe", "office", "hotel", "clinic",
            "hospital", "airport", "station", "center", "park", "tower",
        )

        private val ACCOUNT_SECRET_PAIR_REGEX = Regex(
            """\b(login|credentials?|account|username)\s*(?:is|:|=)?\s*[^\s,;/]+\s*/\s*[^\s,;]*[^\s,;.]""",
            RegexOption.IGNORE_CASE,
        )
        private val PASSWORD_SECRET_REGEX = Regex(
            """\b(password|passcode|pin|pwd|otp|verification code|api key|token)(?:\s*(?:is|:|=)\s*|\s+)(?=\S*[\d@#$%^&*!])[^\s,;]*[^\s,;.]""",
            RegexOption.IGNORE_CASE,
        )
        private val CJK_PASSWORD_SECRET_REGEX = Regex(
            """(?:\u5BC6\u7801|\u53E3\u4EE4)(?:\u662F|\u4E3A|:|\uFF1A|=|\s)*\s*(?=\S*[\d@#$%^&*!A-Za-z])[A-Za-z0-9@#$%^&*!._-]{4,}""",
        )

        /**
         * Normalizes free-form model or rule output into at most four concise points.
         * Splits on newlines and sentence punctuation while keeping decimals and clock
         * times such as `3:00` or `10.5` intact.
         */
        internal fun normalizeBulletSummary(rawSummary: String): String {
            if (rawSummary.isBlank()) return ""
            val isCjk = CJK_RUN_REGEX.containsMatchIn(rawSummary)
            val maxBullet = if (isCjk) MAX_CJK_BULLET_CHARS else MAX_BULLET_CHARS
            val minClause = if (isCjk) MIN_CJK_CLAUSE_CHARS else MIN_CLAUSE_CHARS
            val maxSingle = if (isCjk) MAX_CJK_SINGLE_POINT_CHARS else MAX_SINGLE_POINT_CHARS
            val joinSep = if (isCjk) "\uFF0C" else ", "

            val rawSegments = SENTENCE_SPLIT_REGEX.split(rawSummary.replace("\r\n", "\n"))
                .map { segment ->
                    stripLeadingSummaryArtifact(segment)
                        .trim(' ', '\t', ',', '\uFF0C', '\u3001', '\u2022', '\u00B7')
                        .trimEnd('.')
                        .trim()
                }
                .filter { it.isNotBlank() }

            val concisePoints = ArrayList<String>(4)
            for (segment in rawSegments) {
                if (segment.length > maxBullet && (segment.contains(", ") || segment.contains('\uFF0C'))) {
                    val subClauses = segment.split(", ", "\uFF0C")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    var carry = ""
                    for (clause in subClauses) {
                        val combined = if (carry.isEmpty()) clause else "$carry$joinSep$clause"
                        if (combined.length < minClause) {
                            carry = combined
                        } else {
                            concisePoints += combined
                            carry = ""
                        }
                    }
                    if (carry.isNotBlank()) {
                        if (concisePoints.isNotEmpty() && carry.length < minClause) {
                            val lastIdx = concisePoints.lastIndex
                            concisePoints[lastIdx] = "${concisePoints[lastIdx]}$joinSep$carry"
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
                capped.size == 1 -> clipAtWord(capped[0], maxSingle)
                else -> capped.joinToString("\n") { "\u2022 ${clipAtWord(it, maxBullet)}" }.take(MAX_SUMMARY_CHARS)
            }
        }

        internal fun redactSensitiveDetails(text: String): String {
            var result = ACCOUNT_SECRET_PAIR_REGEX.replace(text) { match ->
                "${match.groupValues[1]} saved (see source)"
            }
            result = PASSWORD_SECRET_REGEX.replace(result) { match ->
                "${match.groupValues[1]} saved (see source)"
            }
            result = CJK_PASSWORD_SECRET_REGEX.replace(
                result,
                "\u5BC6\u7801$CJK_REDACTION_SUFFIX",
            )
            return result
        }

        internal fun stripLeadingSummaryArtifact(segment: String): String {
            var current = segment.trim()
            repeat(2) {
                current = BULLET_PREFIX_REGEX.replace(current, "").trimStart()
                current = SUMMARY_ARTIFACT_PREFIX_REGEX.replace(current, "").trimStart()
            }
            return current
        }

        internal fun cleanStreamingPreviewText(text: String): String {
            if (text.isEmpty()) return ""
            return text.replace("\r\n", "\n")
                .split('\n')
                .joinToString("\n") { line ->
                    val trimmedStart = line.trimStart()
                    val hasBullet = trimmedStart.startsWith('\u2022') || trimmedStart.startsWith('\u00B7')
                    val stripped = stripLeadingSummaryArtifact(trimmedStart)
                    when {
                        stripped.isEmpty() -> if (hasBullet) "\u2022 " else ""
                        hasBullet -> "\u2022 $stripped"
                        else -> stripped
                    }
                }
        }

        internal fun clipAtWord(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val hardCut = text.take(maxChars - 1)
            val lastSpace = hardCut.lastIndexOf(' ')
            val cut = if (lastSpace >= maxChars / 2) hardCut.substring(0, lastSpace) else hardCut
            return cut.trimEnd(' ', ',', ';', ':', '-') + "\u2026"
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
