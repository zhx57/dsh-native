package dev.dsh.nativeapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The running status prints `深度求索中，用时 {duration} ···`, and the duration
 * format is part of matching the web client.
 *
 * Transcribed from its `formatRunDuration`: hours appear only past an hour, minutes
 * only past a minute, and seconds always. So a short turn reads `12秒` rather than
 * `0分12秒`, which is what the browser shows.
 */
class RunningDurationTest {

    @Test
    fun `sub-minute shows seconds only`() {
        assertEquals("1秒", formatDuration(1_000))
        assertEquals("12秒", formatDuration(12_400))
        assertEquals("59秒", formatDuration(59_999))
    }

    @Test
    fun `minute boundary adds the minute part`() {
        assertEquals("1分0秒", formatDuration(60_000))
        assertEquals("2分30秒", formatDuration(150_000))
    }

    @Test
    fun `hour boundary adds the hour part`() {
        assertEquals("1小时0分0秒", formatDuration(3_600_000))
        assertEquals("1小时1分1秒", formatDuration(3_661_000))
    }

    @Test
    fun `negative input floors at zero second`() {
        // `System.currentTimeMillis()` can move backwards across a clock adjustment;
        // the label must not print a negative duration.
        assertEquals("0秒", formatDuration(-5_000))
    }
}
