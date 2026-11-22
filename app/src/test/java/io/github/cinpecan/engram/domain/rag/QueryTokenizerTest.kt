package io.github.cinpecan.engram.domain.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTokenizerTest {

    @Test
    fun tokenizeDropsStopwordsAndShortTokens() {
        assertEquals(
            listOf("deposit", "helios", "display", "hold"),
            QueryTokenizer.tokenize("Why is the deposit for Helios Display on hold?"),
        )
    }

    @Test
    fun stemCollapsesCommonInflectionsAndPreservesUsStems() {
        assertEquals(QueryTokenizer.stem("quoted"), QueryTokenizer.stem("quoting"))
        assertEquals(QueryTokenizer.stem("quotes"), QueryTokenizer.stem("quote"))
        assertEquals(QueryTokenizer.stem("supply"), QueryTokenizer.stem("supplies"))
        assertEquals(QueryTokenizer.stem("applied"), QueryTokenizer.stem("apply"))
        assertEquals(QueryTokenizer.stem("displays"), QueryTokenizer.stem("display"))
        assertEquals("marcus", QueryTokenizer.stem("marcus"))
        assertEquals("status", QueryTokenizer.stem("status"))
        assertEquals("net-60".filter { it.isLetterOrDigit() }, "net60")
        assertEquals("2026", QueryTokenizer.stem("2026"))
    }

    @Test
    fun ftsMatchExpressionIsSanitizedOrJoined() {
        // Lowercase tokens can never be parsed as FTS operators; quotes, '-' and '*' are stripped.
        assertEquals(
            "deposit OR helios OR near OR display",
            QueryTokenizer.buildFtsMatchExpression("\"deposit\" -Helios* NEAR display AND or"),
        )
        assertNull(QueryTokenizer.buildFtsMatchExpression("what is it?"))
        assertNull(QueryTokenizer.buildFtsMatchExpression("   "))
    }

    @Test
    fun emitsCjkBigramsPreservesMeaningfulNumbersAndMatchesOneEditTypos() {
        val cjkSummary = "\u5929\u51B7\u65F6\u627E\u55B5\u55B5\u3001\u6C6A\u6C6A\u4E00\u8D77\u73A9"
        val summaryTokens = QueryTokenizer.tokenize(cjkSummary)
        assertTrue(summaryTokens.contains("\u5929\u51B7"))
        assertTrue(summaryTokens.contains("\u55B5\u55B5"))
        assertTrue(summaryTokens.contains("\u6C6A\u6C6A"))

        val quantityTokens = QueryTokenizer.tokenize("\u4E703\u65A4\u82F9\u679C")
        assertFalse(quantityTokens.contains("\u4E70\u65A4"))
        assertTrue(quantityTokens.contains("\u82F9\u679C"))

        val mathTokens = QueryTokenizer.tokenize("\u7B9731\u52A082\u662F\u591A\u5C11")
        assertTrue(mathTokens.contains("31"))
        assertTrue(mathTokens.contains("82"))

        assertTrue(QueryTokenizer.isWithinOneTypo("marucs", "marcus"))
        assertTrue(QueryTokenizer.isWithinOneTypo("helois", "helios"))
        assertTrue(QueryTokenizer.isWithinOneTypo("marcs", "marcus"))
        assertTrue(QueryTokenizer.isWithinOneTypo("marcuss", "marcus"))
        assertFalse(QueryTokenizer.isWithinOneTypo("mars", "marcus"))
        assertFalse(QueryTokenizer.isWithinOneTypo("weather", "warranti"))
    }
}
