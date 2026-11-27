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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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

    private fun springBounce(view: View, startScale: Float = 0.94f) {
        view.scaleX = startScale
        view.scaleY = startScale
        val spring = SpringForce(1f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        SpringAnimation(view, DynamicAnimation.SCALE_X).apply { this.spring = spring }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y).apply { this.spring = spring }.start()
    }

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
            strokeWidth = (1 * d).toInt()
            strokeColor = Color.parseColor("#2A3242")
            setCardBackgroundColor(Color.parseColor("#171C26"))
        }
        val rippleHost = BentoRippleHostLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            val vPad = if (heightMatchParent) (16 * d).toInt() else (13 * d).toInt()
            setPadding((18 * d).toInt(), vPad, (18 * d).toInt(), vPad)
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
        "TALK_TO_GEMINI" -> buildTalkToGeminiCard(context, inStack)
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

    fun buildTalkToGeminiCard(context: Context, inStack: Boolean = false): View {
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        val card = MaterialCardView(context).apply {
            layoutParams = if (!inStack) {
                ViewGroup.MarginLayoutParams((270 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (16 * d).toInt()
                }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            radius = 32f * d
            cardElevation = 8f * d
            strokeWidth = (1.5f * d).toInt()
            strokeColor = Color.parseColor("#7389A8FF")
            setCardBackgroundColor(Color.TRANSPARENT)
        }

        val rippleHost = BentoRippleHostLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#1B213E"),
                    Color.parseColor("#271C45"),
                    Color.parseColor("#16243D")
                )
            )
        }

        val iconBadgeSize = (86 * d).toInt()
        val haloView = com.kyle.kitchenhub.ui.overlay.M3CookieShapeView(context).apply {
            lobes = 7
            isHaloMode = true
            alpha = 0.22f
            layoutParams = android.widget.FrameLayout.LayoutParams(iconBadgeSize, iconBadgeSize, Gravity.CENTER)
        }
        val cookieView = com.kyle.kitchenhub.ui.overlay.M3CookieShapeView(context).apply {
            lobes = 9
            isHaloMode = false
            layoutParams = android.widget.FrameLayout.LayoutParams((68 * d).toInt(), (68 * d).toInt(), Gravity.CENTER)
        }
        val geminiIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_app_gemini)
            layoutParams = android.widget.FrameLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt(), Gravity.CENTER)
        }
        val orbHost = android.widget.FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(iconBadgeSize, iconBadgeSize).apply {
                bottomMargin = (14 * d).toInt()
            }
            addView(haloView)
            addView(cookieView)
            addView(geminiIcon)
        }

        val statusSubtitle = TextView(context).apply {
            text = "Ask anything, set timers, or control your home"
            typeface = tf
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B8C7E8"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var animRunning = false
        var phase = 0f
        val animStep = object : Runnable {
            override fun run() {
                if (!animRunning) return
                phase = (phase + 0.066f) % 22.0f
                cookieView.rotation = (phase / 22.0f) * 360f
                val s = 1.0f + 0.04f * kotlin.math.sin((phase / 2.8f) * 2.0 * Math.PI).toFloat()
                haloView.scaleX = s
                haloView.scaleY = s
                handler.postDelayed(this, 66L)
            }
        }
        val resetHalo = Runnable {
            haloView.animate().alpha(0.22f).scaleX(1f).scaleY(1f).setDuration(300L).start()
        }

        val muteListener: (Boolean) -> Unit = { muted ->
            handler.post {
                geminiIcon.setColorFilter(if (muted) Color.parseColor("#FF5252") else Color.TRANSPARENT)
                geminiIcon.alpha = if (muted) 0.75f else 1f
                statusSubtitle.text = if (muted) "Microphone muted • Tap to ask" else "Ask anything, set timers, or control your home"
                statusSubtitle.setTextColor(Color.parseColor(if (muted) "#FCA5A5" else "#B8C7E8"))
            }
        }

        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                animRunning = true
                handler.postDelayed(animStep, 66L)
                com.kyle.kitchenhub.ui.overlay.FloatingGeminiPill.addMuteListener(muteListener)
            }
            override fun onViewDetachedFromWindow(v: View) {
                animRunning = false
                handler.removeCallbacks(animStep)
                handler.removeCallbacks(resetHalo)
                com.kyle.kitchenhub.ui.overlay.FloatingGeminiPill.removeMuteListener(muteListener)
            }
        })

        fun triggerGemini() {
            TactileAudioEngine.playClick()
            springBounce(card, 0.96f)
            handler.removeCallbacks(resetHalo)
            geminiIcon.animate().rotationBy(90f).setDuration(260L).start()
            haloView.animate().alpha(0.55f).scaleX(1.12f).scaleY(1.12f).setDuration(220L).start()
            handler.postDelayed(resetHalo, 1600L)
            val duck = com.kyle.kitchenhub.data.ConfigRepository.get(context).bargeInDuckPercent
            MicArbiter.handoffToGemini(context, duck) {
                context.sendBroadcast(
                    Intent(com.kyle.kitchenhub.service.wakeword.KitchenBackgroundService.ACTION_SHOW_MOCK_GEMINI)
                        .setPackage(context.packageName)
                )
            }
        }

        content.addView(TextView(context).apply {
            text = "GEMINI"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#C2D6FF"))
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * d
                setColor(Color.parseColor("#334273"))
                setStroke((1 * d).toInt(), Color.parseColor("#4D9BB8FF"))
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.START
            }
        })

        content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        content.addView(orbHost)

        content.addView(TextView(context).apply {
            text = "Talk to Gemini"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
        })

        content.addView(statusSubtitle)

        content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })

        content.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "Ask Gemini"
            isAllCaps = false
            setIconResource(R.drawable.ic_mic_rounded)
            iconSize = (18 * d).toInt()
            iconPadding = (8 * d).toInt()
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setBackgroundColor(Color.parseColor("#A8C7FA"))
            setTextColor(Color.parseColor("#0D1526"))
            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#0D1526"))
            cornerRadius = (999 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, (48 * d).toInt())
            setOnClickListener { triggerGemini() }
        })

        card.setOnClickListener { triggerGemini() }
        rippleHost.addView(content)
        card.addView(rippleHost)
        return card
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
        root.gravity = Gravity.CENTER_VERTICAL
        val isBedtime = title.contains("Bedtime", true)
        val iconRes = if (isBedtime) R.drawable.ic_bedtime_rounded else R.drawable.ic_brightness_rounded
        val tintHex = if (isBedtime) "#D0BCFF" else "#FCD34D"
        val badgeHex = if (isBedtime) "#2B243D" else "#382F1B"
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.parseColor(tintHex))
                val pad = (8 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(badgeHex))
                }
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply {
                    marginEnd = (10 * d).toInt()
                }
            })
            addView(TextView(context).apply {
                text = title; typeface = tf; paint.isFakeBoldText = true; textSize = 18f
                setTextColor(Color.parseColor("#EEF3FC"))
            })
        })
        root.addView(TextView(context).apply {
            text = subtitle; typeface = tf; textSize = 13f
            setTextColor(Color.parseColor("#9EB0CE"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * d).toInt()
                bottomMargin = (14 * d).toInt()
            }
        })
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
                    text = "No items yet • Add or speak"
                    typeface = tf
                    textSize = 13.5f
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
            contentDescription = "Voice add"
            setIconResource(R.drawable.ic_mic_rounded)
            iconSize = (18 * d).toInt()
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
            setPadding(0, 0, 0, 0)
            insetTop = 0; insetBottom = 0; insetLeft = 0; insetRight = 0
            minWidth = 0
            cornerRadius = (999 * d).toInt()
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
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
                        override fun onReadyForSpeech(params: Bundle?) {
                            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
                        }
                        override fun onError(error: Int) {
                            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
                            sr.destroy()
                            CoroutineScope(Dispatchers.Main).launch { MicArbiter.exitStateToWakeWord() }
                        }
                        override fun onResults(results: Bundle?) {
                            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
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
            setPadding((14 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f * d
                setColor(Color.parseColor("#202633"))
                setStroke((1 * d).toInt(), Color.parseColor("#2E3748"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt() }
        }
        val input = EditText(context).apply {
            hint = "Add item..."
            background = null
            setPadding(0, (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
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

    fun buildKitchenConverterCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 370, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        root.addView(TextView(context).apply {
            text = "Unit Converter"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 17.5f
            setTextColor(Color.parseColor("#EEF3FC"))
        })

        var mode = 0 // 0=Temp, 1=Weight, 2=Volume, 3=Scale
        var value = 180f
        var scaleMult = 1.0f

        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
        }
        val tvMain = TextView(context).apply {
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 16.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#A8C7FA"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
        }
        val tvSub = TextView(context).apply {
            typeface = tf
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#C4EDD7"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (2 * d).toInt() }
        }
        val stepRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        }
        val stepRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * d).toInt() }
        }

        fun refresh() {
            when (mode) {
                0 -> {
                    val c = value.roundToInt().coerceIn(50, 280)
                    val f = ((c * 9f / 5f) + 32f).roundToInt()
                    val fan = (c - 20).coerceAtLeast(40)
                    val gas = when {
                        c <= 135 -> "Gas 1"; c <= 150 -> "Gas 2"; c <= 165 -> "Gas 3"
                        c <= 180 -> "Gas 4"; c <= 190 -> "Gas 5"; c <= 205 -> "Gas 6"
                        c <= 220 -> "Gas 7"; else -> "Gas 8"
                    }
                    tvMain.text = "${c}°C  •  ${f}°F  •  $gas"
                    tvSub.text = "Fan oven: ${fan}°C"
                }
                1 -> {
                    val g = value.roundToInt().coerceIn(1, 5000)
                    val oz = String.format(Locale.US, "%.2f", g / 28.3495f)
                    val lb = String.format(Locale.US, "%.2f", g / 453.592f)
                    val kg = String.format(Locale.US, "%.2f", g / 1000f)
                    tvMain.text = "${g}g  •  ${oz}oz"
                    tvSub.text = "${lb}lb  •  ${kg}kg"
                }
                2 -> {
                    val ml = value.roundToInt().coerceIn(1, 5000)
                    val flOz = String.format(Locale.US, "%.1f", ml / 29.5735f)
                    val cups = String.format(Locale.US, "%.2f", ml / 240f)
                    val tbsp = String.format(Locale.US, "%.1f", ml / 15f)
                    val tsp = String.format(Locale.US, "%.1f", ml / 5f)
                    tvMain.text = "${ml}ml  •  ${flOz} fl oz  •  ${cups} cups"
                    tvSub.text = "${tbsp} tbsp  •  ${tsp} tsp"
                }
                else -> {
                    val base = value.roundToInt().coerceIn(1, 5000)
                    val scaled = String.format(Locale.US, "%.1f", base * scaleMult)
                    tvMain.text = "$base × ${scaleMult}x = $scaled"
                    tvSub.text = "Tap multiplier below to scale ingredients"
                }
            }
        }

        fun rebuildSteppers() {
            stepRow1.removeAllViews()
            stepRow2.removeAllViews()
            val deltas = when (mode) {
                0 -> listOf(-10, -5, -1, 1, 5, 10)
                1, 2 -> listOf(-50, -10, -1, 1, 10, 50)
                else -> listOf(-25, -5, -1, 1, 5, 25)
            }
            deltas.forEachIndexed { idx, delta ->
                stepRow1.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                    text = if (delta > 0) "+$delta" else "$delta"
                    isAllCaps = false
                    textSize = 11.5f
                    maxLines = 1
                    minWidth = 0
                    setPadding(0, 0, 0, 0)
                    cornerRadius = (999 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f).apply {
                        if (idx < deltas.lastIndex) marginEnd = (4 * d).toInt()
                    }
                    setOnClickListener {
                        TactileAudioEngine.playClick()
                        value = (value + delta).coerceAtLeast(1f)
                        refresh()
                    }
                })
            }
            if (mode == 3) {
                stepRow2.visibility = View.VISIBLE
                listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEachIndexed { i, mult ->
                    stepRow2.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                        text = "${mult}x"
                        isAllCaps = false
                        textSize = 11.5f
                        maxLines = 1
                        minWidth = 0
                        setPadding(0, 0, 0, 0)
                        cornerRadius = (999 * d).toInt()
                        layoutParams = LinearLayout.LayoutParams(0, (32 * d).toInt(), 1f).apply {
                            if (i < 4) marginEnd = (4 * d).toInt()
                        }
                        setOnClickListener {
                            TactileAudioEngine.playClick()
                            scaleMult = mult
                            refresh()
                        }
                    })
                }
            } else {
                stepRow2.visibility = View.GONE
            }
        }

        val modeButtons = mutableListOf<MaterialButton>()
        fun syncModeChips() {
            modeButtons.forEachIndexed { i, btn ->
                val active = i == mode
                btn.setBackgroundColor(Color.parseColor(if (active) "#A8C7FA" else "#232A38"))
                btn.setTextColor(Color.parseColor(if (active) "#0B1220" else "#C4D0E3"))
            }
        }

        listOf("Temp", "Weight", "Vol", "Scale").forEachIndexed { idx, lbl ->
            val b = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = lbl
                isAllCaps = false
                textSize = 11.5f
                maxLines = 1
                minWidth = 0
                setPadding(0, 0, 0, 0)
                insetTop = 0; insetBottom = 0
                cornerRadius = (999 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(0, (32 * d).toInt(), 1f).apply {
                    if (idx < 3) marginEnd = (5 * d).toInt()
                }
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    mode = idx
                    value = when (idx) { 0 -> 180f; 1 -> 250f; 2 -> 240f; else -> 100f }
                    syncModeChips()
                    rebuildSteppers()
                    refresh()
                }
            }
            modeButtons.add(b)
            modeRow.addView(b)
        }
        syncModeChips()

        root.addView(modeRow)
        root.addView(tvMain)
        root.addView(tvSub)
        root.addView(stepRow1)
        root.addView(stepRow2)
        rebuildSteppers()
        refresh()
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

        val palette = listOf("#A8C7FA", "#F2B8B5", "#C4EDD7", "#FFE082", "#E8D9FF")
        val names = com.kyle.kitchenhub.data.ConfigRepository.get(context).householdContacts
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(5)
            .ifEmpty { listOf("Kyle", "Mum", "Dad", "Home") }
        val contacts = names.mapIndexed { i, n ->
            Triple(n, palette[i % palette.size], n.first().uppercaseChar().toString())
        }
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

    private fun smartDeviceIconRes(dev: com.kyle.kitchenhub.data.SmartHomeDevice): Int {
        val lower = dev.name.lowercase(Locale.ROOT)
        return when {
            dev.kind == "climate" -> R.drawable.ic_thermostat_rounded
            "speaker" in lower || "audio" in lower || "sonos" in lower -> R.drawable.ic_speaker_rounded
            dev.kind == "light" || "light" in lower || "lamp" in lower || "wardrobe" in lower || "led" in lower ->
                R.drawable.ic_lightbulb_rounded
            else -> R.drawable.ic_power_plug_rounded
        }
    }

    fun buildSmartHomeCard(context: Context, inStack: Boolean = false): View = wrapCard(context, 380, !inStack) { root ->
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        val subtitleView = TextView(context).apply {
            text = "Loading devices…"
            typeface = tf
            textSize = 12.5f
            setTextColor(Color.parseColor("#8EA4C8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val headerActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * d).toInt() }
                addView(TextView(context).apply {
                    text = "Home controls"
                    typeface = tf
                    paint.isFakeBoldText = true
                    textSize = 20f
                    setTextColor(Color.parseColor("#EEF3FC"))
                    maxLines = 1
                })
                addView(subtitleView)
            })
            addView(headerActions)
        }
        root.addView(header)

        val devicesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(devicesContainer)
        }
        root.addView(scroll)

        var currentDevices: List<com.kyle.kitchenhub.data.SmartHomeDevice> = emptyList()

        fun updateSummary() {
            if (currentDevices.isEmpty()) {
                subtitleView.text = "Not connected"
                subtitleView.setTextColor(Color.parseColor("#8EA4C8"))
            } else {
                val onCount = currentDevices.count { it.isOn }
                val total = currentDevices.size
                subtitleView.text = if (onCount > 0) "$onCount on • $total devices" else "All off • $total devices"
                subtitleView.setTextColor(
                    Color.parseColor(if (onCount > 0) "#A8C7FA" else "#8EA4C8")
                )
            }
        }

        fun buildToggleTile(dev: com.kyle.kitchenhub.data.SmartHomeDevice): View {
            val isLight = dev.kind == "light" || "light" in dev.name.lowercase(Locale.ROOT) || "wardrobe" in dev.name.lowercase(Locale.ROOT)

            val tileCard = MaterialCardView(context).apply {
                radius = 22f * d
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                foreground = context.getDrawable(ripple.resourceId)
            }

            val iconBadgeBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val iconView = ImageView(context).apply {
                setImageResource(smartDeviceIconRes(dev))
                val pad = (8 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = iconBadgeBg
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt())
            }
            val dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val statusDot = View(context).apply {
                background = dotBg
                layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt())
            }
            val nameTv = TextView(context).apply {
                text = dev.name
                typeface = tf
                paint.isFakeBoldText = true
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
            }
            val stateTv = TextView(context).apply {
                typeface = tf
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (2 * d).toInt() }
            }

            fun applyTileState() {
                val on = dev.isOn
                val bgHex: String
                val strokeHex: String
                val badgeHex: String
                val iconTintHex: String
                val nameHex: String
                val stateHex: String
                val dotHex: String

                if (on && isLight) {
                    bgHex = "#2D2618"
                    strokeHex = "#66FBBF24"
                    badgeHex = "#4A3B19"
                    iconTintHex = "#FDE68A"
                    nameHex = "#FFFBEB"
                    stateHex = "#FCD34D"
                    dotHex = "#FCD34D"
                } else if (on) {
                    bgHex = "#1C2A3F"
                    strokeHex = "#668AB4F8"
                    badgeHex = "#2A3E5C"
                    iconTintHex = "#A8C7FA"
                    nameHex = "#EEF3FC"
                    stateHex = "#8AB4F8"
                    dotHex = "#8AB4F8"
                } else {
                    bgHex = "#202633"
                    strokeHex = "#2D3648"
                    badgeHex = "#2A3242"
                    iconTintHex = "#7E8DA6"
                    nameHex = "#C4D0E3"
                    stateHex = "#7E8DA6"
                    dotHex = "#434E63"
                }

                tileCard.radius = (if (on) 28f else 18f) * d
                tileCard.setCardBackgroundColor(Color.parseColor(bgHex))
                tileCard.strokeWidth = ((if (on) 1.5f else 1f) * d).toInt()
                tileCard.strokeColor = Color.parseColor(strokeHex)
                iconBadgeBg.setColor(Color.parseColor(badgeHex))
                iconView.setColorFilter(Color.parseColor(iconTintHex))
                dotBg.setColor(Color.parseColor(dotHex))
                nameTv.setTextColor(Color.parseColor(nameHex))
                stateTv.setTextColor(Color.parseColor(stateHex))
                stateTv.text = if (on) "On" else "Off"
            }

            applyTileState()

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconView)
                    addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
                    addView(statusDot)
                })
                addView(nameTv)
                addView(stateTv)
            }
            tileCard.addView(inner)

            tileCard.setOnClickListener {
                TactileAudioEngine.playClick()
                springBounce(tileCard, 0.93f)
                val next = !dev.isOn
                dev.isOn = next
                applyTileState()
                updateSummary()
                CoroutineScope(Dispatchers.Main).launch {
                    val ok = runCatching {
                        com.kyle.kitchenhub.data.SmartHomeRepository.toggleDevice(context, dev, next)
                    }.getOrDefault(false)
                    if (!ok) {
                        dev.isOn = !next
                        applyTileState()
                        updateSummary()
                        Toast.makeText(context, "Could not update ${dev.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return tileCard
        }

        fun buildClimateTile(dev: com.kyle.kitchenhub.data.SmartHomeDevice): View {
            var targetC = dev.targetTempC
            val tile = MaterialCardView(context).apply {
                radius = 22f * d
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#1C2A3F"))
                strokeWidth = (1 * d).toInt()
                strokeColor = Color.parseColor("#354866")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() }
            }
            val tvTherm = TextView(context).apply {
                text = "Target • ${targetC}°C"
                typeface = tf
                textSize = 13f
                fontFeatureSettings = "tnum"
                setTextColor(Color.parseColor("#A8C7FA"))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_thermostat_rounded)
                    setColorFilter(Color.parseColor("#A8C7FA"))
                    val pad = (8 * d).toInt()
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#2A3E5C"))
                    }
                    layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply {
                        marginEnd = (10 * d).toInt()
                    }
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(TextView(context).apply {
                        text = dev.name
                        typeface = tf
                        paint.isFakeBoldText = true
                        textSize = 14f
                        setTextColor(Color.parseColor("#EEF3FC"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(tvTherm)
                })
                listOf(-1, +1).forEach { delta ->
                    addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                        text = if (delta < 0) "−" else "+"
                        minWidth = (40 * d).toInt()
                        minimumWidth = (40 * d).toInt()
                        cornerRadius = (999 * d).toInt()
                        layoutParams = LinearLayout.LayoutParams((42 * d).toInt(), (38 * d).toInt()).apply {
                            if (delta < 0) marginEnd = (6 * d).toInt()
                        }
                        setOnClickListener {
                            TactileAudioEngine.playClick()
                            targetC = (targetC + delta).coerceIn(15, 28)
                            tvTherm.text = "Target • ${targetC}°C"
                            CoroutineScope(Dispatchers.Main).launch {
                                com.kyle.kitchenhub.data.SmartHomeRepository.setThermostat(context, dev, targetC)
                            }
                        }
                    })
                }
            }
            tile.addView(row)
            return tile
        }

        lateinit var load: () -> Unit

        fun renderHeaderActions(connected: Boolean) {
            headerActions.removeAllViews()
            if (!connected) return
            headerActions.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_refresh_rounded)
                setColorFilter(Color.parseColor("#A8C7FA"))
                val pad = (8 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#222938"))
                    setStroke((1 * d).toInt(), Color.parseColor("#323C50"))
                }
                layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                    marginEnd = (6 * d).toInt()
                }
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    subtitleView.text = "Refreshing…"
                    load()
                }
            })
            headerActions.addView(TextView(context).apply {
                text = "Home"
                typeface = tf
                paint.isFakeBoldText = true
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#A8C7FA"))
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 999f * d
                    setColor(Color.parseColor("#222938"))
                    setStroke((1 * d).toInt(), Color.parseColor("#323C50"))
                }
                layoutParams = LinearLayout.LayoutParams(-2, (34 * d).toInt())
                setOnClickListener {
                    TactileAudioEngine.playClick()
                    val home = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
                    if (home != null) context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else Toast.makeText(context, "Google Home not installed", Toast.LENGTH_SHORT).show()
                }
            })
        }

        load = {
            CoroutineScope(Dispatchers.Main).launch {
                val devices = runCatching {
                    com.kyle.kitchenhub.data.SmartHomeRepository.fetchDevices(context)
                }.getOrDefault(emptyList())
                currentDevices = devices
                devicesContainer.removeAllViews()
                updateSummary()
                renderHeaderActions(devices.isNotEmpty())

                if (devices.isEmpty()) {
                    devicesContainer.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding((8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                        addView(ImageView(context).apply {
                            setImageResource(R.drawable.ic_home_rounded)
                            setColorFilter(Color.parseColor("#A8C7FA"))
                            val pad = (14 * d).toInt()
                            setPadding(pad, pad, pad, pad)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#24324A"))
                            }
                            layoutParams = LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()).apply {
                                bottomMargin = (14 * d).toInt()
                            }
                        })
                        addView(TextView(context).apply {
                            text = "Connect Google Home"
                            typeface = tf
                            paint.isFakeBoldText = true
                            textSize = 16.5f
                            setTextColor(Color.parseColor("#EEF3FC"))
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (6 * d).toInt() }
                        })
                        addView(TextView(context).apply {
                            text = "Control your lights, plugs, speakers, and thermostats right from your display."
                            typeface = tf
                            textSize = 13.5f
                            setTextColor(Color.parseColor("#8EA4C8"))
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * d).toInt() }
                        })
                        addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
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
                    })
                } else {
                    val toggleables = devices.filter { it.kind == "light" } + devices.filter { it.kind == "switch" }
                    toggleables.chunked(2).forEach { pair ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            clipChildren = false
                            clipToPadding = false
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                                bottomMargin = (10 * d).toInt()
                            }
                        }
                        pair.forEachIndexed { idx, dev ->
                            val tile = buildToggleTile(dev).apply {
                                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                                    if (idx == 0) marginEnd = (10 * d).toInt()
                                }
                            }
                            row.addView(tile)
                        }
                        if (pair.size == 1) {
                            row.addView(View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                            })
                        }
                        devicesContainer.addView(row)
                    }
                    devices.filter { it.kind == "climate" }.forEach { dev ->
                        devicesContainer.addView(buildClimateTile(dev))
                    }
                }
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { load() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}
