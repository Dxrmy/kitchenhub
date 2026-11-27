package com.kyle.kitchenhub.ui.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import com.kyle.kitchenhub.R
import com.kyle.kitchenhub.core.ui.PixelSparkleView
import com.kyle.kitchenhub.databinding.PageCarouselBinding
import com.kyle.kitchenhub.databinding.PageClockBinding
import com.kyle.kitchenhub.ui.stickynote.StickyNoteDialog
import kotlin.math.abs

/**
 * Snaps horizontal Fuchsia cards to paddingStart (32dp) instead of screen center,
 * preventing Card 0 ("Good afternoon, Kyle") from ever being pushed off the left edge.
 */
class StartSnapHelper : LinearSnapHelper() {
    private var horizontalHelper: OrientationHelper? = null

    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View
    ): IntArray {
        val out = IntArray(2)
        if (layoutManager.canScrollHorizontally()) {
            val helper = getHorizontalHelper(layoutManager)
            out[0] = helper.getDecoratedStart(targetView) - helper.startAfterPadding
        }
        return out
    }

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        if (layoutManager !is LinearLayoutManager) return super.findSnapView(layoutManager)
        val count = layoutManager.childCount
        if (count == 0) return null

        val firstComplete = layoutManager.findFirstCompletelyVisibleItemPosition()
        if (firstComplete == 0) return layoutManager.findViewByPosition(0)
        val lastComplete = layoutManager.findLastCompletelyVisibleItemPosition()
        if (lastComplete == layoutManager.itemCount - 1) return null

        val helper = getHorizontalHelper(layoutManager)
        val startEdge = helper.startAfterPadding
        var closestChild: View? = null
        var minDistance = Int.MAX_VALUE

        for (i in 0 until count) {
            val child = layoutManager.getChildAt(i) ?: continue
            val dist = abs(helper.getDecoratedStart(child) - startEdge)
            if (dist < minDistance) {
                minDistance = dist
                closestChild = child
            }
        }
        return closestChild
    }

    private fun getHorizontalHelper(lm: RecyclerView.LayoutManager): OrientationHelper {
        val existing = horizontalHelper
        if (existing != null && existing.layoutManager === lm) return existing
        return OrientationHelper.createHorizontalHelper(lm).also { horizontalHelper = it }
    }
}

