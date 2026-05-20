package io.github.wandersalamander.vocabtrainer.quiz

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.exp

/**
 * Vocab card with a pull-down gesture. Drag tracks the finger 1:1 up to
 * [TRIGGER_DP] then rubber-bands toward [MAX_PULL_DP] past it — the change
 * in resistance gives the finger a tactile cue that mirrors the haptic
 * tick fired at the same threshold.
 *
 * Release behaviour splits on whether the threshold was crossed:
 *  - Crossed: [onPullCommitted] fires and the card returns with a single
 *    readable overshoot. The finger's release velocity (scaled by the
 *    current rubber-band stiffness so the card picks up where the user
 *    *felt* it was moving, not where the finger was moving) seeds the
 *    spring.
 *  - Not crossed (or pulled back up before release): the card settles
 *    silently with a critically-damped spring. Saving the bounce for the
 *    committed case keeps it from feeling like the card spat back a
 *    short-of-threshold pull.
 *
 * Child buttons (⚑ flag, ✓ learned) and the tappable foreign word keep
 * working: onInterceptTouchEvent only claims the touch stream once vertical
 * travel exceeds the system touch slop AND dominates horizontal travel.
 * Disabled until [pullEnabled] is flipped on.
 */
class SwipeableVocabCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    var pullEnabled: Boolean = false
    var onPullCommitted: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val triggerPx = TRIGGER_DP * density
    private val maxPullPx = MAX_PULL_DP * density
    private val overpullPx = maxPullPx - triggerPx
    private val maxBounceVelocityPx = MAX_BOUNCE_VELOCITY_DP_PER_S * density

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var crossedThreshold: Boolean = false
    private var velocityTracker: VelocityTracker? = null

    // Committed release: one readable overshoot. Stiffness sits well below
    // STIFFNESS_MEDIUM (1500) so the boing reads at ~400ms instead of
    // collapsing into a ~150ms stutter. Damping 0.55 sits between
    // LOW_BOUNCY (0.75) and MEDIUM_BOUNCY (0.5) — ~12% overshoot, visible
    // without clipping into the streak card above.
    private val bouncyReturn: SpringAnimation by lazy {
        SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f)
                .setDampingRatio(BOUNCY_DAMPING_RATIO)
                .setStiffness(BOUNCY_STIFFNESS)
        }
    }

    // Uncommitted release: smooth, no overshoot. A bounce here would feel
    // like the card spitting back a half-hearted pull.
    private val smoothReturn: SpringAnimation by lazy {
        SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
        }
    }

    init {
        // Lift above sibling cards so a downward drag visually overlaps them
        // instead of slipping behind cardLifetime / cardActive.
        translationZ = density
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!pullEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(ev)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (dy > touchSlop && dy > abs(dx)) return true
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> releaseVelocityTracker()
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!pullEnabled) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached when the touch lands on empty card space (no child
                // claimed it). onInterceptTouchEvent's DOWN branch covers the
                // child-then-intercept path.
                beginGesture(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - downY
                translationY = resistance(dy)
                if (!crossedThreshold && dy >= triggerPx) {
                    crossedThreshold = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = ev.rawY - downY
                val cardVelocity = computeCardVelocityAt(dy)
                releaseVelocityTracker()
                if (dy >= triggerPx) {
                    onPullCommitted?.invoke()
                    bouncyReturn
                        .setStartVelocity(
                            cardVelocity.coerceIn(-maxBounceVelocityPx, maxBounceVelocityPx)
                        )
                        .start()
                } else {
                    smoothReturn.setStartVelocity(cardVelocity).start()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseVelocityTracker()
                smoothReturn.setStartVelocity(0f).start()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun beginGesture(ev: MotionEvent) {
        downX = ev.rawX
        downY = ev.rawY
        crossedThreshold = false
        bouncyReturn.cancel()
        smoothReturn.cancel()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    /**
     * Translate the finger's release velocity into the card's actual visual
     * velocity. Past [triggerPx] the rubber band attenuates how much each
     * finger-pixel pushes the card, so the card moves slower than the
     * finger. Feeding raw finger velocity into the spring would cause a
     * visible jolt at release; scaling by the resistance derivative makes
     * the spring continue the motion the user *saw*.
     */
    private fun computeCardVelocityAt(fingerDy: Float): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000) // px/sec
        val fingerVelocity = tracker.yVelocity
        val factor = if (fingerDy <= triggerPx) 1f
        else exp(-(fingerDy - triggerPx) / overpullPx)
        return fingerVelocity * factor
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 1:1 finger-to-card travel until the trigger threshold, then an
     * asymptotic rubber band into the remaining [overpullPx] of room.
     * The kink at [triggerPx] makes the threshold *felt* in the gesture
     * itself — the user notices the resistance change at the same instant
     * the haptic tick fires.
     */
    private fun resistance(dy: Float): Float {
        if (dy <= 0f) return 0f
        if (dy <= triggerPx) return dy
        val past = dy - triggerPx
        return triggerPx + overpullPx * (1f - exp(-past / overpullPx))
    }

    companion object {
        private const val TRIGGER_DP = 48f
        private const val MAX_PULL_DP = 80f
        private const val BOUNCY_DAMPING_RATIO = 0.55f
        private const val BOUNCY_STIFFNESS = 350f
        // Cap the velocity carried into the bouncy spring so a violent
        // flick doesn't catapult the card past the top edge on overshoot.
        private const val MAX_BOUNCE_VELOCITY_DP_PER_S = 1500f
    }
}
