package com.kyle.kitchenhub.ui.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    var variant: String = "MINIMAL"
        set(value) { field = value; invalidate() }
    var reactiveAccent: Int? = null
        set(value) { field = value; invalidate() }
    var sunProgress: Float = -1f
        set(value) { field = value; invalidate() }
    var hasArtBackdrop: Boolean = false
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
    private val squirclePath = Path()
    private val squircleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val horizonArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(8f * resources.displayMetrics.density, 6f * resources.displayMetrics.density), 0f)
    }
    private val horizonDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val horizonArcRect = RectF()
    private val maskBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val effectiveNight = nightMode || variant == "NIGHTSTAND"
        val (c1, c2) = if (effectiveNight) {
            if (variant == "NIGHTSTAND") {
                Color.parseColor("#D94436") to Color.parseColor("#8F261C")
            } else {
                Color.parseColor("#FF2D20") to Color.parseColor("#C91D14")
            }
        } else {
            palettes[(paletteIndex % palettes.size + palettes.size) % palettes.size]
        }

        val nowMs = System.currentTimeMillis()
        val breathe = 0.5f + 0.5f * sin((nowMs % 8000L) / 8000.0 * 2.0 * PI).toFloat()

        if (!compactMode) {
            val d = resources.displayMetrics.density
            val t = bgDarkness.coerceIn(0f, 1f)
            val topInset = (84f * (1f - t) - 24f * t) * d
            val sideInset = (32f * (1f - t) - 24f * t) * d
            val bottomInset = (32f * (1f - t) - 24f * t) * d
            val corner = (32f * (1f - t)).coerceAtLeast(0f) * d

            squircleRect.set(sideInset, topInset, w - sideInset, h - bottomInset)
            squirclePath.reset()
            squirclePath.addRoundRect(squircleRect, corner, corner, Path.Direction.CW)

            val fillAlpha = if (hasArtBackdrop && (variant == "NOW_PLAYING" || variant == "PHOTOS")) {
                // Keep soft scrim over art/photo backdrop; darken fully as t -> 1
                (bgAlpha * (140f + 115f * t)).toInt().coerceIn(0, 255)
            } else {
                (bgAlpha * 255f).toInt().coerceIn(0, 255)
            }
            if (fillAlpha > 0) {
                squircleFillPaint.color = Color.argb(fillAlpha, 0, 0, 0)
                canvas.drawRoundRect(squircleRect, corner, corner, squircleFillPaint)
            }

            if (variant == "HORIZON" && !effectiveNight && bgAlpha > 0.02f) {
                val hAlpha = (bgAlpha * (1f - 0.65f * t)).coerceIn(0f, 1f)
                val driftShift = 0.08f * sin((nowMs % 16000L) / 16000.0 * 2.0 * PI).toFloat()
                val bottomMood = reactiveAccent ?: Color.parseColor("#2A426E")
                val topCol = Color.argb((hAlpha * 210f).toInt(), 9, 13, 24)
                val midCol = Color.argb((hAlpha * 220f).toInt(), 20, 30, 54)
                val botCol = Color.argb(
                    (hAlpha * 175f).toInt(),
                    Color.red(bottomMood),
                    Color.green(bottomMood),
                    Color.blue(bottomMood)
                )
                horizonPaint.shader = LinearGradient(
                    0f, squircleRect.top,
                    w * driftShift, squircleRect.bottom,
                    intArrayOf(topCol, midCol, botCol),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.save()
                canvas.clipPath(squirclePath)
                canvas.drawRect(squircleRect, horizonPaint)

                // Subtle sunrise-to-sunset arc along the bottom of the squircle
                if (sunProgress in 0f..1f) {
                    val arcW = squircleRect.width() * 0.42f
                    val cx = squircleRect.centerX()
                    val cy = squircleRect.bottom + 24f * d
                    horizonArcRect.set(cx - arcW * 0.5f, cy - 58f * d, cx + arcW * 0.5f, cy + 58f * d)
                    horizonArcPaint.color = Color.argb((hAlpha * 90f).toInt(), 255, 213, 79)
                    canvas.drawArc(horizonArcRect, 200f, 140f, false, horizonArcPaint)

                    val angleDeg = 200f + sunProgress * 140f
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val dx = horizonArcRect.centerX() + (horizonArcRect.width() * 0.5f) * cos(rad).toFloat()
                    val dy = horizonArcRect.centerY() + (horizonArcRect.height() * 0.5f) * sin(rad).toFloat()
                    horizonDotPaint.color = Color.argb((hAlpha * 220f).toInt(), 255, 213, 79)
                    canvas.drawCircle(dx, dy, 4.5f * d, horizonDotPaint)
                }
                canvas.restore()
            }

            if (variant != "NIGHTSTAND") {
                val borderAlpha = (bgAlpha * ((34f + 34f * breathe) * (1f - t))).toInt().coerceIn(0, 255)
                if (borderAlpha > 0) {
                    val borderRgb = if (effectiveNight) Color.WHITE else (reactiveAccent ?: Color.WHITE)
                    maskBorderPaint.color = Color.argb(
                        borderAlpha,
                        Color.red(borderRgb),
                        Color.green(borderRgb),
                        Color.blue(borderRgb)
                    )
                    canvas.drawRoundRect(squircleRect, corner, corner, maskBorderPaint)
                }
            }
        }

        val json = XMorphFaceRenderer.getFace(context, xmorphIndex) ?: return
        val animSec = if (effectiveNight) 0f else (nowMs % 120_000L) / 1000f
        XMorphFaceRenderer.drawFace(context, canvas, json, w, h, compactMode, effectiveNight, c1, c2, xmorphIndex, animSec)
    }
}
