package dev.dsh.nativeapp.ui.chat

import dev.dsh.nativeapp.data.TimelineItem

/** One turn's first row, so a tap can land exactly on the turn's opening. */
data class TurnAnchor(val turn: Int, val rowIndex: Int)

/**
 * First row of every turn in the loaded timeline.
 *
 * The fold emits a [TimelineItem.TurnDivider] at each `turn/start` after the first,
 * so the ladder is derived from those markers instead of guessing from message
 * counts: a turn with no user row (a steered message waking it) still gets an
 * anchor, and a turn that only ran tools still appears.
 *
 * The head anchor takes the first divider's turn minus one, because everything
 * above that divider belongs to the preceding turn. This keeps the numbers honest
 * when history paging has not reached the log start yet: the window may begin in
 * turn 30, and labelling that head "1" would misreport where the reader is.
 */
fun turnAnchors(timeline: List<TimelineItem>): List<TurnAnchor> {
    if (timeline.isEmpty()) return emptyList()
    val dividers = timeline.mapIndexedNotNull { index, item ->
        if (item is TimelineItem.TurnDivider && index > 0) TurnAnchor(item.turn, index) else null
    }
    val headTurn = dividers.firstOrNull()?.turn?.minus(1)?.coerceAtLeast(1) ?: 1
    return buildList {
        add(TurnAnchor(turn = headTurn, rowIndex = 0))
        addAll(dividers)
    }
}

/**
 * Turn whose row range contains [rowIndex].
 *
 * The last anchor at or above the row wins, which is what makes the counter read
 * the turn you are actually looking at even when the viewport sits mid-turn among
 * dozens of tool rows.
 */
fun currentTurn(anchors: List<TurnAnchor>, rowIndex: Int): Int? =
    anchors.lastOrNull { it.rowIndex <= rowIndex }?.turn

/** Anchor strictly above [topRow], for the up arrow. */
fun previousTurn(anchors: List<TurnAnchor>, topRow: Int): TurnAnchor? =
    anchors.lastOrNull { it.rowIndex < topRow }

/** Anchor strictly below [bottomRow], for the down arrow. */
fun nextTurn(anchors: List<TurnAnchor>, bottomRow: Int): TurnAnchor? =
    anchors.firstOrNull { it.rowIndex > bottomRow }

/** Final anchor, used by jump-to-latest. */
fun latestTurn(anchors: List<TurnAnchor>): TurnAnchor? = anchors.lastOrNull()
