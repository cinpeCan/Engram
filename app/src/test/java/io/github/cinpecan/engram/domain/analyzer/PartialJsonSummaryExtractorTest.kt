package io.github.cinpecan.engram.domain.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialJsonSummaryExtractorTest {

    @Test
    fun returnsNullUntilSummaryValueStarts() {
        assertNull(PartialJsonSummaryExtractor.extract(""))
        assertNull(PartialJsonSummaryExtractor.extract("{\"summ"))
        assertNull(PartialJsonSummaryExtractor.extract("{\"summary\""))
        assertNull(PartialJsonSummaryExtractor.extract("{\"summary\" :  "))
        assertEquals(PartialSummary("", isClosed = false), PartialJsonSummaryExtractor.extract("{\"summary\": \""))
    }

    @Test
    fun streamsPartialValueAndDetectsClosingQuote() {
        val partial = PartialJsonSummaryExtractor.extract("{\"summary\":\"• Elena introduced Mar")
        assertEquals(PartialSummary("• Elena introduced Mar", isClosed = false), partial)

        val closed = PartialJsonSummaryExtractor.extract("{\"summary\":\"• A\\n• B\",\"tags\":[\"Le")
        assertEquals(PartialSummary("• A\n• B", isClosed = true), closed)
    }

    @Test
    fun toleratesCodeFencesAndWhitespace() {
        val raw = "```json\n{\n  \"summary\" : \"Review at 3:00 PM\"\n}"
        assertEquals(PartialSummary("Review at 3:00 PM", isClosed = true), PartialJsonSummaryExtractor.extract(raw))
    }

    @Test
    fun decodesEscapesAndNeverEmitsHalfEscapes() {
        val base = "{\"summary\":\"Say \\\"hi\\\" \\\\ ok"
        assertEquals("Say \"hi\" \\ ok", PartialJsonSummaryExtractor.extract(base)!!.text)

        // Trailing lone backslash is withheld until the next chunk arrives.
        val danglingBackslash = PartialJsonSummaryExtractor.extract("{\"summary\":\"line\\")!!
        assertEquals("line", danglingBackslash.text)
        assertFalse(danglingBackslash.isClosed)
        assertEquals("line\n", PartialJsonSummaryExtractor.extract("{\"summary\":\"line\\n")!!.text)

        // Incomplete \\u escape is withheld; complete escape decodes.
        assertEquals("caf", PartialJsonSummaryExtractor.extract("{\"summary\":\"caf\\u00")!!.text)
        assertEquals("café", PartialJsonSummaryExtractor.extract("{\"summary\":\"caf\\u00e9")!!.text)

        // Escaped quote must not close the string.
        val escapedQuote = PartialJsonSummaryExtractor.extract("{\"summary\":\"a\\\"")!!
        assertEquals("a\"", escapedQuote.text)
        assertFalse(escapedQuote.isClosed)
    }

    @Test
    fun handlesSurrogatePairsSplitAcrossChunks() {
        val highOnly = PartialJsonSummaryExtractor.extract("{\"summary\":\"ok \\ud83d\\ude")!!
        assertEquals("ok ", highOnly.text)
        val full = PartialJsonSummaryExtractor.extract("{\"summary\":\"ok \\ud83d\\ude80\"")!!
        assertEquals("ok \uD83D\uDE80", full.text)
        assertTrue(full.isClosed)
    }

    @Test
    fun incrementalChunksProduceMonotonicPrefixes() {
        val json = "{\"summary\":\"• Sarah aligns the clause\\n• Priya releases the deposit\",\"tags\":[]}"
        var previous = ""
        var closedSeen = false
        for (end in 1..json.length) {
            val result = PartialJsonSummaryExtractor.extract(json.substring(0, end)) ?: continue
            assertTrue(result.text.startsWith(previous))
            assertFalse(result.text.contains('{'))
            previous = result.text
            closedSeen = closedSeen || result.isClosed
        }
        assertTrue(closedSeen)
        assertEquals("• Sarah aligns the clause\n• Priya releases the deposit", previous)
    }
}
