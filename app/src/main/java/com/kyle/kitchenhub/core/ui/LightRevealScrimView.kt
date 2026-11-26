package com.kyle.kitchenhub.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Faithful AOSP Pixel LightRevealScrim implementation with organic elliptical
 * multi-stop feathered light-wave expansion (wake) and inverse collapse (sleep).
 */
class LightRevealScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDoubleTapWakeFromSleep: ((x: Float, y: Float) -> Unit)? = null

    private var originX = 0f
    private var originY = 0f
    private var revealAmount = 1f
    private var sleeping = false
    private var targetScaleView: View? = null

    private val wakeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    private val sleepInterpolator = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    private var activeAnimator: ValueAnimator? = null

    private val shaderMatrix = Matrix()
    private val radialScrimShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            0x00000000,
            0x00000000,
            0x55000000,
            0xCC000000.toInt(),
            0xFF000000.toInt()
        ),
        floatArrayOf(0f, 0.35f, 0.62f, 0.84f, 1.0f),
        Shader.TileMode.CLAMP
    )
    private val radialGlowShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            0x00A8C7FA,
            0x28A8C7FA,
            0x00A8C7FA
        ),
        floatArrayOf(0.28f, 0.56f, 0.86f),
        Shader.TileMode.CLAMP
    )

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = radialScrimShader
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = radialGlowShader
    }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val sleepGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = sleeping

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!sleeping) return false
            TactileAudioEngine.playClick()
            sleeping = false
            onDoubleTapWakeFromSleep?.invoke(e.x, e.y)
            return true
        }
    })

    val isSleeping: Boolean
        get() = sleeping

    fun revealFrom(x: Float, y: Float, scaleTarget: View? = null, onBeforeStart: (() -> Unit)? = null) {
        activeAnimator?.cancel()
        sleeping = false
        originX = x
        originY = y
        targetScaleView = scaleTarget
        revealAmount = 0f
        visibility = VISIBLE
        scaleTarget?.apply {
            scaleX = 0.975f
            scaleY = 0.975f
        }
        onBeforeStart?.invoke()
        invalidate()

        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            interpolator = wakeInterpolator
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                revealAmount = v
                targetScaleView?.let { tv ->
                    val s = 0.975f + 0.025f * v
                    tv.scaleX = s
                    tv.scaleY = s
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    targetScaleView?.apply {
                        scaleX = 1f
                        scaleY = 1f
                    }
                    if (!sleeping && revealAmount >= 0.999f) {
                        visibility = GONE
                    }
                }
            })
            start()
        }
    }

    fun collapseToSleep(x: Float, y: Float, scaleTarget: View? = null, onCollapsed: (() -> Unit)? = null) {
        activeAnimator?.cancel()
        sleeping = true
        originX = x
        originY = y
        targetScaleView = scaleTarget
        revealAmount = 1f
        visibility = VISIBLE
        invalidate()

        activeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 380L
            interpolator = sleepInterpolator
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                revealAmount = v
                targetScaleView?.let { tv ->
                    val s = 0.975f + 0.025f * v
                    tv.scaleX = s
                    tv.scaleY = s
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    targetScaleView?.apply {
                        scaleX = 1f
                        scaleY = 1f
                    }
                    onCollapsed?.invoke()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!sleeping) return false
        sleepGestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (sleeping && revealAmount <= 0.001f) {
            veilPaint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, veilPaint)
            return
        }

        val dTopLeft = hypot(originX, originY)
        val dTopRight = hypot(w - originX, originY)
        val dBotLeft = hypot(originX, h - originY)
        val dBotRight = hypot(w - originX, h - originY)
        val maxRadius = max(max(dTopLeft, dTopRight), max(dBotLeft, dBotRight)).coerceAtLeast(1f)
        val curRadius = maxRadius * (0.15f + 1.10f * revealAmount)

        shaderMatrix.reset()
        shaderMatrix.postScale(curRadius * 1.25f, curRadius * 1.0f)
        shaderMatrix.postTranslate(originX, originY)
        radialScrimShader.setLocalMatrix(shaderMatrix)
        radialGlowShader.setLocalMatrix(shaderMatrix)

        // Stage 2: Outer edge release (60% -> 100%)
        val outerAlpha = if (revealAmount <= 0.60f) {
            1f
        } else {
            ((1f - revealAmount) / 0.40f).coerceIn(0f, 1f)
        }
        if (outerAlpha > 0f) {
            scrimPaint.alpha = (outerAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        }

        // Whisper-soft radial glow along the expanding front
        val glowWave = sin(revealAmount * PI).toFloat().coerceIn(0f, 1f)
        if (glowWave > 0.01f) {
            glowPaint.alpha = (glowWave * 210f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, glowPaint)
        }

        // Stage 1: Initial bloom fade (0% -> 30%)
        val innerVeilAlpha = if (revealAmount >= 0.30f) {
            0f
        } else {
            ((0.30f - revealAmount) / 0.30f).coerceIn(0f, 1f)
        }
        if (innerVeilAlpha > 0f) {
            veilPaint.alpha = (innerVeilAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, veilPaint)
        }
    }
}
