package com.apfaviz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Small setup slider that borrows the playback scrubber's lens language without
 * using backdrop capture. The thumb is deliberately compact so the settings
 * cards stay quiet; only the thumb itself gets the local glass/lens treatment.
 */
class LensSettingSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            progress = progress.coerceIn(0, field)
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            val next = value.coerceIn(0, max)
            if (field == next) return
            field = next
            invalidate()
        }

    var onProgressChanged: ((Int) -> Unit)? = null
    var onStopTracking: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val trackH = dp(4f)
    private val thumbW = dp(32f)
    private val thumbH = dp(22f)
    private val pressedW = dp(3f)
    private val sideInset = dp(2f)
    private var tracking = false

    private val inactive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 220, 224, 232)
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 212, 191)
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 248, 251, 255)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(128, 255, 255, 255)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
    }
    private val lensActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(97, 231, 211)
    }
    private val lensInactive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 229, 234, 240)
    }

    private val r = RectF()
    private val thumb = RectF()
    private val hi = RectF()
    private val clipPath = Path()

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(36f).roundToInt()
    }

    private fun left(): Float = paddingLeft + sideInset + thumbW * 0.5f
    private fun right(): Float = width - paddingRight - sideInset - thumbW * 0.5f
    private fun fraction(): Float = progress.toFloat() / max.toFloat()

    private fun xForProgress(): Float {
        val l = left()
        val rr = right().coerceAtLeast(l)
        return l + (rr - l) * fraction().coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(36f).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return

        val cy = height * 0.5f
        val l = left()
        val rr = right().coerceAtLeast(l)
        val tx = xForProgress()

        r.set(l, cy - trackH * 0.5f, rr, cy + trackH * 0.5f)
        canvas.drawRoundRect(r, trackH, trackH, inactive)
        r.set(l, cy - trackH * 0.5f, tx, cy + trackH * 0.5f)
        canvas.drawRoundRect(r, trackH, trackH, active)

        val w = thumbW + if (tracking) pressedW else 0f
        thumb.set(tx - w * 0.5f, cy - thumbH * 0.5f, tx + w * 0.5f, cy + thumbH * 0.5f)
        canvas.drawRoundRect(thumb, thumbH * 0.5f, thumbH * 0.5f, glass)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            thumb,
            thumbH * 0.5f,
            thumbH * 0.5f,
            Path.Direction.CW
        )
        canvas.clipPath(clipPath)
        val lensY = cy + dp(0.5f)
        val lensH = dp(5.5f)
        r.set(l, lensY - lensH * 0.5f, rr, lensY + lensH * 0.5f)
        canvas.drawRoundRect(r, lensH, lensH, lensInactive)
        r.set(l, lensY - lensH * 0.5f, tx, lensY + lensH * 0.5f)
        canvas.drawRoundRect(r, lensH, lensH, lensActive)
        canvas.restoreToCount(save)

        hi.set(
            thumb.left + dp(1.5f), thumb.top + dp(1.5f),
            thumb.right - dp(1.5f), thumb.centerY()
        )
        canvas.drawRoundRect(hi, thumbH * 0.5f, thumbH * 0.5f, highlight)
        canvas.drawRoundRect(thumb, thumbH * 0.5f, thumbH * 0.5f, rim)
    }

    private fun update(x: Float) {
        val l = left()
        val rr = right().coerceAtLeast(l + 1f)
        val f = ((x - l) / (rr - l)).coerceIn(0f, 1f)
        val next = (f * max).roundToInt().coerceIn(0, max)
        if (next != progress) {
            progress = next
            onProgressChanged?.invoke(next)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                update(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                update(event.x)
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