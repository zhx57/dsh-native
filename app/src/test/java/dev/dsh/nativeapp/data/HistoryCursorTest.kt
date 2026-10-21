package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paging depends on the builder reporting the *event* low-water mark.
 *
 * The regression these lock down: `oldestSeq()` used to be the minimum row `seq`,
 * so a page whose rows were all duplicates left it unchanged, the same window was
 * requested forever, and the app concluded there was no earlier history while the
 * host was still reporting `hasMore = true`.
 */
class HistoryCursorTest {

    private fun record(seq: Long, type: String, data: String): JsonObject = Json.parseToJsonElement(
        """{"type":"event","event":{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}}""",
    ) as JsonObject

    private fun user(seq: Long, text: String) = record(
        seq,
        "user/message",
        """{"content":[{"type":"text","text":"$text"}],"source":{"kind":"user"}}""",
    )

    @Test
    fun `an empty builder reports no window rather than the log start`() {
        // This is the distinction the premature "already the earliest" notice hinged
        // on: 0 means "nothing folded yet, window unknown", not "reached seq 0".
        // The caller must skip, never conclude.
        val builder = TimelineBuilder()
        assertEquals(0L, builder.oldestSeq())
    }

    @Test
    fun `folding only seq zero still reports a known window`() {
        // A session's very first events sit at seq 0; `noteEvent` ignores them so the
        // sentinel keeps meaning "unknown" instead of colliding with a real seq.
        val builder = TimelineBuilder()
        builder.applyRecord(record(0, "permission/preset", """{"preset":"never"}"""))
        assertEquals(0L, builder.oldestSeq())

        // One real event makes the window known.
        builder.applyRecord(user(4, "hi"))
        assertEquals(4L, builder.oldestSeq())
    }

    @Test
    fun `oldest seq is the lowest event folded`() {
        val builder = TimelineBuilder()
        builder.applyRecord(user(10, "a"))
        builder.applyRecord(user(30, "b"))
        assertEquals(10L, builder.oldestSeq())
    }

    @Test
    fun `events with no visible row still advance the window`() {
        val builder = TimelineBuilder()
        builder.applyRecord(user(50, "visible"))
        // A permission event produces no transcript row, but it was consumed: paging
        // from 50 instead of 40 would request a window that overlaps what is loaded.
        builder.applyRecord(record(40, "permission/preset", """{"preset":"never"}"""))
        assertEquals(40L, builder.oldestSeq())
    }

    @Test
    fun `prepending an older page lowers the window`() {
        val builder = TimelineBuilder()
        builder.applyRecord(user(100, "new"))
        assertEquals(100L, builder.oldestSeq())

        builder.prependPage(listOf(user(60, "old"), user(70, "old2")))
        assertEquals(60L, builder.oldestSeq())
        assertEquals(listOf("old", "old2", "new"), texts(builder.snapshot()))
    }

    @Test
    fun `a fully duplicate page cannot advance and is reported as exhausted`() {
        val builder = TimelineBuilder()
        val page = listOf(user(60, "same"), user(70, "same2"))
        builder.prependPage(page)
        val afterFirst = builder.oldestSeq()

        // The host can legitimately hand back the same events when the window
        // overlaps. The cursor must not move, so the caller knows to stop asking.
        builder.prependPage(page)
        assertEquals(afterFirst, builder.oldestSeq())
        assertEquals(listOf("same", "same2"), texts(builder.snapshot()))
    }

    @Test
    fun `paging walks all the way back`() {
        val builder = TimelineBuilder()
        builder.applyRecord(user(100, "t3"))
        builder.prependPage(listOf(user(70, "t2")))
        builder.prependPage(listOf(user(40, "t1")))
        assertEquals(40L, builder.oldestSeq())
        assertEquals(listOf("t1", "t2", "t3"), texts(builder.snapshot()))
        assertTrue(builder.oldestSeq() > 0)
    }

    private fun texts(items: List<TimelineItem>): List<String> =
        items.filterIsInstance<TimelineItem.UserMessage>().map { row ->
            row.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        }
}
