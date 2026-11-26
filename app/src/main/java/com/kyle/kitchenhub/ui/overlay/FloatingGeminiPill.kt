package com.kyle.kitchenhub.ui.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.kyle.kitchenhub.R
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.databinding.ViewFloatingGeminiButtonBinding
import com.kyle.kitchenhub.service.audio.MicArbiter

class FloatingGeminiPill(
    private val context: Context,
    private val onAvdMockGemini: (() -> Unit)? = null
) {
    private var binding: ViewFloatingGeminiButtonBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapSpring: SpringAnimation? = null
    private var idleAnimator: AnimatorSet? = null
    private var idleFrameRunnable: Runnable? = null
    private var borderAnimator: ValueAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    companion object {
        @Volatile
        private var activeInstance: FloatingGeminiPill? = null

        @Volatile
        private var currentDarkness: Float = 0f

        fun setAmbientDarkness(darkness: Float) {
            val d = darkness.coerceIn(0f, 1f)
            currentDarkness = d
            activeInstance?.applyAmbientDarkness(d)
        }
    }

    private val geminiColors = intArrayOf(
        Color.parseColor("#4285F4"),
        Color.parseColor("#9B72CB"),
        Color.parseColor("#D96570"),
        Color.parseColor("#4285F4")
    )

    fun show() {
        if (!Settings.canDrawOverlays(context) || binding != null) return
        val themedCtx = ContextThemeWrapper(context, R.style.Theme_KitchenHub)
        val d = themedCtx.resources.displayMetrics.density
        val screenH = themedCtx.resources.displayMetrics.heightPixels

        val b = ViewFloatingGeminiButtonBinding.inflate(LayoutInflater.from(themedCtx))
        b.ivGeminiIcon.setImageResource(R.drawable.ic_app_gemini)
        b.geminiGlowHalo.lobes = 7
        b.geminiGlowHalo.isHaloMode = true
        b.geminiCookieBody.lobes = 9
        b.geminiCookieBody.isHaloMode = false

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (22 * d).toInt()
            y = screenH - (96 * d).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        b.root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapSpring?.cancel()
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    b.geminiFloatingCard.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy > 64) moved = true
                    lp.x = startX + dx
                    lp.y = (startY + dy).coerceIn((12 * d).toInt(), screenH - (98 * d).toInt())
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    b.geminiFloatingCard.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        TactileAudioEngine.playClick()
                        triggerAssistantHandoff()
                    } else if (moved) {
                        snapToEdge(v, lp)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(b.root, lp)
            binding = b
            params = lp
            activeInstance = this
            applyAmbientDarkness(currentDarkness)
            startGeminiAnimations(b)
        }
    }

    private fun startGeminiAnimations(b: ViewFloatingGeminiButtonBinding) {
        idleFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        var phase = 0f
        val step = object : Runnable {
            override fun run() {
                if (binding == null) return
                phase = (phase + 0.066f) % 22.0f
                b.geminiCookieBody.rotation = (phase / 22.0f) * 360f
                val s = 1.0f + 0.04f * kotlin.math.sin((phase / 2.8f) * 2.0 * Math.PI).toFloat()
                b.geminiGlowHalo.scaleX = s
                b.geminiGlowHalo.scaleY = s
                mainHandler.postDelayed(this, 66L) // ~15 fps
            }
        }
        idleFrameRunnable = step
        mainHandler.postDelayed(step, 66L)
    }

    private val resetHaloRunnable = Runnable {
        val b = binding ?: return@Runnable
        b.geminiGlowHalo.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(300L).start()
    }

    fun triggerAssistantHandoff() {
        showListeningFeedback()
        val duckPercent = ConfigRepository.get(context).bargeInDuckPercent
        MicArbiter.handoffToGemini(
            context = context,
            duckPercent = duckPercent,
            onAvdMockGemini = onAvdMockGemini
        )
    }

    private fun showListeningFeedback() {
        mainHandler.post {
            val b = binding ?: return@post
            mainHandler.removeCallbacks(resetHaloRunnable)
            b.ivGeminiIcon.animate().rotationBy(90f).setDuration(260L).start()
            b.geminiGlowHalo.animate().alpha(0.48f).scaleX(1.10f).scaleY(1.10f).setDuration(220L).start()
            mainHandler.postDelayed(resetHaloRunnable, 1600L)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun pulseAudio(active: Boolean) {
        // Audio reactivity intentionally disabled per user preference
    }

    fun applyAmbientDarkness(darkness: Float) {
        mainHandler.post {
            val b = binding ?: return@post
            b.geminiCookieBody.darkness = darkness
            b.geminiGlowHalo.alpha = 0.18f * (1f - darkness)
            b.ivGeminiIcon.alpha = 1f
        }
    }

    fun hide() {
        if (activeInstance === this) activeInstance = null
        idleFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        idleFrameRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        idleAnimator?.cancel()
        borderAnimator?.cancel()
        snapSpring?.cancel()
        binding?.root?.let { runCatching { wm.removeView(it) } }
        binding = null
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val screenW = context.resources.displayMetrics.widthPixels
        val margin = (18 * context.resources.displayMetrics.density).toInt()
        val targetX = if (lp.x + v.width / 2 < screenW / 2) margin else (screenW - v.width - margin)

        val holder = FloatValueHolder(lp.x.toFloat())
        snapSpring = SpringAnimation(holder).apply {
            spring = SpringForce(targetX.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
            addUpdateListener { _, value, _ ->
                lp.x = value.toInt()
                runCatching { wm.updateViewLayout(v, lp) }
            }
            start()
        }
    }
}
