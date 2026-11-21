package io.github.cinpecan.engram.domain.rag

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import java.util.Locale

/** One retrieved memory, numbered in the order it is cited to the model (`[1]`, `[2]`, ...). */
@Immutable
data class Citation(
    val index: Int,
    val memoryId: String,
    val headline: String,
    val contextText: String,
    val createdAtMillis: Long,
    val lexicalRank: Int?,
    val graphHops: Int?,
    val score: Double,
)

/** A relation edge walked by the graph channel, tagged with the hop at which it was reached. */
@Immutable
data class TraceEdge(
    val source: String,
    val target: String,
    val relation: String,
    val bidirectional: Boolean,
    val memoryId: String,
    val hop: Int,
)

@Immutable
data class RetrievalResult(
    val citations: List<Citation> = emptyList(),
    val traceEdges: List<TraceEdge> = emptyList(),
    val seedEntities: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = citations.isEmpty()
}

/**
 * On-device GraphRAG retriever fusing two orthogonal channels with Reciprocal Rank Fusion:
 *
 * 1. **Lexical** — SQLite FTS4 (`porter` tokenizer) candidate ids, re-ranked by distinct
 *    stemmed query-term overlap (recency breaks ties).
 * 2. **Graph** — entities named in the question seed a breadth-first walk (≤ [maxHops]) over the
 *    undirected entity graph built from every memory's relation triples; memories owning walked
 *    edges are ranked by hop distance. This surfaces related notes that never mention the
 *    query words at all.
 *
 * Pure Kotlin and deterministic, so it is unit-testable without Android.
 */
