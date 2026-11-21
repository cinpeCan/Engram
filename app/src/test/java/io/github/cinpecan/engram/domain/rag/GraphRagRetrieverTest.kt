package io.github.cinpecan.engram.domain.rag

import io.github.cinpecan.engram.data.seed.SeedStorylineProvider
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRagRetrieverTest {

    private val seeds = SeedStorylineProvider.createInitialStoryline(nowMillis = 1_800_000_000_000L)
    private val retriever = GraphRagRetriever()

    private fun lexicalHits(question: String): List<String> {
        val queryStems = QueryTokenizer.stemmedTokens(question)
        return seeds.filter { memory ->
            val doc = QueryTokenizer.stemmedTokens("${memory.content} ${memory.summary}")
            queryStems.any { it in doc }
        }.map { it.id }
    }

    @Test
    fun graphChannelReachesNotesThatNeverMentionTheQueriedEntity() {
        val question = "What is happening with Helios Display?"
        val result = retriever.retrieve(question, seeds, lexicalHits(question))

        val byId = result.citations.associateBy { it.memoryId }
        // seed-1 mentions Helios Display directly.
        assertNotNull(byId["seed-1"]?.lexicalRank)
        // seed-2 never says "Helios" but is reached via Helios Display - Micro-OLED Module - Liam Chen.
        val seed2 = byId.getValue("seed-2")
        assertNull(seed2.lexicalRank)
        assertEquals(2, seed2.graphHops)

        assertEquals(setOf("Helios Display"), result.seedEntities)
        val pairs = result.traceEdges.map { setOf(it.source, it.target) }
        assertTrue(setOf("Helios Display", "Micro-OLED Module") in pairs)
        assertTrue(setOf("Liam Chen", "Micro-OLED Module") in pairs)
        assertTrue(result.traceEdges.all { it.source != it.target })
        assertEquals(result.citations.map { it.index }, (1..result.citations.size).toList())
    }

    @Test
    fun fusesLexicalAndGraphEvidenceForMultiHopQuestions() {
        val question = "Why is the deposit for the Helios Display module on hold?"
        val result = retriever.retrieve(question, seeds, lexicalHits(question))
        val ids = result.citations.map { it.memoryId }
        assertEquals(listOf("seed-1", "seed-2"), ids.take(2))
        // seed-2 holds the actual blocker (deposit) and is supported by both channels.
        val seed2 = result.citations.first { it.memoryId == "seed-2" }
        assertNotNull(seed2.lexicalRank)
        assertEquals(1, seed2.graphHops)
        assertTrue(result.citations.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun opposingEdgesMergeIntoOneBidirectionalTraceEdge() {
        val question = "What did Sarah and Marcus agree?"
        val result = retriever.retrieve(question, seeds, lexicalHits(question))
        val sarahMarcus = result.traceEdges.filter {
            setOf(it.source, it.target) == setOf("Sarah Jenkins", "Marcus Cole")
        }
        assertEquals(1, sarahMarcus.size)
        assertTrue(sarahMarcus.single().bidirectional)
    }

    @Test
    fun unrelatedOrStopwordOnlyQuestionsReturnNothing() {
        assertTrue(retriever.retrieve("What is the weather on Mars?", seeds, emptyList()).isEmpty)
        assertTrue(retriever.retrieve("what is it?", seeds, emptyList()).isEmpty)
    }

    @Test
    fun pendingMemoriesAreNeverCited() {
        val pending = seeds.map { it.copy(analysisStatus = MemoryAnalysisStatus.PENDING) }
        assertTrue(retriever.retrieve("Helios Display", pending, pending.map { it.id }).isEmpty)
    }
}
