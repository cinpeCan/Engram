package io.github.cinpecan.engram.domain.rag

import java.util.Locale

/**
 * Lightweight English query tokenizer shared by the FTS4 MATCH builder and the graph channel.
 *
 * Normalization mirrors what SQLite's `porter` tokenizer does on the index side closely enough
 * for entity matching: lowercase, alphanumeric split, stopword removal, and light suffix stemming.
 */
object QueryTokenizer {

    private val SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

    private val STOPWORDS: Set<String> = setOf(
        "a", "about", "after", "again", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "been", "before", "being", "but", "by", "can", "could", "did", "do", "does", "doing",
        "for", "from", "get", "got", "had", "has", "have", "having", "he", "her", "here", "him",
        "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "me", "my", "next",
        "no", "not", "now", "of", "on", "or", "our", "out", "she", "should", "so", "still",
        "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those",
        "to", "up", "us", "was", "we", "were", "what", "when", "where", "which", "while", "who",
        "whom", "why", "will", "with", "would", "you", "your", "tell", "know", "need", "any",
    )

    /** Lowercased, stopword-free tokens of at least two characters, in first-seen order. */
    fun tokenize(text: String): List<String> =
        text.lowercase(Locale.US)
            .split(SPLIT_REGEX)
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()

    /** Stemmed variant used for set comparisons between query and entity names / memory text. */
    fun stemmedTokens(text: String): Set<String> = tokenize(text).mapTo(LinkedHashSet()) { stem(it) }

    /**
     * Minimal Porter-style suffix stripping (step 1 subset). Deliberately conservative: it only
     * needs to collapse common inflections such as `quoted` / `quoting` / `quotes` -> `quot`.
     */
    fun stem(token: String): String {
        var word = token
        if (word.length <= 3 || word.any { it.isDigit() }) return word
        word = when {
            word.endsWith("sses") -> word.dropLast(2)
            word.endsWith("ies") -> word.dropLast(3) + "i"
            word.endsWith("ss") -> word
            word.endsWith("s") && word.length > 3 -> word.dropLast(1)
            else -> word
        }
        word = when {
            word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
            word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
            else -> word
        }
        if (word.endsWith("e") && word.length > 4) {
            word = word.dropLast(1)
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
