package com.kyle.kitchenhub.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class PixelSparkleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val PARTICLE_COUNT = 36
        private const val DURATION_MS = 520L
    }

    var sparklesEnabled: Boolean = true
    var accentColor: Int = Color.parseColor("#A8C7FA")

    private val cosAngles = FloatArray(PARTICLE_COUNT)
    private val sinAngles = FloatArray(PARTICLE_COUNT)
    private val maxDistances = FloatArray(PARTICLE_COUNT)
    private val radii = FloatArray(PARTICLE_COUNT)
    private val isAccent = BooleanArray(PARTICLE_COUNT)

    private var originX = 0f
    private var originY = 0f
    private var progress = 1f
    private var active = false

    private val density = resources.displayMetrics.density
    private val maxPossibleDist = 185f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION_MS
        interpolator = DecelerateInterpolator(1.75f)
        addUpdateListener { anim ->
            progress = anim.animatedValue as Float
            if (progress >= 1f) active = false
            invalidate()
        }
    }

    fun triggerBurst(x: Float, y: Float, customAccent: Int? = null) {
        // Pixel sparkle effect disabled
        return
    }

    fun triggerBurstOnView(target: View, customAccent: Int? = null) {
        // Pixel sparkle effect disabled
        return
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val animProgress = progress
        for (i in 0 until PARTICLE_COUNT) {
            val dist = maxDistances[i] * animProgress
            val distProgress = (dist / maxPossibleDist).coerceIn(0f, 1f)
            val alpha = ((1f - distProgress).pow(1.6f) * (1f - animProgress) * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            val shrink = (1f - distProgress * 0.55f) * (1f - animProgress * 0.35f)
            val px = originX + cosAngles[i] * dist
            val py = originY + sinAngles[i] * dist
            val base = if (isAccent[i]) accentColor else 0xFFFFFFFF.toInt()
            paint.color = (alpha shl 24) or (base and 0x00FFFFFF)
            canvas.drawCircle(px, py, (radii[i] * shrink).coerceAtLeast(0.5f), paint)
        }
    }
}
