package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Blocks pager gestures while allowing a content tap to dismiss the reader menu. */
internal fun Modifier.blockReaderGestures(
    enabled: Boolean,
    onContentTap: () -> Unit
): Modifier = pointerInput(enabled, onContentTap) {
    if (!enabled) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        val downPosition = down.position
        var isTap = true
        down.consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { change ->
                if ((change.position - downPosition).getDistance() > 12f) isTap = false
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (isTap) onContentTap()
    }
}
