package com.kyle.kitchenhub.ui.setup

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kyle.kitchenhub.MainActivity
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.service.wakeword.OpenWakeWordEngine

class SetupWizardActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var stepTitle: TextView
    private lateinit var stepBody: LinearLayout
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private var wakeWordEngine: OpenWakeWordEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(40, 28, 40, 28)
        }
        stepTitle = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        stepBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(stepBody)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }
        btnBack = makePillButton("Back", "#2B2F3A") {
            if (step > 0) { step--; renderStep() }
        }
        btnNext = makePillButton("Next", "#A8C7FA", Color.BLACK) {
            if (step < 3) {
                step++
                renderStep()
            } else {
                wakeWordEngine?.stop()
                ConfigRepository.update(this) { it.copy(setupCompleted = true) }
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
        nav.addView(btnBack)
        nav.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        nav.addView(btnNext)

        root.addView(stepTitle)
        root.addView(scroll)
        root.addView(nav)
        setContentView(root)
        renderStep()
    }

    override fun onResume() {
        super.onResume()
        if (step == 1) renderStep()
    }

    override fun onDestroy() {
        wakeWordEngine?.stop()
        super.onDestroy()
    }

    private fun renderStep() {
        stepBody.removeAllViews()
        btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = if (step == 3) "Finish Setup" else "Next"

        when (step) {
            0 -> renderEnvironmentStep()
            1 -> renderPermissionsStep()
            2 -> renderLauncherStep()
            3 -> renderVoiceTestStep()
        }
    }

    private fun renderEnvironmentStep() {
        stepTitle.text = "Step 1 of 4 — Hardware & Environment"
        val (availRam, totalRam) = DeviceEnvironment.getMemoryMetrics(this)
        val (availDisk, totalDisk) = DeviceEnvironment.getStorageMetrics()
        addCard("Device Mode", if (DeviceEnvironment.isEmulator) "Desktop AVD Emulator (1:1 Parity)" else "Echo Show 8 (crown)")
        addCard("Root Access (su)", if (DeviceEnvironment.hasRoot) "Available" else "Not available (AVD / Unrooted fallback)")
        addCard("Memory & Storage", "RAM: ${availRam}MB / ${totalRam}MB free  •  Storage: ${availDisk}MB / ${totalDisk}MB free")
        addCard("Far-Field Mic Profile", if (DeviceEnvironment.hasFarFieldConfig()) "Detected AFE.cfg" else "Standard AudioRecord (AVD/Generic)")
    }

    private fun renderPermissionsStep() {
        stepTitle.text = "Step 2 of 4 — Permissions Hub"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasWriteSettings = Settings.System.canWrite(this)
        val hasDnd = nm.isNotificationPolicyAccessGranted
        val hasBattery = pm.isIgnoringBatteryOptimizations(packageName)

        addPermRow("Microphone (RECORD_AUDIO)", hasMic) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        addPermRow("Overlay Pill (SYSTEM_ALERT_WINDOW)", hasOverlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addPermRow("Brightness Control (WRITE_SETTINGS)", hasWriteSettings) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        addPermRow("Quiet DND Policy", hasDnd) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        addPermRow("Media Listener (YouTube Music)", isNotificationListenerEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addPermRow("Battery Optimization Exempt", hasBattery) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        stepBody.addView(makePillButton("Show ADB Grant Commands", "#2B2F3A") {
            AlertDialog.Builder(this)
                .setTitle("ADB Grant Commands")
                .setMessage(
                    "adb shell pm grant $packageName android.permission.RECORD_AUDIO\n" +
                        "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n" +
                        "adb shell appops set $packageName WRITE_SETTINGS allow\n" +
                        "adb shell dumpsys deviceidle whitelist +$packageName"
                )
                .setPositiveButton("OK", null).show()
        })
    }

    private fun renderLauncherStep() {
        stepTitle.text = "Step 3 of 4 — Launcher & Display Setup"
        val cfg = ConfigRepository.get(this)
        addCard("Idle StandBy Clock Timeout", "${cfg.idleTimeoutSec} seconds")
        stepBody.addView(makePillButton("Set KitchenHub as Default Home", "#2B2F3A") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        })
        if (!DeviceEnvironment.isEmulator && DeviceEnvironment.hasRoot) {
            stepBody.addView(makePillButton("Disable System Sleep & Lockscreen (Root)", "#2B2F3A") {
                DeviceEnvironment.execRoot("settings put system screen_off_timeout 2147483647 && settings put secure lockscreen.disabled 1")
            })
        }
    }

    private fun renderVoiceTestStep() {
        stepTitle.text = "Step 4 of 4 — Personalize & Wake-Word Test"
        val cfg = ConfigRepository.get(this)
        addCard("Location & Weather", "${cfg.locationName} (${cfg.latitude}, ${cfg.longitude})")
        addCard("Default StandBy Face", "${cfg.clockFace}  •  Wake Word: ${cfg.wakeWordModel}")

        val scoreLabel = TextView(this).apply {
            text = "Tap 'Start Voice Test' and say \"Hey Jarvis\""
            textSize = 18f
            setTextColor(Color.parseColor("#A8C7FA"))
            setPadding(0, 12, 0, 8)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        stepBody.addView(scoreLabel)
        stepBody.addView(bar)
        stepBody.addView(makePillButton("Start Voice Test", "#30D158", Color.BLACK) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                scoreLabel.text = "Grant RECORD_AUDIO first"
                return@makePillButton
            }
            val engine = wakeWordEngine ?: OpenWakeWordEngine(this).also { wakeWordEngine = it }
            engine.start(
                onScore = { score, _ ->
                    runOnUiThread {
                        bar.progress = (score * 100).toInt().coerceIn(0, 100)
                    }
                },
                onDetected = { score ->
                    runOnUiThread {
                        TactileAudioEngine.playNoteChime()
                        scoreLabel.text = "Detected! Confidence: ${(score * 100).toInt()}%"
                    }
                }
            )
        })
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun addCard(title: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E222B"))
                cornerRadius = 24f * resources.displayMetrics.density
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
        }
        box.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(Color.parseColor("#9AA0A6")) })
        box.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(Color.WHITE) })
        stepBody.addView(box)
    }

    private fun addPermRow(label: String, granted: Boolean, onFix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E222B"))
                cornerRadius = 20f * resources.displayMetrics.density
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        row.addView(TextView(this).apply {
            text = if (granted) "[OK] $label" else "[!] $label"
            textSize = 16f
            setTextColor(Color.parseColor(if (granted) "#30D158" else "#FFD60A"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (!granted) row.addView(makePillButton("Grant", "#A8C7FA", Color.BLACK, onFix))
        stepBody.addView(row)
    }

    private fun makePillButton(label: String, bgHex: String, fgColor: Int = Color.WHITE, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            setTextColor(fgColor)
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 999f
            }
            TactileAudioEngine.attachSpringPress(this) { onClick() }
        }
    }
}
