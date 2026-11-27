package com.kyle.kitchenhub.core.ui

import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Prevents Android from revealing the navigation or status bar when a Dialog window opens
 * over an immersive fullscreen launcher, provides left-edge swipe-back dismissal,
 * and tracks open dialogs so idle/clock transitions can dismiss them cleanly.
 */
object DialogImmersive {
    private val activeDialogs = mutableListOf<WeakReference<Dialog>>()

    fun dismissAll() {
        val it = activeDialogs.iterator()
        while (it.hasNext()) {
            val d = it.next().get()
            if (d == null) {
                it.remove()
            } else {
                if (d.isShowing) runCatching { d.dismiss() }
                it.remove()
            }
        }
    }

    fun show(dialog: Dialog, superShow: () -> Unit) {
        activeDialogs.removeAll { it.get() == null || it.get() === dialog }
        activeDialogs.add(WeakReference(dialog))
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        superShow()
        dialog.window?.let { w ->
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    fun attachEdgeSwipeDismiss(dialog: Dialog, view: View) {
        val d = view.resources.displayMetrics.density
        var edgeStart = false
        var startX = 0f
        var startY = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    edgeStart = ev.x <= 48f * d
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (edgeStart) {
                        val dx = ev.rawX - startX
                        val dy = abs(ev.rawY - startY)
                        if (dx > 14f * d && dx > dy * 1.4f) {
                            view.translationX = dx * 0.45f
                            return@setOnTouchListener true
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (edgeStart) {
                        val dx = ev.rawX - startX
                        edgeStart = false
                        if (dx > 96f * d) {
                            dialog.dismiss()
                            return@setOnTouchListener true
                        }
                        view.animate().translationX(0f).setDuration(160L).start()
                    }
                    false
                }
                else -> false
            }
        }
    }
}
