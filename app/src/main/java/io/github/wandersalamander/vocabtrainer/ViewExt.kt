package io.github.wandersalamander.vocabtrainer

import android.app.Activity
import android.os.SystemClock
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

/** Alpha applied to a control dimmed by [setInteractive] while disabled. */
private const val DISABLED_ALPHA = 0.4f

/**
 * Toggle this view as an interactive control: [View.isEnabled], [View.isClickable],
 * and an alpha dim while disabled, all flipped in lockstep. The quiz UI always
 * moves these together; bundling them collapses the repeated pairs and removes
 * the risk of setting one without the other. The dim restores the disabled fade
 * the action buttons used to get for free as MaterialButtons, now that they're
 * flat TextViews with a selector background (see bg_btn_ghost / bg_btn_primary).
 */
fun View.setInteractive(interactive: Boolean) {
    isEnabled = interactive
    isClickable = interactive
    alpha = if (interactive) 1f else DISABLED_ALPHA
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
 * Pad the receiver by the system-bar insets under edge-to-edge: status bar at
 * the top, gesture-nav bar at the bottom, plus any side insets.
 *
 * On screens with the window-decor AppCompat ActionBar, appcompat 1.7 reports a
 * top inset here that ALREADY includes the action-bar height (status bar +
 * action bar), so applying that top inset alone drops the content just below the
 * bar. Older appcompat reported only the status bar, which is why this used to
 * add the action-bar height on top — doing that now double-counts and leaves a
 * gap the height of the action bar. Screens with no action bar simply get the
 * status-bar height in the top inset, so the same code is correct there too.
 *
 * Apply this to the scrolling container (e.g. the ScrollView) rather than its
 * inner content so that, with clipToPadding on, the insets clip scrolled rows
 * above the nav bar (and below the action bar) instead of letting them slide
 * under. Adds onto whatever padding the view already had; `clipToPadding=false`
 * is preserved so animation floaters (e.g. the goldenrod "+N" / "-N" deltas that
 * lift OUT of the streak / active cards) still draw through the padded region
 * above their parent card.
 *
 * Idempotent — calling twice replaces the previous listener via Android's
 * single-listener contract on the same view.
 */
internal fun View.applySystemBarInsetsAsPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(
            left = initialLeft + bars.left,
            top = initialTop + bars.top,
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
