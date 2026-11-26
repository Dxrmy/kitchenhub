package com.kyle.kitchenhub.ui.clock

import android.service.dreams.DreamService

class KitchenDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        val container = StandByClockContainer(this).apply {
            onDoubleTapExit = { _, _ -> finish() }
        }
        setContentView(container)
    }
}
