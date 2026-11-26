package com.kyle.kitchenhub.service.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.StickyNoteStore
import com.kyle.kitchenhub.service.audio.MicArbiter
import com.kyle.kitchenhub.service.timer.MultiTimerManager
import com.kyle.kitchenhub.ui.overlay.FloatingGeminiPill

class KitchenBackgroundService : Service() {

    companion object {
        const val ACTION_SHOW_MOCK_GEMINI = "com.kyle.kitchenhub.ACTION_MOCK_GEMINI"
        const val ACTION_NOTE_RECEIVED = "com.kyle.kitchenhub.ACTION_NOTE_RECEIVED"
        private const val CH_ID = "kitchenhub_bg"
    }

    private var wakeWordEngine: OpenWakeWordEngine? = null
    private var geminiPill: FloatingGeminiPill? = null
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (mi.lowMemory || mi.availMem < 220L * 1024L * 1024L) {
                com.kyle.kitchenhub.core.ui.FrostedBackdropEngine.trim()
                System.gc()
            }
            if (android.provider.Settings.canDrawOverlays(this@KitchenBackgroundService)) {
                geminiPill?.show()
            }
            watchdogHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MultiTimerManager.init(this)
        startForegroundNotification()
        geminiPill = FloatingGeminiPill(this) {
            sendBroadcast(Intent(ACTION_SHOW_MOCK_GEMINI).setPackage(packageName))
        }.apply { show() }

        val cfg = ConfigRepository.get(this)
        if (cfg.stickyHttpEnabled) {
            StickyNoteStore.startHttpReceiver(this, cfg.stickyHttpPort, cfg.stickyHttpToken) {
                sendBroadcast(Intent(ACTION_NOTE_RECEIVED).setPackage(packageName))
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val engine = OpenWakeWordEngine(this).also { wakeWordEngine = it }
            engine.threshold = cfg.wakeWordThreshold
            MicArbiter.registerWakeWordHooks(
                onPause = { engine.stop(); geminiPill?.pulseAudio(false) },
                onResume = { startWakeWordLoop() }
            )
            startWakeWordLoop()
        }
        watchdogHandler.postDelayed(watchdogRunnable, 60_000L)
    }

    private fun startWakeWordLoop() {
        val cfg = ConfigRepository.get(this)
        wakeWordEngine?.start(
            onScore = { _, rms -> geminiPill?.pulseAudio(rms > 400f) },
            onDetected = {
                MicArbiter.handoffToGemini(this, cfg.bargeInDuckPercent) {
                    sendBroadcast(Intent(ACTION_SHOW_MOCK_GEMINI).setPackage(packageName))
                }
            }
        )
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "KitchenHub Assistant", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, CH_ID)
            .setContentTitle("KitchenHub Active")
            .setContentText("Wake-word & floating pill running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        wakeWordEngine?.stop()
        geminiPill?.hide()
        StickyNoteStore.stopHttpReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
