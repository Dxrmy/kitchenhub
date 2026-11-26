package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.hypot

/**
 * Wraps the inside of an individual bento box / card so the Material 3 pixel-splash
 * M3RippleShaderView lives directly inside the card (clipped to its rounded outline,
 * scrolling 1:1 with the card) and replaces Android's standard circular expand ripple.
 */
class BentoRippleHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var rippleView: M3RippleShaderView? = null
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onFinishInflate() {
        super.onFinishInflate()
        ensureRippleView()
    }

    private fun ensureRippleView(): M3RippleShaderView {
        rippleView?.let { return it }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is M3RippleShaderView) {
                rippleView = c
                return c
            }
        }
        val rv = M3RippleShaderView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(rv)
        rippleView = rv
        return rv
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                stripRipplesRecursive(parent as? View ?: this)
                ensureRippleView().triggerRipple(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                    rippleView?.fadeOutFast()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                rippleView?.fadeOutFast()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private val TRANSPARENT_SL = ColorStateList.valueOf(Color.TRANSPARENT)

        private fun stripRipplesRecursive(view: View) {
            when (view) {
                is MaterialCardView -> view.rippleColor = TRANSPARENT_SL
                is MaterialButton -> view.rippleColor = TRANSPARENT_SL
            }
            (view.background as? RippleDrawable)?.setColor(TRANSPARENT_SL)
            (view.foreground as? RippleDrawable)?.setColor(TRANSPARENT_SL)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    stripRipplesRecursive(view.getChildAt(i))
                }
            }
        }
    }
}
