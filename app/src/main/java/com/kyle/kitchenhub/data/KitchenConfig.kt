package com.kyle.kitchenhub.data

import org.json.JSONObject

data class KitchenConfig(
    val setupCompleted: Boolean = false,
    // Appearance & M3 Expressive
    val dynamicColorSource: String = "WALLPAPER", // WALLPAPER, ALBUM_ART, MANUAL
    val manualAccentHex: String = "#A8C7FA",
    val blurRadius: Int = 18,
    val reduceTransparency: Boolean = false,
    val sparklesEnabled: Boolean = true,
    val tactileAudioEnabled: Boolean = true,
    val tactileVolume: Float = 0.65f,
    // StandBy Clock & Display
    val clockFace: String = "FLOAT", // FLOAT, SOLAR, DIGITAL, ANALOG, MINIMAL, SPLIT, PHOTOS
    val clockPaletteIndex: Int = 0,
    val idleTimeoutSec: Int = 60, // 30, 60, 120, 300, 0(Off)
    val pixelShiftPx: Int = 6,
    val pixelShiftIntervalSec: Int = 60,
    val nightModeStartHour: Int = 22,
    val nightModeEndHour: Int = 7,
    val nightBrightness: Int = 18,
    // Voice, Wake Word & Audio
    val wakeWordModel: String = "hey_google.onnx",
    val customModelPath: String = "",
    val wakeWordThreshold: Float = 0.25f,
    val bargeInDuckPercent: Int = 35,
    val nightVolumeCapEnabled: Boolean = true,
    val nightVolumeCapPercent: Int = 30,
    // Timers, Sticky Notes & Macros
    val timerPresets: String = "Pasta:10,Oven:25,Tea:3,Roast:45",
    val wakeWordlessAlarmStop: Boolean = true,
    val stickyFont: String = "CAVEAT", // CAVEAT, PATRICK_HAND
    val stickyHttpEnabled: Boolean = false,
    val stickyHttpPort: Int = 8765,
    val stickyHttpToken: String = "kitchen123",
    val lockScreenMaxNotes: Int = 3,
    // Google & UK Ecosystem
    val locationName: String = "Solihull, UK",
    val latitude: Double = 52.4118,
    val longitude: Double = -1.7776,
    val useFahrenheit: Boolean = false,
    val icalUrl: String = "",
    val photoFolderPath: String = "/sdcard/Pictures",
    val photoIntervalSec: Int = 20,
    val rtspUrl: String = "",
    // Smart Home & Google Home OAuth
    val smartHomeBackend: String = "GOOGLE_HOME", // GOOGLE_HOME, HOME_ASSISTANT, LOCAL
    val googleProjectId: String = "kitchenhub-515921",
    val googleOAuthClientId: String = "444388916200-bgqlu3716ssnbtijipj3inkqjs9ca64h.apps.googleusercontent.com",
    val googleAccountEmail: String = "",
    val haBaseUrl: String = "",
    val haToken: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("setupCompleted", setupCompleted)
        put("dynamicColorSource", dynamicColorSource)
        put("manualAccentHex", manualAccentHex)
        put("blurRadius", blurRadius)
        put("reduceTransparency", reduceTransparency)
        put("sparklesEnabled", sparklesEnabled)
        put("tactileAudioEnabled", tactileAudioEnabled)
        put("tactileVolume", tactileVolume.toDouble())
        put("clockFace", clockFace)
        put("clockPaletteIndex", clockPaletteIndex)
        put("idleTimeoutSec", idleTimeoutSec)
        put("pixelShiftPx", pixelShiftPx)
        put("pixelShiftIntervalSec", pixelShiftIntervalSec)
        put("nightModeStartHour", nightModeStartHour)
        put("nightModeEndHour", nightModeEndHour)
        put("nightBrightness", nightBrightness)
        put("wakeWordModel", wakeWordModel)
        put("customModelPath", customModelPath)
        put("wakeWordThreshold", wakeWordThreshold.toDouble())
        put("bargeInDuckPercent", bargeInDuckPercent)
        put("nightVolumeCapEnabled", nightVolumeCapEnabled)
        put("nightVolumeCapPercent", nightVolumeCapPercent)
        put("timerPresets", timerPresets)
        put("wakeWordlessAlarmStop", wakeWordlessAlarmStop)
        put("stickyFont", stickyFont)
        put("stickyHttpEnabled", stickyHttpEnabled)
        put("stickyHttpPort", stickyHttpPort)
        put("stickyHttpToken", stickyHttpToken)
        put("lockScreenMaxNotes", lockScreenMaxNotes)
        put("locationName", locationName)
        put("latitude", latitude)
        put("longitude", longitude)
        put("useFahrenheit", useFahrenheit)
        put("icalUrl", icalUrl)
        put("photoFolderPath", photoFolderPath)
        put("photoIntervalSec", photoIntervalSec)
        put("rtspUrl", rtspUrl)
        put("smartHomeBackend", smartHomeBackend)
        put("googleProjectId", googleProjectId)
        put("googleOAuthClientId", googleOAuthClientId)
        put("googleAccountEmail", googleAccountEmail)
        put("haBaseUrl", haBaseUrl)
        put("haToken", haToken)
    }

    companion object {
        fun fromJson(j: JSONObject): KitchenConfig = KitchenConfig(
            setupCompleted = j.optBoolean("setupCompleted", false),
            dynamicColorSource = j.optString("dynamicColorSource", "WALLPAPER"),
            manualAccentHex = j.optString("manualAccentHex", "#A8C7FA"),
            blurRadius = j.optInt("blurRadius", 18),
            reduceTransparency = j.optBoolean("reduceTransparency", false),
            sparklesEnabled = j.optBoolean("sparklesEnabled", true),
            tactileAudioEnabled = j.optBoolean("tactileAudioEnabled", true),
            tactileVolume = j.optDouble("tactileVolume", 0.65).toFloat(),
            clockFace = j.optString("clockFace", "FLOAT"),
            clockPaletteIndex = j.optInt("clockPaletteIndex", 0),
            idleTimeoutSec = j.optInt("idleTimeoutSec", 60),
            pixelShiftPx = j.optInt("pixelShiftPx", 6),
            pixelShiftIntervalSec = j.optInt("pixelShiftIntervalSec", 60),
            nightModeStartHour = j.optInt("nightModeStartHour", 22),
            nightModeEndHour = j.optInt("nightModeEndHour", 7),
            nightBrightness = j.optInt("nightBrightness", 18),
            wakeWordModel = j.optString("wakeWordModel", "hey_google.onnx"),
            customModelPath = j.optString("customModelPath", ""),
            wakeWordThreshold = j.optDouble("wakeWordThreshold", 0.25).toFloat(),
            bargeInDuckPercent = j.optInt("bargeInDuckPercent", 35),
            nightVolumeCapEnabled = j.optBoolean("nightVolumeCapEnabled", true),
            nightVolumeCapPercent = j.optInt("nightVolumeCapPercent", 30),
            timerPresets = j.optString("timerPresets", "Pasta:10,Oven:25,Tea:3,Roast:45"),
            wakeWordlessAlarmStop = j.optBoolean("wakeWordlessAlarmStop", true),
            stickyFont = j.optString("stickyFont", "CAVEAT"),
            stickyHttpEnabled = j.optBoolean("stickyHttpEnabled", false),
            stickyHttpPort = j.optInt("stickyHttpPort", 8765),
            stickyHttpToken = j.optString("stickyHttpToken", "kitchen123"),
            lockScreenMaxNotes = j.optInt("lockScreenMaxNotes", 3),
            locationName = j.optString("locationName", "Solihull, UK"),
            latitude = j.optDouble("latitude", 52.4118),
            longitude = j.optDouble("longitude", -1.7776),
            useFahrenheit = j.optBoolean("useFahrenheit", false),
            icalUrl = j.optString("icalUrl", ""),
            photoFolderPath = j.optString("photoFolderPath", "/sdcard/Pictures"),
            photoIntervalSec = j.optInt("photoIntervalSec", 20),
            rtspUrl = j.optString("rtspUrl", ""),
            smartHomeBackend = j.optString("smartHomeBackend", "GOOGLE_HOME"),
            googleProjectId = j.optString("googleProjectId", "kitchenhub-515921"),
            googleOAuthClientId = j.optString(
                "googleOAuthClientId",
                "444388916200-bgqlu3716ssnbtijipj3inkqjs9ca64h.apps.googleusercontent.com"
            ),
            googleAccountEmail = j.optString("googleAccountEmail", ""),
            haBaseUrl = j.optString("haBaseUrl", ""),
            haToken = j.optString("haToken", "")
        )
    }
}
