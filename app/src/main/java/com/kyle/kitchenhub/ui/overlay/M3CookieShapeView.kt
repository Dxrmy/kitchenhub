package com.kyle.kitchenhub.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight Material 3 Expressive Cookie-Cutter shape view (7- or 9-lobed scalloped cookie).
 * Precomputes the Path and gradient shader in [onSizeChanged] for zero per-frame allocations.
 */
class M3CookieShapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var lobes: Int = 9
        set(value) {
            field = value.coerceAtLeast(4)
            rebuildPath()
            invalidate()
        }

    var isHaloMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var darkness: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            fillPaint.color = blendColor(normalFillColor, Color.BLACK, clamped)
            strokePaint.alpha = 255
            invalidate()
        }

    private val normalFillColor = Color.parseColor("#EB1B1F2A")

    fun setSolidTonalStyle(fillColor: Int, strokeColor: Int? = null) {
        fillPaint.color = fillColor
        strokePaint.shader = null
        if (strokeColor != null) {
            strokePaint.color = strokeColor
            strokePaint.alpha = 255
        } else {
            strokePaint.alpha = 0
        }
        invalidate()
    }

    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val inv = 1f - t
        val a = (Color.alpha(from) * inv + Color.alpha(to) * t).toInt()
        val r = (Color.red(from) * inv + Color.red(to) * t).toInt()
        val g = (Color.green(from) * inv + Color.green(to) * t).toInt()
        val b = (Color.blue(from) * inv + Color.blue(to) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private val cookiePath = Path()
    private val shaderMatrix = Matrix()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EB1B1F2A")
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A9B72CB")
    }

    private val geminiColors = intArrayOf(
        Color.parseColor("#CC4285F4"),
        Color.parseColor("#CC9B72CB"),
        Color.parseColor("#CCD96570"),
        Color.parseColor("#CC4285F4")
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w * 0.5f
        val cy = h * 0.5f
        strokePaint.shader = SweepGradient(cx, cy, geminiColors, null).also {
            shaderMatrix.setRotate(-45f, cx, cy)
            it.setLocalMatrix(shaderMatrix)
        }
        rebuildPath()
    }

    private fun rebuildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        cookiePath.reset()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val inset = if (isHaloMode) 1f else strokePaint.strokeWidth * 0.75f
        val outerR = min(w, h) * 0.5f - inset
        val innerR = outerR * 0.84f
        val n = lobes
        val step = (2.0 * PI) / n

        // Smooth M3 Expressive scalloped cookie: alternating convex lobe peaks and soft valleys
        val firstMidAngle = -PI * 0.5 - step * 0.5
        cookiePath.moveTo(
            cx + (innerR * cos(firstMidAngle)).toFloat(),
            cy + (innerR * sin(firstMidAngle)).toFloat()
        )
        for (i in 0 until n) {
            val peakAngle = -PI * 0.5 + i * step
            val nextValleyAngle = peakAngle + step * 0.5
            // Control point pushed slightly outward for plump M3 cookie lobes
            val ctrlR = outerR * 1.13f
            val ctrlX = cx + (ctrlR * cos(peakAngle)).toFloat()
            val ctrlY = cy + (ctrlR * sin(peakAngle)).toFloat()
            val endX = cx + (innerR * cos(nextValleyAngle)).toFloat()
            val endY = cy + (innerR * sin(nextValleyAngle)).toFloat()
            cookiePath.quadTo(ctrlX, ctrlY, endX, endY)
        }
        cookiePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (isHaloMode) {
            canvas.drawPath(cookiePath, haloPaint)
        } else {
            canvas.drawPath(cookiePath, fillPaint)
            canvas.drawPath(cookiePath, strokePaint)
        }
    }
}
