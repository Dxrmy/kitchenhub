package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

object FontCache {
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun get(context: Context, assetPath: String): Typeface {
        return cache.getOrPut(assetPath) {
            runCatching {
                Typeface.createFromAsset(context.applicationContext.assets, assetPath)
            }.getOrDefault(Typeface.SANS_SERIF)
        }
    }

    fun caveat(context: Context): Typeface = get(context, "fonts/Caveat.ttf")
    fun patrickHand(context: Context): Typeface = get(context, "fonts/PatrickHand-Regular.ttf")
    fun plusJakarta(context: Context): Typeface = get(context, "fonts/PlusJakartaSans.ttf")
    fun robotoFlex(context: Context): Typeface = get(context, "fonts/RobotoFlex.ttf")
    fun dynaPuff(context: Context): Typeface = get(context, "fonts/DynaPuff.ttf")
    fun bagelFat(context: Context): Typeface = get(context, "fonts/BagelFatOne.ttf")
    fun oswald(context: Context): Typeface = get(context, "fonts/Oswald.ttf")
    fun anton(context: Context): Typeface = get(context, "fonts/Anton.ttf")
    fun spaceGrotesk(context: Context): Typeface = get(context, "fonts/SpaceGrotesk.ttf")
    fun inter(context: Context): Typeface = get(context, "fonts/Inter.ttf")
}
