package io.github.cinpecan.engram.domain.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkBigramTokenizerTest {

    @Test
    fun emitsOverlappingChineseBigramsAndLowercaseAsciiTokens() {
        val indexed = CjkBigramTokenizer.tokenizeForIndex("采购合同 test_panel 密码 Admin@9527")
        val tokens = indexed.split(' ')

        assertTrue(tokens.contains("采购"))
        assertTrue(tokens.contains("购合"))
        assertTrue(tokens.contains("合同"))
        assertTrue(tokens.contains("test_panel"))
        assertTrue(tokens.contains("admin"))
        assertTrue(tokens.contains("9527"))
    }

    @Test
    fun usesPrefixWildcardForSingleCjkCharacterQuery() {
        val expr = CjkBigramTokenizer.buildFts4MatchExpression("张")
        assertNotNull(expr)
        assertEquals("张*", expr)
    }

    @Test
    fun sanitizesQuotesAndOperatorsFromFtsMatchExpression() {
        val expr = CjkBigramTokenizer.buildFts4MatchExpression("\"法务\" * 合同")
        assertNotNull(expr)
        assertFalse(expr!!.contains("**"))
        assertTrue(expr.contains("\"法务\""))
        assertTrue(expr.contains("\"合同\""))
    }
}
