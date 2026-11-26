package com.kyle.kitchenhub.ui.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.core.ui.FrostedBackdropEngine
import com.kyle.kitchenhub.core.ui.PixelSparkleView

class FrostedSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var label: String = "Volume"
    var progress: Int = 50
        set(value) { field = value.coerceIn(0, 100); invalidate() }
    var sparkleOverlay: PixelSparkleView? = null
    var onChanged: ((Int) -> Unit)? = null

    private var dragging = false
    private var lastDetentStep = -1
    private val rect = RectF()
    private val fillRect = RectF()
    private val thumbRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D98AB4F8") }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#062E6F") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = FontCache.plusJakarta(context)
        textSize = 17f * resources.displayMetrics.scaledDensity
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (66 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        val r = h * 0.5f
        rect.set(0f, 0f, w, h)
        FrostedBackdropEngine.drawFrostedRect(canvas, this, rect, r, 0xB8)

        val fillW = (w * (progress / 100f)).coerceAtLeast(h)
        fillRect.set(0f, 0f, fillW.coerceAtMost(w), h)
        canvas.drawRoundRect(fillRect, r, r, fillPaint)

        // M3 Expressive handle: widens slightly when dragged
        val thumbW = (if (dragging) 7f else 4f) * d
        val thumbH = 30f * d
        val thumbX = (fillRect.right - 16f * d).coerceIn(16f * d, w - 16f * d)
        thumbRect.set(thumbX - thumbW * 0.5f, (h - thumbH) * 0.5f, thumbX + thumbW * 0.5f, (h + thumbH) * 0.5f)
        canvas.drawRoundRect(thumbRect, thumbW, thumbW, thumbPaint)

        val cy = h * 0.5f - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText("$label  •  $progress%", 24f * d, cy, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                sparkleOverlay?.triggerBurstOnView(this)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromX(x: Float) {
        val pct = ((x / width.coerceAtLeast(1)) * 100f).toInt().coerceIn(0, 100)
        if (pct != progress) {
            progress = pct
            val step = pct / 5
            if (step != lastDetentStep) {
                lastDetentStep = step
                TactileAudioEngine.playDetentTick()
            }
            onChanged?.invoke(pct)
        }
    }
}
