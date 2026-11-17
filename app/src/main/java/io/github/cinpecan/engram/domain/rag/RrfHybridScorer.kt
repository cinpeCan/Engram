package io.github.cinpecan.engram.domain.rag

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.domain.model.MemoryItem

@Immutable
data class RagSearchResult(
    val memory: MemoryItem,
    val lexicalRank: Int?,
    val vectorRank: Int?,
    val vectorScore: Float,
    val rrfScore: Double,
)

/**
 * Reciprocal Rank Fusion (RRF) combining FTS4 lexical ranking and in-memory cosine similarity ranking.
 */
object RrfHybridScorer {

    const val DEFAULT_RRF_K: Int = 60

    fun score(
        memories: List<MemoryItem>,
        lexicalOrderedIds: List<String>,
        queryEmbedding: FloatArray,
        memoryEmbeddings: Map<String, FloatArray>,
        topK: Int = 5,
        kConstant: Int = DEFAULT_RRF_K,
    ): List<RagSearchResult> {
        if (memories.isEmpty() || topK <= 0) return emptyList()

        val memoryById = memories.associateBy { it.id }
        val lexicalRanks = HashMap<String, Int>(lexicalOrderedIds.size)
        lexicalOrderedIds.forEachIndexed { idx, id ->
            if (!lexicalRanks.containsKey(id) && memoryById.containsKey(id)) {
                lexicalRanks[id] = idx + 1
            }
        }

        val vecScores = HashMap<String, Float>(memories.size)
        val vecCandidates = ArrayList<Pair<String, Float>>(memories.size)
        for (memory in memories) {
            val vec = memoryEmbeddings[memory.id] ?: continue
            val sim = VectorMath.cosineSimilarity(queryEmbedding, vec)
            vecScores[memory.id] = sim
            if (sim > 0.05f) {
                vecCandidates.add(memory.id to sim)
            }
        }
        vecCandidates.sortByDescending { it.second }

        val vectorRanks = HashMap<String, Int>(vecCandidates.size)
        vecCandidates.forEachIndexed { idx, pair ->
            vectorRanks[pair.first] = idx + 1
        }

        val candidateIds = LinkedHashSet<String>().apply {
            addAll(lexicalRanks.keys)
            addAll(vectorRanks.keys)
        }

        return candidateIds.mapNotNull { id ->
            val memory = memoryById[id] ?: return@mapNotNull null
            val lexRank = lexicalRanks[id]
            val vecRank = vectorRanks[id]
            val rrf =
                (if (lexRank != null) 1.0 / (kConstant + lexRank) else 0.0) +
                    (if (vecRank != null) 1.0 / (kConstant + vecRank) else 0.0)
            RagSearchResult(
                memory = memory,
                lexicalRank = lexRank,
                vectorRank = vecRank,
                vectorScore = vecScores[id] ?: 0f,
                rrfScore = rrf,
            )
        }
            .sortedByDescending { it.rrfScore }
            .take(topK)
    }
}
