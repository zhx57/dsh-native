package dev.dsh.nativeapp.ui.chat

import dev.dsh.nativeapp.data.TimelineItem

/**
 * List-row indices of the items that count as a "message".
 *
 * A turn produces one user row, one assistant row and then one row per tool call;
 * stepping row by row therefore moved a few pixels per tap. Navigation instead
 * jumps between these anchors.
 */
fun messageAnchors(timeline: List<TimelineItem>): List<Int> =
    timeline.indices.filter { index ->
        when (timeline[index]) {
            is TimelineItem.UserMessage -> true
            is TimelineItem.AssistantMessage -> true
            else -> false
        }
    }

/** First anchor strictly above [visibleTop], or null at the top of the list. */
fun anchorBefore(anchors: List<Int>, visibleTop: Int): Int? =
    anchors.lastOrNull { it < visibleTop }

/** First anchor strictly below the visible bottom row, or null at the tail. */
fun anchorAfter(anchors: List<Int>, visibleBottom: Int): Int? =
    anchors.firstOrNull { it > visibleBottom }

/** Last anchor in the transcript, used by the jump-to-latest arrow. */
fun lastAnchor(anchors: List<Int>): Int? = anchors.lastOrNull()
