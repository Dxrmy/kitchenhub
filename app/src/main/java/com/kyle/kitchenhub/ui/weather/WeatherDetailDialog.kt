package com.kyle.kitchenhub.ui.weather

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.loadingindicator.LoadingIndicator
import com.kyle.kitchenhub.R
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.DailyForecast
import com.kyle.kitchenhub.data.DetailedWeather
import com.kyle.kitchenhub.data.WeatherAndCalRepository
import com.kyle.kitchenhub.ui.overlay.M3CookieShapeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dark-themed Material 3 Expressive expanded weather modal with morphing LoadingIndicator
 * and animated weather condition effects.
 */
class WeatherDetailDialog(context: Context) : Dialog(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val valAnimators = mutableListOf<ValueAnimator>()
    private var effectsAnimator: ValueAnimator? = null
    private val d = context.resources.displayMetrics.density

    override fun show() = com.kyle.kitchenhub.core.ui.DialogImmersive.show(this) { super.show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = ConfigRepository.get(context)
        val tf = FontCache.plusJakarta(context)

        val rootCard = MaterialCardView(context).apply {
            radius = 32f * d
            cardElevation = 12f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#111520"))
        }

        val rootFrame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val effectsView = com.kyle.kitchenhub.core.ui.WeatherEffectsShaderView(context).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(300))
        }

        val rootWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Top Bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(20), dp(8))
        }
        val tvCity = TextView(context).apply {
            text = cfg.locationName
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 22f
            setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnClose = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            isAllCaps = false
            cornerRadius = dp(999)
            setTextColor(Color.parseColor("#A8C7FA"))
            setOnClickListener { TactileAudioEngine.playClick(); dismiss() }
        }
        topBar.addView(tvCity)
        topBar.addView(btnClose)
        rootWrap.addView(topBar)

        val bodyHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(24))
        }
        scroll.addView(content)
        bodyHost.addView(scroll)

        // Material 3 Expressive Cookie-Cutter Morphing LoadingIndicator
        val loaderWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val m3Loader = LoadingIndicator(context).apply {
            setIndicatorColor(Color.parseColor("#A8C7FA"))
            indicatorSize = dp(64)
        }
        val tvLoading = TextView(context).apply {
            text = "Loading forecast..."
            typeface = tf
            textSize = 14f
            setTextColor(Color.parseColor("#9EB0CE"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) }
        }
        loaderWrap.addView(m3Loader)
        loaderWrap.addView(tvLoading)
        bodyHost.addView(loaderWrap)

        rootWrap.addView(bodyHost)
        rootFrame.addView(rootWrap)
        rootFrame.addView(effectsView)
        rootCard.addView(rootFrame)
        setContentView(rootCard)
        com.kyle.kitchenhub.core.ui.DialogImmersive.attachEdgeSwipeDismiss(this, rootCard)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.65f)
        val w = (context.resources.displayMetrics.widthPixels * 0.88f).toInt()
        val h = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
        window?.setLayout(w, h)

        val loadStartMs = System.currentTimeMillis()
        scope.launch {
            val detail = WeatherAndCalRepository.fetchDetailedWeather(cfg)
            val elapsed = System.currentTimeMillis() - loadStartMs
            if (elapsed < 950L) delay(950L - elapsed)

            if (detail == null) {
                tvLoading.text = "Weather unavailable offline."
                m3Loader.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(260L).start()
            } else {
                effectsView.setCondition(detail.condition)
                populateContent(content, detail, tf)
                loaderWrap.animate()
                    .alpha(0f)
                    .scaleX(0.55f)
                    .scaleY(0.55f)
                    .setDuration(280L)
                    .withEndAction { loaderWrap.visibility = View.GONE }
                    .start()
                scroll.animate().alpha(1f).setDuration(300L).start()
            }
        }
    }

    private fun populateContent(content: LinearLayout, w: DetailedWeather, tf: Typeface) {
        // 1. Hero Header
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(18))
        }
        hero.addView(TextView(context).apply {
            text = w.condition
            typeface = tf
            textSize = 19f
            setTextColor(Color.parseColor("#B8CCF0"))
        })
        hero.addView(TextView(context).apply {
            text = "${w.tempDisplay}${w.unitSym}"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 68f
            includeFontPadding = false
            setTextColor(Color.parseColor("#FFFFFF"))
        })
        hero.addView(TextView(context).apply {
            text = "Day: ${w.dayHi}° • Night: ${w.nightLo}°"
            typeface = tf
            textSize = 15f
            setTextColor(Color.parseColor("#9EB0CE"))
        })
        content.addView(hero)

        // 2. Daily Forecast Card with Dual-Line Chart
        val dailyCard = darkCard()
        val dailyInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        dailyInner.addView(sectionHeader("Daily forecast", tf))
        val chartRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(10) }
        }
        chartRow.addView(DailyChartView(context, w.daily, tf).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        })
        dailyInner.addView(chartRow)
        dailyCard.addView(dailyInner)
        content.addView(dailyCard)

        // 3. Hourly Forecast Card
        if (w.hourly.isNotEmpty()) {
            val hourlyCard = darkCard().apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            }
            val hourlyInner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            hourlyInner.addView(sectionHeader("Hourly forecast", tf))
            val hScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            }
            val hStrip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            w.hourly.forEach { hr ->
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumWidth = dp(58)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                col.addView(TextView(context).apply {
                    text = hr.hourLabel
                    typeface = tf
                    textSize = 12f
                    setTextColor(Color.parseColor("#9EB0CE"))
                })
                col.addView(ImageView(context).apply {
                    setImageResource(condIcon(hr.condition))
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                        topMargin = dp(6); bottomMargin = dp(6)
                    }
                })
                col.addView(TextView(context).apply {
                    text = "${hr.temp}°"
                    typeface = tf
                    paint.isFakeBoldText = true
                    textSize = 16f
                    setTextColor(Color.parseColor("#EEF3FC"))
                })
                if (hr.precipPct > 0) {
                    col.addView(TextView(context).apply {
                        text = "${hr.precipPct}%"
                        typeface = tf
                        textSize = 11f
                        setTextColor(Color.parseColor("#7CC4FF"))
                    })
                }
                hStrip.addView(col)
            }
            hScroll.addView(hStrip)
            hourlyInner.addView(hScroll)
            hourlyCard.addView(hourlyInner)
            content.addView(hourlyCard)
        }

        // 4. Material 3 Expressive Metric Tiles Grid (4 columns across landscape)
        val grid1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(168)).apply { topMargin = dp(14) }
        }
        grid1.addView(buildHumidityTile(w, tf))
        grid1.addView(buildCookieTile("UV index", "${w.uvIndex}", uvLabel(w.uvIndex), "#1E2638", "#A8C7FA", tf))
        grid1.addView(buildCookieTile("Visibility", "${w.visibilityKm} km", if (w.visibilityKm >= 8) "Good" else "Moderate", "#27223B", "#D0BCFF", tf))
        grid1.addView(buildPressureTile(w, tf))
        content.addView(grid1)

        // 5. Sun & Wind Row
        val grid2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(14) }
        }
        grid2.addView(buildSunTile(w, tf))
        grid2.addView(buildWindTile(w, tf))
        content.addView(grid2)
    }

    private fun darkCard(): MaterialCardView = MaterialCardView(context).apply {
        radius = 26f * d
        cardElevation = 2f * d
        strokeWidth = 0
        setCardBackgroundColor(Color.parseColor("#1C2232"))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun sectionHeader(title: String, tf: Typeface) = TextView(context).apply {
        text = title
        typeface = tf
        paint.isFakeBoldText = true
        textSize = 16f
        setTextColor(Color.parseColor("#EEF3FC"))
    }

    private fun buildHumidityTile(w: DetailedWeather, tf: Typeface): View {
        val card = MaterialCardView(context).apply {
            radius = 28f * d
            cardElevation = 2f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#27223B"))
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(10) }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        box.addView(TextView(context).apply {
            text = "Humidity"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D0BCFF"))
        })
        box.addView(TextView(context).apply {
            text = "${w.humidityPct}%"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
        })
        box.addView(TextView(context).apply {
            text = "${w.dewPoint}° Dew point"
            typeface = tf
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B8A6E8"))
        })
        card.addView(box)
        return card
    }

    private fun buildCookieTile(
        title: String,
        bigValue: String,
        sub: String,
        bgHex: String,
        inkHex: String,
        tf: Typeface
    ): View {
        val wrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(10) }
        }
        val cookie = M3CookieShapeView(context).apply {
            lobes = if (title == "UV index") 12 else 9
            isHaloMode = false
            setSolidTonalStyle(Color.parseColor(bgHex), Color.parseColor("#38A8C7FA"))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        var lastCookieMs = 0L
        ObjectAnimator.ofFloat(cookie, View.ROTATION, 0f, 360f).apply {
            duration = if (title == "UV index") 26000L else 32000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastCookieMs < 66L) return@addUpdateListener
                lastCookieMs = now
                cookie.rotation = anim.animatedValue as Float
            }
        }.also {
            // Drive via 15fps ValueAnimator instead of 60fps ObjectAnimator
        }
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (title == "UV index") 26000L else 32000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastCookieMs >= 66L) { // ~15 fps
                    lastCookieMs = now
                    cookie.rotation = anim.animatedValue as Float
                }
            }
            start()
            valAnimators.add(this)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        box.addView(TextView(context).apply {
            text = title
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(inkHex))
        })
        box.addView(TextView(context).apply {
            text = bigValue
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
        })
        box.addView(TextView(context).apply {
            text = sub
            typeface = tf
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9EB0CE"))
        })
        wrap.addView(cookie)
        wrap.addView(box)
        return wrap
    }

    private fun buildPressureTile(w: DetailedWeather, tf: Typeface): View {
        val card = MaterialCardView(context).apply {
            radius = 84f * d
            cardElevation = 2f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#1C2232"))
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        box.addView(TextView(context).apply {
            text = "Pressure"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A8C7FA"))
        })
        box.addView(TextView(context).apply {
            text = "${w.pressureHpa}"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
        })
        box.addView(TextView(context).apply {
            text = "hPa"
            typeface = tf
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9EB0CE"))
        })
        card.addView(box)
        return card
    }

    private fun buildSunTile(w: DetailedWeather, tf: Typeface): View {
        val card = darkCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(sectionHeader("Sun", tf))
        box.addView(SunArcView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        val timesRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        timesRow.addView(TextView(context).apply {
            text = w.sunrise
            typeface = tf
            textSize = 13f
            setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        timesRow.addView(TextView(context).apply {
            text = w.sunset
            typeface = tf
            textSize = 13f
            gravity = Gravity.END
            setTextColor(Color.parseColor("#EEF3FC"))
        })
        box.addView(timesRow)
        card.addView(box)
        return card
    }

    private fun buildWindTile(w: DetailedWeather, tf: Typeface): View {
        val card = darkCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(sectionHeader("Wind", tf))
        box.addView(TextView(context).apply {
            text = "${w.windKmh} km/h"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); bottomMargin = dp(4) }
        })
        box.addView(TextView(context).apply {
            text = "Current surface wind"
            typeface = tf
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9EB0CE"))
        })
        card.addView(box)
        return card
    }

    private fun uvLabel(uv: Int): String = when {
        uv <= 2 -> "Low"
        uv <= 5 -> "Moderate"
        uv <= 7 -> "High"
        else -> "Very High"
    }

    private fun condIcon(cond: String): Int = when {
        cond.contains("Rain", true) || cond.contains("Storm", true) -> R.drawable.ic_rainy_rounded
        cond.contains("Clear", true) -> R.drawable.ic_sunny_rounded
        else -> R.drawable.ic_partly_cloudy_rounded
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    override fun onStop() {
        valAnimators.forEach { it.cancel() }
        valAnimators.clear()
        effectsAnimator?.cancel()
        effectsAnimator = null
        scope.cancel()
        super.onStop()
    }

    private class WeatherConditionEffectsView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var mode = 0 // 0=cloudy, 1=clear/sun, 2=rain/storm, 3=snow
        private var phase = 0f

        // 15fps Material 3 Expressive weather effects (0 per-frame allocations)
        private val sunPath = Path()
        private val cloudPath = Path()
        private val flakePath = Path()
        private val dropRect = RectF()
        private val fxMatrix = Matrix()
        private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#24FFD54F")
            style = Paint.Style.FILL
        }
        private val sunCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFB300")
            style = Paint.Style.FILL
        }
        private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1FA8C7FA")
            style = Paint.Style.FILL
        }
        private val cloudSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#147CC4FF")
            style = Paint.Style.FILL
        }
        private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4D7CC4FF")
            style = Paint.Style.FILL
        }
        private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44EEF3FC")
            style = Paint.Style.FILL
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            buildScallopedPath(sunPath, 12, 68f * d, 55f * d)
            buildScallopedPath(flakePath, 6, 7f * d, 4f * d)
            buildM3CloudPath(cloudPath, 90f * d)
        }

        private fun buildScallopedPath(out: Path, lobes: Int, outerR: Float, innerR: Float) {
            out.reset()
            val step = (2.0 * PI) / lobes
            val firstMid = -PI * 0.5 - step * 0.5
            out.moveTo((innerR * cos(firstMid)).toFloat(), (innerR * sin(firstMid)).toFloat())
            for (i in 0 until lobes) {
                val peak = -PI * 0.5 + i * step
                val nextValley = peak + step * 0.5
                val ctrlR = outerR * 1.12f
                out.quadTo(
                    (ctrlR * cos(peak)).toFloat(),
                    (ctrlR * sin(peak)).toFloat(),
                    (innerR * cos(nextValley)).toFloat(),
                    (innerR * sin(nextValley)).toFloat()
                )
            }
            out.close()
        }

        private fun buildM3CloudPath(out: Path, scale: Float) {
            out.reset()
            // Material 3 Expressive soft-capsule cloud silhouette centered near (0,0)
            out.addRoundRect(-scale * 0.9f, -scale * 0.05f, scale * 0.9f, scale * 0.52f, scale * 0.28f, scale * 0.28f, Path.Direction.CW)
            out.addCircle(-scale * 0.35f, -scale * 0.12f, scale * 0.42f, Path.Direction.CW)
            out.addCircle(scale * 0.14f, -scale * 0.24f, scale * 0.50f, Path.Direction.CW)
            out.addCircle(scale * 0.56f, -scale * 0.06f, scale * 0.34f, Path.Direction.CW)
        }

        fun setCondition(cond: String) {
            mode = when {
                cond.contains("Rain", true) || cond.contains("Storm", true) -> 2
                cond.contains("Snow", true) -> 3
                cond.contains("Clear", true) -> 1
                else -> 0
            }
            invalidate()
        }

        fun startAnim(): ValueAnimator {
            var lastDrawMs = 0L
            return ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastDrawMs >= 66L) { // ~15 fps
                        lastDrawMs = now
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            when (mode) {
                1 -> {
                    // Rotating Material 3 12-lobe Sunny cookie + inner sun disc
                    canvas.save()
                    canvas.translate(w * 0.80f, h * 0.30f)
                    canvas.rotate(phase * 360f)
                    canvas.drawPath(sunPath, sunGlowPaint)
                    canvas.restore()
                    canvas.drawCircle(w * 0.80f, h * 0.30f, 36f * d, sunCorePaint)
                }
                2 -> {
                    // M3 Clouds + tilted pill raindrops
                    val drift = sin(phase * 2 * PI).toFloat() * 18f * d
                    canvas.save(); canvas.translate(w * 0.20f + drift, h * 0.26f); canvas.drawPath(cloudPath, cloudPaint); canvas.restore()
                    canvas.save(); canvas.translate(w * 0.78f - drift, h * 0.24f); canvas.drawPath(cloudPath, cloudSoftPaint); canvas.restore()
                    canvas.save()
                    canvas.rotate(14f, w * 0.5f, h * 0.5f)
                    for (i in 0 until 14) {
                        val rx = ((i * 92f * d + phase * 140f * d) % (w + 80f * d)) - 40f * d
                        val ry = ((i * 53f * d + phase * 480f * d) % (h + 40f * d)) - 20f * d
                        dropRect.set(rx, ry, rx + 4f * d, ry + 18f * d)
                        canvas.drawRoundRect(dropRect, 2f * d, 2f * d, rainPaint)
                    }
                    canvas.restore()
                }
                3 -> {
                    // M3 Clouds + rotating 6-lobe M3 flower snowflakes
                    val drift = sin(phase * 2 * PI).toFloat() * 16f * d
                    canvas.save(); canvas.translate(w * 0.22f + drift, h * 0.26f); canvas.drawPath(cloudPath, cloudSoftPaint); canvas.restore()
                    for (i in 0 until 14) {
                        val sx = (i * 88f * d + sin((phase + i * 0.12) * 2 * PI).toFloat() * 14f * d) % w
                        val sy = (i * 47f * d + phase * 220f * d) % h
                        canvas.save()
                        canvas.translate(sx, sy)
                        canvas.rotate((phase * 180f + i * 25f) % 360f)
                        canvas.drawPath(flakePath, snowPaint)
                        canvas.restore()
                    }
                }
                else -> {
                    // Partly Cloudy: M3 Sunny 12-lobe cookie peeking behind drifting M3 Clouds
                    canvas.save()
                    canvas.translate(w * 0.28f, h * 0.22f)
                    canvas.rotate(phase * 360f)
                    fxMatrix.setScale(0.72f, 0.72f)
                    canvas.concat(fxMatrix)
                    canvas.drawPath(sunPath, sunGlowPaint)
                    canvas.restore()

                    val shift = sin(phase * 2 * PI).toFloat() * 22f * d
                    canvas.save(); canvas.translate(w * 0.24f + shift, h * 0.34f); canvas.drawPath(cloudPath, cloudPaint); canvas.restore()
                    canvas.save(); canvas.translate(w * 0.76f - shift, h * 0.30f); canvas.drawPath(cloudPath, cloudSoftPaint); canvas.restore()
                }
            }
        }
    }

    private class SunArcView(context: Context) : View(context) {
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = Color.parseColor("#66FFD54F")
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFD54F")
        }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            rect.set(w * 0.12f, h * 0.25f, w * 0.88f, h * 1.45f)
            canvas.drawArc(rect, 195f, 150f, false, arcPaint)
            canvas.drawCircle(w * 0.35f, h * 0.45f, 7f * resources.displayMetrics.density, dotPaint)
        }
    }

    private class DailyChartView(
        context: Context,
        private val days: List<DailyForecast>,
        private val tf: Typeface
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
            color = Color.parseColor("#38BDF8")
        }
        private val loPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            color = Color.parseColor("#818CF8")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = tf
            textSize = 12.5f * d
            color = Color.parseColor("#EEF3FC")
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = tf
            textSize = 11f * d
            color = Color.parseColor("#9EB0CE")
        }
        private val precipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = tf
            textSize = 11f * d
            color = Color.parseColor("#7CC4FF")
        }
        private val hiPath = Path()
        private val loPath = Path()

        override fun onDraw(canvas: Canvas) {
            if (days.isEmpty() || width <= 0 || height <= 0) return
            val w = width.toFloat(); val h = height.toFloat()
            val colW = w / days.size
            val allHi = days.maxOf { it.hiTemp }
            val allLo = days.minOf { it.loTemp }
            val span = (allHi - allLo).coerceAtLeast(1).toFloat()
            val topY = h * 0.36f
            val botY = h * 0.76f

            hiPath.reset()
            loPath.reset()
            days.forEachIndexed { idx, day ->
                val cx = colW * (idx + 0.5f)
                canvas.drawText(day.dayLabel, cx, 16f * d, textPaint)
                canvas.drawText(day.dateLabel, cx, 32f * d, subPaint)

                val yHi = topY + ((allHi - day.hiTemp) / span) * (botY - topY) * 0.72f
                val yLo = topY + ((allHi - day.loTemp) / span) * (botY - topY) * 0.72f + (16f * d)
                if (idx == 0) {
                    hiPath.moveTo(cx, yHi)
                    loPath.moveTo(cx, yLo)
                } else {
                    hiPath.lineTo(cx, yHi)
                    loPath.lineTo(cx, yLo)
                }
                canvas.drawText("${day.hiTemp}°", cx, yHi - 8f * d, textPaint)
                canvas.drawText("${day.loTemp}°", cx, yLo + 16f * d, subPaint)
                if (day.precipPct > 0) {
                    canvas.drawText("${day.precipPct}%", cx, h - 6f * d, precipPaint)
                }
            }
            canvas.drawPath(hiPath, hiPaint)
            canvas.drawPath(loPath, loPaint)
        }
    }
}
