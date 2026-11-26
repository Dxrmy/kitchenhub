package com.kyle.kitchenhub.data

import android.content.Context
import android.graphics.Color
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FrostedBackdropEngine
import org.json.JSONObject
import java.io.File

object ConfigRepository {
    private const val PREFS = "kitchenhub_config"
    private const val KEY_JSON = "config_json"

    @Volatile
    private var cached: KitchenConfig? = null

    fun get(context: Context): KitchenConfig {
        cached?.let { return it }
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        val cfg = if (raw.isNullOrBlank()) KitchenConfig() else runCatching {
            KitchenConfig.fromJson(JSONObject(raw))
        }.getOrDefault(KitchenConfig())
        cached = cfg
        applyRuntime(cfg)
        return cfg
    }

    fun update(context: Context, transform: (KitchenConfig) -> KitchenConfig): KitchenConfig {
        val next = transform(get(context))
        cached = next
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, next.toJson().toString())
            .apply()
        applyRuntime(next)
        return next
    }

    fun exportToFile(context: Context): File {
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "kitchenhub-backup.json")
        out.writeText(get(context).toJson().toString(2))
        return out
    }

    fun importFromFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val cfg = KitchenConfig.fromJson(JSONObject(file.readText()))
            update(context) { cfg }
            true
        }.getOrDefault(false)
    }

    private fun applyRuntime(cfg: KitchenConfig) {
        FrostedBackdropEngine.blurRadius = cfg.blurRadius
        FrostedBackdropEngine.reduceTransparency = cfg.reduceTransparency
        if (cfg.dynamicColorSource == "MANUAL") {
            runCatching { FrostedBackdropEngine.setDynamicTint(Color.parseColor(cfg.manualAccentHex)) }
        }
        TactileAudioEngine.enabled = cfg.tactileAudioEnabled
        TactileAudioEngine.volume = cfg.tactileVolume
    }
}
