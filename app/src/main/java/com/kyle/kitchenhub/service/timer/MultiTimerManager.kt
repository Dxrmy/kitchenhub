package com.kyle.kitchenhub.service.timer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.service.audio.MicArbiter
import com.kyle.kitchenhub.service.audio.MicState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class KitchenTimer(
    val id: Long,
    val label: String,
    var remainingSec: Int,
    var paused: Boolean = false,
    var ringing: Boolean = false,
    val totalSec: Int = remainingSec
)

object MultiTimerManager {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timers = mutableListOf<KitchenTimer>()
    private val listeners = mutableListOf<(List<KitchenTimer>) -> Unit>()

    private var ringtone: Ringtone? = null
    private var alarmOverlay: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ticking = false
    private var externalStopIntent: android.app.PendingIntent? = null
    private var externalAddIntent: android.app.PendingIntent? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            var anyActive = false
            for (t in timers) {
                if (!t.paused && !t.ringing && t.remainingSec > 0) {
                    t.remainingSec--
                    anyActive = true
                    if (t.remainingSec == 0) {
                        t.ringing = true
                        onTimerExpired(t)
                    }
                } else if (t.paused || t.ringing) {
                    anyActive = true
                }
            }
            notifyListeners()
            if (anyActive) handler.postDelayed(this, 1000L) else ticking = false
        }
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(fn: (List<KitchenTimer>) -> Unit) {
        listeners.add(fn)
        fn(timers.toList())
    }

    fun removeListener(fn: (List<KitchenTimer>) -> Unit) {
        listeners.remove(fn)
    }

    fun startTimer(label: String, minutes: Int) {
        timers.add(KitchenTimer(System.nanoTime(), label, (minutes * 60).coerceAtLeast(5)))
        ensureTicking()
        notifyListeners()
    }

    fun addOneMinute(id: Long) {
        addSeconds(id, 60)
    }

    fun addTwoMinutes(id: Long) {
        runCatching { externalAddIntent?.send() }
        addSeconds(id, 120)
    }

    private fun addSeconds(id: Long, sec: Int) {
        timers.find { it.id == id }?.let {
            it.remainingSec += sec
            it.ringing = false
        }
        stopAlarmEffects()
        ensureTicking()
        notifyListeners()
    }

    fun triggerExternalRingingTimer(
        label: String,
        stopIntent: android.app.PendingIntent? = null,
        addMinIntent: android.app.PendingIntent? = null
    ) {
        val ctx = appContext ?: return
        externalStopIntent = stopIntent
        externalAddIntent = addMinIntent
        val existing = timers.find { it.ringing }
        if (existing != null) return
        val t = KitchenTimer(System.nanoTime(), label, 0, paused = false, ringing = true, totalSec = 60)
        timers.add(0, t)
        handler.post {
            showAlarmOverlay(ctx, t)
            if (ConfigRepository.get(ctx).wakeWordlessAlarmStop) {
                startWakeWordlessStopListener(ctx, t)
            }
        }
        notifyListeners()
    }

    fun togglePause(id: Long) {
        timers.find { it.id == id }?.let { it.paused = !it.paused }
        notifyListeners()
    }

    fun dismissTimer(id: Long) {
        timers.removeAll { it.id == id }
        if (timers.none { it.ringing }) stopAlarmEffects()
        notifyListeners()
    }

    fun dismissAllRinging() {
        runCatching { externalStopIntent?.send() }
        externalStopIntent = null
        externalAddIntent = null
        timers.removeAll { it.ringing }
        stopAlarmEffects()
        notifyListeners()
    }

    private fun ensureTicking() {
        if (!ticking) {
            ticking = true
            handler.postDelayed(tickRunnable, 1000L)
        }
    }

    private fun notifyListeners() {
        val snap = timers.toList()
        listeners.forEach { it(snap) }
    }

    private fun onTimerExpired(timer: KitchenTimer) {
        val ctx = appContext ?: return
        playAlarmSound(ctx)
        showAlarmOverlay(ctx, timer)
        if (ConfigRepository.get(ctx).wakeWordlessAlarmStop) {
            startWakeWordlessStopListener(ctx, timer)
        }
    }

    private fun playAlarmSound(ctx: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
    }

    private fun showAlarmOverlay(ctx: Context, timer: KitchenTimer) {
        if (!Settings.canDrawOverlays(ctx) || alarmOverlay != null) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E68B0000"))
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(ctx).apply {
            text = "${timer.label} Timer Done!"
            textSize = 44f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(ctx).apply {
            text = "Say \"STOP\" or \"SNOOZE\" — or tap below"
            textSize = 20f
            setTextColor(Color.parseColor("#FFCDD2"))
            setPadding(0, 0, 0, 32)
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun makeBtn(lbl: String, bg: String, onClick: () -> Unit) = Button(ctx).apply {
            text = lbl; textSize = 26f; setTextColor(Color.WHITE); setPadding(56, 28, 56, 28)
            background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = 999f }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 24 }
            TactileAudioEngine.attachSpringPress(this) { onClick() }
        }
        row.addView(makeBtn("STOP", "#D50000") { dismissAllRinging() })
        row.addView(makeBtn("+2m", "#37474F") { addTwoMinutes(timer.id) })
        root.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(root, params); alarmOverlay = root }
    }

    private fun startWakeWordlessStopListener(ctx: Context, timer: KitchenTimer) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
        scope.launch {
            MicArbiter.enterState(MicState.ALARM_STOP_LISTENING)
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { speechRecognizer = it }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { scope.launch { MicArbiter.exitStateToWakeWord() } }
                override fun onPartialResults(partialResults: Bundle?) { checkResults(partialResults, timer) }
                override fun onResults(results: Bundle?) {
                    if (!checkResults(results, timer)) scope.launch { MicArbiter.exitStateToWakeWord() }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30000L)
            }
            sr.startListening(intent)
        }
    }

    private fun checkResults(bundle: Bundle?, timer: KitchenTimer): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val joined = matches.joinToString(" ").lowercase()
        return when {
            joined.contains("stop") || joined.contains("dismiss") -> { dismissAllRinging(); true }
            joined.contains("snooze") -> { addOneMinute(timer.id); true }
            else -> false
        }
    }

    private fun stopAlarmEffects() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { speechRecognizer?.destroy() }; speechRecognizer = null
        alarmOverlay?.let { view ->
            val wm = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            runCatching { wm?.removeView(view) }
        }
        alarmOverlay = null
        scope.launch {
            if (MicArbiter.state == MicState.ALARM_STOP_LISTENING) MicArbiter.exitStateToWakeWord()
        }
    }
}
