package io.github.cinpecan.engram.domain.analyzer

import io.github.cinpecan.engram.domain.model.RelationTriple
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAnalyzerTest {

    private class StubEdgeEngine(private val fixedResponse: String) : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Unloaded)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)

        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            emit(StreamChunk.Delta(fixedResponse))
            emit(
                StreamChunk.Completed(
                    fullText = fixedResponse,
                    telemetry = InferenceTelemetry("stub", "stub", 5, 10, 4, 400.0, 128.0, false),
                ),
            )
        }

        override suspend fun embedText(text: String, dims: Int): FloatArray = FloatArray(dims)
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentNativeHeapBytes(): Long = 128L * 1024 * 1024
    }

    @Test
    fun parsesFencedModelJsonAndEnforcesDeponderInvariants() = runTest {
        val jsonOutput = """
            ```json
            {
              "summary": "法务对齐合同条款",
              "tags": ["法务商务"],
              "calendarProposal": null,
              "relations": [
                {"source": "张姐", "target": "张姐", "relation": "自环应过滤", "bidirectional": false},
                {"source": "老李", "target": "张姐", "relation": "提交条款", "bidirectional": false},
                {"source": "张姐", "target": "老李", "relation": "反馈修改", "bidirectional": false}
              ]
            }
            ```
        """.trimIndent()

        val analyzer = MemoryAnalyzer(StubEdgeEngine(jsonOutput))
        val payload = analyzer.analyze("老李和法务张姐对接合同条款")

        assertEquals("法务对齐合同条款", payload.summary)
        assertEquals(1, payload.relations.size)
        val edge = payload.relations.single()
        assertEquals("老李", edge.source)
        assertEquals("张姐", edge.target)
        assertTrue(edge.bidirectional)
    }

    @Test
    fun heuristicFallbackExtractsCalendarAndTriplesFromChineseText() = runTest {
        val genericStub = """{"summary":"端侧结构化解析已完成","tags":["端侧推理"],"calendarProposal":null,"relations":[]}"""
        val analyzer = MemoryAnalyzer(StubEdgeEngine(genericStub))

        val payload = analyzer.analyze(
            "明天下午15:00在软件园三期B区7楼会议室，和法务张姐、陈工过一遍采购合同补充条款。",
        )

        val proposal = payload.calendarProposal
        assertNotNull(proposal)
        requireNotNull(proposal)
        assertTrue(proposal.startTimeIso.contains("15:00"))
        assertEquals("软件园三期B区7楼会议室", proposal.location)
        assertTrue(payload.relations.isNotEmpty())
        assertTrue(payload.relations.none { it.source == it.target })
    }

    @Test
    fun sanitizeRelationsDropsSelfLoopsAndMergesReversePairs() {
        val cleaned = MemoryAnalyzer.sanitizeRelations(
            listOf(
                RelationTriple(source = "A", target = "A", relation = "loop"),
                RelationTriple(source = "A", target = "B", relation = "引荐"),
                RelationTriple(source = "B", target = "A", relation = "回访"),
            ),
        )
        assertEquals(1, cleaned.size)
        assertTrue(cleaned.single().bidirectional)
    }
}
