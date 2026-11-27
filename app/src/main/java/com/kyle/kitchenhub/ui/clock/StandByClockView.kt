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

    private val squircleRect = RectF()
    private val squircleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
            // t = 0: black squircle below the top tab bar over the shared AmbientBackdropView
            // t = 1: black squircle expands outward to cover everything black
            val t = bgDarkness.coerceIn(0f, 1f)
            val topInset = (68f * (1f - t) - 24f * t) * d
            val sideInset = (22f * (1f - t) - 24f * t) * d
            val bottomInset = (22f * (1f - t) - 24f * t) * d
            val corner = (36f * (1f - t)).coerceAtLeast(0f) * d

            squircleRect.set(sideInset, topInset, w - sideInset, h - bottomInset)

            val fillAlpha = (bgAlpha * 255f).toInt().coerceIn(0, 255)
            if (fillAlpha > 0) {
                squircleFillPaint.color = Color.argb(fillAlpha, 0, 0, 0)
                canvas.drawRoundRect(squircleRect, corner, corner, squircleFillPaint)
            }

            val borderAlpha = (bgAlpha * (95f * (1f - t))).toInt().coerceIn(0, 255)
            if (borderAlpha > 0) {
                maskBorderPaint.color = Color.argb(
                    borderAlpha,
                    Color.red(c1),
                    Color.green(c1),
                    Color.blue(c1)
                )
                canvas.drawRoundRect(squircleRect, corner, corner, maskBorderPaint)
            }
        }

        val json = XMorphFaceRenderer.getFace(context, xmorphIndex) ?: return
        val animSec = if (nightMode) 0f else (System.currentTimeMillis() % 120_000L) / 1000f
        XMorphFaceRenderer.drawFace(context, canvas, json, w, h, compactMode, nightMode, c1, c2, xmorphIndex, animSec)
    }
}
