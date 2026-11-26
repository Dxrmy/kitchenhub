package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.content.ContextCompat

object AppIconResolver {
    fun resolve(context: Context, packageName: String?, fallbackResId: Int): Drawable? {
        if (!packageName.isNullOrBlank()) {
            runCatching {
                return context.packageManager.getApplicationIcon(packageName)
            }
        }
        return ContextCompat.getDrawable(context, fallbackResId)
    }

    fun applyTo(imageView: ImageView, packageName: String?, fallbackResId: Int) {
        imageView.setImageDrawable(resolve(imageView.context, packageName, fallbackResId))
    }
}
