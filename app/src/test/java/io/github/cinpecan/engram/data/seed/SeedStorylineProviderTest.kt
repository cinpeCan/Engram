package io.github.cinpecan.engram.data.seed

import io.github.cinpecan.engram.domain.model.AnalysisSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedStorylineProviderTest {

    @Test
    fun eachSeedRelationIsGroundedInItsOwnCardText() {
        val seeds = SeedStorylineProvider.createInitialStoryline(nowMillis = 1_700_000_000_000L)
        assertEquals(listOf("seed-1", "seed-2", "seed-3"), seeds.map { it.id })

        for (seed in seeds) {
            assertEquals(AnalysisSource.SEED, seed.analysisSource)
            assertEquals("", seed.telemetryBadge)
            assertTrue(seed.relations.isNotEmpty())
            val text = seed.content.lowercase()
            for (rel in seed.relations) {
                assertTrue("${seed.id}: ${rel.source}", text.contains(groundingKey(rel.source)))
                assertTrue("${seed.id}: ${rel.target}", text.contains(groundingKey(rel.target)))
                assertTrue(rel.source != rel.target)
                assertTrue(rel.source.length <= 32 && rel.target.length <= 32)
                assertTrue(rel.relation.length <= 24)
                assertEquals(seed.id, rel.memoryId)
            }
            assertEquals(seed.relations.size, seed.relations.map { it.id }.toSet().size)
            assertTrue(seed.summary.length <= 300)
        }
        assertEquals(null, seeds[0].calendarProposal)
        assertEquals(null, seeds[1].calendarProposal)
        assertEquals("Building B, Conference Room 402", seeds[2].calendarProposal?.location)
    }

    /** Canonical graph nodes may append a generic noun (e.g. "Micro-OLED Module" for "Micro-OLED"). */
    private fun groundingKey(entity: String): String = entity.lowercase().removeSuffix(" module")
}
