package com.kyle.kitchenhub.ui.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.core.ui.PixelSparkleView
import com.kyle.kitchenhub.ui.settings.SettingsActivity

class QuickSettingsShade(
    context: Context,
    sparkle: PixelSparkleView,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        setPadding((40 * d).toInt(), (28 * d).toInt(), (40 * d).toInt(), (28 * d).toInt())
        visibility = View.GONE
        elevation = 24f * d
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EC141822"))
            setStroke((1 * d).toInt(), Color.parseColor("#28FFFFFF"))
            val r = 28f * d
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        addView(TextView(context).apply {
            text = "Quick Settings"
            typeface = FontCache.plusJakarta(context)
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (16 * d).toInt())
        })

        val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val musicSlider = FrostedSliderView(context).apply {
            label = "Music Volume"
            sparkleOverlay = sparkle
            progress = (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / musicMax
            onChanged = { pct -> am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * musicMax) / 100, 0) }
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() }
        }
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val alarmSlider = FrostedSliderView(context).apply {
            label = "Alarm & Voice Volume"
            sparkleOverlay = sparkle
            progress = (am.getStreamVolume(AudioManager.STREAM_ALARM) * 100) / alarmMax
            onChanged = { pct -> am.setStreamVolume(AudioManager.STREAM_ALARM, (pct * alarmMax) / 100, 0) }
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() }
        }
        val curBri = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(165)
        val briSlider = FrostedSliderView(context).apply {
            label = "Screen Brightness"
            sparkleOverlay = sparkle
            progress = (curBri * 100) / 255
            onChanged = { pct ->
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255) / 100)
                }
            }
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = (18 * d).toInt() }
        }
        addView(musicSlider); addView(alarmSlider); addView(briSlider)

        val btnRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        fun addBtn(lbl: String, bgHex: String = "#252B3A", action: () -> Unit) {
            btnRow.addView(Button(context).apply {
                text = lbl
                isAllCaps = false
                typeface = FontCache.plusJakarta(context)
                textSize = 14f
                setTextColor(Color.WHITE)
                minWidth = 0; minimumWidth = 0
                minHeight = (50 * d).toInt()
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    setStroke((1 * d).toInt(), Color.parseColor("#24FFFFFF"))
                    cornerRadius = 999f
                }
                layoutParams = LayoutParams(0, -2, 1f).apply { marginEnd = (10 * d).toInt() }
                TactileAudioEngine.attachSpringPress(this) { action() }
            })
        }
        addBtn("Quiet DND") {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
        addBtn("Bluetooth") {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        addBtn("Sleep") {
            DeviceEnvironment.sleepScreen(context) { onClose() }
        }
        addBtn("Settings") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        addBtn("Close", "#333A4D") { onClose() }
        addView(btnRow)
    }
}
