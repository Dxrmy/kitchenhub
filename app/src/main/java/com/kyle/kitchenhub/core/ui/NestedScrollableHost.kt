package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Locks ViewPager2 on ACTION_DOWN so edge-of-screen swipes ALWAYS scroll the
 * section's horizontal RecyclerView first, only releasing to ViewPager2 when
 * a fresh drag starts at the start/end edge and moves outward.
 */
class NestedScrollableHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var startedAtStart = false
    private var startedAtEnd = false

    private val parentViewPager: ViewPager2?
        get() {
            var v: View? = parent as? View
            while (v != null && v !is ViewPager2) {
                v = v.parent as? View
            }
            return v as? ViewPager2
        }

    private val child: View?
        get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleInterceptTouchEvent(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleInterceptTouchEvent(e: MotionEvent) {
        val orientation = parentViewPager?.orientation ?: return
        if (orientation != ViewPager2.ORIENTATION_HORIZONTAL) return
        val rv = child ?: return

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = e.x
                initialY = e.y
                startedAtStart = !rv.canScrollHorizontally(-1)
                startedAtEnd = !rv.canScrollHorizontally(1)
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - initialX
                val dy = e.y - initialY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    if (abs(dy) > abs(dx)) {
                        parent.requestDisallowInterceptTouchEvent(false)
                    } else {
                        val allowPageSwitch = (dx > 0f && startedAtStart) || (dx < 0f && startedAtEnd)
                        parent.requestDisallowInterceptTouchEvent(!allowPageSwitch)
                    }
                }
            }
        }
    }
}
