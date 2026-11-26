package com.kyle.kitchenhub.ui.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FontCache
import com.kyle.kitchenhub.databinding.ItemAppTrayCardBinding
import com.kyle.kitchenhub.databinding.ViewAppTrayBinding

class AppTrayOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onAskGemini: () -> Unit
) : FrameLayout(context) {

    private data class InstalledAppEntry(
        val label: String,
        val pkg: String,
        val icon: Drawable
    )

    val binding: ViewAppTrayBinding = ViewAppTrayBinding.inflate(LayoutInflater.from(context), this, false)
    val drawerCard: View
        get() = binding.appTrayCard

    init {
        visibility = View.GONE
        val d = resources.displayMetrics.density
        val cardLp = LayoutParams(LayoutParams.MATCH_PARENT, (416 * d).toInt(), Gravity.BOTTOM)
        addView(binding.root, cardLp)
        binding.appTrayCard.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, com.kyle.kitchenhub.R.color.shade_frosted_bg))

        val tf = FontCache.plusJakarta(context)
        binding.tvTrayTitle.typeface = tf
        binding.tvTrayTitle.paint.isFakeBoldText = true

        binding.btnCloseTrayIcon.setOnClickListener {
            TactileAudioEngine.playClick()
            onClose()
        }

        populateInstalledApps()
    }

    fun populateInstalledApps() {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                InstalledAppEntry(
                    label = it.loadLabel(pm).toString(),
                    pkg = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }

        binding.trayRowsContainer.removeAllViews()
        val inf = LayoutInflater.from(context)
        val tf = FontCache.plusJakarta(context)
        val d = resources.displayMetrics.density
        val gapPx = (14 * d).toInt()
        val rowHeightPx = (92 * d).toInt()

        val rows = apps.chunked(4)
        rows.forEachIndexed { rIdx, rowApps ->
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply {
                    if (rIdx < rows.lastIndex) bottomMargin = gapPx
                }
            }
            for (c in 0 until 4) {
                val app = rowApps.getOrNull(c)
                if (app == null) {
                    rowLayout.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                            if (c < 3) marginEnd = gapPx
                        }
                    })
                } else {
                    val itemB = ItemAppTrayCardBinding.inflate(inf, rowLayout, false)
                    itemB.root.layoutParams = (itemB.root.layoutParams as LinearLayout.LayoutParams).apply {
                        if (c < 3) marginEnd = gapPx
                    }
                    itemB.tvTrayAppName.typeface = tf
                    itemB.tvTrayAppName.paint.isFakeBoldText = true
                    itemB.tvTrayAppSub.typeface = tf
                    itemB.tvTrayAppName.text = app.label
                    itemB.tvTrayAppSub.text = "Installed app"
                    itemB.ivTrayAppIcon.setImageDrawable(app.icon)

                    itemB.trayItemCard.setOnClickListener {
                        TactileAudioEngine.playClick()
                        onClose()
                        val launch = pm.getLaunchIntentForPackage(app.pkg)
                        if (launch != null) {
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            Toast.makeText(context, "Cannot open ${app.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    rowLayout.addView(itemB.root)
                }
            }
            binding.trayRowsContainer.addView(rowLayout)
        }
    }
}
