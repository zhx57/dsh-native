package dev.dsh.nativeapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collapsed reasoning row shows a summary, transcribed from the web client's
 * `latestCompletedParagraphFirstLine` and `firstLine`.
 *
 * While running the summary is the first line of the latest paragraph that already
 * has a terminator, so it advances paragraph by paragraph instead of flickering on
 * every token. Once settled it is simply the first line.
 */
class ReasoningSummaryTest {

    @Test
    fun `running summary ignores an unterminated paragraph`() {
        // The second paragraph has no newline yet, so it is not a candidate.
        val text = "first thought\nstill first\n\nsecond thought so far"
        assertEquals("first thought", latestCompletedParagraphFirstLine(text))
    }

    @Test
    fun `running summary advances once the paragraph completes`() {
        val text = "first thought\n\nsecond thought\nmore detail"
        assertEquals("second thought", latestCompletedParagraphFirstLine(text))
    }

    @Test
    fun `running summary is empty before any line terminates`() {
        assertEquals("", latestCompletedParagraphFirstLine("only a fragment"))
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val text = "a\n\n\nb\nc"
        assertEquals("b", latestCompletedParagraphFirstLine(text))
    }

    @Test
    fun `settled summary is the first non-empty line`() {
        assertEquals("hello", firstLine("\n\nhello\nworld"))
        assertEquals("", firstLine("   \n\t\n"))
    }
}
