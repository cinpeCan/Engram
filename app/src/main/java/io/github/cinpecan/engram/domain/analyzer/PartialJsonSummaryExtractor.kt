package io.github.cinpecan.engram.domain.analyzer

/**
 * Snapshot of the `"summary"` string decoded from a possibly unterminated JSON stream.
 *
 * @property text decoded characters generated so far (never ends in a half escape sequence).
 * @property isClosed `true` once the closing quote of the summary string has arrived.
 */
data class PartialSummary(
    val text: String,
    val isClosed: Boolean,
)

/**
 * Incrementally extracts the `"summary"` value from streaming model output so the UI can
 * render a typewriter preview instead of raw JSON. Tolerates leading code fences and
 * whitespace, and withholds incomplete escapes (`\`, `\u12`, lone high surrogates).
 */
object PartialJsonSummaryExtractor {
    private const val KEY = "\"summary\""

    fun extract(rawJsonSoFar: String): PartialSummary? {
        val keyIdx = rawJsonSoFar.indexOf(KEY)
        if (keyIdx < 0) return null
        var i = skipWhitespace(rawJsonSoFar, keyIdx + KEY.length)
        if (i >= rawJsonSoFar.length || rawJsonSoFar[i] != ':') return null
        i = skipWhitespace(rawJsonSoFar, i + 1)
        if (i >= rawJsonSoFar.length || rawJsonSoFar[i] != '"') return null
        i++

        val out = StringBuilder(rawJsonSoFar.length - i)
        val n = rawJsonSoFar.length
        while (i < n) {
            val c = rawJsonSoFar[i]
            when {
                c == '"' -> return PartialSummary(out.toString(), isClosed = true)
                c != '\\' -> {
                    out.append(c)
                    i++
                }
                else -> {
                    if (i + 1 >= n) break
                    when (val esc = rawJsonSoFar[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        '"', '\\', '/' -> out.append(esc)
                        'u' -> {
                            val code = parseHex4(rawJsonSoFar, i + 2) ?: break
                            val ch = code.toChar()
                            if (Character.isHighSurrogate(ch)) {
                                val lowStart = i + 6
                                if (lowStart + 6 > n) break
                                val low = if (rawJsonSoFar[lowStart] == '\\' && rawJsonSoFar[lowStart + 1] == 'u') {
                                    parseHex4(rawJsonSoFar, lowStart + 2)?.toChar()
                                } else {
                                    null
                                }
                                if (low != null && Character.isLowSurrogate(low)) {
                                    out.append(ch).append(low)
                                    i = lowStart + 6
                                    continue
                                }
                                // Unpaired high surrogate: drop it rather than emit an invalid char.
                            } else if (!Character.isLowSurrogate(ch)) {
                                out.append(ch)
                            }
                            i += 6
                            continue
                        }
                        else -> out.append(esc)
                    }
                    i += 2
                }
            }
        }
        return PartialSummary(out.toString(), isClosed = false)
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun parseHex4(text: String, from: Int): Int? {
        if (from + 4 > text.length) return null
        var value = 0
        for (k in from until from + 4) {
            val digit = Character.digit(text[k], 16)
            if (digit < 0) return null
            value = (value shl 4) or digit
        }
        return value
    }
}
