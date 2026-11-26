package com.kyle.kitchenhub.core.stability

import android.content.Context
import android.content.Intent

object CrashFailsafe {
    private const val PREFS = "kitchenhub_failsafe"
    private const val KEY_LAST_CRASH = "last_crash_ms"
    private const val KEY_SAFE_MODE = "safe_mode_active"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_CRASH, 0L)
            val doubleCrash = last > 0L && (now - last) <= 10_000L
            prefs.edit()
                .putLong(KEY_LAST_CRASH, now)
                .putBoolean(KEY_SAFE_MODE, doubleCrash)
                .commit()
            if (doubleCrash) {
                val i = Intent(appCtx, SafeRecoveryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                runCatching { appCtx.startActivity(i) }
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun shouldEnterSafeMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SAFE_MODE, false)
    }

    fun clearSafeMode(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAFE_MODE, false).putLong(KEY_LAST_CRASH, 0L).apply()
    }
}
