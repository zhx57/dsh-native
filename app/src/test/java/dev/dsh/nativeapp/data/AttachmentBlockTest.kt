package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachment blocks must survive the fold.
 *
 * Measured shapes from a real log:
 *  - `{"type":"file","attachment":{"attachmentId":"sha256:…","name":"…txt","bytes":1016642}}`
 *  - `{"type":"image","mediaType":"…","data":"…"}` or an attachment reference
 *
 * A `file` block used to fall through to Unknown and was dropped, so a sent file left
 * no trace in the transcript.
 */
class AttachmentBlockTest {

    private fun event(seq: Long, data: String): JsonObject = Json.parseToJsonElement(
        """{"type":"event","event":{"type":"user/message","seq":$seq,"time":1,"data":$data}}""",
    ) as JsonObject

    @Test
    fun `a file attachment becomes a File block with its size`() {
        val builder = TimelineBuilder()
        builder.applyRecord(
            event(
                1,
                """{"content":[
                    {"type":"text","text":"看看这个"},
                    {"type":"file","attachment":{
                        "attachmentId":"sha256:abc","name":"notes.txt","bytes":1016642}}
                ],"source":{"kind":"user"}}""",
            ),
        )

        val row = builder.snapshot().filterIsInstance<TimelineItem.UserMessage>().single()
        val file = row.blocks.filterIsInstance<ContentBlock.File>().single()
        assertEquals("notes.txt", file.name)
        assertEquals("sha256:abc", file.attachmentId)
        assertEquals(1_016_642L, file.bytes)
    }

    @Test
    fun `an inline image keeps its media type and payload`() {
        val builder = TimelineBuilder()
        builder.applyRecord(
            event(
                1,
                """{"content":[
                    {"type":"image","mediaType":"image/png","data":"AAAA","name":"shot.png"}
                ],"source":{"kind":"user"}}""",
            ),
        )

        val row = builder.snapshot().filterIsInstance<TimelineItem.UserMessage>().single()
        val image = row.blocks.filterIsInstance<ContentBlock.Image>().single()
        assertEquals("image/png", image.mediaType)
        assertEquals("shot.png", image.name)
    }

    @Test
    fun `an image given only as an attachment reference still renders`() {
        val builder = TimelineBuilder()
        builder.applyRecord(
            event(
                1,
                """{"content":[
                    {"type":"image","attachment":{"attachmentId":"sha256:x","name":"p.png","mediaType":"image/png"}}
                ],"source":{"kind":"user"}}""",
            ),
        )

        val row = builder.snapshot().filterIsInstance<TimelineItem.UserMessage>().single()
        assertEquals(1, row.blocks.filterIsInstance<ContentBlock.Image>().size)
    }

    @Test
    fun `a message with only an attachment is not dropped`() {
        val builder = TimelineBuilder()
        builder.applyRecord(
            event(
                1,
                """{"content":[
                    {"type":"file","attachment":{"attachmentId":"sha256:z","name":"a.bin","bytes":10}}
                ],"source":{"kind":"user"}}""",
            ),
        )

        assertTrue(builder.snapshot().any { it is TimelineItem.UserMessage })
    }
}