class NestHubPagerAdapter(
    private val context: Context,
    private val sparkle: PixelSparkleView,
    private val onEnterClock: () -> Unit,
    private val onWakeFromClock: (x: Float, y: Float) -> Unit,
    private val onRefreshBanner: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CLOCK = 0
        private const val VIEW_TYPE_CAROUSEL = 1
    }

    class ClockPageHolder(val binding: PageClockBinding) : RecyclerView.ViewHolder(binding.root)
    class CarouselPageHolder(val binding: PageCarouselBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = 5

    override fun getItemViewType(position: Int): Int =
        if (position == 0) VIEW_TYPE_CLOCK else VIEW_TYPE_CAROUSEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        if (viewType == VIEW_TYPE_CLOCK) {
            val b = PageClockBinding.inflate(inf, parent, false)
            b.clockPageRoot.inPagerMode = true
            b.clockPageRoot.onDoubleTapExit = { x, y -> onWakeFromClock(x, y) }
            return ClockPageHolder(b)
        }

        val b = PageCarouselBinding.inflate(inf, parent, false)
        val gapPx = (20 * context.resources.displayMetrics.density).toInt()
        b.carouselRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        b.carouselRecycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val pos = parent.getChildAdapterPosition(view)
                if (pos > 0) outRect.left = gapPx
            }
        })
        StartSnapHelper().attachToRecyclerView(b.carouselRecycler)
        return CarouselPageHolder(b)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("in_place") && holder is CarouselPageHolder) {
            holder.binding.carouselRecycler.adapter?.notifyItemRangeChanged(
                0,
                holder.binding.carouselRecycler.adapter?.itemCount ?: 0,
                "in_place"
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ClockPageHolder) {
            holder.binding.clockPageRoot.refreshFromConfig()
            return
        }
        if (holder !is CarouselPageHolder) return

        val cards = when (position) {
            1 -> buildList {
                add(FuchsiaCardItem.HomeBento)
                add(FuchsiaCardItem.StackedCustomPair("SHOPPING_LIST", "KITCHEN_CONVERTER", 370))
                if (com.kyle.kitchenhub.data.ConfigRepository.get(context).icalUrl.isNotBlank()) {
                    add(FuchsiaCardItem.CalendarStandBy)
                }
            }
            2 -> {
                val ytMusicCard = FuchsiaCardItem.FeatureCard(
                    "com.google.android.apps.youtube.music",
                    R.drawable.ic_app_ytmusic,
                    "YouTube Music",
                    "Browse kitchen playlists & mixes",
                    ""
                ) { launchPkg("com.google.android.apps.youtube.music", "YouTube Music") }
                val ytCard = FuchsiaCardItem.FeatureCard(
                    "com.google.android.youtube",
                    R.drawable.ic_app_youtube,
                    "YouTube",
                    "Ad-free recipes, news & video",
                    ""
                ) {
                    val yt = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                        ?: context.packageManager.getLaunchIntentForPackage("com.google.android.youtube.tv")
                    if (yt != null) context.startActivity(yt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else launchPkg("com.google.android.youtube", "YouTube")
                }
                listOf(
                    FuchsiaCardItem.MediaBento(ytMusicCard, ytCard),
                    FuchsiaCardItem.BluetoothSpeaker
                )
            }
            3 -> listOf(
                FuchsiaCardItem.CustomCard("HOUSEHOLD_CONTACTS"),
                FuchsiaCardItem.FeatureCard(
                    "com.google.android.apps.tachyon",
                    R.drawable.ic_app_meet,
                    "Google Meet",
                    "Hands-free voice & video calls with family",
                    "Open Meet"
                ) { launchPkg("com.google.android.apps.tachyon", "Google Meet") },
                FuchsiaCardItem.FeatureCard(
                    "com.whatsapp",
                    R.drawable.ic_app_whatsapp,
                    "WhatsApp",
                    "Linked companion messages & kitchen calls",
                    "Open WhatsApp"
                ) { launchPkg("com.whatsapp", "WhatsApp") },
                FuchsiaCardItem.FeatureCard(
                    "com.google.android.keep",
                    R.drawable.ic_app_keep,
                    "Google Keep",
                    "Shared family grocery & kitchen shopping list",
                    "Open Keep"
                ) { launchPkg("com.google.android.keep", "Google Keep") }
            )
            else -> listOf(
                FuchsiaCardItem.CustomCard("SMART_HOME"),
                FuchsiaCardItem.StackedCustomPair("COOKING_MODE", "BEDTIME_MODE", 360),
                FuchsiaCardItem.FeatureCard(
                    null,
                    R.drawable.ic_notifications_off_rounded,
                    "Quiet / DND",
                    "Silence notifications — allow kitchen alarms only",
                    "Enable Quiet"
                ) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    Toast.makeText(context, "Quiet DND active", Toast.LENGTH_SHORT).show()
                }
            )
        }

        val rv = holder.binding.carouselRecycler
        if (rv.tag == position && rv.adapter is FuchsiaCarouselAdapter) {
            rv.adapter?.notifyDataSetChanged()
        } else {
            rv.tag = position
            rv.adapter = FuchsiaCarouselAdapter(context, cards, sparkle, onEnterClock, onRefreshBanner)
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun attachNestedScrollGuard(rv: RecyclerView) {
        var startX = 0f
        var startY = 0f
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        r.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - startX
                        val dy = e.y - startY
                        if (abs(dx) > 6f && abs(dx) > abs(dy)) {
                            val dir = if (dx < 0) 1 else -1
                            r.parent?.requestDisallowInterceptTouchEvent(r.canScrollHorizontally(dir))
                        } else if (abs(dy) > abs(dx) && abs(dy) > 10f) {
                            r.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(r: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun launchPkg(pkg: String, label: String) {
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(context, "$label not installed", Toast.LENGTH_SHORT).show()
    }
}
