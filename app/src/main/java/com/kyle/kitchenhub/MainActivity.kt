package com.kyle.kitchenhub

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.core.stability.CrashFailsafe
import com.kyle.kitchenhub.core.stability.SafeRecoveryActivity
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.core.ui.FrostedBackdropEngine
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.StickyNoteStore
import com.kyle.kitchenhub.databinding.ActivityMainBinding
import com.kyle.kitchenhub.service.audio.MicArbiter
import com.kyle.kitchenhub.service.wakeword.KitchenBackgroundService
import com.kyle.kitchenhub.ui.launcher.AppTrayOverlay
import com.kyle.kitchenhub.ui.launcher.NestHubPagerAdapter
import com.kyle.kitchenhub.ui.settings.SettingsActivity
import com.kyle.kitchenhub.ui.setup.SetupWizardActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class AmbientBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var linearShader: LinearGradient? = null
    private var radialShader: RadialGradient? = null
    private var cachedIsDay: Boolean? = null

    private fun ensureShaders(w: Float, h: Float) {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDay = hr in 6..17
        if (linearShader != null && radialShader != null && cachedIsDay == isDay) return
        cachedIsDay = isDay
        val c1 = if (isDay) Color.parseColor("#131B2E") else Color.parseColor("#0D121D")
        val c2 = if (isDay) Color.parseColor("#1A1528") else Color.parseColor("#090C14")
        linearShader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        radialShader = RadialGradient(
            w * 0.22f, h * 0.18f, w * 0.65f,
            Color.parseColor("#2E4B7BEC"), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        linearShader = null
        radialShader = null
        if (w > 0 && h > 0) ensureShaders(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        ensureShaders(w, h)
        paint.shader = linearShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = radialShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var appTray: AppTrayOverlay
    private lateinit var tabButtons: List<MaterialButton>
    private var isActivityResumed = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { enterStandByClock() }
    private val deepDimRunnable = Runnable {
        window.attributes = window.attributes.apply { screenBrightness = 0.08f }
    }
    private val hideTopBarRunnable = Runnable { setTopBarVisible(false) }
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            if (::b.isInitialized) b.topClockText.text = clockFmt.format(Date())
            idleHandler.postDelayed(this, 15_000L)
        }
    }

    private var draggingShade = false
    private var draggingTray = false
    private var dragStartY = 0f
    private var dragStartTransY = 0f
    private var lastSliderTickStep = -1

    private val bgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                KitchenBackgroundService.ACTION_SHOW_MOCK_GEMINI -> {
                    if (isActivityResumed) showAvdMockGemini()
                }
                KitchenBackgroundService.ACTION_NOTE_RECEIVED -> refreshReminderBanner()
                com.kyle.kitchenhub.service.media.MediaBridgeService.ACTION_DEVICE_NOTIFICATION -> {
                    val app = intent.getStringExtra("appLabel") ?: "Notification"
                    val title = intent.getStringExtra("title").orEmpty()
                    val text = intent.getStringExtra("text").orEmpty()
                    val category = title.ifBlank { app }
                    val body = text.ifBlank { title }
                    showFloatingBanner(category, body, isNote = false)
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                    if (::b.isInitialized) b.mainPager.adapter?.notifyItemChanged(1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashFailsafe.shouldEnterSafeMode(this)) {
            startActivity(Intent(this, SafeRecoveryActivity::class.java)); finish(); return
        }
        val cfg = ConfigRepository.get(this)
        if (!cfg.setupCompleted) {
            startActivity(Intent(this, SetupWizardActivity::class.java)); finish(); return
        }

        seedDefaultBackdrop()
        runCatching { com.kyle.kitchenhub.data.GoogleHomeBridge.registerActivity(this) }
        startForegroundService(Intent(this, KitchenBackgroundService::class.java))

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyImmersiveFullscreen()
        setupTopBarAndPager()
        setupAppTrayAndLightReveal()

        registerReceiver(bgReceiver, IntentFilter().apply {
            addAction(KitchenBackgroundService.ACTION_SHOW_MOCK_GEMINI)
            addAction(KitchenBackgroundService.ACTION_NOTE_RECEIVED)
            addAction(com.kyle.kitchenhub.service.media.MediaBridgeService.ACTION_DEVICE_NOTIFICATION)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        })
        idleHandler.post(clockTick)
        refreshReminderBanner()
        resetIdleTimer()
    }

    private fun seedDefaultBackdrop() {
        val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 160f, 100f, Color.parseColor("#243B55"), Color.parseColor("#141E30"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, 160f, 100f, p)
        FrostedBackdropEngine.updateBackdrop(bmp)
    }

    private fun clockTabShiftPx(): Float {
        if (b.tab0.left > 0) return b.tab0.left.toFloat()
        b.tabClock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return b.tabClock.measuredWidth + 8f * resources.displayMetrics.density
    }

    private fun setupTopBarAndPager() {
        val tf = FontCache.plusJakarta(this)
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        b.tab0.text = when (hr) {
            in 5..11 -> "Your morning"
            in 12..17 -> "Your afternoon"
            else -> "Your evening"
        }

        // Page 0 = Clock, Page 1 = Home, Page 2 = Media, Page 3 = Communicate, Page 4 = Home Control
        tabButtons = listOf(b.tabClock, b.tab0, b.tab1, b.tab2, b.tab3)
        tabButtons.forEachIndexed { pageIdx, btn ->
            btn.typeface = tf
            btn.setOnClickListener {
                TactileAudioEngine.playClick()
                b.sparkleOverlay.triggerBurstOnView(it)
                b.mainPager.setCurrentItem(pageIdx, true)
            }
        }
        b.topClockText.typeface = tf
        b.ivTopNotifIcon.setOnClickListener {
            TactileAudioEngine.playClick()
            val note = StickyNoteStore.get(this)
            if (note != null && !note.acknowledged) {
                showFloatingBanner("Sticky Note • ${note.author}", note.text, isNote = true)
            } else if (lastNotifBody.isNotBlank()) {
                showFloatingBanner(lastNotifCategory, lastNotifBody, isNote = false)
            }
        }

        b.sparkleOverlay.sparklesEnabled = ConfigRepository.get(this).sparklesEnabled
        b.mainPager.isUserInputEnabled = true
        b.mainPager.offscreenPageLimit = 2
        b.mainPager.offscreenPageLimit = 2
        b.mainPager.adapter = NestHubPagerAdapter(
            this,
            b.sparkleOverlay,
            onEnterClock = { enterStandByClock() },
            onWakeFromClock = { x, y -> wakeFromClockWithLightReveal(x, y) },
            onRefreshBanner = { refreshReminderBanner() }
        )

        b.mainPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val shift = clockTabShiftPx()
                if (position == 0) {
                    // Sliding between Page 0 (Clock) and Page 1 (Home) — blend backgrounds seamlessly
                    b.ambientBackdrop.alpha = positionOffset
                    b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)
                        ?.setTransitionFraction(positionOffset)
                    if (positionOffset > 0.02f) setTopBarVisible(true)
                    b.tabStrip.translationX = -shift * positionOffset
                    b.topClockText.alpha = positionOffset
                    b.ivTopNotifIcon.alpha = positionOffset
                    b.bottomPillWrap.alpha = positionOffset
                    b.bottomPillWrap.visibility = if (positionOffset < 0.04f) View.INVISIBLE else View.VISIBLE
                } else {
                    // Pages 1..4: keep Clock tab tucked cleanly off-left so Home aligns at 32dp
                    b.ambientBackdrop.alpha = 1f
                    setTopBarVisible(true)
                    b.tabStrip.translationX = -shift
                    b.topClockText.alpha = 1f
                    b.ivTopNotifIcon.alpha = 1f
                    b.bottomPillWrap.alpha = 1f
                    b.bottomPillWrap.visibility = View.VISIBLE
                }
            }

            override fun onPageSelected(position: Int) {
                updateTabStyles(position)
                if (position == 0) {
                    b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)
                        ?.refreshLockStickyNote()
                    scheduleTopBarAutoHide()
                } else {
                    idleHandler.removeCallbacks(hideTopBarRunnable)
                    setTopBarVisible(true)
                }
            }
        })

        b.mainPager.setCurrentItem(1, false)
        updateTabStyles(1)
        b.tabStrip.post {
            if (b.mainPager.currentItem >= 1) {
                b.tabStrip.translationX = -clockTabShiftPx()
            }
        }
    }

    private fun updateTabStyles(active: Int) {
        val activeBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.m3_secondary_container))
        val inactiveBg = ColorStateList.valueOf(Color.TRANSPARENT)
        val activeFg = ContextCompat.getColor(this, R.color.m3_on_secondary_container)
        val inactiveFg = ContextCompat.getColor(this, R.color.m3_on_surface_variant)

        tabButtons.forEachIndexed { idx, btn ->
            val sel = idx == active
            btn.backgroundTintList = if (sel) activeBg else inactiveBg
            btn.setTextColor(if (sel) activeFg else inactiveFg)
            btn.iconTint = ColorStateList.valueOf(if (sel) activeFg else inactiveFg)
            btn.paint.isFakeBoldText = sel
        }
    }

    private fun setupQuickSettingsShade() {
        b.shadeScrim.setOnClickListener { setTrayOpen(false); setBottomBarOpen(false) }
    }

    private fun detentTick(pct: Int) {
        val step = pct / 5
        if (step != lastSliderTickStep) {
            lastSliderTickStep = step
            TactileAudioEngine.playDetentTick()
        }
    }

    private var activeBottomSliderMode: String? = null

    private fun setupAppTrayAndLightReveal() {
        appTray = AppTrayOverlay(
            this,
            onClose = { setTrayOpen(false) },
            onAskGemini = {
                val duck = ConfigRepository.get(this).bargeInDuckPercent
                MicArbiter.handoffToGemini(this, duck) { showAvdMockGemini() }
            }
        )
        b.appTrayHost.addView(appTray, android.widget.FrameLayout.LayoutParams(-1, -1))
        b.shadeScrim.setOnClickListener {
            setTrayOpen(false)
            setBottomBarOpen(false)
        }
        b.bottomPillWrap.setOnClickListener {
            TactileAudioEngine.playClick()
            setBottomBarOpen(b.bottomBarInclude.bottomBarRoot.visibility != View.VISIBLE)
        }
        b.lightRevealScrim.onDoubleTapWakeFromSleep = { x, y ->
            wakeFromClockWithLightReveal(x, y)
        }
        setupBottomQuickBar()
    }

    private fun setupBottomQuickBar() {
        val bb = b.bottomBarInclude
        bb.bottomControlBarCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.shade_frosted_bg))
        bb.bottomSliderCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.shade_frosted_bg))
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val notifMax = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION).coerceAtLeast(1)

        bb.btnBarBrightness.setOnClickListener {
            TactileAudioEngine.playClick()
            if (activeBottomSliderMode == "BRI" && bb.bottomSliderCard.visibility == View.VISIBLE) {
                bb.bottomSliderCard.visibility = View.GONE
                activeBottomSliderMode = null
            } else {
                activeBottomSliderMode = "BRI"
                bb.btnExpandVolumes.visibility = View.GONE
                bb.extraVolumesContainer.visibility = View.GONE
                bb.ivBottomSliderIcon.setImageResource(R.drawable.ic_brightness_rounded)
                val curBri = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(165)
                val step = ((curBri * 10f) / 255f).roundToInt().coerceIn(1, 10)
                bb.bottomInlineSlider.value = step.toFloat()
                val autoMode = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                }.getOrDefault(false)
                bb.ivBottomSliderIcon.setColorFilter(
                    Color.parseColor(if (autoMode) "#A8C7FA" else "#FFFFFF")
                )
                bb.bottomSliderCard.visibility = View.VISIBLE
            }
        }

        bb.ivBottomSliderIcon.setOnClickListener {
            if (activeBottomSliderMode == "BRI" && Settings.System.canWrite(this)) {
                TactileAudioEngine.playClick()
                val curMode = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                }.getOrDefault(0)
                val nextAuto = curMode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (nextAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                bb.ivBottomSliderIcon.setColorFilter(
                    Color.parseColor(if (nextAuto) "#A8C7FA" else "#FFFFFF")
                )
                android.widget.Toast.makeText(
                    this,
                    if (nextAuto) "Auto Brightness: ON" else "Auto Brightness: OFF",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        bb.btnBarVolume.setOnClickListener {
            TactileAudioEngine.playClick()
            if (activeBottomSliderMode == "VOL" && bb.bottomSliderCard.visibility == View.VISIBLE) {
                bb.bottomSliderCard.visibility = View.GONE
                activeBottomSliderMode = null
            } else {
                activeBottomSliderMode = "VOL"
                bb.btnExpandVolumes.visibility = View.VISIBLE
                bb.btnExpandVolumes.rotation = if (bb.extraVolumesContainer.visibility == View.VISIBLE) 180f else 0f
                bb.ivBottomSliderIcon.setImageResource(R.drawable.ic_volume_up_rounded)
                val volStep = ((am.getStreamVolume(AudioManager.STREAM_MUSIC) * 10f) / musicMax).roundToInt().coerceIn(1, 10)
                bb.bottomInlineSlider.value = volStep.toFloat()
                bb.sliderExtraAlarm.value = ((am.getStreamVolume(AudioManager.STREAM_ALARM) * 10f) / alarmMax).roundToInt().coerceIn(1, 10).toFloat()
                bb.sliderExtraNotif.value = ((am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) * 10f) / notifMax).roundToInt().coerceIn(1, 10).toFloat()
                bb.bottomSliderCard.visibility = View.VISIBLE
            }
        }

        bb.btnExpandVolumes.setOnClickListener {
            TactileAudioEngine.playClick()
            val show = bb.extraVolumesContainer.visibility != View.VISIBLE
            android.transition.TransitionManager.beginDelayedTransition(
                bb.bottomSliderCard,
                android.transition.AutoTransition().apply { duration = 220L }
            )
            bb.extraVolumesContainer.visibility = if (show) View.VISIBLE else View.GONE
            bb.btnExpandVolumes.animate().rotation(if (show) 180f else 0f).setDuration(220L).start()
        }

        bb.sliderExtraAlarm.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val step = value.roundToInt().coerceIn(1, 10)
                detentTick(step * 10)
                am.setStreamVolume(AudioManager.STREAM_ALARM, (step * alarmMax) / 10, 0)
            }
        }
        bb.sliderExtraNotif.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val step = value.roundToInt().coerceIn(1, 10)
                detentTick(step * 10)
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (step * notifMax) / 10, 0)
            }
        }

        bb.bottomInlineSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val step = value.roundToInt().coerceIn(1, 10)
            detentTick(step * 10)
            if (activeBottomSliderMode == "VOL") {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (step * musicMax) / 10, 0)
            } else if (activeBottomSliderMode == "BRI" && Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (step * 255) / 10)
            }
        }

        val nmInit = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var dndOn = nmInit.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        fun syncDndVisual() {
            bb.btnBarDnd.backgroundTintList = ColorStateList.valueOf(
                if (dndOn) ContextCompat.getColor(this, R.color.m3_primary) else Color.TRANSPARENT
            )
            bb.btnBarDnd.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (dndOn) R.color.m3_on_primary else R.color.m3_on_surface)
            )
        }
        syncDndVisual()
        bb.btnBarDnd.setOnClickListener {
            TactileAudioEngine.playClick()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                android.widget.Toast.makeText(this, "Grant Do Not Disturb access in Settings", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dndOn = !dndOn
            syncDndVisual()
            nm.setInterruptionFilter(if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL)
        }

        bb.btnBarAlarms.setOnClickListener {
            TactileAudioEngine.playClick()
            setBottomBarOpen(false)
            com.kyle.kitchenhub.data.SystemAlarmsRepository.openOfficialAlarmsUi(this)
        }

        bb.btnBarApps.setOnClickListener {
            TactileAudioEngine.playClick()
            setBottomBarOpen(false)
            setTrayOpen(true)
        }

        bb.btnBarSettings.setOnClickListener {
            TactileAudioEngine.playClick()
            setBottomBarOpen(false)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setBottomBarOpen(open: Boolean) {
        val bar = b.bottomBarInclude.bottomBarRoot
        val h = (bar.height.takeIf { it > 0 } ?: (120 * resources.displayMetrics.density).toInt()).toFloat() + 32f
        if (open) {
            bar.visibility = View.VISIBLE
            b.shadeScrim.visibility = View.VISIBLE
            if (bar.translationY == 0f) bar.translationY = h
        } else {
            b.bottomBarInclude.bottomSliderCard.visibility = View.GONE
            activeBottomSliderMode = null
        }
        b.shadeScrim.animate().alpha(if (open) 1f else 0f).setDuration(200L).withEndAction {
            if (!open && appTray.visibility != View.VISIBLE) {
                b.shadeScrim.visibility = View.GONE
            }
        }.start()
        SpringAnimation(bar, DynamicAnimation.TRANSLATION_Y, if (open) 0f else h).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            addEndListener { _, _, _, _ -> if (!open) bar.visibility = View.GONE }
            start()
        }
    }

    private fun wakeFromClockWithLightReveal(x: Float, y: Float) {
        b.sparkleOverlay.triggerBurst(x, y)
        b.lightRevealScrim.revealFrom(x, y, b.foregroundStage) {
            b.mainPager.setCurrentItem(1, false)
            updateTabStyles(1)
            b.tabStrip.translationX = -clockTabShiftPx()
            b.topClockText.alpha = 1f
            b.bottomPillWrap.alpha = 1f
            b.bottomPillWrap.visibility = View.VISIBLE
            resetIdleTimer()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setShadeOpen(open: Boolean) {
        // Top Quick Settings shade removed in favor of Nest Hub bottom control bar
    }

    private fun setTrayOpen(open: Boolean) {
        val card = appTray.drawerCard
        val trayH = (card.height.takeIf { it > 0 } ?: (416 * resources.displayMetrics.density).toInt()).toFloat() + 32f
        if (open) {
            appTray.visibility = View.VISIBLE
            b.shadeScrim.visibility = View.VISIBLE
            if (card.translationY == 0f) card.translationY = trayH
        }
        b.shadeScrim.animate().alpha(if (open) 1f else 0f).setDuration(220L).withEndAction {
            if (!open && b.bottomBarInclude.bottomBarRoot.visibility != View.VISIBLE) b.shadeScrim.visibility = View.GONE
        }.start()
        SpringAnimation(card, DynamicAnimation.TRANSLATION_Y, if (open) 0f else trayH).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            addEndListener { _, _, _, _ -> if (!open) appTray.visibility = View.GONE }
            start()
        }
    }

    private val hideVolumeBarRunnable = Runnable { setBottomBarOpen(false) }

    private var muteHoldTriggered = false
    private val muteHoldRunnable = Runnable {
        muteHoldTriggered = true
        val nowMuted = com.kyle.kitchenhub.service.audio.MicArbiter.state !=
            com.kyle.kitchenhub.service.audio.MicState.HARDWARE_MUTED
        TactileAudioEngine.playClick()
        com.kyle.kitchenhub.ui.overlay.FloatingGeminiPill.setHardwareMuted(nowMuted)
        if (nowMuted) {
            com.kyle.kitchenhub.service.audio.MicArbiter.enterStateAsync(
                com.kyle.kitchenhub.service.audio.MicState.HARDWARE_MUTED
            )
            android.widget.Toast.makeText(this, "Mic Muted", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            com.kyle.kitchenhub.service.audio.MicArbiter.exitStateToWakeWordAsync()
            android.widget.Toast.makeText(this, "Mic Unmuted", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_MUTE ||
            event.keyCode == 307
        ) {
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        muteHoldTriggered = false
                        idleHandler.removeCallbacks(muteHoldRunnable)
                        idleHandler.postDelayed(muteHoldRunnable, 520L)
                    }
                }
                android.view.KeyEvent.ACTION_UP -> {
                    idleHandler.removeCallbacks(muteHoldRunnable)
                }
            }
            return true
        }
        if (::b.isInitialized && (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val curStep = ((am.getStreamVolume(AudioManager.STREAM_MUSIC) * 10f) / maxVol)
                    .roundToInt().coerceIn(1, 10)
                val nextStep = (curStep + if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                    .coerceIn(1, 10)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (nextStep * maxVol) / 10, 0)
                detentTick(nextStep * 10)

                val bb = b.bottomBarInclude
                activeBottomSliderMode = "VOL"
                bb.btnExpandVolumes.visibility = View.VISIBLE
                bb.ivBottomSliderIcon.setImageResource(R.drawable.ic_volume_up_rounded)
                bb.bottomInlineSlider.value = nextStep.toFloat()
                bb.bottomSliderCard.visibility = View.VISIBLE
                setBottomBarOpen(true)
                idleHandler.removeCallbacks(hideVolumeBarRunnable)
                idleHandler.postDelayed(hideVolumeBarRunnable, 2800L)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        if (::b.isInitialized && !b.lightRevealScrim.isSleeping) {
            resetIdleTimer()
            if (b.mainPager.currentItem == 0 && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                setTopBarVisible(true)
                scheduleTopBarAutoHide()
            }
            val trayCard = appTray.drawerCard
            val trayH = (trayCard.height.takeIf { it > 0 } ?: (416 * d).toInt()).toFloat() + 32f
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            val trayTopY = screenH - trayH

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = ev.y
                    draggingShade = false
                    draggingTray = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - dragStartY
                    if (!draggingTray && abs(dy) > 20f * d) {
                        if (dy < 0 && b.bottomBarInclude.bottomBarRoot.visibility != View.VISIBLE && b.mainPager.currentItem >= 1 && dragStartY > screenH - 120f * d) {
                            setBottomBarOpen(true)
                            return true
                        } else if (dy > 0 && appTray.visibility == View.VISIBLE && dragStartY >= trayTopY - 24f * d) {
                            draggingTray = true
                            dragStartTransY = trayCard.translationY
                        }
                    }
                    if (draggingTray) {
                        val ty = (dragStartTransY + dy).coerceIn(0f, trayH)
                        trayCard.translationY = ty
                        b.shadeScrim.alpha = 1f - (ty / trayH).coerceIn(0f, 1f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingTray) {
                        setTrayOpen(trayCard.translationY < trayH * 0.52f)
                        draggingTray = false
                        return true
                    }
                    // Tapping the clear dimmed scrim above the 392dp App Drawer closes it cleanly
                    if (ev.actionMasked == MotionEvent.ACTION_UP &&
                        appTray.visibility == View.VISIBLE &&
                        abs(ev.y - dragStartY) < 12f * d &&
                        ev.y < trayTopY
                    ) {
                        setTrayOpen(false)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleTopBarAutoHide() {
        idleHandler.removeCallbacks(hideTopBarRunnable)
        if (::b.isInitialized && b.mainPager.currentItem == 0) {
            idleHandler.postDelayed(hideTopBarRunnable, 3500L)
        }
    }

    private fun setTopBarVisible(visible: Boolean) {
        if (!::b.isInitialized) return
        val bar = b.topChromeColumn
        val targetAlpha = if (visible) 1f else 0f
        val targetTy = if (visible) 0f else -(bar.height.takeIf { it > 0 } ?: 84).toFloat()
        if (bar.alpha == targetAlpha && bar.translationY == targetTy) return
        bar.animate().cancel()
        bar.animate()
            .alpha(targetAlpha)
            .translationY(targetTy)
            .setDuration(if (visible) 240L else 360L)
            .start()
        val clockContainer = b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)
        clockContainer?.setAmbientDarkened(!visible)
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.removeCallbacks(deepDimRunnable)
        if (window.attributes.screenBrightness >= 0f) {
            window.attributes = window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        val sec = ConfigRepository.get(this).idleTimeoutSec
        if (sec > 0) {
            idleHandler.postDelayed(idleRunnable, sec * 1000L)
            idleHandler.postDelayed(deepDimRunnable, (sec + 30) * 1000L)
        }
    }

    private fun enterStandByClock() {
        com.kyle.kitchenhub.core.ui.DialogImmersive.dismissAll()
        if (::b.isInitialized && b.mainPager.currentItem != 0) {
            b.mainPager.setCurrentItem(0, true)
        }
    }

    private var hasDeviceNotification = false
    private var lastNotifCategory = ""
    private var lastNotifBody = ""

    private fun refreshReminderBanner() {
        b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)?.refreshLockStickyNote()
        val note = StickyNoteStore.get(this)
        if (note != null && !note.acknowledged) {
            showFloatingBanner("Sticky Note • ${note.author}", note.text, isNote = true)
        } else {
            b.ivTopNotifIcon.visibility = if (hasDeviceNotification) View.VISIBLE else View.GONE
        }
    }

    private fun showFloatingBanner(category: String, body: String, isNote: Boolean) {
        if (!isNote) {
            hasDeviceNotification = true
            lastNotifCategory = category
            lastNotifBody = body
            b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)?.hasPendingNotification = true
        }
        b.ivTopNotifIcon.visibility = View.VISIBLE

        val itemB = com.kyle.kitchenhub.databinding.ItemFloatingNotificationBinding.inflate(
            layoutInflater, b.notificationStackContainer, false
        )
        itemB.tvItemNotifCategory.text = category
        itemB.tvItemNotifBody.text = body
        itemB.ivItemNotifIcon.setImageResource(if (isNote) R.drawable.ic_app_stickynote else R.drawable.ic_notifications_rounded)

        val d = resources.displayMetrics.density
        val card = itemB.root
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.background = FrostedBackdropEngine.createFrostedDrawable(card, 24f)
        card.alpha = 0f
        card.translationY = -40f * d
        card.scaleX = 0.94f
        card.scaleY = 0.94f

        while (b.notificationStackContainer.childCount >= 3) {
            val evicted = b.notificationStackContainer.getChildAt(b.notificationStackContainer.childCount - 1)
            (evicted.tag as? Runnable)?.let { idleHandler.removeCallbacks(it) }
            evicted.animate().cancel()
            b.notificationStackContainer.removeView(evicted)
        }
        b.notificationStackContainer.addView(card, 0)

        card.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f))
            .start()

        var dismissed = false
        var autoDismissRunnable: Runnable? = null
        val dismissCard = { explicitUserTap: Boolean ->
            if (!dismissed) {
                dismissed = true
                autoDismissRunnable?.let { idleHandler.removeCallbacks(it) }
                card.animate().cancel()
                if (isNote && explicitUserTap) StickyNoteStore.acknowledge(this)
                card.animate()
                    .alpha(0f)
                    .translationY(-20f * d)
                    .setDuration(260L)
                    .withEndAction {
                        b.notificationStackContainer.removeView(card)
                        if (b.notificationStackContainer.childCount == 0 && !isNote) {
                            hasDeviceNotification = false
                            b.ivTopNotifIcon.visibility = View.GONE
                            b.mainPager.findViewById<com.kyle.kitchenhub.ui.clock.StandByClockContainer>(R.id.clockPageRoot)?.hasPendingNotification = false
                        }
                    }.start()
            }
        }
        autoDismissRunnable = Runnable {
            if (card.isAttachedToWindow) dismissCard(false)
        }.also {
            card.tag = it
            idleHandler.postDelayed(it, 5500L)
        }
        itemB.btnItemNotifDone.setOnClickListener { TactileAudioEngine.playClick(); dismissCard(true) }
    }

    private fun showAvdMockGemini() {
        runOnUiThread {
            if (!isFinishing && isActivityResumed) {
                AlertDialog.Builder(this)
                    .setTitle("Gemini Assistant")
                    .setMessage("Listening via MicArbiter (150ms HAL handoff ready).")
                    .setPositiveButton("Done", null).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        if (com.kyle.kitchenhub.service.audio.MicArbiter.state ==
            com.kyle.kitchenhub.service.audio.MicState.STICKY_NOTE_DICTATING
        ) {
            com.kyle.kitchenhub.service.audio.MicArbiter.exitStateToWakeWordAsync()
        }
        if (::b.isInitialized) {
            b.mainPager.adapter?.notifyItemChanged(1, "in_place")
            refreshReminderBanner()
        }
    }

    override fun onPause() {
        isActivityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    private fun applyImmersiveFullscreen() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.removeCallbacks(hideTopBarRunnable)
        idleHandler.removeCallbacks(clockTick)
        runCatching { unregisterReceiver(bgReceiver) }
        super.onDestroy()
    }
}
