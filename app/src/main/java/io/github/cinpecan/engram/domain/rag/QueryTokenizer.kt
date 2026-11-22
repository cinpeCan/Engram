package io.github.cinpecan.engram.domain.rag

import java.util.Locale
import kotlin.math.abs

/**
 * Lightweight multilingual query tokenizer shared by the FTS4 MATCH builder, lexical scorer,
 * and entity graph channel.
 *
 * For Latin text, normalization mirrors SQLite's `porter` tokenizer (lowercase, alphanumeric
 * split, stopword removal, and light suffix stemming) with bounded 1-edit typo tolerance. For CJK
 * text, emits overlapping bigrams because SQLite `porter` does not segment unspaced scripts.
 */
object QueryTokenizer {

    private val SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val CJK_CHAR_REGEX = Regex("[\u4E00-\u9FFF]")
    private val CJK_RUN_REGEX = Regex("[\u4E00-\u9FFF]+")
    private val CJK_QUESTION_PHRASES = Regex(
        "\u4E3A\u4EC0\u4E48|\u4EC0\u4E48|\u600E\u4E48|\u54EA\u91CC|\u54EA\u513F|\u591A\u5C11",
    )
    private val CJK_STOP_PARTICLES = Regex(
        "[\u8C01\u5417\u5462\u5427\u554A\u5440\u7684\u4E86\u7740\u8FC7\u548C\u4E0E\u8DDF\u628A\u88AB\u5728\u662F\u6709\u8BDD]+",
    )

    private val STOPWORDS: Set<String> = setOf(
        "a", "about", "after", "again", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "been", "before", "being", "but", "by", "can", "could", "did", "do", "does", "doing",
        "for", "from", "get", "got", "had", "has", "have", "having", "he", "her", "here", "him",
        "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "me", "my", "next",
        "no", "not", "now", "of", "on", "or", "our", "out", "she", "should", "so", "still",
        "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those",
        "to", "up", "us", "was", "we", "were", "what", "when", "where", "which", "while", "who",
        "whom", "why", "will", "with", "would", "you", "your", "tell", "know", "need",
    )

    fun containsCjk(text: String): Boolean = CJK_CHAR_REGEX.containsMatchIn(text)

    /** Lowercased, stopword-free tokens (including CJK 2-grams when present), in first-seen order. */
    fun tokenize(text: String): List<String> {
        if (!containsCjk(text)) {
            return text.lowercase(Locale.US)
                .split(SPLIT_REGEX)
                .filter { it.length >= 2 && it !in STOPWORDS }
                .distinct()
        }

        val tokens = LinkedHashSet<String>()
        val nonCjk = CJK_RUN_REGEX.replace(text.lowercase(Locale.US), " ")
        nonCjk.split(SPLIT_REGEX)
            .filter { it.length >= 2 && it !in STOPWORDS }
            .forEach { tokens += it }

        addCjkBigrams(text, tokens)
        return tokens.toList()
    }

    private fun addCjkBigrams(source: String, sink: MutableSet<String>) {
        val cleaned = CJK_STOP_PARTICLES.replace(
            CJK_QUESTION_PHRASES.replace(source, " "),
            " ",
        )
        for (match in CJK_RUN_REGEX.findAll(cleaned)) {
            val run = match.value
            if (run.length < 2) continue
            if (run.length == 2) {
                sink += run
            } else {
                for (i in 0 until run.length - 1) {
                    sink += run.substring(i, i + 2)
                }
            }
        }
    }

    /** Stemmed variant used for set comparisons between query and entity names / memory text. */
    fun stemmedTokens(text: String): Set<String> = tokenize(text).mapTo(LinkedHashSet()) { stem(it) }

    /** Returns true for pure ASCII letter stems (length >= 5, shared initial) within Damerau-Levenshtein <= 1. */
    fun isWithinOneTypo(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (la < 5 || lb < 5 || abs(la - lb) > 1 || a[0] != b[0]) return false
        for (i in 0 until la) {
            if (a[i] !in 'a'..'z') return false
        }
        for (i in 0 until lb) {
            if (b[i] !in 'a'..'z') return false
        }

        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return true
            if (a.regionMatches(i + 1, b, i + 1, la - i - 1)) return true
            return i + 1 < la &&
                a[i] == b[i + 1] &&
                a[i + 1] == b[i] &&
                a.regionMatches(i + 2, b, i + 2, la - i - 2)
        }

        val shorter = if (la < lb) a else b
        val longer = if (la < lb) b else a
        var i = 0
        while (i < shorter.length && shorter[i] == longer[i]) i++
        if (i == shorter.length) return true
        return shorter.regionMatches(i, longer, i + 1, shorter.length - i)
    }

    fun matchesStemOrTypo(queryStem: String, candidateStems: Set<String>): Boolean =
        queryStem in candidateStems || candidateStems.any { isWithinOneTypo(queryStem, it) }

    /**
     * Minimal Porter-style suffix stripping (step 1 subset). Deliberately conservative: it only
     * needs to collapse common inflections such as `quoted` / `quoting` / `quotes` -> `quot` and
     * `supply` / `supplies` -> `suppli`.
     */
    fun stem(token: String): String {
        var word = token
        if (word.length <= 3 || word.any { it.isDigit() }) return word
        word = when {
            word.endsWith("sses") -> word.dropLast(2)
            word.endsWith("ies") -> word.dropLast(3) + "i"
            word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") -> word
            word.endsWith("s") && word.length > 3 -> word.dropLast(1)
            else -> word
        }
        word = when {
            word.endsWith("ied") && word.length > 4 -> word.dropLast(3) + "i"
            word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
            word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
            else -> word
        }
        if (word.endsWith("e") && word.length > 4) {
            word = word.dropLast(1)
        }
        if (word.length > 3 && word.endsWith('y') && word.dropLast(1).any { it in "aeiou" }) {
            word = word.dropLast(1) + "i"
        }
        return word
    }

    /**
     * Builds a safe SQLite FTS4 MATCH expression from free text: lowercase alphanumeric tokens
     * joined with `OR` (lowercase tokens can never be parsed as FTS operators). The `porter`
     * tokenizer stems both sides, so `quoting` matches indexed `quoted`. Returns null when
     * nothing remains, so raw user input never reaches MATCH.
     */
    fun buildFtsMatchExpression(text: String, maxTerms: Int = 8): String? {
        val terms = tokenize(text)
            .map { token -> token.filter { it.isLetterOrDigit() } }
            .filter { it.length >= 2 }
            .take(maxTerms)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ")
    }
}
