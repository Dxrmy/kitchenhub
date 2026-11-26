package com.kyle.kitchenhub.core.env

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

object DeviceEnvironment {

    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish")
    }

    val isCrownDevice: Boolean by lazy {
        Build.DEVICE.equals("crown", ignoreCase = true) ||
            Build.PRODUCT.contains("crown", ignoreCase = true)
    }

    val hasRoot: Boolean by lazy {
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val code = proc.waitFor()
            code == 0
        }.getOrDefault(false)
    }

    fun getMemoryMetrics(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024L * 1024L)
        val totalMb = mi.totalMem / (1024L * 1024L)
        return availMb to totalMb
    }

    fun getStorageMetrics(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val availMb = stat.availableBytes / (1024L * 1024L)
        val totalMb = stat.totalBytes / (1024L * 1024L)
        return availMb to totalMb
    }

    fun execRoot(cmd: String): Boolean {
        if (isEmulator) return false
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    fun sleepScreen(context: Context, onAvdMockSleep: (() -> Unit)? = null): Boolean {
        if (!isEmulator && hasRoot && execRoot("input keyevent 223")) return true
        onAvdMockSleep?.invoke()
        return false
    }

    fun launchGemini(context: Context, onAvdMockGemini: (() -> Unit)? = null): Boolean {
        if (!isEmulator && hasRoot && execRoot("input keyevent 219")) return true
        val pm = context.packageManager
        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (voiceIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(voiceIntent) }.isSuccess
        ) {
            return true
        }
        val bardIntent = pm.getLaunchIntentForPackage("com.google.android.apps.bard")
        if (bardIntent != null &&
            runCatching { context.startActivity(bardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        ) {
            return true
        }
        val assistIntent = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (assistIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(assistIntent) }.isSuccess
        ) {
            return true
        }
        onAvdMockGemini?.invoke()
        return false
    }

    fun hasFarFieldConfig(): Boolean = File("/vendor/etc/audio-algorithms/AFE.cfg").exists()

    object LocalTestHarness {
        var mockNowPlayingEnabled: Boolean = false
        var mockTrackTitle: String = "Espresso (Kitchen Mix)"
        var mockTrackArtist: String = "Sabrina Carpenter"
        var mockNightModePreview: Boolean = false
        var mockHardwareMuted: Boolean = false
        var mockRingingTimer: Boolean = false
        var lastSimulatedGeminiAt: Long = 0L

        fun isActive(): Boolean = isEmulator
    }
}
