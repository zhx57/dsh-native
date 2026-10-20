package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering and completeness of the transcript fold.
 *
 * These cases are transcribed from real session logs: the shapes that used to
 * render wrongly were `assistant/message` carrying `text, tool-call`, a step whose
 * stream announced a call before the committed message arrived, and an overlapping
 * history page that re-folded events already in memory.
 */
class TimelineBuilderTest {

    private fun event(seq: Long, type: String, data: String): JsonObject = Json.parseToJsonElement(
        """{"type":"event","event":{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}}""",
    ) as JsonObject

    private fun fold(vararg records: JsonObject): List<TimelineItem> {
        val builder = TimelineBuilder()
        records.forEach(builder::applyRecord)
        return builder.snapshot()
    }

    @Test
    fun `a call surfaced by the stream moves below the text that preceded it`() {
        val builder = TimelineBuilder()
        builder.applyStreamFrame(
            Json.parseToJsonElement("""{"type":"start","attemptId":"a1","turn":1,"step":1}""") as JsonObject,
        )
        builder.applyStreamFrame(
            Json.parseToJsonElement(
                """{"type":"chunk","attemptId":"a1","turn":1,"step":1,"chunk":{"type":"text-delta","text":"let me check"}}""",
            ) as JsonObject,
        )
        // The stream learns the call before the committed message arrives, so its
        // row is appended above the prose that the message will reprint.
        builder.applyStreamFrame(
            Json.parseToJsonElement(
                """{"type":"chunk","attemptId":"a1","turn":1,"step":1,"chunk":{"type":"tool-call-delta","id":"c1","name":"read"}}""",
            ) as JsonObject,
        )
        builder.applyRecord(
            event(
                4,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"role":"assistant","content":[
                    {"type":"text","text":"let me check"},
                    {"type":"tool-call","id":"c1","name":"read","arguments":"{}"}
                ]}}""",
            ),
        )

        val kinds = builder.snapshot().map { it::class.simpleName }
        assertEquals(listOf("AssistantMessage", "ToolCallItem"), kinds)
        val prose = builder.snapshot().first() as TimelineItem.AssistantMessage
        assertEquals("let me check", (prose.blocks.single() as ContentBlock.Text).text)
        assertTrue(!prose.streaming)
    }

    @Test
    fun `text before a tool call stays above it`() {
        val rows = fold(
            event(1, "step/start", """{"turn":1,"step":1}"""),
            event(
                2,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"role":"assistant","content":[
                    {"type":"text","text":"checking the file"},
                    {"type":"tool-call","id":"c1","name":"read","arguments":"{}"}
                ]}}""",
            ),
            event(3, "tool/call", """{"turn":1,"step":1,"callId":"c1","name":"read","arguments":"{}"}"""),
            event(
                4,
                "tool/result",
                """{"turn":1,"step":1,"message":{"role":"tool",
                    "source":{"kind":"tool","callId":"c1"},"toolCallId":"c1",
                    "content":[{"type":"text","text":"file body"}],"isError":false}}""",
            ),
        )

        val kinds = rows.map { it::class.simpleName }
        assertEquals(listOf("AssistantMessage", "ToolCallItem"), kinds)
        val prose = rows[0] as TimelineItem.AssistantMessage
        assertEquals("checking the file", (prose.blocks.single() as ContentBlock.Text).text)
        val tool = rows[1] as TimelineItem.ToolCallItem
        assertEquals("read", tool.name)
        assertEquals("file body", tool.result)
    }

    @Test
    fun `text after a tool call opens a second row below it`() {
        val rows = fold(
            event(
                1,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"role":"assistant","content":[
                    {"type":"text","text":"before"},
                    {"type":"tool-call","id":"c1","name":"read","arguments":"{}"},
                    {"type":"text","text":"after"}
                ]}}""",
            ),
        )

        val kinds = rows.map { it::class.simpleName }
        assertEquals(listOf("AssistantMessage", "ToolCallItem", "AssistantMessage"), kinds)
        assertEquals("before", ((rows[0] as TimelineItem.AssistantMessage).blocks.single() as ContentBlock.Text).text)
        assertEquals("after", ((rows[2] as TimelineItem.AssistantMessage).blocks.single() as ContentBlock.Text).text)
    }

    @Test
    fun `a call announced by the stream is not registered twice`() {
        val builder = TimelineBuilder()
        builder.applyStreamFrame(
            Json.parseToJsonElement(
                """{"type":"start","attemptId":"a1","turn":1,"step":1}""",
            ) as JsonObject,
        )
        builder.applyStreamFrame(
            Json.parseToJsonElement(
                """{"type":"chunk","attemptId":"a1","turn":1,"step":1,"chunk":
                    {"type":"tool-call-delta","id":"c1","name":"bash"}}""",
            ) as JsonObject,
        )
        builder.applyRecord(
            event(5, "tool/call", """{"turn":1,"step":1,"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}"""),
        )
        builder.applyRecord(
            event(
                6,
                "tool/result",
                """{"turn":1,"step":1,"message":{"role":"tool",
                    "source":{"kind":"tool","callId":"c1"},"toolCallId":"c1",
                    "content":[{"type":"text","text":"a.txt"}],"isError":false}}""",
            ),
        )

        val tools = builder.snapshot().filterIsInstance<TimelineItem.ToolCallItem>()
        assertEquals(1, tools.size)
        assertEquals("bash", tools.single().name)
        assertTrue(tools.single().arguments.contains("ls"))
        assertEquals("a.txt", tools.single().result)
    }

    @Test
    fun `an overlapping history page does not duplicate rows`() {
        val builder = TimelineBuilder()
        val shared = event(
            2,
            "assistant/message",
            """{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}}""",
        )
        builder.applyRecord(
            event(1, "user/message", """{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}"""),
        )
        builder.applyRecord(shared)

        // The older page re-includes the same assistant event, which is what the
        // follow window and a history page overlap on in practice.
        builder.prependPage(
            listOf(
                event(1, "user/message", """{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}"""),
                shared,
            ),
        )

        val assistants = builder.snapshot().filterIsInstance<TimelineItem.AssistantMessage>()
        assertEquals(1, assistants.size)
    }

    @Test
    fun `conversation keeps event order across turns`() {
        val rows = fold(
            event(1, "user/message", """{"content":[{"type":"text","text":"first"}],"source":{"kind":"user"}}"""),
            event(
                2,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"one"}]}}""",
            ),
            event(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            event(4, "user/message", """{"content":[{"type":"text","text":"second"}],"source":{"kind":"user"}}"""),
            event(
                5,
                "assistant/message",
                """{"turn":2,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"two"}]}}""",
            ),
        )

        assertEquals(
            listOf("UserMessage", "AssistantMessage", "UserMessage", "AssistantMessage"),
            rows.map { it::class.simpleName },
        )
    }

    @Test
    fun `an aborted step keeps the text it already produced`() {
        val builder = TimelineBuilder()
        builder.applyStreamFrame(
            Json.parseToJsonElement("""{"type":"start","attemptId":"a1","turn":1,"step":1}""") as JsonObject,
        )
        builder.applyStreamFrame(
            Json.parseToJsonElement(
                """{"type":"chunk","attemptId":"a1","turn":1,"step":1,"chunk":{"type":"text-delta","text":"partial answer"}}""",
            ) as JsonObject,
        )
        builder.applyRecord(event(9, "turn/end", """{"turn":1,"reason":{"kind":"aborted"}}"""))

        val prose = builder.snapshot().filterIsInstance<TimelineItem.AssistantMessage>()
        assertEquals(1, prose.size)
        assertEquals("partial answer", (prose.single().blocks.single() as ContentBlock.Text).text)
        assertTrue(!prose.single().streaming)
    }

    @Test
    fun `a failed attempt surfaces its failure`() {
        val rows = fold(
            event(
                7,
                "assistant/attempt",
                """{"turn":1,"step":2,"stream":[
                    {"type":"chunk","chunk":{"type":"finish","reason":{"kind":"error",
                        "failure":{"message":"Stream ended without finish_reason","code":"TRANSPORT"}}}}
                ]}""",
            ),
        )

        val outcome = rows.filterIsInstance<TimelineItem.TurnOutcome>().single()
        assertEquals(TurnOutcomeKind.Error, outcome.kind)
        assertEquals("Stream ended without finish_reason", outcome.message)
    }

    @Test
    fun `slash commands keep their result`() {
        val rows = fold(
            event(1, "command/run", """{"commandId":"cmd-1","name":"compact","args":""}"""),
            event(2, "command/done", """{"commandId":"cmd-1","kind":"success","text":"Compacted 12 items."}"""),
        )

        val command = rows.filterIsInstance<TimelineItem.CommandItem>().single()
        assertEquals("/compact", command.line)
        assertEquals("Compacted 12 items.", command.result)
    }
}
