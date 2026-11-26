package com.kyle.kitchenhub.ui.calendar

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.DialogImmersive
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.data.ConfigRepository
import com.kyle.kitchenhub.data.WeatherAndCalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full-screen Material 3 Expressive scrollable weekly agenda overlay with left-edge swipe back.
 */
class CalendarAgendaDialog(context: Context) : Dialog(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun show() = DialogImmersive.show(this) { super.show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)
        val cfg = ConfigRepository.get(context)

        val rootCard = MaterialCardView(context).apply {
            radius = 32f * d
            cardElevation = 12f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#111520"))
        }

        val rootWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        }
        topBar.addView(TextView(context).apply {
            text = "Weekly Agenda"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 22f
            setTextColor(Color.parseColor("#EEF3FC"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        topBar.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            isAllCaps = false
            cornerRadius = (999 * d).toInt()
            setTextColor(Color.parseColor("#A8C7FA"))
            setOnClickListener { TactileAudioEngine.playClick(); dismiss() }
        })
        rootWrap.addView(topBar)

        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(listCol)
        }
        rootWrap.addView(scroll)
        rootCard.addView(rootWrap)
        setContentView(rootCard)

        DialogImmersive.attachEdgeSwipeDismiss(this, rootCard)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.65f)
        val w = (context.resources.displayMetrics.widthPixels * 0.88f).toInt()
        val h = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
        window?.setLayout(w, h)

        val statusTv = TextView(context).apply {
            text = if (cfg.icalUrl.isBlank()) "Configure an iCal URL in Settings to sync family events." else "Loading weekly schedule..."
            typeface = tf
            textSize = 15f
            setTextColor(Color.parseColor("#9EB0CE"))
        }
        listCol.addView(statusTv)

        if (cfg.icalUrl.isNotBlank()) {
            scope.launch {
                val evs = WeatherAndCalRepository.fetchIcalEvents(cfg.icalUrl)
                listCol.removeAllViews()
                if (evs.isEmpty()) {
                    listCol.addView(statusTv.apply { text = "No upcoming events this week." })
                } else {
                    evs.take(20).forEach { ev ->
                        val rowCard = MaterialCardView(context).apply {
                            radius = 20f * d
                            cardElevation = 0f
                            strokeWidth = 0
                            setCardBackgroundColor(Color.parseColor("#1B2130"))
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() }
                        }
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
                        }
                        row.addView(TextView(context).apply {
                            text = ev.title
                            typeface = tf
                            paint.isFakeBoldText = true
                            textSize = 16.5f
                            setTextColor(Color.parseColor("#EEF3FC"))
                        })
                        row.addView(TextView(context).apply {
                            text = ev.whenText
                            typeface = tf
                            textSize = 13.5f
                            setTextColor(Color.parseColor("#A8C7FA"))
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * d).toInt() }
                        })
                        rowCard.addView(row)
                        listCol.addView(rowCard)
                    }
                }
            }
        }
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
