package io.github.wandersalamander.vocabtrainer

import android.app.Activity
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

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

/**
 * Switch this activity's window into edge-to-edge so the system bars become
 * transparent overlays and apps see real status-bar / nav-bar insets via
 * [ViewCompat.setOnApplyWindowInsetsListener]. Android 15+ with targetSdk 35
 * enforces this by default; calling it explicitly keeps Android 14 and earlier
 * visually consistent so we only need one inset-handling code path.
 */
internal fun Activity.enableEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * Pixel height of the AppCompat-themed action bar for this activity. Returns
 * 0 when the theme has no action bar (NoActionBar variants).
 */
internal fun Activity.themeActionBarHeightPx(): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
        TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    } else 0
}

/**
 * Push the receiver below the status bar + window-decor action bar and above
 * the gesture-nav bar. Edge-to-edge (targetSdk 35) removes the system's
 * automatic reservation for those strips, so the activity's content otherwise
 * draws underneath them. Adds the inset values on top of whatever padding the
 * view already had — the existing 14dp / 20dp room used by activity_main's
 * cards stays exactly the same after this.
 *
 * Pass [actionBarHeightPx] = [themeActionBarHeightPx] when the activity uses
 * the window-decor AppCompat ActionBar (which under edge-to-edge no longer
 * reserves a strip of its own); pass 0 on screens with their own Toolbar.
 *
 * `clipToPadding=false` is preserved so animation floaters (e.g. the goldenrod
 * "+N" / "-N" deltas that lift OUT of the streak / active cards) still draw
 * through the padded region above their parent card.
 *
 * Idempotent — calling twice replaces the previous listener via Android's
 * single-listener contract on the same view.
 */
internal fun View.applySystemBarAndActionBarInsetsAsPadding(actionBarHeightPx: Int) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(
            left = initialLeft + bars.left,
            top = initialTop + bars.top + actionBarHeightPx,
            right = initialRight + bars.right,
            bottom = initialBottom + bars.bottom,
        )
        windowInsets
    }
    // Trigger an inset dispatch in case the view is already attached; without
    // this, padding stays at the pre-insets values until the next config
    // change (e.g. rotation) fires the listener.
    ViewCompat.requestApplyInsets(this)
}
