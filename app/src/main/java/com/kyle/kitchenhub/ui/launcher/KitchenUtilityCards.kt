package com.kyle.kitchenhub.ui.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kyle.kitchenhub.R
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.BentoRippleHostLayout
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.service.audio.MicArbiter
import com.kyle.kitchenhub.service.audio.MicState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builders for Kitchen-First Utilities (Shopping List, Kitchen Converter),
 * Household Contacts (Meet), and Inline Smart Home Controls.
 */
object KitchenUtilityCards {

    private const val PREFS_SHOP = "kitchenhub_shopping"
    private const val PREFS_HOME = "kitchenhub_smarthome"

    private fun wrapCard(
        context: Context,
        widthDp: Int = 340,
        heightMatchParent: Boolean = true,
        builder: (LinearLayout) -> Unit
    ): View {
        val d = context.resources.displayMetrics.density
        val card = MaterialCardView(context).apply {
            layoutParams = if (heightMatchParent) {
                ViewGroup.MarginLayoutParams((widthDp * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (16 * d).toInt()
                }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            radius = 32f * d
            cardElevation = 4f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#181D27"))
        }
        val rippleHost = BentoRippleHostLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        }
        builder(content)
        rippleHost.addView(content)
        card.addView(rippleHost)
        return card
    }

    fun buildByKey(
        context: Context,
        key: String,
        onEnterClock: () -> Unit,
        onRefreshBanner: () -> Unit,
        inStack: Boolean = false
    ): View = when (key) {
        "SHOPPING_LIST" -> buildShoppingListCard(context, inStack)
        "KITCHEN_CONVERTER" -> buildKitchenConverterCard(context, inStack)
        "HOUSEHOLD_CONTACTS" -> buildHouseholdContactsCard(context, inStack)
        "COOKING_MODE" -> buildQuickActionCard(
            context, "Cooking Mode", "Boost brightness & keep screen awake", "Activate", inStack
        ) {
            if (android.provider.Settings.System.canWrite(context)) {
                android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 215)
            }
            Toast.makeText(context, "Cooking Mode enabled", Toast.LENGTH_SHORT).show()
        }
        "BEDTIME_MODE" -> buildQuickActionCard(
            context, "Bedtime Mode", "Lower speaker volume & open Night Clock", "Start Bedtime", inStack
        ) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * 0.2f).toInt(), 0)
            onEnterClock()
        }
        else -> buildSmartHomeCard(context, inStack)
    }

    fun buildStackedPair(
        context: Context,
        topKey: String,
        bottomKey: String,
        widthDp: Int,
        onEnterClock: () -> Unit,
        onRefreshBanner: () -> Unit
    ): View {
        val d = context.resources.displayMetrics.density
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams((widthDp * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = (16 * d).toInt()
            }
        }
        val top = buildByKey(context, topKey, onEnterClock, onRefreshBanner, inStack = true).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (16 * d).toInt()
        }
        val bot = buildByKey(context, bottomKey, onEnterClock, onRefreshBanner, inStack = true)
        col.addView(top)
        col.addView(bot)
        return col
    }

    private fun buildQuickActionCard(
        context: Context,
        title: String,
        subtitle: String,
        btnText: String,
        inStack: Boolean,
        onClick: () -> Unit
    ): View = wrapCard(context, 360, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)
        root.addView(TextView(context).apply {
            text = title; typeface = tf; paint.isFakeBoldText = true; textSize = 19f
            setTextColor(Color.parseColor("#EEF3FC"))
        })
        root.addView(TextView(context).apply {
            text = subtitle; typeface = tf; textSize = 13.5f
            setTextColor(Color.parseColor("#9EB0CE"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * d).toInt() }
        })
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        root.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = btnText; isAllCaps = false; cornerRadius = (999 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, (44 * d).toInt())
            setOnClickListener { TactileAudioEngine.playClick(); onClick() }
        })
    }

    fun buildShoppingListCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 360, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)
        val prefs = context.getSharedPreferences(PREFS_SHOP, Context.MODE_PRIVATE)

        data class ShopItem(val text: String, var done: Boolean)

        fun loadItems(): MutableList<ShopItem> {
            val raw = prefs.getString("items", null)
            if (raw.isNullOrBlank()) return mutableListOf()
            val out = mutableListOf<ShopItem>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(ShopItem(o.optString("t"), o.optBoolean("d")))
                }
            }
            return out
        }

        fun saveItems(list: List<ShopItem>) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.text).put("d", it.done)) }
            prefs.edit().putString("items", arr.toString()).apply()
        }

        val items = loadItems()
        val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderList() {
            listCol.removeAllViews()
            if (items.isEmpty()) {
                listCol.addView(TextView(context).apply {
                    text = "Nothing on the list yet. Add an item or tap Speak."
                    typeface = tf
                    textSize = 14f
                    setTextColor(Color.parseColor("#8B95A7"))
                    setPadding(0, (8 * d).toInt(), 0, 0)
                })
            }
            items.forEachIndexed { idx, item ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                    setOnClickListener {
                        TactileAudioEngine.playClick()
                        item.done = !item.done
                        saveItems(items)
                        renderList()
                    }
                }
                val dot = TextView(context).apply {
                    text = if (item.done) "✓" else "○"
                    textSize = 17f
                    setTextColor(Color.parseColor(if (item.done) "#A8C7FA" else "#8B95A7"))
                    setPadding(0, 0, (10 * d).toInt(), 0)
                }
                val label = TextView(context).apply {
                    text = item.text
                    typeface = tf
                    textSize = 15.5f
                    setTextColor(Color.parseColor(if (item.done) "#748094" else "#EEF3FC"))
                    if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val del = TextView(context).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(Color.parseColor("#8B95A7"))
                    setPadding((8 * d).toInt(), 0, (4 * d).toInt(), 0)
                    setOnClickListener {
                        TactileAudioEngine.playClick()
                        items.removeAt(idx)
                        saveItems(items)
                        renderList()
                    }
                }
                row.addView(dot); row.addView(label); row.addView(del)
                listCol.addView(row)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Shopping List"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 20f
            setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val btnVoice = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "Speak"
            isAllCaps = false
            textSize = 12.5f
            cornerRadius = (999 * d).toInt()
            setOnClickListener {
                TactileAudioEngine.playClick()
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Toast.makeText(context, "Speech service unavailable", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                CoroutineScope(Dispatchers.Main).launch {
                    MicArbiter.enterState(MicState.STICKY_NOTE_DICTATING)
                    delay(160L)
                    val sr = SpeechRecognizer.createSpeechRecognizer(context)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { text = "Listening..." }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { text = "Speak" }
                        override fun onError(error: Int) {
                            text = "Speak"
                            sr.destroy()
                            CoroutineScope(Dispatchers.Main).launch { MicArbiter.exitStateToWakeWord() }
                        }
                        override fun onResults(results: Bundle?) {
                            text = "Speak"
                            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                            if (!spoken.isNullOrEmpty()) {
                                items.add(0, ShopItem(spoken, false))
                                saveItems(items)
                                renderList()
                            }
                            sr.destroy()
                            CoroutineScope(Dispatchers.Main).launch { MicArbiter.exitStateToWakeWord() }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    sr.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    )
                }
            }
        }
        header.addView(btnVoice)
        root.addView(header)

        val addRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt() }
        }
        val input = EditText(context).apply {
            hint = "Add item..."
            setHintTextColor(Color.parseColor("#748094"))
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnAdd = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "+"
            textSize = 16f
            minWidth = (44 * d).toInt()
            cornerRadius = (999 * d).toInt()
            setOnClickListener {
                TactileAudioEngine.playClick()
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    items.add(0, ShopItem(t, false))
                    saveItems(items)
                    input.setText("")
                    renderList()
                }
            }
        }
        addRow.addView(input); addRow.addView(btnAdd)
        root.addView(addRow)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(listCol)
        }
        root.addView(scroll)
        renderList()
    }

    fun buildKitchenConverterCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 360, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        root.addView(TextView(context).apply {
            text = "Kitchen Converter"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 20f
            setTextColor(Color.parseColor("#EEF3FC"))
        })

        // Oven temperature: °C <-> °F <-> Gas Mark
        var tempC = 180
        val tvTemp = TextView(context).apply {
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 19f
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#A8C7FA"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * d).toInt() }
        }
        fun refreshTemp() {
            val f = ((tempC * 9f / 5f) + 32f).roundToInt()
            val gas = when {
                tempC <= 135 -> "Gas 1"
                tempC <= 150 -> "Gas 2"
                tempC <= 165 -> "Gas 3"
                tempC <= 180 -> "Gas 4"
                tempC <= 190 -> "Gas 5"
                tempC <= 205 -> "Gas 6"
                tempC <= 220 -> "Gas 7"
                else -> "Gas 8"
            }
            tvTemp.text = "${tempC}°C  •  ${f}°F  •  $gas"
        }
        refreshTemp()
        root.addView(tvTemp)

        val tempRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        }
        listOf(-10, +10).forEach { delta ->
            tempRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = if (delta < 0) "-10°C" else "+10°C"
                isAllCaps = false
                cornerRadius = (999 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (delta < 0) marginEnd = (8 * d).toInt()
                }
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    tempC = (tempC + delta).coerceIn(100, 260)
                    refreshTemp()
                }
            })
        }
        root.addView(tempRow)

        // Weight / Volume: g <-> oz <-> cups
        var grams = 250
        val tvWeight = TextView(context).apply {
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 19f
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#C4EDD7"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * d).toInt() }
        }
        fun refreshWeight() {
            val oz = String.format(Locale.US, "%.1f", grams / 28.3495f)
            val cups = String.format(Locale.US, "%.2f", grams / 240f)
            tvWeight.text = "${grams}g  •  ${oz}oz  •  ${cups} cups"
        }
        refreshWeight()
        root.addView(tvWeight)

        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        }
        listOf(-50, +50).forEach { delta ->
            weightRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = if (delta < 0) "-50g" else "+50g"
                isAllCaps = false
                cornerRadius = (999 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (delta < 0) marginEnd = (8 * d).toInt()
                }
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    grams = (grams + delta).coerceIn(25, 2000)
                    refreshWeight()
                }
            })
        }
        root.addView(weightRow)
    }

    fun buildHouseholdContactsCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 360, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        root.addView(TextView(context).apply {
            text = "Household"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 20f
            setTextColor(Color.parseColor("#EEF3FC"))
        })
        root.addView(TextView(context).apply {
            text = "One-tap Google Meet call"
            typeface = tf
            textSize = 13.5f
            setTextColor(Color.parseColor("#9EB0CE"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * d).toInt() }
        })

        val contacts = listOf(
            Triple("Kyle", "#A8C7FA", "K"),
            Triple("Mum", "#F2B8B5", "M"),
            Triple("Dad", "#C4EDD7", "D"),
            Triple("Home", "#FFE082", "H")
        )
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        contacts.forEach { (name, hex, initial) ->
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    CoroutineScope(Dispatchers.Main).launch {
                        MicArbiter.enterState(MicState.STICKY_NOTE_DICTATING)
                        val meet = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.tachyon")
                        if (meet != null) {
                            context.startActivity(meet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            Toast.makeText(context, "Calling $name on Meet (app not installed)", Toast.LENGTH_SHORT).show()
                            MicArbiter.exitStateToWakeWord()
                        }
                    }
                }
            }
            val avatar = TextView(context).apply {
                text = initial
                typeface = tf
                paint.isFakeBoldText = true
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#111520"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                }
                val sz = (58 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            }
            val lbl = TextView(context).apply {
                text = name
                typeface = tf
                textSize = 13.5f
                setTextColor(Color.parseColor("#EEF3FC"))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * d).toInt() }
            }
            col.addView(avatar); col.addView(lbl)
            grid.addView(col)
        }
        root.addView(grid)
    }

    fun buildSmartHomeCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 380, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        root.addView(TextView(context).apply {
            text = "Home controls"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 20f
            setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() }
        })

        val devicesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(devicesContainer)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(footer)

        fun deviceRow(dev: com.kyle.kitchenhub.data.SmartHomeDevice): View {
            return if (dev.kind == "climate") {
                var targetC = dev.targetTempC
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
                    val tvTherm = TextView(context).apply {
                        text = "${dev.name} • ${targetC}°C"
                        typeface = tf
                        paint.isFakeBoldText = true
                        textSize = 15.5f
                        fontFeatureSettings = "tnum"
                        setTextColor(Color.parseColor("#A8C7FA"))
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                    addView(tvTherm)
                    listOf(-1, +1).forEach { delta ->
                        addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                            text = if (delta < 0) "-" else "+"
                            minWidth = (44 * d).toInt()
                            cornerRadius = (999 * d).toInt()
                            layoutParams = LinearLayout.LayoutParams(-2, (40 * d).toInt()).apply {
                                if (delta < 0) marginEnd = (6 * d).toInt()
                            }
                            setOnClickListener {
                                TactileAudioEngine.playClick()
                                targetC = (targetC + delta).coerceIn(15, 28)
                                tvTherm.text = "${dev.name} • ${targetC}°C"
                                CoroutineScope(Dispatchers.Main).launch {
                                    com.kyle.kitchenhub.data.SmartHomeRepository.setThermostat(context, dev, targetC)
                                }
                            }
                        })
                    }
                }
            } else {
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                    addView(TextView(context).apply {
                        text = dev.name
                        typeface = tf
                        textSize = 15.5f
                        setTextColor(Color.parseColor("#EEF3FC"))
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(MaterialSwitch(context).apply {
                        isChecked = dev.isOn
                        setOnCheckedChangeListener { _, checked ->
                            TactileAudioEngine.playClick()
                            CoroutineScope(Dispatchers.Main).launch {
                                com.kyle.kitchenhub.data.SmartHomeRepository.toggleDevice(context, dev, checked)
                            }
                        }
                    })
                }
            }
        }

        fun load() {
            CoroutineScope(Dispatchers.Main).launch {
                val devices = com.kyle.kitchenhub.data.SmartHomeRepository.fetchDevices(context)
                devicesContainer.removeAllViews()
                footer.removeAllViews()
                if (devices.isEmpty()) {
                    devicesContainer.addView(TextView(context).apply {
                        text = "Connect Google Home to control your lights, plugs and thermostats."
                        typeface = tf
                        textSize = 14f
                        setTextColor(Color.parseColor("#9EB0CE"))
                    })
                    footer.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                        text = "Connect Google Home"
                        isAllCaps = false
                        cornerRadius = (999 * d).toInt()
                        layoutParams = LinearLayout.LayoutParams(-1, (48 * d).toInt())
                        setOnClickListener {
                            TactileAudioEngine.playClick()
                            CoroutineScope(Dispatchers.Main).launch {
                                val msg = com.kyle.kitchenhub.data.GoogleHomeBridge.requestPermissions(context)
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                load()
                            }
                        }
                    })
                } else {
                    // Group by device type: lights, plugs/switches, climate
                    listOf("light", "switch", "climate").forEach { kind ->
                        devices.filter { it.kind == kind }.forEach { devicesContainer.addView(deviceRow(it)) }
                    }
                    footer.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                        text = "Open Google Home"
                        isAllCaps = false
                        cornerRadius = (999 * d).toInt()
                        layoutParams = LinearLayout.LayoutParams(-1, (48 * d).toInt())
                        setOnClickListener {
                            TactileAudioEngine.playClick()
                            val home = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
                            if (home != null) context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            else Toast.makeText(context, "Google Home not installed", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { load() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}
