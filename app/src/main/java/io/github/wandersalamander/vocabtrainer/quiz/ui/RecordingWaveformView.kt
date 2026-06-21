package io.github.wandersalamander.vocabtrainer.quiz.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import io.github.wandersalamander.vocabtrainer.R
import kotlin.math.abs
import kotlin.math.max

/**
 * Voice-message bar view for the Speaking tier's recording — the WhatsApp-style
 * row of amplitude bars. It shows live loudness bars while recording, the full
 * waveform afterwards, and a left-to-right "played" fill during playback.
 *
 * It is also the record control: a single tap replays the take, while a
 * press-and-hold records. The two are disambiguated by a short hold threshold
 * ([HOLD_THRESHOLD_MS]) — stock Android's long-press timing is for a different
 * (one-shot) semantic, so the tap/hold split is handled here.
 */
class RecordingWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Fired once the press passes the hold threshold (recording should start). */
    var onHoldRecordStart: (() -> Unit)? = null

    /** Fired when a recording hold is released (recording should stop + play). */
    var onHoldRecordStop: (() -> Unit)? = null

    /** Fired when a recording hold is aborted (pointer cancelled). */
    var onHoldRecordCancel: (() -> Unit)? = null

    /** Fired on a quick tap (replay the existing take, if any). */
    var onTapPlay: (() -> Unit)? = null

    /** Prompt drawn centered when there's no waveform yet (e.g. "🎤 Hold to speak"). */
    var idleText: CharSequence = ""
        set(value) {
            field = value
            invalidate()
        }

    // Normalized amplitude samples (0..1), oldest first.
    private val samples = ArrayList<Float>()

    // 0..1 playback progress; bars left of it render at full alpha.
    private var playedFraction = 0f

    private val density = resources.displayMetrics.density
    private val barWidth = 3f * density
    private val barGap = 2.5f * density
    private val sidePad = 4f * density
    private val minBarHalf = 1.5f * density
    private val corner = 1.5f * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        color = ContextCompat.getColor(context, R.color.card_text_muted)
    }
    private val barColor = ContextCompat.getColor(context, R.color.seg_speak)

    // ── tap-vs-hold gesture ──
    private val handler = Handler(Looper.getMainLooper())
    private var holdEngaged = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val holdRunnable = Runnable {
        holdEngaged = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHoldRecordStart?.invoke()
    }

    val hasWaveform: Boolean get() = samples.isNotEmpty()

    /**
     * Clear the waveform data back to the idle prompt. Deliberately does NOT
     * touch the in-flight gesture state (`holdEngaged` / the pending hold):
     * SpeakingController.startRecording() calls this the instant a hold engages,
     * and clearing `holdEngaged` here would make the following ACTION_UP read as
     * a tap — so the release (stop + play back) would never fire.
     */
    fun reset() {
        samples.clear()
        playedFraction = 0f
        invalidate()
    }

    /** Append one normalized (0..1) amplitude sample while recording. */
    fun addSample(amplitude: Float) {
        samples.add(amplitude.coerceIn(0f, 1f))
        playedFraction = 0f
        invalidate()
    }

    /** 0..1 playback progress; bars left of it render filled. */
    fun setPlayedFraction(fraction: Float) {
        playedFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (samples.isEmpty()) {
            if (idleText.isNotEmpty()) {
                val baseline = h / 2f - (idlePaint.descent() + idlePaint.ascent()) / 2f
                canvas.drawText(idleText.toString(), w / 2f, baseline, idlePaint)
            }
            return
        }
        val slot = barWidth + barGap
        val barCount = max(1, ((w - 2f * sidePad) / slot).toInt())
        val n = samples.size
        val drawn = if (n <= barCount) n else barCount
        val midY = h / 2f
        val maxHalf = h / 2f - minBarHalf - sidePad
        var x = sidePad
        for (i in 0 until drawn) {
            val amp = if (n <= barCount) {
                samples[i]
            } else {
                // Bucket-max downsample so a long take still shows whole.
                val start = i * n / drawn
                val end = ((i + 1) * n / drawn).coerceAtMost(n)
                var m = 0f
                for (j in start until end) m = max(m, samples[j])
                m
            }
            val half = minBarHalf + amp * maxHalf
            val played = (i + 0.5f) / drawn <= playedFraction
            barPaint.color = barColor
            barPaint.alpha = if (played) 255 else 105
            canvas.drawRoundRect(x, midY - half, x + barWidth, midY + half, corner, corner, barPaint)
            x += slot
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                holdEngaged = false
                // Claim the gesture so a scrolling ancestor can't steal the hold.
                parent?.requestDisallowInterceptTouchEvent(true)
                handler.postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // A real drag before the hold engages reads as a scroll, not a
                // record — drop the pending start. Once recording, ignore drift.
                if (!holdEngaged &&
                    (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
                ) {
                    handler.removeCallbacks(holdRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (holdEngaged) {
                    holdEngaged = false
                    onHoldRecordStop?.invoke()
                } else {
                    performClick()
                    onTapPlay?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                if (holdEngaged) {
                    holdEngaged = false
                    onHoldRecordCancel?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        // Hold longer than this to start recording; a shorter press is a tap
        // (replay). Snappier than the system long-press timeout (~500ms).
        private const val HOLD_THRESHOLD_MS = 200L
    }
}
