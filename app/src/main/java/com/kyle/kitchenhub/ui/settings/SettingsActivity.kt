package com.kyle.kitchenhub.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import com.kyle.kitchenhub.core.ui.BentoRippleHostLayout
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.core.ui.FrostedBackdropEngine
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.ui.setup.SetupWizardActivity
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var detailPane: LinearLayout
    private val navButtons = mutableListOf<MaterialButton>()
    private var activeTab = 0
    private var versionTapCount = 0

    private val categories = listOf(
        "Appearance & Expressive",
        "StandBy Clock & Display",
        "Voice, Wake Word & Audio",
        "Timers, Notes & Macros",
        "Google & UK Ecosystem",
        "System, Diagnostics & Backup"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.kyle.kitchenhub.data.GoogleHomeBridge.registerActivity(this)
        val d = resources.displayMetrics.density
        val tf = FontCache.plusJakarta(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }

        // Left Bento Navigation Card
        val railCard = MaterialCardView(this).apply {
            radius = 32f * d
            cardElevation = 4f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#151923"))
            layoutParams = LinearLayout.LayoutParams((310 * d).toInt(), -1).apply { marginEnd = (18 * d).toInt() }
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
        }
        rail.addView(TextView(this).apply {
            text = "Settings"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 24f
            setTextColor(Color.parseColor("#EEF3FC"))
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (16 * d).toInt())
        })
        categories.forEachIndexed { idx, name ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = name
                typeface = tf
                textSize = 14.5f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                cornerRadius = (999 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(-1, (48 * d).toInt()).apply { bottomMargin = (8 * d).toInt() }
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    activeTab = idx
                    updateNavSelection()
                    renderCategory()
                }
            }
            navButtons.add(btn)
            rail.addView(btn)
        }
        rail.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        rail.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close Settings"
            typeface = tf
            isAllCaps = false
            cornerRadius = (999 * d).toInt()
            setTextColor(Color.parseColor("#A8C7FA"))
            layoutParams = LinearLayout.LayoutParams(-1, (48 * d).toInt())
            setOnClickListener { TactileAudioEngine.playClick(); finish() }
        })
        railCard.addView(rail)

        // Right Bento Detail Pane
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        detailPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(detailPane)

        root.addView(railCard)
        root.addView(scroll)
        setContentView(root)
        updateNavSelection()
        renderCategory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun updateNavSelection() {
        navButtons.forEachIndexed { i, btn ->
            val sel = i == activeTab
            btn.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (sel) "#A8C7FA" else "#1E2432")
            )
            btn.setTextColor(Color.parseColor(if (sel) "#0B192E" else "#E2E6EE"))
        }
    }

    private fun renderCategory() {
        detailPane.removeAllViews()
        val d = resources.displayMetrics.density
        val tf = FontCache.plusJakarta(this)
        val cfg = ConfigRepository.get(this)

        detailPane.addView(TextView(this).apply {
            text = categories[activeTab]
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 24f
            setTextColor(Color.parseColor("#EEF3FC"))
            setPadding((6 * d).toInt(), (4 * d).toInt(), 0, (16 * d).toInt())
        })

        when (activeTab) {
            0 -> {
                addSlider("Frosted Blur Radius", cfg.blurRadius, 4, 24) { v ->
                    ConfigRepository.update(this) { it.copy(blurRadius = v) }
                }
                addToggle("Reduce Transparency (Solid Surfaces)", cfg.reduceTransparency) { b ->
                    ConfigRepository.update(this) { it.copy(reduceTransparency = b) }
                }
                addToggle("Pixel Sparkle Dot Bursts", cfg.sparklesEnabled) { b ->
                    ConfigRepository.update(this) { it.copy(sparklesEnabled = b) }
                }
                addToggle("Tactile Audio Clicks", cfg.tactileAudioEnabled) { b ->
                    ConfigRepository.update(this) { it.copy(tactileAudioEnabled = b) }
                }
            }
            1 -> {
                addCycle("Default Clock Face", listOf("XMORPH", "FLOAT", "SOLAR", "DIGITAL", "ANALOG", "MINIMAL", "SPLIT", "PHOTOS"), cfg.clockFace) { next ->
                    ConfigRepository.update(this) { it.copy(clockFace = next) }
                }
                addCycle("Idle Clock Timeout (sec)", listOf("30", "60", "120", "300", "0"), cfg.idleTimeoutSec.toString()) { next ->
                    ConfigRepository.update(this) { it.copy(idleTimeoutSec = next.toInt()) }
                }
                addSlider("Pixel Shift Amplitude (px)", cfg.pixelShiftPx, 4, 10) { v ->
                    ConfigRepository.update(this) { it.copy(pixelShiftPx = v) }
                }
                addSlider("Night Mode Start Hour", cfg.nightModeStartHour, 18, 23) { v ->
                    ConfigRepository.update(this) { it.copy(nightModeStartHour = v) }
                }
                addSlider("Night Mode End Hour", cfg.nightModeEndHour, 4, 10) { v ->
                    ConfigRepository.update(this) { it.copy(nightModeEndHour = v) }
                }
            }
            2 -> {
                addCycle("Active ONNX Wake-Word", listOf("hey_google.onnx", "hey_jarvis_v0.1.onnx"), cfg.wakeWordModel) { next ->
                    ConfigRepository.update(this) { it.copy(wakeWordModel = next) }
                }
                addSlider("Wake-Word Threshold (%)", (cfg.wakeWordThreshold * 100).toInt(), 15, 90) { v ->
                    ConfigRepository.update(this) { it.copy(wakeWordThreshold = v / 100f) }
                }
                addSlider("Music Ducking on Wake (%)", cfg.bargeInDuckPercent, 10, 80) { v ->
                    ConfigRepository.update(this) { it.copy(bargeInDuckPercent = v) }
                }
                addToggle("Nighttime Volume Auto-Cap", cfg.nightVolumeCapEnabled) { b ->
                    ConfigRepository.update(this) { it.copy(nightVolumeCapEnabled = b) }
                }
            }
            3 -> {
                addCycle("Quick Timer Presets (min)", listOf("5,10,15,20", "1,3,5,10", "5,15,30,45", "10,20,30,60"), cfg.quickTimerMinutes) { next ->
                    ConfigRepository.update(this) { it.copy(quickTimerMinutes = next) }
                }
                addInfo("Household Contacts", cfg.householdContacts)
                addToggle("Wake-Wordless Alarm Stop ('Stop'/'Snooze')", cfg.wakeWordlessAlarmStop) { b ->
                    ConfigRepository.update(this) { it.copy(wakeWordlessAlarmStop = b) }
                }
                addCycle("Sticky Note Font", listOf("PATRICK_HAND", "CAVEAT_CURSIVE"), cfg.stickyFont) { next ->
                    ConfigRepository.update(this) { it.copy(stickyFont = next) }
                }
                addToggle("Local HTTP Sticky Receiver (Port ${cfg.stickyHttpPort})", cfg.stickyHttpEnabled) { b ->
                    ConfigRepository.update(this) { it.copy(stickyHttpEnabled = b) }
                }
                addSlider("Lock Screen Notes Shown", cfg.lockScreenMaxNotes, 1, 5) { v ->
                    ConfigRepository.update(this) { it.copy(lockScreenMaxNotes = v) }
                }
            }
            4 -> {
                addInfo("Open-Meteo Location", "${cfg.locationName} (${cfg.latitude}, ${cfg.longitude})")
                addToggle("Use Fahrenheit (°F)", cfg.useFahrenheit) { b ->
                    ConfigRepository.update(this) { it.copy(useFahrenheit = b) }
                }
                addCycle("Smart Home Backend", listOf("GOOGLE_HOME", "HOME_ASSISTANT", "LOCAL"), cfg.smartHomeBackend) { next ->
                    ConfigRepository.update(this) { it.copy(smartHomeBackend = next) }
                }
                addInfo("Google Project ID", cfg.googleProjectId.ifBlank { "Not set" })
                addInfo("Google OAuth Client ID", cfg.googleOAuthClientId.ifBlank { "Not set" })
                addActionRow("Connect Google Home (OAuth)", "Edit Config",
                    onLeft = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            val msg = com.kyle.kitchenhub.data.GoogleHomeBridge.requestPermissions(this@SettingsActivity)
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                            renderCategory()
                        }
                    },
                    onRight = { showEditSmartHomeDialog() }
                )
                addInfo("Photo Frame Folder", cfg.photoFolderPath)
                addInfo("iCal / RTSP", if (cfg.icalUrl.isBlank()) "Not configured" else cfg.icalUrl)
            }
            5 -> {
                val (availRam, totalRam) = DeviceEnvironment.getMemoryMetrics(this)
                val (availDisk, totalDisk) = DeviceEnvironment.getStorageMetrics()
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
                addInfo("Health", "RAM: ${availRam}/${totalRam}MB  •  Disk: ${availDisk}/${totalDisk}MB  •  IP: $ip")

                addActionRow("One-Tap RAM / Backdrop Trim", "Check for Update",
                    onLeft = {
                        FrostedBackdropEngine.trim()
                        System.gc()
                        Toast.makeText(this, "Caches trimmed", Toast.LENGTH_SHORT).show()
                        renderCategory()
                    },
                    onRight = {
                        Toast.makeText(this, "Checking for KitchenHub OTA update...", Toast.LENGTH_SHORT).show()
                        Thread {
                            val urls = listOf(
                                "https://api.github.com/repos/Dxrmy/kitchenhub/releases/latest"
                            )
                            var foundTag: String? = null
                            for (u in urls) {
                                runCatching {
                                    val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 2500
                                    conn.readTimeout = 2500
                                    if (conn.responseCode in 200..299) {
                                        val obj = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                                        foundTag = obj.optString("tag_name").ifBlank { obj.optString("version") }
                                    }
                                    conn.disconnect()
                                }
                                if (!foundTag.isNullOrBlank()) break
                            }
                            runOnUiThread {
                                val current = com.kyle.kitchenhub.BuildConfig.VERSION_NAME
                                val latest = foundTag?.trim()?.removePrefix("v").orEmpty()
                                val msg = when {
                                    latest.isEmpty() -> "No release found on GitHub yet (current v$current)"
                                    latest == current -> "Up to date (v$current)"
                                    else -> "Update available: v$latest (current v$current)"
                                }
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                if (latest.isNotEmpty() && latest != current) {
                                    runCatching {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Dxrmy/kitchenhub/releases/latest"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            }
                        }.start()
                    }
                )
                addActionRow("Export JSON Backup", "Import JSON Backup",
                    onLeft = {
                        val f = ConfigRepository.exportToFile(this)
                        Toast.makeText(this, "Exported: ${f.name}", Toast.LENGTH_SHORT).show()
                    },
                    onRight = {
                        val f = File(getExternalFilesDir(null) ?: filesDir, "kitchenhub-backup.json")
                        val ok = ConfigRepository.importFromFile(this, f)
                        Toast.makeText(this, if (ok) "Imported!" else "No backup found", Toast.LENGTH_SHORT).show()
                        renderCategory()
                    }
                )
                addActionButton("Re-run Setup Wizard") {
                    startActivity(Intent(this, SetupWizardActivity::class.java))
                }

                if (DeviceEnvironment.LocalTestHarness.isActive()) {
                    addToggle("[AVD] Mock Now Playing", DeviceEnvironment.LocalTestHarness.mockNowPlayingEnabled) {
                        DeviceEnvironment.LocalTestHarness.mockNowPlayingEnabled = it
                    }
                    addToggle("[AVD] Instant Night Mode Preview", DeviceEnvironment.LocalTestHarness.mockNightModePreview) {
                        DeviceEnvironment.LocalTestHarness.mockNightModePreview = it
                    }
                    addToggle("[AVD] Simulated Hardware Mute", DeviceEnvironment.LocalTestHarness.mockHardwareMuted) {
                        DeviceEnvironment.LocalTestHarness.mockHardwareMuted = it
                    }
                }

                detailPane.addView(TextView(this).apply {
                    text = "KitchenHub v${com.kyle.kitchenhub.BuildConfig.VERSION_NAME} (Tap 5x for Android Settings)"
                    typeface = tf
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#8B95A7"))
                    setPadding((8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                    setOnClickListener {
                        if (++versionTapCount >= 5) {
                            versionTapCount = 0
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                })
            }
        }
    }

    private fun wrapBentoRow(contentBuilder: (LinearLayout) -> Unit) {
        val d = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 24f * d
            cardElevation = 2f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#181D27"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() }
        }
        val ripple = BentoRippleHostLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
        }
        contentBuilder(box)
        ripple.addView(box)
        card.addView(ripple)
        detailPane.addView(card)
    }

    private fun addInfo(title: String, value: String) = wrapBentoRow { box ->
        val tf = FontCache.plusJakarta(this)
        box.addView(TextView(this).apply { text = title; typeface = tf; textSize = 13f; setTextColor(Color.parseColor("#9EB0CE")) })
        box.addView(TextView(this).apply { text = value; typeface = tf; textSize = 16.5f; setTextColor(Color.parseColor("#EEF3FC")) })
    }

    private fun addToggle(title: String, initial: Boolean, onChange: (Boolean) -> Unit) = wrapBentoRow { box ->
        val tf = FontCache.plusJakarta(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title; typeface = tf; textSize = 16.5f; setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(MaterialSwitch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> TactileAudioEngine.playClick(); onChange(checked) }
        })
        box.addView(row)
    }

    private fun addSlider(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) = wrapBentoRow { box ->
        val tf = FontCache.plusJakarta(this)
        val lbl = TextView(this).apply {
            text = "$title: $value"; typeface = tf; textSize = 16f; setTextColor(Color.parseColor("#EEF3FC"))
            fontFeatureSettings = "tnum"
        }
        val slider = Slider(this).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            this.value = value.coerceIn(min, max).toFloat()
            addOnChangeListener { _, v, fromUser ->
                val iv = v.roundToInt()
                lbl.text = "$title: $iv"
                if (fromUser) onChange(iv)
            }
        }
        box.addView(lbl); box.addView(slider)
    }

    private fun addCycle(title: String, options: List<String>, current: String, onSelect: (String) -> Unit) {
        addActionButton("$title: $current") {
            val next = options[(options.indexOf(current) + 1) % options.size]
            onSelect(next)
            renderCategory()
        }
    }

    private fun addActionButton(label: String, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        detailPane.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = label
            typeface = FontCache.plusJakarta(this@SettingsActivity)
            textSize = 15.5f
            isAllCaps = false
            cornerRadius = (999 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, (52 * d).toInt()).apply { bottomMargin = (10 * d).toInt() }
            setOnClickListener { TactileAudioEngine.playClick(); onClick() }
        })
    }

    private fun addActionRow(leftLabel: String, rightLabel: String, onLeft: () -> Unit, onRight: () -> Unit) {
        val d = resources.displayMetrics.density
        val tf = FontCache.plusJakarta(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() }
        }
        listOf(leftLabel to onLeft, rightLabel to onRight).forEachIndexed { i, (lbl, fn) ->
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = lbl; typeface = tf; textSize = 14.5f; isAllCaps = false; cornerRadius = (999 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f).apply {
                    if (i == 0) marginEnd = (10 * d).toInt()
                }
                setOnClickListener { TactileAudioEngine.playClick(); fn() }
            })
        }
        detailPane.addView(row)
    }

    private fun showEditSmartHomeDialog() {
        val cfg = ConfigRepository.get(this)
        val d = resources.displayMetrics.density
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (4 * d).toInt())
        }
        val etProj = android.widget.EditText(this).apply {
            hint = "Google Project ID"
            setText(cfg.googleProjectId)
        }
        val etClient = android.widget.EditText(this).apply {
            hint = "Google OAuth Client ID"
            setText(cfg.googleOAuthClientId)
        }
        val etHaUrl = android.widget.EditText(this).apply {
            hint = "Home Assistant URL (optional)"
            setText(cfg.haBaseUrl)
        }
        val etHaTok = android.widget.EditText(this).apply {
            hint = "Home Assistant Token (optional)"
            setText(cfg.haToken)
        }
        wrap.addView(etProj); wrap.addView(etClient); wrap.addView(etHaUrl); wrap.addView(etHaTok)
        android.app.AlertDialog.Builder(this)
            .setTitle("Smart Home & Google OAuth Config")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                ConfigRepository.update(this) {
                    it.copy(
                        googleProjectId = etProj.text.toString().trim(),
                        googleOAuthClientId = etClient.text.toString().trim(),
                        haBaseUrl = etHaUrl.text.toString().trim(),
                        haToken = etHaTok.text.toString().trim()
                    )
                }
                renderCategory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
