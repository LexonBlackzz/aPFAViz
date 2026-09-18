package com.apfaviz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Lightweight playback scrubber with a wide lens-like thumb.
 *
 * This deliberately does NOT use LiquidGlassView or backdrop capture: playback
 * sits over a SurfaceView, and sampling that surface would add exactly the kind
 * of GPU/copy work aPFAViz tries to keep out of the MIDI hot path. The thumb
 * instead fakes the glass cue locally by re-drawing the track inside a
 * translucent capsule with a tiny optical offset, rim highlight and soft
 * non-blurred shadow.
 */
class GlassPlaybackSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var max: Int = 1000
        set(value) {
            field = value.coerceAtLeast(1)
            progress = progress.coerceIn(0, field)
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    var onStartTracking: (() -> Unit)? = null
    var onProgressChanged: ((progress: Int, fromUser: Boolean) -> Unit)? = null
    var onStopTracking: ((progress: Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val trackHeight = dp(5f)
    private val activeTrackHeight = dp(6f)
    private val thumbWidth = dp(58f)
    private val thumbHeight = dp(32f)
    private val pressedExtraWidth = dp(5f)
    private val pressedExtraHeight = dp(2f)
    private val sideInset = dp(6f)

    private var tracking = false

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(112, 208, 212, 221)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(63, 145, 245)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 0, 0, 0)
    }
    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 245, 249, 255)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(145, 255, 255, 255)
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(62, 156, 190, 221)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(68, 255, 255, 255)
    }
    private val lensActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 181, 255)
    }
    private val lensInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 230, 234, 242)
    }

    private val trackRect = RectF()
    private val activeRect = RectF()
    private val thumbRect = RectF()
    private val shadowRect = RectF()
    private val highlightRect = RectF()
    private val innerRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48f).roundToInt()
        contentDescription = "Playback position"
    }

    private fun trackLeft(): Float = paddingLeft + sideInset + thumbWidth * 0.5f
    private fun trackRight(): Float =
        width - paddingRight - sideInset - thumbWidth * 0.5f

    private fun fraction(): Float =
        if (max <= 0) 0f else progress.toFloat() / max.toFloat()

    private fun thumbCenterX(): Float {
        val left = trackLeft()
        val right = trackRight().coerceAtLeast(left)
        return left + (right - left) * fraction().coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(48f).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cy = height * 0.5f
        val left = trackLeft()
        val right = trackRight().coerceAtLeast(left)
        val tx = thumbCenterX()

        trackRect.set(left, cy - trackHeight * 0.5f, right, cy + trackHeight * 0.5f)
        canvas.drawRoundRect(trackRect, trackHeight, trackHeight, inactivePaint)

        activeRect.set(left, cy - activeTrackHeight * 0.5f,
            tx, cy + activeTrackHeight * 0.5f)
        canvas.drawRoundRect(activeRect, activeTrackHeight, activeTrackHeight, activePaint)

        val w = thumbWidth + if (tracking) pressedExtraWidth else 0f
        val h = thumbHeight + if (tracking) pressedExtraHeight else 0f
        thumbRect.set(tx - w * 0.5f, cy - h * 0.5f, tx + w * 0.5f, cy + h * 0.5f)

        // A cheap, non-blurred grounding shadow. Avoiding BlurMaskFilter keeps
        // this hardware-friendly while the native renderer is under load.
        shadowRect.set(thumbRect)
        shadowRect.offset(0f, dp(2f))
        canvas.drawRoundRect(shadowRect, h * 0.5f, h * 0.5f, shadowPaint)

        // Neutral translucent body: the track remains visible below it.
        canvas.drawRoundRect(thumbRect, h * 0.5f, h * 0.5f, glassFillPaint)

        // Re-draw the track inside the capsule slightly lower and brighter.
        // That discontinuity is the local "lens" cue, with no backdrop capture.
        val save = canvas.save()
        canvas.clipRoundRect(thumbRect, h * 0.5f, h * 0.5f)
        val lensY = cy + dp(0.8f)
        val lensH = dp(7f)

        trackRect.set(left, lensY - lensH * 0.5f, right, lensY + lensH * 0.5f)
        canvas.drawRoundRect(trackRect, lensH, lensH, lensInactivePaint)

        activeRect.set(left, lensY - lensH * 0.5f, tx, lensY + lensH * 0.5f)
        canvas.drawRoundRect(activeRect, lensH, lensH, lensActivePaint)
        canvas.restoreToCount(save)

        // Specular top-left rim.
        highlightRect.set(
            thumbRect.left + dp(2f),
            thumbRect.top + dp(2f),
            thumbRect.right - dp(2f),
            thumbRect.centerY() + dp(1f)
        )
        canvas.drawRoundRect(highlightRect, h * 0.5f, h * 0.5f, highlightPaint)

        innerRect.set(
            thumbRect.left + dp(1.5f),
            thumbRect.top + dp(1.5f),
            thumbRect.right - dp(1.5f),
            thumbRect.bottom - dp(1.5f)
        )
        canvas.drawRoundRect(innerRect, h * 0.5f, h * 0.5f, innerRimPaint)
        canvas.drawRoundRect(thumbRect, h * 0.5f, h * 0.5f, rimPaint)
    }

    private fun updateFromTouch(x: Float, fromUser: Boolean) {
        val left = trackLeft()
        val right = trackRight().coerceAtLeast(left + 1f)
        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val next = (f * max).roundToInt().coerceIn(0, max)
        if (next != progress) {
            progress = next
            onProgressChanged?.invoke(next, fromUser)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, true)
                onStartTracking?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x, true)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                onStopTracking?.invoke(progress)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onStopTracking?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