class GraphRagRetriever(
    private val maxHops: Int = 2,
    private val topK: Int = 6,
    private val rrfK: Int = 60,
    private val maxTraceEdges: Int = 14,
) {

    fun retrieve(
        question: String,
        memories: List<MemoryItem>,
        lexicalHitIds: List<String>,
    ): RetrievalResult {
        val queryStems = QueryTokenizer.stemmedTokens(question)
        if (queryStems.isEmpty()) return RetrievalResult()

        val ready = memories.filter { it.analysisStatus == MemoryAnalysisStatus.READY }
        if (ready.isEmpty()) return RetrievalResult()
        val byId = ready.associateBy { it.id }

        val lexicalRanking = rankLexical(queryStems, lexicalHitIds, byId)

        val graph = EntityGraph.from(ready)
        val seedKeys = graph.matchEntities(queryStems).ifEmpty {
            lexicalRanking.firstOrNull()?.let { graph.entitiesOf(it) }.orEmpty()
        }
        val walk = graph.walk(seedKeys, maxHops)
        val graphRanking = rankGraph(walk, byId)

        val fused = fuse(lexicalRanking, graphRanking).take(topK)
        if (fused.isEmpty()) return RetrievalResult()

        val lexicalPos = lexicalRanking.withIndex().associate { (i, id) -> id to i + 1 }
        val graphHops = walk.memoryMinHop
        val citations = fused.mapIndexed { idx, (memoryId, score) ->
            val memory = byId.getValue(memoryId)
            Citation(
                index = idx + 1,
                memoryId = memoryId,
                headline = headlineOf(memory),
                contextText = memory.content.trim().take(MAX_CONTEXT_CHARS),
                createdAtMillis = memory.createdAtMillis,
                lexicalRank = lexicalPos[memoryId],
                graphHops = graphHops[memoryId],
                score = score,
            )
        }

        val citedIds = citations.mapTo(HashSet()) { it.memoryId }
        val traceEdges = mergeTrace(walk.edges.filter { it.memoryId in citedIds })
        return RetrievalResult(
            citations = citations,
            traceEdges = traceEdges,
            seedEntities = seedKeys.mapNotNullTo(LinkedHashSet()) { graph.displayName(it) },
        )
    }

    private fun rankLexical(
        queryStems: Set<String>,
        lexicalHitIds: List<String>,
        byId: Map<String, MemoryItem>,
    ): List<String> =
        lexicalHitIds.distinct()
            .mapNotNull { id -> byId[id] }
            .map { memory ->
                val docStems = QueryTokenizer.stemmedTokens(
                    "${memory.content} ${memory.summary} ${memory.tags.joinToString(" ")}",
                )
                memory to queryStems.count { it in docStems }
            }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.createdAtMillis },
            )
            .map { it.first.id }

    private fun rankGraph(walk: GraphWalk, byId: Map<String, MemoryItem>): List<String> =
        walk.memoryMinHop.keys
            .filter { it in byId }
            .sortedWith(
                compareBy<String> { walk.memoryMinHop.getValue(it) }
                    .thenByDescending { id -> walk.edges.count { it.memoryId == id } }
                    .thenByDescending { byId.getValue(it).createdAtMillis },
            )

    private fun fuse(vararg rankings: List<String>): List<Pair<String, Double>> {
        val scores = LinkedHashMap<String, Double>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { idx, id ->
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (rrfK + idx + 1)
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /** De-duplicates by unordered pair, merging opposing directions into one bidirectional edge. */
    private fun mergeTrace(edges: List<TraceEdge>): List<TraceEdge> {
        val merged = LinkedHashMap<Pair<String, String>, TraceEdge>()
        edges.sortedBy { it.hop }.forEach { edge ->
            if (edge.source.equals(edge.target, ignoreCase = true)) return@forEach
            val key = orderedKey(edge.source, edge.target)
            val existing = merged[key]
            merged[key] = when {
                existing == null -> edge
                existing.source.equals(edge.source, ignoreCase = true) -> existing
                else -> existing.copy(bidirectional = true)
            }
        }
        return merged.values.take(maxTraceEdges)
    }

    private fun headlineOf(memory: MemoryItem): String {
        val firstLine = memory.summary.lineSequence()
            .map { it.trim().removePrefix("•").trim() }
            .firstOrNull { it.isNotEmpty() }
        return (firstLine ?: memory.content.trim()).take(MAX_HEADLINE_CHARS)
    }

    private class GraphWalk(
        val edges: List<TraceEdge>,
        val memoryMinHop: Map<String, Int>,
    )

    private class EntityGraph(
        private val adjacency: Map<String, List<RawEdge>>,
        private val displayNames: Map<String, String>,
        private val entityStems: Map<String, Set<String>>,
        private val entitiesByMemory: Map<String, Set<String>>,
    ) {
        data class RawEdge(
            val from: String,
            val to: String,
            val source: String,
            val target: String,
            val relation: String,
            val bidirectional: Boolean,
            val memoryId: String,
        )

        fun displayName(key: String): String? = displayNames[key]

        fun entitiesOf(memoryId: String): Set<String> = entitiesByMemory[memoryId].orEmpty()

        fun matchEntities(queryStems: Set<String>): Set<String> =
            entityStems.filter { (_, stems) -> stems.any { it in queryStems } }.keys

        fun walk(seeds: Set<String>, maxHops: Int): GraphWalk {
            if (seeds.isEmpty()) return GraphWalk(emptyList(), emptyMap())
            val distance = HashMap<String, Int>()
            val queue = ArrayDeque<String>()
            seeds.forEach { distance[it] = 0; queue.addLast(it) }
            val walked = ArrayList<TraceEdge>()
            val seenEdges = HashSet<RawEdge>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val d = distance.getValue(node)
                if (d >= maxHops) continue
                adjacency[node].orEmpty().forEach { edge ->
                    val neighbor = if (edge.from == node) edge.to else edge.from
                    if (seenEdges.add(edge)) {
                        walked += TraceEdge(
                            source = edge.source,
                            target = edge.target,
                            relation = edge.relation,
                            bidirectional = edge.bidirectional,
                            memoryId = edge.memoryId,
                            hop = d + 1,
                        )
                    }
                    if (neighbor !in distance) {
                        distance[neighbor] = d + 1
                        queue.addLast(neighbor)
                    }
                }
            }
            val minHop = HashMap<String, Int>()
            walked.forEach { edge ->
                minHop[edge.memoryId] = minOf(minHop[edge.memoryId] ?: Int.MAX_VALUE, edge.hop)
            }
            return GraphWalk(walked, minHop)
        }

        companion object {
            fun from(memories: List<MemoryItem>): EntityGraph {
                val adjacency = HashMap<String, MutableList<RawEdge>>()
                val names = HashMap<String, String>()
                val byMemory = HashMap<String, MutableSet<String>>()
                memories.forEach { memory ->
                    memory.relations.forEach { rel ->
                        val a = keyOf(rel.source)
                        val b = keyOf(rel.target)
                        if (a.isEmpty() || b.isEmpty() || a == b) return@forEach
                        names.putIfAbsent(a, rel.source.trim())
                        names.putIfAbsent(b, rel.target.trim())
                        val edge = RawEdge(
                            from = a,
                            to = b,
                            source = names.getValue(a),
                            target = names.getValue(b),
                            relation = rel.relation,
                            bidirectional = rel.bidirectional,
                            memoryId = memory.id,
                        )
                        adjacency.getOrPut(a) { mutableListOf() } += edge
                        adjacency.getOrPut(b) { mutableListOf() } += edge
                        byMemory.getOrPut(memory.id) { mutableSetOf() }.apply { add(a); add(b) }
                    }
                }
                val stems = names.mapValues { (_, display) -> QueryTokenizer.stemmedTokens(display) }
                return EntityGraph(adjacency, names, stems, byMemory)
            }

            private fun keyOf(name: String): String = name.trim().lowercase(Locale.US)
        }
    }

    private fun orderedKey(a: String, b: String): Pair<String, String> {
        val x = a.trim().lowercase(Locale.US)
        val y = b.trim().lowercase(Locale.US)
        return if (x <= y) x to y else y to x
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 1200
        private const val MAX_HEADLINE_CHARS = 64
    }
}
