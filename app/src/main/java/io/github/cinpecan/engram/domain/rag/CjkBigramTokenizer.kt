package io.github.cinpecan.engram.domain.rag

/**
 * Splits mixed Chinese/English text into overlapping CJK bigrams and lowercase ASCII tokens
 * so SQLite FTS4 retrieves Chinese compounds and alphanumeric credentials reliably without external tokenizers.
 */
object CjkBigramTokenizer {

    fun tokenizeForIndex(text: String): String = tokenizeInternal(text).joinToString(separator = " ")

    fun tokenizeQuery(query: String): List<String> = tokenizeInternal(query).distinct()

    fun buildFts4MatchExpression(query: String): String? {
        val tokens = tokenizeQuery(query)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " OR ") { token ->
            val sanitized = token.replace("\"", "").replace("*", "")
            if (sanitized.length == 1 && isCjk(sanitized[0])) {
                "$sanitized*"
            } else {
                "\"$sanitized\""
            }
        }
    }

    private fun tokenizeInternal(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = ArrayList<String>(text.length)
        val asciiBuffer = StringBuilder()
        val cjkBuffer = StringBuilder()

        fun flushAscii() {
            if (asciiBuffer.isNotEmpty()) {
                val word = asciiBuffer.toString().lowercase()
                if (word.isNotBlank()) {
                    tokens.add(word)
                }
                asciiBuffer.clear()
            }
        }

        fun flushCjk() {
            val len = cjkBuffer.length
            if (len == 1) {
                tokens.add(cjkBuffer.toString())
            } else if (len >= 2) {
                for (i in 0 until len - 1) {
                    tokens.add(cjkBuffer.substring(i, i + 2))
                }
            }
            cjkBuffer.clear()
        }

        for (ch in text) {
            when {
                isAsciiWordChar(ch) -> {
                    flushCjk()
                    asciiBuffer.append(ch)
                }
                isCjk(ch) -> {
                    flushAscii()
                    cjkBuffer.append(ch)
                }
                else -> {
                    flushAscii()
                    flushCjk()
                }
            }
        }
        flushAscii()
        flushCjk()
        return tokens
    }

    private fun isAsciiWordChar(ch: Char): Boolean =
        (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_'

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)
    }
}
