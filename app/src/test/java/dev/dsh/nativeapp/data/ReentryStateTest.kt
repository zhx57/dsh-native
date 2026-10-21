package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-entry semantics: a client that attaches mid-turn must learn both that the
 * session is running and when the turn started.
 *
 * `api-session/status` on `$events` only fires when the flag *changes*, so it cannot
 * answer either question for a session that was already running. The folded log can,
 * because `turn/start` and `turn/end` are durable.
 */
class ReentryStateTest {

    private fun event(seq: Long, type: String, data: String, time: Long = 1_000L + seq) =
        Json.parseToJsonElement(
            """{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}""",
        ) as JsonObject

    @Test
    fun `an unclosed turn reports its start time`() {
        val builder = TimelineBuilder()
        builder.applyRecord(event(1, "turn/start", """{"turn":1}""", time = 500L))
        builder.applyRecord(event(2, "step/start", """{"turn":1,"step":1}"""))

        assertEquals(500L, builder.openTurnStartedAt())
    }

    @Test
    fun `a closed turn reports idle`() {
        val builder = TimelineBuilder()
        builder.applyRecord(event(1, "turn/start", """{"turn":1}""", time = 500L))
        builder.applyRecord(event(2, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""))

        assertNull(builder.openTurnStartedAt())
    }

    @Test
    fun `the newest open turn wins across several turns`() {
        val builder = TimelineBuilder()
        builder.applyRecord(event(1, "turn/start", """{"turn":1}""", time = 100L))
        builder.applyRecord(event(2, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""))
        builder.applyRecord(event(3, "turn/start", """{"turn":2}""", time = 900L))

        assertEquals(900L, builder.openTurnStartedAt())
    }

    @Test
    fun `a stream baseline restores the in-flight output`() {
        // Shape taken from the host's SessionAssistantStreamAccumulator.snapshot():
        // a compact record list, not an array of frames.
        val builder = TimelineBuilder()
        val snapshot = Json.parseToJsonElement(
            """{"type":"snapshot","records":[],"assistantStream":{"revision":3,"activeAttempt":{
                "attemptId":"a1","startedAfterSeq":10,"turn":2,"step":1,"nextIndex":4,
                "stream":[
                    {"type":"text-chunks","time0":1000,"index":0,"dt":[],"texts":["partial ","answer"],"lastTime":1100},
                    {"type":"tool-call-chunks","time0":1200,"index":1,"dt":[],"id":"c1","name":"pwsh","args":["{\"command\":","\"ls\"}"],"lastTime":1300}
                ]}}}""",
        ) as JsonObject
        builder.applyFollowValue(snapshot)

        val rows = builder.snapshot()
        // Restored prose must sit above the restored tool row, matching the live path.
        assertEquals(
            listOf("AssistantMessage", "ToolCallItem"),
            rows.map { it::class.simpleName },
        )
        val prose = rows[0] as TimelineItem.AssistantMessage
        assertEquals("partial answer", (prose.blocks.single() as ContentBlock.Text).text)
        assertTrue(prose.streaming)

        val tool = rows[1] as TimelineItem.ToolCallItem
        assertEquals("pwsh", tool.name)
        // Argument fragments are joined, so the call shows what the model already said.
        assertEquals("{\"command\":\"ls\"}", tool.arguments)
    }

    @Test
    fun `an idle baseline adds nothing`() {
        val builder = TimelineBuilder()
        val snapshot = Json.parseToJsonElement(
            """{"type":"snapshot","records":[],"assistantStream":{"revision":0}}""",
        ) as JsonObject
        builder.applyFollowValue(snapshot)

        assertTrue(builder.snapshot().isEmpty())
        assertNull(builder.openTurnStartedAt())
    }
}
