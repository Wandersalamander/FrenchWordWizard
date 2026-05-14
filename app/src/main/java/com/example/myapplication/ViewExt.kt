package com.example.myapplication

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
