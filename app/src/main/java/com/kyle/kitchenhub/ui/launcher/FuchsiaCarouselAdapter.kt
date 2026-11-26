package com.kyle.kitchenhub.ui.launcher

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.RecyclerView
import com.kyle.kitchenhub.R
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.AppIconResolver
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.core.ui.PixelSparkleView
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.StickyNoteStore
import com.kyle.kitchenhub.data.SystemAlarmsRepository
import com.kyle.kitchenhub.data.WeatherAndCalRepository
import com.kyle.kitchenhub.databinding.CardAppFeatureBinding
import com.kyle.kitchenhub.databinding.CardHomeBentoBinding
import com.kyle.kitchenhub.databinding.CardHomeBluetoothBinding
import com.kyle.kitchenhub.databinding.CardHomeCalendarBinding
import com.kyle.kitchenhub.databinding.CardHomeStickyNoteBinding
import com.kyle.kitchenhub.databinding.CardHomeTimerBinding
import com.kyle.kitchenhub.databinding.CardHomeWeatherBinding
import com.kyle.kitchenhub.databinding.CardMediaBentoBinding
import com.kyle.kitchenhub.databinding.CardMediaNowPlayingBinding
import com.kyle.kitchenhub.databinding.ItemAgendaRowBinding
import com.kyle.kitchenhub.databinding.ItemForecastDayBinding
import com.kyle.kitchenhub.databinding.ItemTimerAlarmEntryBinding
import com.kyle.kitchenhub.service.media.MediaBridgeService
import com.kyle.kitchenhub.service.timer.KitchenTimer
import com.kyle.kitchenhub.service.timer.MultiTimerManager
import com.kyle.kitchenhub.ui.stickynote.StickyNoteDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

sealed class FuchsiaCardItem {
    object HomeBento : FuchsiaCardItem()
    object WeatherHero : FuchsiaCardItem()
    object StickyNote : FuchsiaCardItem()
    object KitchenTimer : FuchsiaCardItem()
    object CalendarStandBy : FuchsiaCardItem()
    object MediaNowPlaying : FuchsiaCardItem()
    object BluetoothSpeaker : FuchsiaCardItem()
    data class CustomCard(val key: String) : FuchsiaCardItem()
    data class StackedCustomPair(val topKey: String, val bottomKey: String, val widthDp: Int = 360) : FuchsiaCardItem()
    data class MediaBento(val ytMusic: FeatureCard, val yt: FeatureCard) : FuchsiaCardItem()
    data class FeatureCard(
        val pkg: String?,
        val fallbackIcon: Int,
        val title: String,
        val subtitle: String,
        val actionText: String,
        val onClick: () -> Unit
    ) : FuchsiaCardItem()
}

