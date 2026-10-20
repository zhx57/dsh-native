package dev.dsh.nativeapp.ui.chat

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether transcript text may be selected right now.
 *
 * Compose renders its selection handles and the floating toolbar in their own
 * popup window, which always sits above the Activity's own content. While the
 * drawer or the settings overlay is on screen those handles would float on top of
 * it, so selection is switched off for as long as an overlay is visible.
 */
val LocalTextSelectable = compositionLocalOf { true }
