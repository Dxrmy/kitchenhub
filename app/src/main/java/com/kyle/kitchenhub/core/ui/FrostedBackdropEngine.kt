package com.kyle.kitchenhub.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.palette.graphics.Palette

object FrostedBackdropEngine {
    private const val BACKDROP_W = 160
    private const val BACKDROP_H = 100

    private var blurredBackdrop: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private var tintColor: Int = Color.parseColor("#1C1E26")
    var reduceTransparency: Boolean = false
    var blurRadius: Int = 18

    private val shaderMatrix = Matrix()
    private val viewLoc = IntArray(2)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#24FFFFFF")
    }

    fun updateBackdrop(source: Bitmap?, dynamicTint: Int? = null) {
        if (source == null) {
            tintColor = dynamicTint ?: Color.parseColor("#1C1E26")
            return
        }
        val scaled = Bitmap.createScaledBitmap(source, BACKDROP_W, BACKDROP_H, true)
        blurredBackdrop?.recycle()
        val blurred = fastStackBlur(scaled, blurRadius.coerceIn(4, 24))
        blurredBackdrop = blurred
        backdropShader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bmpPaint.shader = backdropShader
        if (scaled != blurred) scaled.recycle()

        if (dynamicTint != null) {
            tintColor = dynamicTint
        } else {
            val palette = Palette.from(blurredBackdrop!!).generate()
            tintColor = palette.getDarkVibrantColor(
                palette.getDarkMutedColor(Color.parseColor("#1C1E26"))
            )
        }
    }

    fun setDynamicTint(color: Int) {
        tintColor = color
    }

    fun getTintColor(): Int = tintColor

    fun createFrostedDrawable(view: View, cornerRadiusDp: Float): android.graphics.drawable.Drawable {
        val radiusPx = cornerRadiusDp * view.resources.displayMetrics.density
        val rect = RectF()
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                drawFrostedRect(canvas, view, rect, radiusPx)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    fun drawFrostedRect(
        canvas: Canvas,
        view: View,
        bounds: RectF,
        cornerRadiusPx: Float,
        scrimAlpha: Int = 0xA6
    ) {
        val bmp = blurredBackdrop
        val shader = backdropShader
        if (!reduceTransparency && bmp != null && !bmp.isRecycled && shader != null && view.rootView.width > 0) {
            view.getLocationInWindow(viewLoc)
            val rw = view.rootView.width.toFloat()
            val rh = view.rootView.height.toFloat().coerceAtLeast(1f)
            shaderMatrix.reset()
            shaderMatrix.setScale(rw / BACKDROP_W.toFloat(), rh / BACKDROP_H.toFloat())
            shaderMatrix.postTranslate(-viewLoc[0].toFloat(), -viewLoc[1].toFloat())
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, bmpPaint)
        }

        val alpha = if (reduceTransparency) 0xEE else scrimAlpha
        scrimPaint.color = (alpha shl 24) or (tintColor and 0x00FFFFFF)
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, scrimPaint)

        borderPaint.strokeWidth = view.resources.displayMetrics.density * 0.75f
        borderPaint.color = Color.parseColor("#18FFFFFF")
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }

    fun trim() {
        bmpPaint.shader = null
        backdropShader = null
        blurredBackdrop?.recycle()
        blurredBackdrop = null
    }

    private fun fastStackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val div = radius * 2 + 1

        for (y in 0 until h) {
            val row = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val p = pix[row + i.coerceIn(0, w - 1)]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val add = pix[row + (x + radius + 1).coerceAtMost(w - 1)]
                val rem = pix[row + (x - radius).coerceAtLeast(0)]
                rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                bSum += (add and 0xFF) - (rem and 0xFF)
            }
        }
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val add = tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x]
                val rem = tmp[(y - radius).coerceAtLeast(0) * w + x]
                rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                bSum += (add and 0xFF) - (rem and 0xFF)
            }
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }
}
