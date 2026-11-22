package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.RelationTriple
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lenient JSON parser and structural repair utility for edge and cloud distillation payloads.
 */
internal class LenientAnalysisJsonParser(
    private val lenientJson: Json,
) {
    fun parseJsonPayload(rawText: String): MemoryAnalysisPayload? {
        val candidate = repairModelJsonCandidate(rawText) ?: return null
        val root = runCatching { lenientJson.parseToJsonElement(candidate) }
            .getOrNull() as? JsonObject ?: return null
        val directSummary = (root["summary"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val rootCalTitle = if (!root.containsKey("summary") &&
            (root.containsKey("startTimeIso") || root.containsKey("start_time_iso") || root.containsKey("startTime"))
        ) {
            stringField(root, "title", "event", "name")
        } else {
            null
        }
        val summary = directSummary ?: rootCalTitle ?: return null
        val tags = extractTags(root)
        val cal = extractCalendarProposal(root) ?: if (directSummary == null) extractCalendarProposalFromObject(root) else null
        val rels = extractRelations(root)
        return MemoryAnalysisPayload(
            summary = summary,
            tags = tags,
            calendarProposal = cal,
            relations = rels,
        )
    }

    /** Returns a structural, non-PII diagnostic tag explaining why [rawText] failed to parse. */
    fun diagnoseParseFailure(rawText: String): String {
        if (rawText.isBlank()) return "blank_input"
        val candidate = repairModelJsonCandidate(rawText)
        if (candidate == null) {
            val cleaned = rawText
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val start = cleaned.indexOf('{')
            if (start < 0) {
                return "repair_null(hasBrace=false,openBraces=0,openBrackets=0,inString=false)"
            }
            var inStr = false
            var openBraces = 0
            var openBrackets = 0
            var idx = start
            while (idx < cleaned.length) {
                val c = cleaned[idx]
                if (inStr) {
                    if (c == '\\') {
                        idx += 2
                        continue
                    } else if (c == '"') {
                        inStr = false
                    }
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> openBraces++
                        '}' -> openBraces--
                        '[' -> openBrackets++
                        ']' -> openBrackets--
                    }
                }
                idx++
            }
            return "repair_null(hasBrace=true,openBraces=$openBraces,openBrackets=$openBrackets,inString=$inStr)"
        }
        val parsed = runCatching { lenientJson.parseToJsonElement(candidate) }
        val error = parsed.exceptionOrNull()
        if (error != null) {
            return "json_syntax(${error::class.simpleName ?: "Error"})"
        }
        val elem = parsed.getOrNull()
        if (elem !is JsonObject) {
            return "not_json_object(${elem?.let { it::class.simpleName } ?: "Null"})"
        }
        val summary = (elem["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val salvagedRootTitle = if (!elem.containsKey("summary") &&
            (elem.containsKey("startTimeIso") || elem.containsKey("start_time_iso") || elem.containsKey("startTime"))
        ) {
            stringField(elem, "title", "event", "name")
        } else {
            null
        }
        if (summary.isNullOrBlank() && salvagedRootTitle.isNullOrBlank()) {
            return "blank_summary(keys=${elem.keys.take(6).joinToString(",")})"
        }
        return "ok"
    }

    fun repairModelJsonCandidate(rawText: String): String? {
        if (rawText.isBlank()) return null
        val cleaned = rawText
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null

        val out = StringBuilder(cleaned.length - start + 16)
        var inString = false
        var depth = 0
        var i = start
        val n = cleaned.length
        while (i < n) {
            val ch = cleaned[i]
            if (inString) {
                when {
                    ch == '\\' -> {
                        val next = cleaned.getOrNull(i + 1)
                        when {
                            next == null -> out.append("\\\\")
                            next == '\r' || next == '\n' -> {
                                out.append("\\n")
                                i += if (next == '\r' && cleaned.getOrNull(i + 2) == '\n') 2 else 1
                            }
                            next == 'u' -> {
                                val hasHex = i + 5 < n && (2..5).all { offset ->
                                    cleaned[i + offset] in '0'..'9' ||
                                        cleaned[i + offset] in 'a'..'f' ||
                                        cleaned[i + offset] in 'A'..'F'
                                }
                                if (hasHex) {
                                    out.append("\\u")
                                } else {
                                    out.append("\\\\u")
                                }
                                i++
                            }
                            next in VALID_SIMPLE_ESCAPES -> {
                                out.append('\\').append(next)
                                i++
                            }
                            else -> out.append("\\\\")
                        }
                    }
                    ch == '\r' -> Unit
                    ch == '\n' -> out.append("\\n")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 -> Unit
                    ch == '"' -> {
                        val nextNonWs = nextNonWhitespaceChar(cleaned, i + 1)
                        val isClosing = nextNonWs == null ||
                            nextNonWs == ':' ||
                            nextNonWs == ',' ||
                            nextNonWs == '}' ||
                            nextNonWs == ']'
                        if (isClosing) {
                            inString = false
                            out.append('"')
                        } else {
                            out.append("\\\"")
                        }
                    }
                    else -> out.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        inString = true
                        out.append(ch)
                    }
                    '{' -> {
                        depth++
                        out.append(ch)
                    }
                    '}' -> {
                        stripTrailingComma(out)
                        out.append(ch)
                        depth--
                        if (depth <= 0) break
                    }
                    ']' -> {
                        stripTrailingComma(out)
                        out.append(ch)
                    }
                    else -> out.append(ch)
                }
            }
            i++
        }
        if (depth == 0 && !inString && out.isNotEmpty()) {
            return out.toString()
        }
        if (hasRunawayRepetitionTail(cleaned)) {
            val partial = PartialJsonSummaryExtractor.extract(cleaned)
            if (partial != null && partial.isClosed && partial.text.isNotBlank()) {
                val salvagedSummary = JsonPrimitive(partial.text).toString()
                return "{\"summary\":$salvagedSummary,\"tags\":[],\"calendarProposal\":null,\"relations\":[]}"
            }
        }
        return null
    }

    private fun extractTags(obj: JsonObject): List<String> =
        (obj["tags"] as? JsonArray)?.mapNotNull { item ->
            (item as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun extractCalendarProposal(obj: JsonObject): CalendarProposal? {
        val cal = obj["calendarProposal"] as? JsonObject ?: return null
        return extractCalendarProposalFromObject(cal)
    }

    private fun extractCalendarProposalFromObject(cal: JsonObject): CalendarProposal? {
        val title = stringField(cal, "title", "event", "name").orEmpty()
        val startTimeIso = stringField(cal, "startTimeIso", "start_time_iso", "startTime", "start_time").orEmpty()
        if (title.isBlank() && startTimeIso.isBlank()) return null
        val durationMinutes = parseDurationMinutes(
            cal["durationMinutes"] ?: cal["duration_minutes"] ?: cal["duration"],
        ) ?: 60
        val location = stringField(cal, "location", "place", "venue")
            ?: (cal["location"] as? JsonObject)?.let { stringField(it, "name", "room", "venue", "title") }
        return CalendarProposal(
            title = title,
            startTimeIso = startTimeIso,
            durationMinutes = durationMinutes,
            location = location,
        )
    }

    private fun extractRelations(obj: JsonObject): List<RelationTriple> {
        val arr = obj["relations"] as? JsonArray ?: return emptyList()
        val triples = ArrayList<RelationTriple>(arr.size)
        for (item in arr) {
            val relObj = item as? JsonObject ?: continue
            val source = stringField(relObj, "source").orEmpty()
            val target = stringField(relObj, "target").orEmpty()
            val relation = stringField(relObj, "relation")?.ifEmpty { MemoryAnalyzer.DEFAULT_RELATION }
                ?: MemoryAnalyzer.DEFAULT_RELATION
            val bidirectional = parseBooleanField(relObj["bidirectional"])
            if (source.isNotEmpty() && target.isNotEmpty()) {
                triples += RelationTriple(
                    source = source,
                    target = target,
                    relation = relation,
                    bidirectional = bidirectional,
                )
            }
        }
        return triples
    }

    private fun stringField(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val prim = obj[key] as? JsonPrimitive ?: continue
            if (prim.isString) {
                prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun parseDurationMinutes(el: JsonElement?): Int? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) {
            prim.intOrNull?.let { return it }
            prim.doubleOrNull?.let { return it.roundToInt() }
            return null
        }
        return prim.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun parseBooleanField(el: JsonElement?): Boolean {
        val prim = el as? JsonPrimitive ?: return false
        prim.booleanOrNull?.let { return it }
        val text = prim.contentOrNull?.trim()?.lowercase(Locale.US) ?: return false
        return text == "true" || text == "yes"
    }

    private fun hasRunawayRepetitionTail(text: String): Boolean {
        if (text.length < 16) return false
        val window = text.takeLast(160)
        val len = window.length
        for (period in 2..24) {
            val needed = period * 4
            if (len < needed) break
            val unit = window.substring(len - period)
            if (unit.none { it.isLetterOrDigit() || it in '\u4E00'..'\u9FFF' }) continue
            var repeated = true
            for (copy in 2..4) {
                val offset = len - copy * period
                if (!window.regionMatches(offset, unit, 0, period)) {
                    repeated = false
                    break
                }
            }
            if (repeated) return true
        }
        return false
    }

    private fun nextNonWhitespaceChar(text: String, fromIndex: Int): Char? {
        var idx = fromIndex
        while (idx < text.length) {
            val c = text[idx]
            if (!c.isWhitespace()) return c
            idx++
        }
        return null
    }

    private fun stripTrailingComma(out: StringBuilder) {
        var idx = out.length - 1
        while (idx >= 0 && out[idx].isWhitespace()) {
            idx--
        }
        if (idx >= 0 && out[idx] == ',') {
            out.deleteCharAt(idx)
        }
    }

    private companion object {
        val VALID_SIMPLE_ESCAPES = setOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't')
    }
}
