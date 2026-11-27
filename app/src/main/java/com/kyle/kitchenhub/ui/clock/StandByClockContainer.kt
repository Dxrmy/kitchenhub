package com.kyle.kitchenhub.ui.clock

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.StickyNoteStore
import com.kyle.kitchenhub.data.WeatherAndCalRepository
import com.kyle.kitchenhub.service.media.MediaBridgeService
import com.kyle.kitchenhub.service.timer.MultiTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import kotlin.math.abs

class StandByClockContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onDoubleTapExit: ((x: Float, y: Float) -> Unit)? = null
    var inPagerMode: Boolean = false

    private var modeIndex = 0 // 0=Full Clock, 1=Dual Split, 2=Photo Frame
    private var widgetStackIndex = 0

    private val shiftWrapper = FrameLayout(context)
    private val fullClock = StandByClockView(context)
    private val splitLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val splitClock = StandByClockView(context).apply { compactMode = true }
    private val splitWidgetCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(36, 36, 36, 36)
    }
    private val photoImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val photoClock = TextView(context).apply {
        textSize = 48f
        setTextColor(Color.WHITE)
        setPadding(32, 24, 32, 24)
    }
    private val faceBadge = TextView(context).apply {
        val d = resources.displayMetrics.density
        typeface = FontCache.plusJakarta(context)
        textSize = 13f
        setTextColor(Color.parseColor("#D3E3FD"))
        setPadding((16 * d).toInt(), (7 * d).toInt(), (16 * d).toInt(), (7 * d).toInt())
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#CC1A1F2B"))
            cornerRadius = 999f
        }
        alpha = 0f
    }
    private lateinit var timerStatusIcon: ImageView
    private lateinit var dndStatusIcon: ImageView
    private lateinit var notifStatusIcon: ImageView
    var hasPendingNotification: Boolean = false

    private val lockStickyContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
    }
    private val idleStatusRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = 0f
        val d = resources.displayMetrics.density
        val iconSize = (30 * d).toInt()
        timerStatusIcon = ImageView(context).apply {
            setImageResource(com.kyle.kitchenhub.R.drawable.ic_alarm_rounded)
            setColorFilter(Color.parseColor("#E2E6EE"))
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (16 * d).toInt() }
            visibility = View.GONE
        }
        dndStatusIcon = ImageView(context).apply {
            setImageResource(com.kyle.kitchenhub.R.drawable.ic_notifications_off_rounded)
            setColorFilter(Color.parseColor("#E2E6EE"))
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (16 * d).toInt() }
            visibility = View.GONE
        }
        notifStatusIcon = ImageView(context).apply {
            setImageResource(com.kyle.kitchenhub.R.drawable.ic_notifications_rounded)
            setColorFilter(Color.parseColor("#E2E6EE"))
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            visibility = View.GONE
        }
        addView(timerStatusIcon)
        addView(dndStatusIcon)
        addView(notifStatusIcon)
    }

    private val shiftHandler = Handler(Looper.getMainLooper())
    private var shiftStep = 0
    private val shiftRunnable = object : Runnable {
        override fun run() {
            val cfg = ConfigRepository.get(context)
            val amp = cfg.pixelShiftPx.toFloat()
            val offsets = listOf(amp to 0f, 0f to amp, -amp to 0f, 0f to -amp)
            val (dx, dy) = offsets[shiftStep++ % offsets.size]
            shiftWrapper.animate().translationX(dx).translationY(dy).setDuration(600L).start()
            shiftHandler.postDelayed(this, cfg.pixelShiftIntervalSec * 1000L)
        }
    }

    private var touchStartX = 0f
    private var touchStartY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            TactileAudioEngine.playClick()
            onDoubleTapExit?.invoke(e.rawX, e.rawY)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            TactileAudioEngine.playDetentTick()
            fullClock.paletteIndex++
            splitClock.paletteIndex = fullClock.paletteIndex
            ConfigRepository.update(context) { it.copy(clockPaletteIndex = fullClock.paletteIndex) }
            showFaceBadge("Palette ${(fullClock.paletteIndex % 4) + 1}")
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (!inPagerMode && abs(dx) > abs(dy) && abs(dx) > 80f) {
                modeIndex = (modeIndex + if (dx < 0) 1 else 2) % 3
                renderMode()
                return true
            } else if (abs(dy) > abs(dx) && abs(dy) > 65f) {
                if (modeIndex == 1 && e2.x > width * 0.5f) {
                    widgetStackIndex = (widgetStackIndex + if (dy < 0) 1 else 4) % 5
                    renderSplitWidget()
                } else {
                    cycleClockFace(if (dy < 0) 1 else -1)
                }
                return true
            }
            return false
        }
    })

    init {
        setBackgroundColor(Color.TRANSPARENT)
        shiftWrapper.addView(fullClock, LayoutParams(-1, -1))
        splitLayout.addView(splitClock, LinearLayout.LayoutParams(0, -1, 1f))
        splitLayout.addView(splitWidgetCard, LinearLayout.LayoutParams(0, -1, 1f))
        shiftWrapper.addView(splitLayout, LayoutParams(-1, -1))

        val photoWrap = FrameLayout(context).apply {
            addView(photoImage, LayoutParams(-1, -1))
            addView(photoClock, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }
        shiftWrapper.addView(photoWrap, LayoutParams(-1, -1))

        val d = resources.displayMetrics.density
        val stickyLp = LayoutParams((280 * d).toInt(), -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = (28 * d).toInt()
            bottomMargin = (24 * d).toInt()
        }
        shiftWrapper.addView(lockStickyContainer, stickyLp)

        addView(shiftWrapper, LayoutParams(-1, -1))

        val badgeLp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (44 * resources.displayMetrics.density).toInt()
        }
        addView(faceBadge, badgeLp)

        val statusLp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            val d = resources.displayMetrics.density
            marginStart = (28 * d).toInt()
            bottomMargin = (24 * d).toInt()
        }
        addView(idleStatusRow, statusLp)
        refreshFromConfig()
    }

    private fun cycleClockFace(dir: Int) {
        TactileAudioEngine.playDetentTick()
        val count = XMorphFaceRenderer.listNames(context).size.coerceAtLeast(1)
        val nextIdx = ((fullClock.xmorphIndex + dir) % count + count) % count
        fullClock.xmorphIndex = nextIdx
        splitClock.xmorphIndex = nextIdx
        val name = XMorphFaceRenderer.getFaceName(context, nextIdx)
        showFaceBadge("XMorph • $name")
        ConfigRepository.update(context) { it.copy(clockFace = "XMORPH") }
    }

    private fun showFaceBadge(label: String) {
        faceBadge.text = label
        faceBadge.animate().cancel()
        faceBadge.alpha = 1f
        faceBadge.animate().alpha(0f).setStartDelay(1600L).setDuration(350L).start()
    }

    fun setTransitionFraction(offset: Float) {
        val alpha = 1f - offset.coerceIn(0f, 1f)
        fullClock.bgAlpha = alpha
        splitClock.bgAlpha = alpha
    }

    private fun dismissLockStickyNote(noteId: Long, card: View) {
        TactileAudioEngine.playClick()
        StickyNoteStore.acknowledge(context, noteId)
        card.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(200L).withEndAction {
            refreshLockStickyNote()
            val hasUnread = StickyNoteStore.getAll(context).any { !it.acknowledged }
            notifStatusIcon.visibility = if (hasUnread || hasPendingNotification) View.VISIBLE else View.GONE
        }.start()
    }

    fun refreshLockStickyNote() {
        val cfg = ConfigRepository.get(context)
        val night = isNightTime(cfg.nightModeStartHour, cfg.nightModeEndHour)
        val unread = StickyNoteStore.getAll(context).filter { !it.acknowledged }
        lockStickyContainer.removeAllViews()
        if (unread.isEmpty()) {
            lockStickyContainer.visibility = View.GONE
            return
        }
        val d = resources.displayMetrics.density
        val tfUi = FontCache.plusJakarta(context)
        val tfHand = FontCache.patrickHand(context)
        val maxNotes = cfg.lockScreenMaxNotes.coerceIn(1, 5)

        unread.take(maxNotes).forEachIndexed { idx, note ->
            // Deterministic per-note tilt (-1.8° .. +1.8°) derived from the note id
            val tilt = (((note.id % 7L).toInt() + 7) % 7 - 3) * 0.6f
            val card = com.google.android.material.card.MaterialCardView(context).apply {
                radius = 20f * d
                cardElevation = 4f * d
                rotation = tilt
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    if (idx > 0) topMargin = (10 * d).toInt()
                }
            }
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * d).toInt(), (11 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            }
            val hdr = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvAuthor = TextView(context).apply {
                text = note.author
                typeface = tfUi
                textSize = 11.5f
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val ivCheck = ImageView(context).apply {
                setImageResource(com.kyle.kitchenhub.R.drawable.ic_check_rounded)
                val sz = (20 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            }
            val tvBody = TextView(context).apply {
                text = note.text
                typeface = tfHand
                textSize = 17.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * d).toInt() }
            }
            if (night) {
                card.setCardBackgroundColor(Color.parseColor("#1A0504"))
                card.strokeColor = Color.parseColor("#40FF2D20")
                card.strokeWidth = (1 * d).toInt().coerceAtLeast(1)
                val redInk = Color.parseColor("#FF2D20")
                tvAuthor.setTextColor(redInk)
                tvBody.setTextColor(redInk)
                ivCheck.setColorFilter(redInk)
            } else {
                val base = runCatching { Color.parseColor(note.colorHex) }.getOrDefault(Color.parseColor("#FFF3B0"))
                card.setCardBackgroundColor((0xE6 shl 24) or (base and 0x00FFFFFF))
                card.strokeWidth = 0
                val darkInk = Color.parseColor("#1C1F26")
                tvAuthor.setTextColor(Color.parseColor("#4B5563"))
                tvBody.setTextColor(darkInk)
                ivCheck.setColorFilter(darkInk)
            }
            hdr.addView(tvAuthor); hdr.addView(ivCheck)
            inner.addView(hdr); inner.addView(tvBody)
            card.addView(inner)
            card.setOnClickListener { dismissLockStickyNote(note.id, card) }
            ivCheck.setOnClickListener { dismissLockStickyNote(note.id, card) }
            lockStickyContainer.addView(card)
        }
        if (unread.size > maxNotes) {
            lockStickyContainer.addView(TextView(context).apply {
                text = "+${unread.size - maxNotes} more"
                typeface = tfUi
                textSize = 12.5f
                setTextColor(Color.parseColor(if (night) "#FF2D20" else "#B8C0CC"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
            })
        }
        lockStickyContainer.visibility = View.VISIBLE
    }

    fun setAmbientDarkened(darkened: Boolean) {
        val target = if (darkened) 1f else 0f
        android.animation.ValueAnimator.ofFloat(fullClock.bgDarkness, target).apply {
            duration = if (darkened) 360L else 240L
            addUpdateListener {
                val d = it.animatedValue as Float
                fullClock.bgDarkness = d
                com.kyle.kitchenhub.ui.overlay.FloatingGeminiPill.setAmbientDarkness(d)
            }
            start()
        }
        if (darkened) {
            refreshLockStickyNote()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val dndOn = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            val hasAlarms = com.kyle.kitchenhub.data.SystemAlarmsRepository.getEnabledOfficialAlarms(context).isNotEmpty()
            val hasNote = StickyNoteStore.get(context)?.let { !it.acknowledged } == true
            dndStatusIcon.visibility = if (dndOn) View.VISIBLE else View.GONE
            timerStatusIcon.visibility = if (hasAlarms) View.VISIBLE else View.GONE
            notifStatusIcon.visibility = if (hasNote || hasPendingNotification) View.VISIBLE else View.GONE
        }
        idleStatusRow.animate().cancel()
        idleStatusRow.animate()
            .alpha(if (darkened) 1f else 0f)
            .setDuration(if (darkened) 360L else 200L)
            .start()
    }

    fun refreshFromConfig() {
        val cfg = ConfigRepository.get(context)
        val night = isNightTime(cfg.nightModeStartHour, cfg.nightModeEndHour)
        fullClock.paletteIndex = cfg.clockPaletteIndex
        fullClock.nightMode = night
        splitClock.paletteIndex = cfg.clockPaletteIndex
        splitClock.nightMode = night

        modeIndex = when {
            !inPagerMode && cfg.clockFace == "SPLIT" -> 1
            !inPagerMode && cfg.clockFace == "PHOTOS" -> 2
            else -> 0
        }

        CoroutineScope(Dispatchers.Main).launch {
            val w = WeatherAndCalRepository.fetchWeather(cfg)
            fullClock.weatherText = "${w.tempDisplay} ${w.condition}"
            splitClock.weatherText = fullClock.weatherText
            if (modeIndex == 1) renderSplitWidget()
        }
        renderMode()
        refreshLockStickyNote()
    }

    private fun isNightTime(startH: Int, endH: Int): Boolean {
        if (DeviceEnvironment.LocalTestHarness.isActive() && DeviceEnvironment.LocalTestHarness.mockNightModePreview) {
            return true
        }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startH > endH) (h >= startH || h < endH) else (h in startH until endH)
    }

    private fun renderMode() {
        fullClock.visibility = if (modeIndex == 0) View.VISIBLE else View.GONE
        splitLayout.visibility = if (modeIndex == 1) View.VISIBLE else View.GONE
        (shiftWrapper.getChildAt(2)).visibility = if (modeIndex == 2) View.VISIBLE else View.GONE
        if (modeIndex == 1) renderSplitWidget()
        if (modeIndex == 2) loadPhotoFrame()
    }

    private fun renderSplitWidget() {
        splitWidgetCard.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181B22"))
                cornerRadius = 28f * resources.displayMetrics.density
            }
        }
        val title = TextView(context).apply { textSize = 18f; setTextColor(Color.parseColor("#A8C7FA")) }
        val body = TextView(context).apply { textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 0) }
        val note = StickyNoteStore.get(context)
        when (widgetStackIndex % 5) {
            0 -> {
                title.text = "Sticky Notes"
                body.typeface = FontCache.patrickHand(context)
                body.textSize = 26f
                body.text = note?.text ?: "No unread kitchen notes"
            }
            1 -> {
                title.text = "TIMERS AND ALARMS"
                var txt = "No active timers"
                MultiTimerManager.addListener { list ->
                    if (list.isNotEmpty()) txt = list.joinToString("\n") { "${it.label}: ${it.remainingSec / 60}m ${it.remainingSec % 60}s" }
                }
                body.text = txt
            }
            2 -> {
                title.text = "NOW PLAYING"
                body.text = "${MediaBridgeService.current.title}\n${MediaBridgeService.current.artist}"
            }
            3 -> {
                title.text = "WEATHER"
                body.text = fullClock.weatherText.ifBlank { "Solihull, UK" }
            }
            else -> {
                title.text = "FAMILY CALENDAR"
                body.text = "Tap Launcher for 7-day iCal view"
            }
        }
        card.addView(title); card.addView(body)
        splitWidgetCard.addView(card)
    }

    private fun loadPhotoFrame() {
        val dir = File(ConfigRepository.get(context).photoFolderPath)
        val img = dir.listFiles()?.firstOrNull { it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
        if (img != null) {
            photoImage.setImageBitmap(BitmapFactory.decodeFile(img.absolutePath))
        } else {
            photoImage.setBackgroundColor(Color.parseColor("#1B263B"))
        }
        photoClock.text = StickyNoteStore.currentTimeLabel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shiftHandler.removeCallbacks(shiftRunnable)
        shiftHandler.postDelayed(shiftRunnable, 60_000L)
    }

    override fun onDetachedFromWindow() {
        shiftHandler.removeCallbacks(shiftRunnable)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (inPagerMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.x
                    touchStartY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(ev.x - touchStartX)
                    val dy = abs(ev.y - touchStartY)
                    if (dy > dx && dy > 16f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (dx > dy && dx > 16f) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inPagerMode && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = abs(event.x - touchStartX)
            val dy = abs(event.y - touchStartY)
            if (dy > dx && dy > 16f) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }
}
