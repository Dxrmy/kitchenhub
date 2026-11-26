package com.kyle.kitchenhub.service.media

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.core.ui.FrostedBackdropEngine

data class NowPlayingState(
    val title: String = "Nothing Playing",
    val artist: String = "Tap YouTube Music or Pair Bluetooth",
    val isPlaying: Boolean = false,
    val art: Bitmap? = null
)

data class SystemTimerEntry(
    val id: String,
    val label: String,
    val endElapsedMs: Long,
    val isPaused: Boolean,
    val pausedText: String?,
    val contentIntent: PendingIntent?
)

class MediaBridgeService : NotificationListenerService() {

    companion object {
        const val ACTION_DEVICE_NOTIFICATION = "com.kyle.kitchenhub.ACTION_DEVICE_NOTIFICATION"

        @Volatile
        var current: NowPlayingState = NowPlayingState()
            private set

        @Volatile
        var systemTimers: List<SystemTimerEntry> = emptyList()
            private set

        @Volatile
        private var instance: MediaBridgeService? = null

        private var activeController: MediaController? = null
        private val listeners = mutableListOf<(NowPlayingState) -> Unit>()
        private val timerListeners = mutableListOf<(List<SystemTimerEntry>) -> Unit>()

        fun addTimerListener(fn: (List<SystemTimerEntry>) -> Unit) {
            timerListeners.add(fn)
            fn(systemTimers)
        }

        fun removeTimerListener(fn: (List<SystemTimerEntry>) -> Unit) {
            timerListeners.remove(fn)
        }

        fun addListener(fn: (NowPlayingState) -> Unit) {
            listeners.add(fn)
            fn(current)
        }

        fun removeListener(fn: (NowPlayingState) -> Unit) {
            listeners.remove(fn)
        }

        fun refresh(context: Context) {
            if (DeviceEnvironment.LocalTestHarness.isActive() && DeviceEnvironment.LocalTestHarness.mockNowPlayingEnabled) {
                val mockArt = createMockArt()
                FrostedBackdropEngine.updateBackdrop(mockArt)
                current = NowPlayingState(
                    title = DeviceEnvironment.LocalTestHarness.mockTrackTitle,
                    artist = DeviceEnvironment.LocalTestHarness.mockTrackArtist,
                    isPlaying = true,
                    art = mockArt
                )
                listeners.forEach { it(current) }
                return
            }

            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(context, MediaBridgeService::class.java)
            val sessions = runCatching { msm.getActiveSessions(comp) }.getOrDefault(emptyList())
            val ctrl = sessions.firstOrNull()
            activeController = ctrl
            if (ctrl == null) {
                current = NowPlayingState()
            } else {
                val meta = ctrl.metadata
                val state = ctrl.playbackState
                val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (art != null) FrostedBackdropEngine.updateBackdrop(art)
                current = NowPlayingState(
                    title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Playing Media",
                    artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                    isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                    art = art
                )
            }
            listeners.forEach { it(current) }
        }

        fun togglePlayPause() {
            if (DeviceEnvironment.LocalTestHarness.isActive() && DeviceEnvironment.LocalTestHarness.mockNowPlayingEnabled) {
                current = current.copy(isPlaying = !current.isPlaying)
                listeners.forEach { it(current) }
                return
            }
            val tc = activeController?.transportControls ?: return
            if (current.isPlaying) tc.pause() else tc.play()
        }

        fun getProgressFraction(): Float {
            val ctrl = activeController ?: return 0f
            val dur = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur <= 0L) return 0f
            val st = ctrl.playbackState ?: return 0f
            var pos = st.position
            if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0L) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
                pos += (elapsed * st.playbackSpeed).toLong()
            }
            return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }

        fun seekToFraction(frac: Float) {
            val ctrl = activeController ?: return
            val dur = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur <= 0L) return
            val targetMs = (dur * frac.coerceIn(0f, 1f)).toLong()
            ctrl.transportControls?.seekTo(targetMs)
        }

        fun getTimeLabels(): Pair<String, String> {
            val ctrl = activeController ?: return "0:00" to "-0:00"
            val dur = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur <= 0L) return "0:00" to "-0:00"
            val st = ctrl.playbackState ?: return "0:00" to "-0:00"
            var pos = st.position
            if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0L) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
                pos += (elapsed * st.playbackSpeed).toLong()
            }
            val clamped = pos.coerceIn(0L, dur)
            val rem = (dur - clamped).coerceAtLeast(0L)
            return formatMs(clamped) to "-${formatMs(rem)}"
        }

        private fun formatMs(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val m = totalSec / 60L
            val s = (totalSec % 60L).toString().padStart(2, '0')
            return "$m:$s"
        }

        fun skipNext() { activeController?.transportControls?.skipToNext() }
        fun skipPrev() { activeController?.transportControls?.skipToPrevious() }

        private fun createMockArt(): Bitmap {
            val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 160f, 160f,
                    intArrayOf(Color.parseColor("#FF5E3A"), Color.parseColor("#6C2BD9"), Color.parseColor("#111318")),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, 160f, 160f, p)
            return bmp
        }
    }

    override fun onListenerConnected() {
        instance = this
        refresh(this)
        refreshSystemTimers()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    private fun refreshSystemTimers() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val clockPkgs = setOf("com.google.android.deskclock", "com.android.deskclock")
        val clockNotifs = active.filter { it.packageName in clockPkgs }
        val hasNonSummaryTimer = clockNotifs.any { sbn ->
            val n = sbn.notification ?: return@any false
            val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            val chId = n.channelId.orEmpty().lowercase()
            !isSummary && chId.contains("timer")
        }
        val found = mutableListOf<SystemTimerEntry>()
        for (sbn in clockNotifs) {
            val n = sbn.notification ?: continue
            if (hasNonSummaryTimer && (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val extras = n.extras ?: continue
            val showChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val chId = n.channelId.orEmpty().lowercase()

            val isTimerNotif = (showChrono && countDown) ||
                chId.contains("timer") ||
                title.contains("timer", ignoreCase = true) ||
                text.contains("timer", ignoreCase = true)
            if (!isTimerNotif) continue

            val hasPauseAction = n.actions?.any {
                it.title?.toString()?.contains("pause", ignoreCase = true) == true
            } == true
            val hasResumeAction = n.actions?.any {
                val act = it.title?.toString().orEmpty()
                act.contains("start", ignoreCase = true) || act.contains("resume", ignoreCase = true)
            } == true

            val chronoBases = mutableListOf<Long>()
            val extractedTexts = mutableListOf<String>()
            val rv = n.bigContentView ?: n.contentView
            if (rv != null) {
                runCatching {
                    val container = FrameLayout(this)
                    val root = rv.apply(this, container)
                    val queue = ArrayDeque<View>()
                    queue.add(root)
                    while (queue.isNotEmpty()) {
                        val v = queue.removeFirst()
                        if (v is Chronometer) {
                            chronoBases.add(v.base)
                        } else if (v is TextView) {
                            val t = v.text?.toString()?.trim().orEmpty()
                            if (t.isNotEmpty()) extractedTexts.add(t)
                        }
                        if (v is ViewGroup) {
                            for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
                        }
                    }
                }
            }

            if (chronoBases.isEmpty() && showChrono && countDown && n.`when` > 0L) {
                chronoBases.add(SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()))
            }

            val allTexts = (extractedTexts + listOf(title, text)).filter { it.isNotBlank() }
            val timeCandidates = allTexts.filter { it.contains(":") }
            val labelCandidates = allTexts.filter {
                !it.contains(":") &&
                    !it.equals("Pause", ignoreCase = true) &&
                    !it.equals("Pause all", ignoreCase = true) &&
                    !it.equals("Start", ignoreCase = true) &&
                    !it.equals("Resume", ignoreCase = true) &&
                    !it.equals("Reset", ignoreCase = true) &&
                    !it.equals("Add 1 min", ignoreCase = true) &&
                    !it.equals("+1:00", ignoreCase = true)
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            if (chronoBases.size > 1) {
                chronoBases.forEachIndexed { idx, base ->
                    val running = !hasResumeAction && (hasPauseAction || base > nowElapsed)
                    found.add(
                        SystemTimerEntry(
                            id = "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}:$idx",
                            label = labelCandidates.getOrNull(idx) ?: "Clock Timer ${idx + 1}",
                            endElapsedMs = base,
                            isPaused = !running,
                            pausedText = timeCandidates.getOrNull(idx) ?: "Paused",
                            contentIntent = n.contentIntent
                        )
                    )
                }
            } else {
                val base = chronoBases.firstOrNull() ?: 0L
                val isRunning = !hasResumeAction && (hasPauseAction || base > nowElapsed)
                found.add(
                    SystemTimerEntry(
                        id = "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}",
                        label = labelCandidates.firstOrNull() ?: "Clock Timer",
                        endElapsedMs = base,
                        isPaused = !isRunning,
                        pausedText = timeCandidates.firstOrNull() ?: "Paused",
                        contentIntent = n.contentIntent
                    )
                )
            }
        }
        systemTimers = found
        timerListeners.forEach { it(found) }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        refresh(this)
        refreshSystemTimers()
        if (sbn == null || sbn.packageName == packageName || sbn.packageName == "android") return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val chId = n.channelId.orEmpty().lowercase()

        // Intercept Clock ringing timer/alarm notification, show KitchenHub overlay, and hide default notif
        if (sbn.packageName in setOf("com.google.android.deskclock", "com.android.deskclock")) {
            val stopAct = n.actions?.firstOrNull { it.title?.toString()?.equals("Stop", true) == true }
            val addAct = n.actions?.firstOrNull {
                val t = it.title?.toString().orEmpty()
                t.contains("+1", true) || t.contains("+2", true) || t.contains("Add", true) || t.contains("Snooze", true)
            }
            val isRinging = chId.contains("firing") ||
                stopAct != null ||
                text.contains("time's up", true) ||
                title.contains("0:00")
            if (isRinging) {
                com.kyle.kitchenhub.service.timer.MultiTimerManager.triggerExternalRingingTimer(
                    label = title.ifBlank { "Kitchen Timer" },
                    stopIntent = stopAct?.actionIntent,
                    addMinIntent = addAct?.actionIntent
                )
                runCatching { cancelNotification(sbn.key) }
                return
            }
        }

        if (sbn.isOngoing) return
        if (extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) return
        if (title.isEmpty() && text.isEmpty()) return
        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName)
        sendBroadcast(android.content.Intent(ACTION_DEVICE_NOTIFICATION).setPackage(packageName).apply {
            putExtra("appLabel", appLabel)
            putExtra("title", title)
            putExtra("text", text)
        })
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        refresh(this)
        refreshSystemTimers()
    }
}
