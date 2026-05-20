package io.github.wandersalamander.vocabtrainer

import android.os.SystemClock
import android.view.View

/**
 * Drop-in replacement for [View.setOnClickListener] that swallows repeat taps
 * arriving within [debounceMs] of the last accepted one. Default 350 ms allows
 * ~3 deliberate taps/sec while filtering accidental double-taps.
 */
fun View.setDebouncedOnClickListener(
    debounceMs: Long = 350L,
    action: (View) -> Unit,
) {
    var lastClickMs = 0L
    setOnClickListener { v ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < debounceMs) return@setOnClickListener
        lastClickMs = now
        action(v)
    }
}

/**
 * Brief scale-up-then-back "pop" used to draw attention to a view that just
 * updated. [peakScale] is the apex; default suits small labels (the lifetime
 * counter, an active-bar label). Pass something modest like 1.04f for larger
 * views (a whole card) so the scaled view doesn't overlap its neighbors.
 */
fun View.pop(peakScale: Float = 1.18f) {
    animate().cancel()
    scaleX = 1f
    scaleY = 1f
    animate()
        .scaleX(peakScale).scaleY(peakScale)
        .setDuration(120L)
        .withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
        .start()
}
