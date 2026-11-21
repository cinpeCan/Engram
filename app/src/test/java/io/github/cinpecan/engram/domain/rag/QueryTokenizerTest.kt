package io.github.cinpecan.engram.domain.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun stemCollapsesCommonInflections() {
        assertEquals(QueryTokenizer.stem("quoted"), QueryTokenizer.stem("quoting"))
        assertEquals(QueryTokenizer.stem("quotes"), QueryTokenizer.stem("quote"))
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
}
