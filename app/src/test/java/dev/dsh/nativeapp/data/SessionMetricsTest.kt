package dev.dsh.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session-info figures must agree with a browser showing the same session.
 *
 * Each case here was a place the app disagreed with the web client:
 *
 *  - occupancy is the context *pressure*, not the prompt breakdown;
 *  - cache hit divides by billed input (all three prompt buckets), not by uncached
 *    input alone;
 *  - TPS divides tokens by decode time, not by TTFT.
 */
class SessionMetricsTest {

    @Test
    fun `occupancy percent is used over window`() {
        val occupancy = ContextOccupancy(usedTokens = 125_785, contextWindow = 1_000_000)
        assertEquals(12, occupancy.percent)
        assertEquals(874_215, occupancy.remaining)
    }

    @Test
    fun `occupancy clamps at one hundred and survives a zero window`() {
        assertEquals(100, ContextOccupancy(usedTokens = 2_000, contextWindow = 1_000).percent)
        assertEquals(0, ContextOccupancy(usedTokens = 10, contextWindow = 0).percent)
        assertEquals(5, ContextOccupancy(usedTokens = 5, contextWindow = 10).remaining)
        // An over-full window must not report negative headroom.
        assertEquals(0, ContextOccupancy(usedTokens = 20, contextWindow = 10).remaining)
    }

    @Test
    fun `billed input sums the three disjoint prompt buckets`() {
        val usage = TokenUsage(input = 100, output = 40, cacheRead = 800, cacheWrite = 100)
        assertEquals(1_000, usage.billedInput)
        assertEquals(80, usage.cacheHitPercent)
    }

    @Test
    fun `cache hit is null before anything is billed`() {
        assertNull(TokenUsage(input = 0, output = 10, cacheRead = 0).cacheHitPercent)
    }

    @Test
    fun `tokens per second divides by decode time`() {
        val stats = SessionStats(decodeTokens = 500, decodeMs = 2_000)
        assertEquals(250.0, stats.tokensPerSecond!!, 0.001)
    }

    @Test
    fun `tokens per second is null without decode time`() {
        assertNull(SessionStats(decodeTokens = 500, decodeMs = 0).tokensPerSecond)
    }

    @Test
    fun `average ttft divides by the steps that reported one`() {
        // 11 steps ran but only 10 reported a TTFT; dividing by 11 understated it.
        val stats = SessionStats(steps = 11, ttftMs = 5_000, ttftSteps = 10)
        assertEquals(500L, stats.averageTtftMs)
        assertNull(SessionStats(steps = 3, ttftMs = 5_000, ttftSteps = 0).averageTtftMs)
    }

    @Test
    fun `catalog recognizes only models it still offers`() {
        val catalog = ModelCatalog(
            groups = listOf(
                ModelGroup(
                    id = "qoder",
                    models = listOf(ModelEntry(id = "deepseek-v4.1-flash-1017", name = "v4.1 Flash")),
                ),
            ),
        )
        assertEquals(true, catalog.has("qoder", "deepseek-v4.1-flash-1017"))
        // The session projection keeps naming a model after its config entry is gone.
        assertEquals(false, catalog.has("qoder", "deepseek-v4.1-flash"))
        assertEquals(false, catalog.has("buddy2api", "deepseek-v4.1-flash-1017"))
        assertEquals(true, catalog.loaded)
        assertEquals(false, ModelCatalog().loaded)
    }
}
