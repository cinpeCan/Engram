package io.github.cinpecan.engram.domain.rag

import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrfHybridScorerTest {

    private fun memory(id: String, text: String) = MemoryItem(
        id = id,
        content = text,
        summary = text,
        tags = emptyList(),
        createdAtMillis = 1000L,
        analysisStatus = MemoryAnalysisStatus.READY,
        calendarProposal = null,
        calendarSaved = false,
        relations = emptyList(),
    )

    @Test
    fun prioritizesDualBranchHitsViaReciprocalRankFusion() {
        val m1 = memory("m1", "老李测试密码 Admin@9527")
        val m2 = memory("m2", "法务张姐审查采购合同")
        val m3 = memory("m3", "屏幕硬件亮度参数")

        val queryVec = floatArrayOf(1f, 0f, 0f)
        val embeddings = mapOf(
            "m1" to floatArrayOf(0f, 1f, 0f),
            "m2" to floatArrayOf(0.95f, 0.05f, 0f),
            "m3" to floatArrayOf(0.1f, 0.9f, 0f),
        )

        // m1 only hits lexical rank-1 (1/61), while m2 hits both lexical rank-2 and vector rank-1 (1/62 + 1/61) -> m2 wins
        val ranked = RrfHybridScorer.score(
            memories = listOf(m1, m2, m3),
            lexicalOrderedIds = listOf("m1", "m2"),
            queryEmbedding = queryVec,
            memoryEmbeddings = embeddings,
            topK = 3,
        )

        assertEquals("m2", ranked.first().memory.id)
        assertEquals("m1", ranked[1].memory.id)
        assertTrue(ranked[0].rrfScore > ranked[1].rrfScore)
    }
}
