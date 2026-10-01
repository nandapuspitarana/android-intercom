package com.intercom.video.twoway.functional

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Waits until a node with the given test tag exists in the UI. */
fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) = waitUntil(timeoutMs) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.hasTag(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

/** Waits until some node contains [text]. */
fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

/** Polls [condition] every 25 ms until it is true or [timeoutMs] passes; returns the final value. */
fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(25)
    }
    return condition()
}