class FuchsiaCarouselAdapter(
    private val context: Context,
    private val items: List<FuchsiaCardItem>,
    private val sparkle: PixelSparkleView,
    private val onEnterClock: () -> Unit,
    private val onRefreshBanner: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_BENTO = 0
        private const val TYPE_WEATHER = 1
        private const val TYPE_NOTE = 2
        private const val TYPE_TIMER = 3
        private const val TYPE_CALENDAR = 4
        private const val TYPE_NOW_PLAYING = 5
        private const val TYPE_FEATURE = 6
        private const val TYPE_MEDIA_BENTO = 7
        private const val TYPE_BLUETOOTH = 8
        private const val TYPE_CUSTOM = 9
    }

    class BentoVH(val b: CardHomeBentoBinding) : RecyclerView.ViewHolder(b.root)
    class MediaBentoVH(val b: CardMediaBentoBinding) : RecyclerView.ViewHolder(b.root)
    class BluetoothVH(val b: CardHomeBluetoothBinding) : RecyclerView.ViewHolder(b.root)
    class CustomVH(val host: android.widget.FrameLayout) : RecyclerView.ViewHolder(host)
    class WeatherVH(val b: CardHomeWeatherBinding) : RecyclerView.ViewHolder(b.root)
    class NoteVH(val b: CardHomeStickyNoteBinding) : RecyclerView.ViewHolder(b.root)
    class TimerVH(val b: CardHomeTimerBinding) : RecyclerView.ViewHolder(b.root)
    class CalendarVH(val b: CardHomeCalendarBinding) : RecyclerView.ViewHolder(b.root)
    class NowPlayingVH(val b: CardMediaNowPlayingBinding) : RecyclerView.ViewHolder(b.root)
    class FeatureVH(val b: CardAppFeatureBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is FuchsiaCardItem.HomeBento -> TYPE_BENTO
        is FuchsiaCardItem.WeatherHero -> TYPE_WEATHER
        is FuchsiaCardItem.StickyNote -> TYPE_NOTE
        is FuchsiaCardItem.KitchenTimer -> TYPE_TIMER
        is FuchsiaCardItem.CalendarStandBy -> TYPE_CALENDAR
        is FuchsiaCardItem.MediaNowPlaying -> TYPE_NOW_PLAYING
        is FuchsiaCardItem.MediaBento -> TYPE_MEDIA_BENTO
        is FuchsiaCardItem.BluetoothSpeaker -> TYPE_BLUETOOTH
        is FuchsiaCardItem.CustomCard -> TYPE_CUSTOM
        is FuchsiaCardItem.StackedCustomPair -> TYPE_CUSTOM
        is FuchsiaCardItem.FeatureCard -> TYPE_FEATURE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BENTO -> BentoVH(CardHomeBentoBinding.inflate(inf, parent, false))
            TYPE_MEDIA_BENTO -> MediaBentoVH(CardMediaBentoBinding.inflate(inf, parent, false))
            TYPE_BLUETOOTH -> BluetoothVH(CardHomeBluetoothBinding.inflate(inf, parent, false))
            TYPE_WEATHER -> WeatherVH(CardHomeWeatherBinding.inflate(inf, parent, false))
            TYPE_NOTE -> NoteVH(CardHomeStickyNoteBinding.inflate(inf, parent, false))
            TYPE_TIMER -> TimerVH(CardHomeTimerBinding.inflate(inf, parent, false))
            TYPE_CALENDAR -> CalendarVH(CardHomeCalendarBinding.inflate(inf, parent, false))
            TYPE_NOW_PLAYING -> NowPlayingVH(CardMediaNowPlayingBinding.inflate(inf, parent, false))
            TYPE_CUSTOM -> CustomVH(android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-2, -1)
            })
            else -> FeatureVH(CardAppFeatureBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("in_place") && holder is BentoVH) {
            bindStickyBoard(holder.b, position)
            renderTimersAndOfficialAlarms(holder.b.timerInclude, lastTimersSnapshot)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is BentoVH -> bindBento(holder.b, position)
            is MediaBentoVH -> {
                val item = items[position] as FuchsiaCardItem.MediaBento
                bindNowPlaying(holder.b.nowPlayingInclude)
                bindFeature(holder.b.ytMusicInclude, item.ytMusic)
                bindFeature(holder.b.ytInclude, item.yt)
            }
            is BluetoothVH -> bindBluetooth(holder.b)
            is WeatherVH -> bindWeather(holder.b)
            is NoteVH -> bindStickyNote(holder.b, position)
            is TimerVH -> bindTimerAndAlarms(holder.b)
            is CalendarVH -> bindCalendar(holder.b)
            is NowPlayingVH -> bindNowPlaying(holder.b)
            is CustomVH -> {
                holder.host.removeAllViews()
                val view = when (val item = items[position]) {
                    is FuchsiaCardItem.StackedCustomPair ->
                        KitchenUtilityCards.buildStackedPair(context, item.topKey, item.bottomKey, item.widthDp, onEnterClock, onRefreshBanner)
                    is FuchsiaCardItem.CustomCard ->
                        KitchenUtilityCards.buildByKey(context, item.key, onEnterClock, onRefreshBanner)
                    else -> View(context)
                }
                holder.host.addView(view)
            }
            is FeatureVH -> bindFeature(holder.b, items[position] as FuchsiaCardItem.FeatureCard)
        }
    }

    private fun bindBento(b: CardHomeBentoBinding, pos: Int) {
        bindWeather(b.weatherInclude)
        bindStickyBoard(b, pos)
        bindTimerAndAlarms(b.timerInclude)
    }

    private fun bindStickyBoard(b: CardHomeBentoBinding, pos: Int) {
        val cfg = ConfigRepository.get(context)
        val uiTf = FontCache.plusJakarta(context)
        val bodyTf = if (cfg.stickyFont == "CAVEAT_CURSIVE") FontCache.caveat(context) else FontCache.patrickHand(context)

        b.tvStickyBoardTitle.typeface = uiTf
        b.tvStickyBoardTitle.paint.isFakeBoldText = true
        b.tvStickyEmptyHint.typeface = uiTf

        val notes = StickyNoteStore.getAll(context)
        val openNewNoteDialog = {
            TactileAudioEngine.playClick()
            StickyNoteDialog(context) {
                onRefreshBanner()
                bindStickyBoard(b, pos)
            }.show()
        }
        b.btnBoardAddNote.setOnClickListener { openNewNoteDialog() }
        b.cardStickyBoard.setOnClickListener { openNewNoteDialog() }

        if (notes.isEmpty()) {
            b.tvStickyEmptyHint.visibility = View.VISIBLE
            b.stickyMiniRow.visibility = View.GONE
            return
        }

        b.tvStickyEmptyHint.visibility = View.GONE
        b.stickyMiniRow.visibility = View.VISIBLE

        val slots = listOf(
            Triple(b.miniNoteCard1, b.tvMiniNoteAuthor1, b.tvMiniNoteBody1),
            Triple(b.miniNoteCard2, b.tvMiniNoteAuthor2, b.tvMiniNoteBody2),
            Triple(b.miniNoteCard3, b.tvMiniNoteAuthor3, b.tvMiniNoteBody3)
        )
        slots.forEachIndexed { idx, (card, tvAuthor, tvBody) ->
            val note = notes.getOrNull(idx)
            if (note == null) {
                card.visibility = View.GONE
            } else {
                card.visibility = View.VISIBLE
                tvAuthor.typeface = uiTf
                tvAuthor.text = note.author
                tvBody.typeface = bodyTf
                tvBody.text = note.text
                runCatching { card.setCardBackgroundColor(Color.parseColor(note.colorHex)) }
                card.setOnClickListener {
                    TactileAudioEngine.playClick()
                    StickyNoteDialog(context, editingNote = note) {
                        onRefreshBanner()
                        bindStickyBoard(b, pos)
                    }.show()
                }
            }
        }

        val overflow = notes.size - 3
        if (overflow > 0) {
            b.tvMiniNoteOverflow.visibility = View.VISIBLE
            b.tvMiniNoteOverflow.text = "+$overflow"
        } else {
            b.tvMiniNoteOverflow.visibility = View.GONE
        }
    }

    private fun bindWeather(b: CardHomeWeatherBinding) {
        val cfg = ConfigRepository.get(context)
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        b.cardWeatherHero.setOnClickListener {
            TactileAudioEngine.playClick()
            com.kyle.kitchenhub.ui.weather.WeatherDetailDialog(context).show()
        }
        b.tvGreeting.typeface = FontCache.plusJakarta(context)
        b.tvWeatherLocation.typeface = FontCache.plusJakarta(context)
        b.tvHeroTemp.typeface = FontCache.plusJakarta(context)

        b.tvGreeting.text = when (hr) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        b.weatherHeroBg.setBackgroundResource(
            if (hr in 6..18) R.drawable.bg_weather_hero_day else R.drawable.bg_weather_hero_night
        )
        b.tvWeatherLocation.text = "${cfg.locationName} • Updating..."
        b.weatherLoadingIndicator.animate().cancel()
        b.weatherLoadingIndicator.visibility = View.VISIBLE
        b.weatherLoadingIndicator.alpha = 1f
        b.weatherLoadingIndicator.scaleX = 1f
        b.weatherLoadingIndicator.scaleY = 1f
        val loadStartMs = System.currentTimeMillis()

        CoroutineScope(Dispatchers.Main).launch {
            val w = WeatherAndCalRepository.fetchWeather(cfg)
            val elapsed = System.currentTimeMillis() - loadStartMs
            if (elapsed < 900L) kotlinx.coroutines.delay(900L - elapsed)
            b.weatherLoadingIndicator.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .setDuration(260L)
                .withEndAction { b.weatherLoadingIndicator.visibility = View.GONE }
                .start()
            b.tvHeroTemp.text = w.tempDisplay
            b.tvWeatherLocation.text = "${cfg.locationName} • ${w.condition}"
            b.ivHeroWeather.setImageResource(weatherIconFor(w.condition))
            b.weatherHeroShader.setCondition(w.condition)

            b.forecastStrip.removeAllViews()
            val inf = LayoutInflater.from(context)
            val days = w.forecast.ifEmpty {
                listOf(
                    com.kyle.kitchenhub.data.DailyForecast("Today", w.condition, "13° / 6°"),
                    com.kyle.kitchenhub.data.DailyForecast("Fri", "Cloudy", "12° / 5°"),
                    com.kyle.kitchenhub.data.DailyForecast("Sat", "Clear", "15° / 7°")
                )
            }
            days.forEach { d ->
                val row = ItemForecastDayBinding.inflate(inf, b.forecastStrip, false)
                row.tvDayName.typeface = FontCache.plusJakarta(context)
                row.tvDayHiLo.typeface = FontCache.plusJakarta(context)
                row.tvDayName.text = d.dayLabel
                row.tvDayHiLo.text = d.hiLo
                row.ivDayIcon.setImageResource(weatherIconFor(d.condition))
                b.forecastStrip.addView(row.root)
            }
        }
    }

    private fun bindStickyNote(b: CardHomeStickyNoteBinding, pos: Int) {
        val cfg = ConfigRepository.get(context)
        val note = StickyNoteStore.get(context)
        b.tvNoteAuthor.typeface = FontCache.plusJakarta(context)
        b.tvNoteAuthor.paint.isFakeBoldText = true
        b.tvNoteAuthorBadge.typeface = FontCache.plusJakarta(context)
        // Use upright neat printed handwriting (PatrickHand) close to standard sans font unless cursive explicitly chosen
        b.tvNoteBody.typeface = if (cfg.stickyFont == "CAVEAT_CURSIVE") FontCache.caveat(context) else FontCache.patrickHand(context)

        val bgHex = note?.colorHex ?: "#FFF3B0"
        runCatching { b.cardStickyRoot.setCardBackgroundColor(Color.parseColor(bgHex)) }
        b.tvNoteAuthor.text = "Sticky Notes"
        if (note != null && note.author.isNotBlank()) {
            b.tvNoteAuthorBadge.text = note.author
            b.tvNoteAuthorBadge.visibility = View.VISIBLE
        } else {
            b.tvNoteAuthorBadge.visibility = View.GONE
        }
        b.tvNoteBody.text = note?.text ?: "Tap Speak below to leave a note."
        b.btnNoteDone.visibility = if (note != null) View.VISIBLE else View.GONE

        val openDialog = {
            TactileAudioEngine.playClick()
            StickyNoteDialog(context) {
                onRefreshBanner()
                notifyItemChanged(pos)
            }.show()
        }
        b.cardStickyRoot.setOnClickListener { openDialog() }
        b.btnNoteSpeak.setOnClickListener { sparkle.triggerBurstOnView(it); openDialog() }
        b.btnNoteDone.setOnClickListener {
            TactileAudioEngine.playClick()
            StickyNoteStore.acknowledge(context)
            StickyNoteStore.clear(context)
            onRefreshBanner()
            notifyItemChanged(pos)
        }
    }

    private fun bindTimerAndAlarms(b: CardHomeTimerBinding) {
        val tf = FontCache.plusJakarta(context)
        b.tvTimersCardTitle.typeface = tf
        b.tvTimersCardTitle.paint.isFakeBoldText = true
        b.tvEmptyAlarmPrompt.typeface = tf
        b.tvEmptyAlarmPrompt.paint.isFakeBoldText = true

        val openSetAlarm = { v: View ->
            TactileAudioEngine.playClick()
            sparkle.triggerBurstOnView(v)
            SystemAlarmsRepository.openOfficialAlarmsUi(context)
        }
        b.btnAlarmHeaderAdd.setOnClickListener(openSetAlarm)
        b.timerEmptyStateWrap.setOnClickListener(openSetAlarm)

        val startPresetTimer = { v: View, label: String, minutes: Int ->
            TactileAudioEngine.playClick()
            sparkle.triggerBurstOnView(v)
            if (!SystemAlarmsRepository.startOfficialTimer(context, label, minutes)) {
                MultiTimerManager.startTimer(label, minutes)
            }
        }
        val mins = ConfigRepository.get(context).quickTimerMinutes
            .split(",")
            .mapNotNull { it.trim().toIntOrNull()?.coerceIn(1, 180) }
        val presets = listOf(
            mins.getOrElse(0) { 5 },
            mins.getOrElse(1) { 10 },
            mins.getOrElse(2) { 15 },
            mins.getOrElse(3) { 20 }
        )
        val btns = listOf(b.btnPreset5m, b.btnPreset10m, b.btnPreset15m, b.btnPreset20m)
        btns.forEachIndexed { idx, btn ->
            val m = presets[idx]
            btn.text = "${m}m"
            btn.setOnClickListener { startPresetTimer(it, "${m}m Timer", m) }
        }

        MultiTimerManager.addListener { timers ->
            b.root.post {
                renderTimersAndOfficialAlarms(b, timers)
            }
        }
        MediaBridgeService.addTimerListener { sysTimers ->
            b.root.post {
                renderTimersAndOfficialAlarms(b, lastTimersSnapshot)
                if (sysTimers.any { !it.isPaused }) {
                    scheduleSystemTimerTick(b)
                }
            }
        }
    }

    private var lastTimersSnapshot: List<KitchenTimer> = emptyList()
    private var sysTimerTickPosted = false
    private val systemTimerMaxSec = mutableMapOf<String, Int>()

    private fun scheduleSystemTimerTick(b: CardHomeTimerBinding) {
        if (sysTimerTickPosted) return
        sysTimerTickPosted = true
        b.root.postDelayed({
            sysTimerTickPosted = false
            if (!b.root.isAttachedToWindow) return@postDelayed
            val activeSys = MediaBridgeService.systemTimers.any { !it.isPaused }
            if (activeSys) {
                renderTimersAndOfficialAlarms(b, lastTimersSnapshot)
                scheduleSystemTimerTick(b)
            }
        }, 1000L)
    }

    private fun renderTimersAndOfficialAlarms(b: CardHomeTimerBinding, timers: List<KitchenTimer>) {
        lastTimersSnapshot = timers
        val officialAlarms = SystemAlarmsRepository.getEnabledOfficialAlarms(context)
        val systemTimers = MediaBridgeService.systemTimers
        if (timers.isEmpty() && officialAlarms.isEmpty() && systemTimers.isEmpty()) {
            b.activeEntriesContainer.removeAllViews()
            b.activeEntriesContainer.tag = null
            b.timerEmptyStateWrap.visibility = View.VISIBLE
            b.activeEntriesScroll.visibility = View.GONE
            b.tvEmptyAlarmPrompt.text = "Tap to set an alarm"
            b.timerRing.setProgressCompat(72, false)
            return
        }

        b.timerEmptyStateWrap.visibility = View.GONE
        b.activeEntriesScroll.visibility = View.VISIBLE

        val keys = ArrayList<String>(timers.size + systemTimers.size + officialAlarms.size)
        timers.forEach { keys.add("timer:${it.id}") }
        systemTimers.forEach { keys.add("systimer:${it.id}") }
        officialAlarms.forEach { keys.add("alarm:${it.timeText}:${it.subtitle}") }

        val prevKeys = b.activeEntriesContainer.tag as? List<*>
        val structureChanged = prevKeys != keys || b.activeEntriesContainer.childCount != keys.size

        val inf = LayoutInflater.from(context)
        val tf = FontCache.plusJakarta(context)

        if (structureChanged) {
            b.activeEntriesContainer.removeAllViews()
            b.activeEntriesContainer.tag = keys
        }

        var childIdx = 0
        // 1. Render ALL current running/paused timers
        timers.forEach { t ->
            val row = if (structureChanged) {
                ItemTimerAlarmEntryBinding.inflate(inf, b.activeEntriesContainer, false).also { r ->
                    r.tvEntryPrimary.typeface = tf
                    r.tvEntryPrimary.paint.isFakeBoldText = true
                    r.tvEntrySecondary.typeface = tf
                    r.ivEntryIcon.setImageResource(R.drawable.ic_timer_rounded)
                    r.alarmOnBadge.visibility = View.GONE
                    r.timerRowActions.visibility = View.VISIBLE
                    r.timerRowProgress.visibility = View.VISIBLE
                    r.btnRowPlus1.setOnClickListener {
                        TactileAudioEngine.playClick()
                        MultiTimerManager.addOneMinute(t.id)
                    }
                    r.btnRowPause.setOnClickListener {
                        TactileAudioEngine.playClick()
                        MultiTimerManager.togglePause(t.id)
                    }
                    r.btnRowDismiss.setOnClickListener {
                        TactileAudioEngine.playClick()
                        MultiTimerManager.dismissTimer(t.id)
                    }
                    r.root.tag = r
                    b.activeEntriesContainer.addView(r.root)
                }
            } else {
                b.activeEntriesContainer.getChildAt(childIdx).tag as ItemTimerAlarmEntryBinding
            }
            childIdx++

            val m = t.remainingSec / 60
            val s = (t.remainingSec % 60).toString().padStart(2, '0')
            row.tvEntryPrimary.text = "$m:$s"
            row.tvEntrySecondary.text = when {
                t.ringing -> "${t.label} • Ringing!"
                t.paused -> "${t.label} • Paused"
                else -> "${t.label} Timer"
            }

            val total = t.totalSec.coerceAtLeast(t.remainingSec).coerceAtLeast(1)
            val pct = (((total - t.remainingSec) * 100) / total).coerceIn(0, 100)
            if (row.timerRowProgress.tag != pct) {
                row.timerRowProgress.tag = pct
                row.timerRowProgress.setProgressCompat(pct, false)
            }

            row.btnRowPause.setIconResource(if (t.paused) R.drawable.ic_play_rounded else R.drawable.ic_pause_rounded)
        }

        // 1b. Render official Android Clock / DeskClock timers (from NotificationListenerService)
        systemTimers.forEach { st ->
            val row = if (structureChanged) {
                ItemTimerAlarmEntryBinding.inflate(inf, b.activeEntriesContainer, false).also { r ->
                    r.tvEntryPrimary.typeface = tf
                    r.tvEntryPrimary.paint.isFakeBoldText = true
                    r.tvEntrySecondary.typeface = tf
                    r.ivEntryIcon.setImageResource(R.drawable.ic_timer_rounded)
                    r.timerRowActions.visibility = View.GONE
                    r.alarmOnBadge.visibility = View.GONE
                    r.timerRowProgress.visibility = View.VISIBLE
                    r.entryCardRoot.setOnClickListener {
                        TactileAudioEngine.playClick()
                        sparkle.triggerBurstOnView(it)
                        val sent = st.contentIntent?.let { pi -> runCatching { pi.send() }.isSuccess } == true
                        if (!sent) SystemAlarmsRepository.openOfficialAlarmsUi(context)
                    }
                    r.root.tag = r
                    b.activeEntriesContainer.addView(r.root)
                }
            } else {
                b.activeEntriesContainer.getChildAt(childIdx).tag as ItemTimerAlarmEntryBinding
            }
            childIdx++

            val remSec = if (!st.isPaused && st.endElapsedMs > 0L) {
                ((st.endElapsedMs - android.os.SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)
            } else 0
            if (!st.isPaused && st.endElapsedMs > 0L) {
                val h = remSec / 3600
                val m = (remSec % 3600) / 60
                val s = (remSec % 60).toString().padStart(2, '0')
                row.tvEntryPrimary.text = if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
                row.tvEntrySecondary.text = st.label
            } else if (!st.isPaused) {
                row.tvEntryPrimary.text = st.pausedText ?: "Running"
                row.tvEntrySecondary.text = st.label
            } else {
                row.tvEntryPrimary.text = st.pausedText ?: "--:--"
                row.tvEntrySecondary.text = "${st.label} • Paused"
            }
            val maxSec = if (!st.isPaused && remSec > 0) {
                val prev = systemTimerMaxSec[st.id] ?: remSec
                val updated = maxOf(prev, remSec)
                systemTimerMaxSec[st.id] = updated
                updated
            } else {
                systemTimerMaxSec[st.id] ?: 1
            }
            val pct = if (!st.isPaused && st.endElapsedMs > 0L) {
                (((maxSec - remSec) * 100) / maxSec.coerceAtLeast(1)).coerceIn(0, 100)
            } else 0
            if (row.timerRowProgress.tag != pct) {
                row.timerRowProgress.tag = pct
                row.timerRowProgress.setProgressCompat(pct, false)
            }
        }

        // 2. Render ANY official Android device alarms currently turned ON
        officialAlarms.forEach { alarm ->
            val row = if (structureChanged) {
                ItemTimerAlarmEntryBinding.inflate(inf, b.activeEntriesContainer, false).also { r ->
                    r.tvEntryPrimary.typeface = tf
                    r.tvEntryPrimary.paint.isFakeBoldText = true
                    r.tvEntrySecondary.typeface = tf
                    r.entryCardRoot.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.m3_surface_container_high)
                    )
                    r.ivEntryIcon.setImageResource(R.drawable.ic_alarm_rounded)
                    r.timerRowActions.visibility = View.GONE
                    r.timerRowProgress.visibility = View.GONE
                    r.alarmOnBadge.visibility = View.VISIBLE
                    r.entryCardRoot.setOnClickListener {
                        TactileAudioEngine.playClick()
                        sparkle.triggerBurstOnView(it)
                        SystemAlarmsRepository.openOfficialAlarmsUi(context, alarm.showIntent)
                    }
                    r.root.tag = r
                    b.activeEntriesContainer.addView(r.root)
                }
            } else {
                b.activeEntriesContainer.getChildAt(childIdx).tag as ItemTimerAlarmEntryBinding
            }
            childIdx++

            row.tvEntryPrimary.text = alarm.timeText
            row.tvEntrySecondary.text = alarm.subtitle
        }
    }

    private fun bindCalendar(b: CardHomeCalendarBinding) {
        val cfg = ConfigRepository.get(context)
        b.tvAgendaFallback.typeface = FontCache.plusJakarta(context)
        b.root.setOnClickListener {
            TactileAudioEngine.playClick()
            com.kyle.kitchenhub.ui.calendar.CalendarAgendaDialog(context).show()
        }
        if (cfg.icalUrl.isNotBlank()) {
            CoroutineScope(Dispatchers.Main).launch {
                val evs = WeatherAndCalRepository.fetchIcalEvents(cfg.icalUrl)
                if (evs.isNotEmpty()) {
                    b.agendaContainer.removeAllViews()
                    val inf = LayoutInflater.from(context)
                    evs.take(3).forEach { ev ->
                        val row = ItemAgendaRowBinding.inflate(inf, b.agendaContainer, false)
                        row.tvEventTitle.typeface = FontCache.plusJakarta(context)
                        row.tvEventWhen.typeface = FontCache.plusJakarta(context)
                        row.tvEventTitle.text = ev.title
                        row.tvEventWhen.text = ev.whenText
                        b.agendaContainer.addView(row.root)
                    }
                }
            }
        }
    }

    private fun bindNowPlaying(b: CardMediaNowPlayingBinding) {
        b.tvMediaTitle.typeface = FontCache.plusJakarta(context)
        b.tvMediaArtist.typeface = FontCache.plusJakarta(context)

        val progressTicker = object : Runnable {
            override fun run() {
                if (MediaBridgeService.current.isPlaying) {
                    val pct = (MediaBridgeService.getProgressFraction() * 100f).toInt().coerceIn(0, 100)
                    b.mediaProgress.setProgressCompat(pct, true)
                    val (el, rem) = MediaBridgeService.getTimeLabels()
                    b.tvTimeElapsed.text = el
                    b.tvTimeRemaining.text = rem
                }
                b.root.postDelayed(this, 500L)
            }
        }
        b.root.post(progressTicker)

        b.mediaProgress.setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                val frac = (ev.x / v.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                b.mediaProgress.setProgressCompat((frac * 100f).toInt(), false)
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    MediaBridgeService.seekToFraction(frac)
                }
                true
            } else if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val frac = (ev.x / v.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                MediaBridgeService.seekToFraction(frac)
                true
            } else false
        }

        MediaBridgeService.addListener { st ->
            b.root.post {
                b.turbulenceShader.setCloudsActive(st.isPlaying)
                b.tvMediaTitle.text = st.title
                b.tvMediaArtist.text = st.artist
                val pct = (MediaBridgeService.getProgressFraction() * 100f).toInt().coerceIn(0, 100)
                b.mediaProgress.setProgressCompat(pct, true)
                val (el, rem) = MediaBridgeService.getTimeLabels()
                b.tvTimeElapsed.text = el
                b.tvTimeRemaining.text = rem
                b.btnMediaPlayPause.setIconResource(
                    if (st.isPlaying) R.drawable.ic_pause_rounded else R.drawable.ic_play_rounded
                )
                if (st.art != null) {
                    b.ivAlbumArt.setImageBitmap(st.art)
                    b.ivAlbumPlaceholder.visibility = View.GONE
                    val p = Palette.from(st.art).generate()
                    val accent = p.getLightVibrantColor(p.getVibrantColor(Color.parseColor("#A8C7FA")))
                    val tonalBg = p.getDarkMutedColor(Color.parseColor("#2D3F59"))
                    val onAccent = if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.45) {
                        Color.parseColor("#0B0E14")
                    } else {
                        Color.WHITE
                    }
                    b.tvNowPlayingBadge.setTextColor(accent)
                    b.turbulenceShader.setTintColor(accent)
                    b.tvMediaTitle.setTextColor(Color.WHITE)
                    b.tvMediaArtist.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(accent, Color.WHITE, 0.55f))
                    b.mediaProgress.setIndicatorColor(accent)
                    b.mediaProgress.trackColor = androidx.core.graphics.ColorUtils.setAlphaComponent(tonalBg, 160)
                    b.btnMediaPlayPause.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
                    b.btnMediaPlayPause.iconTint = android.content.res.ColorStateList.valueOf(onAccent)
                    val tonalList = android.content.res.ColorStateList.valueOf(tonalBg)
                    val tonalFg = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    b.btnMediaPrev.backgroundTintList = tonalList
                    b.btnMediaPrev.iconTint = tonalFg
                    b.btnMediaNext.backgroundTintList = tonalList
                    b.btnMediaNext.iconTint = tonalFg
                    b.btnMediaShuffle.backgroundTintList = tonalList
                    b.btnMediaShuffle.iconTint = tonalFg
                    b.btnMediaLoop.backgroundTintList = tonalList
                    b.btnMediaLoop.iconTint = tonalFg
                } else {
                    b.ivAlbumArt.setImageDrawable(null)
                    b.ivAlbumPlaceholder.visibility = View.VISIBLE
                    val defAccent = ContextCompat.getColor(context, R.color.m3_primary)
                    val defSurface = ContextCompat.getColor(context, R.color.m3_surface_container_high)
                    b.tvNowPlayingBadge.setTextColor(defAccent)
                    b.tvMediaTitle.setTextColor(Color.WHITE)
                    b.tvMediaArtist.setTextColor(ContextCompat.getColor(context, R.color.m3_on_surface_variant))
                    b.mediaProgress.setIndicatorColor(defAccent)
                    b.mediaProgress.trackColor = defSurface
                    b.btnMediaPlayPause.backgroundTintList = android.content.res.ColorStateList.valueOf(defAccent)
                    b.btnMediaPlayPause.iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#0B0E14"))
                    val defTonal = android.content.res.ColorStateList.valueOf(defSurface)
                    val defFg = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    b.btnMediaPrev.backgroundTintList = defTonal
                    b.btnMediaPrev.iconTint = defFg
                    b.btnMediaNext.backgroundTintList = defTonal
                    b.btnMediaNext.iconTint = defFg
                    b.btnMediaShuffle.backgroundTintList = defTonal
                    b.btnMediaShuffle.iconTint = defFg
                    b.btnMediaLoop.backgroundTintList = defTonal
                    b.btnMediaLoop.iconTint = defFg
                }
            }
        }

        var shuffleOn = false
        var loopOn = false
        b.btnMediaShuffle.setOnClickListener {
            TactileAudioEngine.playClick()
            shuffleOn = !shuffleOn
            b.btnMediaShuffle.iconTint = android.content.res.ColorStateList.valueOf(
                if (shuffleOn) ContextCompat.getColor(context, R.color.m3_primary) else Color.WHITE
            )
        }
        b.btnMediaLoop.setOnClickListener {
            TactileAudioEngine.playClick()
            loopOn = !loopOn
            b.btnMediaLoop.iconTint = android.content.res.ColorStateList.valueOf(
                if (loopOn) ContextCompat.getColor(context, R.color.m3_primary) else Color.WHITE
            )
        }
        b.btnMediaPrev.setOnClickListener { TactileAudioEngine.playClick(); MediaBridgeService.skipPrev() }
        b.btnMediaPlayPause.setOnClickListener {
            TactileAudioEngine.playClick()
            sparkle.triggerBurstOnView(it)
            MediaBridgeService.togglePlayPause()
        }
        b.btnMediaNext.setOnClickListener { TactileAudioEngine.playClick(); MediaBridgeService.skipNext() }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun bindBluetooth(b: CardHomeBluetoothBinding) {
        val tf = FontCache.plusJakarta(context)
        b.tvBtCardTitle.typeface = tf
        b.tvBtCardTitle.paint.isFakeBoldText = true
        b.tvBtPairedHeader.typeface = tf
        b.tvBtAvailableHeader.typeface = tf
        b.tvBtScanStatus.typeface = tf

        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        b.sliderMediaVolume.value = ((am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 10f) / maxVol)
            .coerceIn(1f, 10f).toInt().toFloat()
        b.sliderMediaVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                TactileAudioEngine.playDetentTick()
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, ((value.toInt() * maxVol) / 10), 0)
            }
        }

        val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val discovered = LinkedHashMap<String, android.bluetooth.BluetoothDevice>()
        val d = context.resources.displayMetrics.density

        fun makeRow(title: String, subtitle: String, onClick: () -> Unit): View {
            val card = com.google.android.material.card.MaterialCardView(context).apply {
                radius = 16f * d
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.m3_surface_container_high))
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
                setOnClickListener { TactileAudioEngine.playClick(); onClick() }
            }
            val col = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            }
            col.addView(android.widget.TextView(context).apply {
                text = title
                typeface = tf
                paint.isFakeBoldText = true
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.m3_on_surface))
            })
            col.addView(android.widget.TextView(context).apply {
                text = subtitle
                typeface = tf
                textSize = 13.5f
                setTextColor(ContextCompat.getColor(context, R.color.m3_on_surface_variant))
            })
            card.addView(col)
            return card
        }

        fun emptyHint(msg: String) = android.widget.TextView(context).apply {
            text = msg
            typeface = tf
            textSize = 14.5f
            setTextColor(ContextCompat.getColor(context, R.color.m3_on_surface_variant))
        }

        fun renderPaired() {
            b.btPairedContainer.removeAllViews()
            if (bt == null) {
                b.btPairedContainer.addView(emptyHint("Bluetooth unavailable"))
                return
            }
            val bonded = runCatching { bt.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            if (bonded.isEmpty()) {
                b.btPairedContainer.addView(emptyHint("No paired devices"))
                return
            }
            bonded.forEach { dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                b.btPairedContainer.addView(makeRow(name, "${dev.address} • Paired") {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                })
            }
        }

        fun renderAvailable() {
            b.btAvailableContainer.removeAllViews()
            if (discovered.isEmpty()) {
                b.btAvailableContainer.addView(emptyHint("Tap Scan to search nearby devices"))
                return
            }
            discovered.values.forEach { dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                b.btAvailableContainer.addView(makeRow(name, "${dev.address} • Tap to pair") {
                    runCatching { bt?.cancelDiscovery() }
                    val ok = runCatching { dev.createBond() }.getOrDefault(false)
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Pairing with $name..." else "Open Bluetooth Settings to pair",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                })
            }
        }

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                        val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                            android.bluetooth.BluetoothDevice.EXTRA_DEVICE
                        ) ?: return
                        val addr = dev.address ?: return
                        if (bt?.bondedDevices?.any { it.address == addr } == true) return
                        discovered[addr] = dev
                        renderAvailable()
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        b.tvBtScanStatus.text = if (discovered.isEmpty()) "None found" else "Done"
                    }
                }
            }
        }

        var registered = false
        fun startScan() {
            val neededPerms = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                neededPerms.add("android.permission.BLUETOOTH_SCAN")
                neededPerms.add("android.permission.BLUETOOTH_CONNECT")
            }
            val missing = neededPerms.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                b.tvBtScanStatus.text = "Granting permission..."
                (context as? android.app.Activity)?.requestPermissions(missing.toTypedArray(), 412)
                return
            }
            if (bt == null || !bt.isEnabled) {
                b.tvBtScanStatus.text = if (bt == null) "Unavailable" else "BT Off"
                renderAvailable()
                return
            }
            if (!registered) {
                runCatching {
                    val f = android.content.IntentFilter().apply {
                        addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
                        addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    }
                    context.registerReceiver(receiver, f)
                    registered = true
                }
            }
            runCatching {
                if (bt.isDiscovering) bt.cancelDiscovery()
                val ok = bt.startDiscovery()
                b.tvBtScanStatus.text = if (ok) "Scanning..." else "Needs permission"
            }.onFailure {
                b.tvBtScanStatus.text = "Unavailable"
            }
        }

        b.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                renderPaired()
                renderAvailable()
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching { bt?.cancelDiscovery() }
                if (registered) {
                    runCatching { context.unregisterReceiver(receiver) }
                    registered = false
                }
            }
        })

        b.btnBtScan.setOnClickListener {
            TactileAudioEngine.playClick()
            renderPaired()
            startScan()
        }

        renderPaired()
        renderAvailable()
    }

    private fun bindFeature(b: CardAppFeatureBinding, item: FuchsiaCardItem.FeatureCard) {
        b.tvFeatureTitle.typeface = FontCache.plusJakarta(context)
        b.tvFeatureSubtitle.typeface = FontCache.plusJakarta(context)
        b.tvFeatureTitle.text = item.title
        b.tvFeatureSubtitle.text = item.subtitle
        b.btnFeatureAction.text = item.actionText
        AppIconResolver.applyTo(b.ivFeatureIcon, item.pkg, item.fallbackIcon)

        val trigger = { v: View ->
            TactileAudioEngine.playClick()
            sparkle.triggerBurstOnView(v)
            item.onClick()
        }
        b.cardFeatureRoot.setOnClickListener(trigger)
        b.btnFeatureAction.setOnClickListener(trigger)
    }

    private fun weatherIconFor(cond: String): Int = when {
        cond.contains("Rain", true) || cond.contains("Storm", true) -> R.drawable.ic_rainy_rounded
        cond.contains("Clear", true) -> R.drawable.ic_sunny_rounded
        else -> R.drawable.ic_partly_cloudy_rounded
    }
}
