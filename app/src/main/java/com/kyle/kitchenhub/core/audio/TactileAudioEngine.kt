package com.kyle.kitchenhub.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object TactileAudioEngine {
    private var pool: SoundPool? = null
    private var clickId = 0
    private var tickId = 0
    private var chimeId = 0
    private var initialized = false

    var enabled: Boolean = true
    var volume: Float = 0.65f

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()

        val cache = context.cacheDir
        clickId = loadTone(cache, "tactile_click.wav", 950.0, 22, 0.45)
        tickId = loadTone(cache, "tactile_tick.wav", 1450.0, 14, 0.30)
        chimeId = loadTone(cache, "tactile_chime.wav", 880.0, 110, 0.50)
    }

    fun playClick() {
        if (enabled && clickId != 0) pool?.play(clickId, volume, volume, 1, 0, 1f)
    }

    fun playDetentTick() {
        if (enabled && tickId != 0) pool?.play(tickId, volume * 0.7f, volume * 0.7f, 1, 0, 1f)
    }

    fun playNoteChime() {
        if (enabled && chimeId != 0) pool?.play(chimeId, volume, volume, 1, 0, 1f)
    }

    fun attachSpringPress(view: View, onClick: (View) -> Unit) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(v, 0.94f)
                MotionEvent.ACTION_UP -> {
                    animateScale(v, 1f)
                    playClick()
                    onClick(v)
                }
                MotionEvent.ACTION_CANCEL -> animateScale(v, 1f)
            }
            true
        }
    }

    private fun animateScale(view: View, target: Float) {
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { prop ->
            SpringAnimation(view, prop, target).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                start()
            }
        }
    }

    private fun loadTone(dir: File, name: String, freqHz: Double, ms: Int, amp: Double): Int {
        val f = File(dir, name)
        if (!f.exists()) writeWav(f, freqHz, ms, amp)
        return pool?.load(f.absolutePath, 1) ?: 0
    }

    private fun writeWav(file: File, freqHz: Double, durationMs: Int, amp: Double) {
        val sampleRate = 22050
        val count = (sampleRate * durationMs) / 1000
        val dataSize = count * 2
        val header = ByteArray(44)
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        putInt(4, 36 + dataSize)
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        putInt(16, 16)
        header[20] = 1; header[22] = 1
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        header[32] = 2; header[34] = 16
        "data".toByteArray().copyInto(header, 36)
        putInt(40, dataSize)

        val pcm = ByteArray(dataSize)
        for (i in 0 until count) {
            val env = exp(-5.5 * i / count)
            val s = (sin(2.0 * PI * freqHz * i / sampleRate) * env * amp * 32767.0).toInt()
            pcm[i * 2] = (s and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        FileOutputStream(file).use { it.write(header); it.write(pcm) }
    }
}
