package com.kyle.kitchenhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SmartHomeDevice(
    val id: String,
    val name: String,
    val kind: String, // "switch", "light", "climate"
    var isOn: Boolean = false,
    var targetTempC: Int = 21
)

/**
 * Configurable Smart Home repository supporting:
 * - Google Home (OAuth Client ID + Project ID, ready for Google Home Android SDK AARs)
 * - Home Assistant REST API (when haBaseUrl + haToken are configured)
 * - Local fallback state
 */
object SmartHomeRepository {

    private const val PREFS = "kitchenhub_smarthome"

    suspend fun fetchDevices(context: Context): List<SmartHomeDevice> = withContext(Dispatchers.IO) {
        val cfg = ConfigRepository.get(context)
        if (cfg.smartHomeBackend == "GOOGLE_HOME") {
            return@withContext GoogleHomeBridge.fetchDevices(context)
        }
        if (cfg.smartHomeBackend == "HOME_ASSISTANT" && cfg.haBaseUrl.isNotBlank() && cfg.haToken.isNotBlank()) {
            return@withContext fetchFromHomeAssistant(cfg.haBaseUrl, cfg.haToken)
        }
        // No connected backend: show nothing (no placeholder devices)
        emptyList()
    }

    suspend fun toggleDevice(context: Context, dev: SmartHomeDevice, turnOn: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cfg = ConfigRepository.get(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(dev.id, turnOn).apply()
        if (cfg.smartHomeBackend == "GOOGLE_HOME" && GoogleHomeBridge.toggleDevice(context, dev.id, turnOn)) {
            return@withContext true
        }
        if (cfg.smartHomeBackend == "HOME_ASSISTANT" && cfg.haBaseUrl.isNotBlank() && cfg.haToken.isNotBlank()) {
            val domain = if (dev.id.startsWith("light.")) "light" else "homeassistant"
            val service = if (turnOn) "turn_on" else "turn_off"
            return@withContext callHaService(cfg.haBaseUrl, cfg.haToken, domain, service, JSONObject().put("entity_id", dev.id))
        }
        true
    }

    suspend fun setThermostat(context: Context, dev: SmartHomeDevice, tempC: Int): Boolean = withContext(Dispatchers.IO) {
        val cfg = ConfigRepository.get(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("thermostat", tempC).apply()
        if (cfg.smartHomeBackend == "GOOGLE_HOME" && GoogleHomeBridge.setThermostat(context, dev.id, tempC)) {
            return@withContext true
        }
        if (cfg.smartHomeBackend == "HOME_ASSISTANT" && cfg.haBaseUrl.isNotBlank() && cfg.haToken.isNotBlank()) {
            return@withContext callHaService(
                cfg.haBaseUrl, cfg.haToken, "climate", "set_temperature",
                JSONObject().put("entity_id", dev.id).put("temperature", tempC)
            )
        }
        true
    }

    private fun fetchFromHomeAssistant(baseUrl: String, token: String): List<SmartHomeDevice> {
        val out = mutableListOf<SmartHomeDevice>()
        runCatching {
            val conn = (URL("${baseUrl.trimEnd('/')}/api/states").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode in 200..299) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("entity_id")
                    val domain = id.substringBefore('.')
                    if (domain !in listOf("light", "switch", "climate")) continue
                    val attrs = o.optJSONObject("attributes")
                    val friendly = attrs?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id
                    val state = o.optString("state")
                    val temp = attrs?.optDouble("temperature", 21.0)?.toInt() ?: 21
                    out.add(SmartHomeDevice(id, friendly, domain, state == "on" || state == "heat", temp))
                    if (out.size >= 8) break
                }
            }
            conn.disconnect()
        }
        return out
    }

    private fun callHaService(baseUrl: String, token: String, domain: String, service: String, body: JSONObject): Boolean {
        return runCatching {
            val conn = (URL("${baseUrl.trimEnd('/')}/api/services/$domain/$service").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 3000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }
}
