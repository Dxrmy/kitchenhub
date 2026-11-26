package com.kyle.kitchenhub.service.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MicState {
    IDLE,
    WAKEWORD_LISTENING,
    HANDING_OFF_TO_GEMINI,
    STICKY_NOTE_DICTATING,
    ALARM_STOP_LISTENING,
    HARDWARE_MUTED
}

object MicArbiter {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var state: MicState = MicState.IDLE
        private set

    private var activeRecord: AudioRecord? = null
    private var resumeWakeWordCallback: (() -> Unit)? = null
    private var pauseWakeWordCallback: (() -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null

    fun registerWakeWordHooks(onPause: () -> Unit, onResume: () -> Unit) {
        pauseWakeWordCallback = onPause
        resumeWakeWordCallback = onResume
    }

    suspend fun acquireForWakeWord(record: AudioRecord): Boolean = mutex.withLock {
        if (state != MicState.IDLE && state != MicState.WAKEWORD_LISTENING) return false
        activeRecord = record
        state = MicState.WAKEWORD_LISTENING
        true
    }

    suspend fun releaseWakeWord() = mutex.withLock {
        releaseActiveRecordLocked()
        if (state == MicState.WAKEWORD_LISTENING) state = MicState.IDLE
    }

    fun handoffToGemini(
        context: Context,
        duckPercent: Int = 35,
        customLaunch: (() -> Boolean)? = null,
        onAvdMockGemini: (() -> Unit)? = null
    ) {
        TactileAudioEngine.playNoteChime()
        scope.launch {
            mutex.withLock {
                if (state == MicState.HANDING_OFF_TO_GEMINI) return@launch
                state = MicState.HANDING_OFF_TO_GEMINI
                pauseWakeWordCallback?.invoke()
                requestDucking(context, duckPercent)
                releaseActiveRecordLocked()
            }
            delay(150L) // Mandatory MT8163 amazon_wrapper HAL release wait
            val launched = customLaunch?.invoke()
                ?: DeviceEnvironment.launchGemini(context, onAvdMockGemini)
            if (!launched && customLaunch != null) {
                onAvdMockGemini?.invoke()
            }

            // Monitor Assistant speech playback & recording, then auto-return to KitchenHub
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cbHandler = Handler(Looper.getMainLooper())
            var assistantSpeaking = false
            var lastActivityMs = System.currentTimeMillis()

            val playbackCb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    val speakingNow = configs.any { cfg ->
                        val usage = cfg.audioAttributes.usage
                        val ctype = cfg.audioAttributes.contentType
                        usage == AudioAttributes.USAGE_ASSISTANT ||
                            (usage == AudioAttributes.USAGE_MEDIA && ctype == AudioAttributes.CONTENT_TYPE_SPEECH) ||
                            ctype == AudioAttributes.CONTENT_TYPE_SPEECH
                    }
                    if (speakingNow) lastActivityMs = System.currentTimeMillis()
                    assistantSpeaking = speakingNow
                }
            }
            val recordingCb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.isNotEmpty()) lastActivityMs = System.currentTimeMillis()
                }
            }
            am.registerAudioPlaybackCallback(playbackCb, cbHandler)
            am.registerAudioRecordingCallback(recordingCb, cbHandler)

            try {
                val startMs = System.currentTimeMillis()
                var hadActivity = false
                // Wait up to 9s silent watchdog, or until speech+recording finish + 3.5s
                while (true) {
                    delay(250L)
                    val now = System.currentTimeMillis()
                    val recording = am.activeRecordingConfigurations.isNotEmpty()
                    if (recording || assistantSpeaking) {
                        hadActivity = true
                        lastActivityMs = now
                    }
                    val elapsedSinceStart = now - startMs
                    val elapsedSinceActivity = now - lastActivityMs
                    if (hadActivity && !recording && !assistantSpeaking && elapsedSinceActivity >= 3500L) break
                    if (!hadActivity && elapsedSinceStart >= 9000L) break
                    if (elapsedSinceStart >= 30_000L) break
                }
            } finally {
                runCatching { am.unregisterAudioPlaybackCallback(playbackCb) }
                runCatching { am.unregisterAudioRecordingCallback(recordingCb) }
            }

            releaseDucking(context)
            mutex.withLock {
                if (state == MicState.HANDING_OFF_TO_GEMINI) state = MicState.IDLE
            }
            resumeWakeWordCallback?.invoke()
            if (launched && !DeviceEnvironment.isEmulator) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    suspend fun enterState(target: MicState) = mutex.withLock {
        pauseWakeWordCallback?.invoke()
        releaseActiveRecordLocked()
        delay(150L)
        state = target
    }

    fun exitStateToWakeWordAsync() {
        scope.launch { exitStateToWakeWord() }
    }

    suspend fun exitStateToWakeWord() = mutex.withLock {
        releaseActiveRecordLocked()
        state = MicState.IDLE
        resumeWakeWordCallback?.invoke()
    }

    private fun releaseActiveRecordLocked() {
        runCatching {
            activeRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                rec.release()
            }
        }
        activeRecord = null
    }

    private fun requestDucking(context: Context, duckPercent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }
    }

    private fun releaseDucking(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
