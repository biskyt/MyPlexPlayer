package com.biskyt.myplexplayer.testutil

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction

/** Clicks what has [text] and can be clicked. */
fun ComposeTestRule.click(text: String) = onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

/** Waits for the board's film page ([BoardTitles.film]): its progress, its cast, and Resume with the remote. */
fun ComposeTestRule.waitForFilmPage() {
    waitForText("Resume · 38 min left", stepMs = 50)
    waitForText("Tom Hale", stepMs = 50)
    waitAdvancingClock(stepMs = 50) { "Resume" in focusedText() }
}
