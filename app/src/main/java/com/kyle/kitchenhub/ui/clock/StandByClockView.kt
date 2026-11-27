package com.kyle.kitchenhub.ui.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class StandByClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var xmorphIndex: Int = 0
        set(value) { field = value; invalidate() }
    var paletteIndex: Int = 0
        set(value) { field = value; invalidate() }
    var nightMode: Boolean = false
        set(value) { field = value; invalidate() }
    var weatherText: String = ""
        set(value) { field = value; invalidate() }
    var compactMode: Boolean = false
        set(value) { field = value; invalidate() }
    var bgDarkness: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var bgAlpha: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            val wasOff = field <= 0.02f
            field = clamped
            invalidate()
            if (wasOff && clamped > 0.02f && !tickerRunning && isAttachedToWindow) {
                tickerRunning = true
                post(ticker)
            }
        }

    private var tickerRunning = false
    private val palettes = listOf(
        Color.parseColor("#D9F99D") to Color.parseColor("#A5F3FC"),
        Color.parseColor("#FF9F0A") to Color.parseColor("#FF375F"),
        Color.parseColor("#30D158") to Color.parseColor("#0A84FF"),
        Color.parseColor("#BF5AF2") to Color.parseColor("#5E5CE6")
    )

    private val ticker = object : Runnable {
        override fun run() {
            if (bgAlpha > 0.02f && isAttachedToWindow) {
                tickerRunning = true
                invalidate()
                postDelayed(this, if (nightMode) 1000L else 66L)
            } else {
                tickerRunning = false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        tickerRunning = true
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        tickerRunning = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private val maskPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val maskRect = RectF()
    private val maskOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val maskBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val (c1, c2) = if (nightMode) {
            Color.parseColor("#FF2D20") to Color.parseColor("#C91D14")
        } else {
            palettes[(paletteIndex % palettes.size + palettes.size) % palettes.size]
        }

        if (!compactMode) {
            val d = resources.displayMetrics.density
            // As bgDarkness goes 0 -> 1, squircle expands out past the screen edges
            val t = bgDarkness.coerceIn(0f, 1f)
            val inset = (22f * (1f - t) - 24f * t) * d
            val corner = (42f * (1f - t)).coerceAtLeast(0f) * d

            // Darken interior smoothly as bgDarkness increases (0 = clear shared AmbientBackdropView)
            val innerDarkAlpha = if (nightMode) {
                (bgAlpha * (170f + 85f * t)).toInt().coerceIn(0, 255)
            } else {
                (bgAlpha * (t * 238f)).toInt().coerceIn(0, 255)
            }
            if (innerDarkAlpha > 0) {
                canvas.drawColor(Color.argb(innerDarkAlpha, 5, 7, 12))
            }

            // Outer squircle border mask (part of the same backdrop, expands outward on darken)
            if (t < 0.99f) {
                maskPath.reset()
                maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
                maskRect.set(inset, inset, w - inset, h - inset)
                maskPath.addRoundRect(maskRect, corner, corner, Path.Direction.CW)

                val outerAlpha = (bgAlpha * (145f * (1f - t))).toInt().coerceIn(0, 255)
                maskOuterPaint.color = Color.argb(outerAlpha, 8, 11, 18)
                canvas.drawPath(maskPath, maskOuterPaint)

                val borderAlpha = (bgAlpha * (95f * (1f - t))).toInt().coerceIn(0, 255)
                if (borderAlpha > 0) {
                    maskBorderPaint.color = Color.argb(
                        borderAlpha,
                        Color.red(c1),
                        Color.green(c1),
                        Color.blue(c1)
                    )
                    canvas.drawRoundRect(maskRect, corner, corner, maskBorderPaint)
                }
            }
        }

        val json = XMorphFaceRenderer.getFace(context, xmorphIndex) ?: return
        val animSec = if (nightMode) 0f else (System.currentTimeMillis() % 120_000L) / 1000f
        XMorphFaceRenderer.drawFace(context, canvas, json, w, h, compactMode, nightMode, c1, c2, xmorphIndex, animSec)
    }
}
