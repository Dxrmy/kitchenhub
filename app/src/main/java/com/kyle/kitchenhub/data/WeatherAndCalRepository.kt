package com.kyle.kitchenhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

data class DailyForecast(
    val dayLabel: String,
    val condition: String,
    val hiLo: String,
    val hiTemp: Int = 0,
    val loTemp: Int = 0,
    val precipPct: Int = 0,
    val windKmh: Int = 0,
    val dateLabel: String = ""
)
data class HourlyForecast(val hourLabel: String, val temp: Int, val condition: String, val precipPct: Int)
data class DetailedWeather(
    val location: String,
    val tempDisplay: String,
    val unitSym: String,
    val condition: String,
    val dayHi: Int,
    val nightLo: Int,
    val humidityPct: Int,
    val dewPoint: Int,
    val uvIndex: Int,
    val visibilityKm: Int,
    val pressureHpa: Int,
    val windKmh: Int,
    val sunrise: String,
    val sunset: String,
    val daily: List<DailyForecast>,
    val hourly: List<HourlyForecast>
)
data class WeatherSnapshot(
    val tempDisplay: String,
    val condition: String,
    val forecast: List<DailyForecast> = emptyList()
)
data class CalendarEventItem(val title: String, val whenText: String)
data class StickyNote(
    val id: Long,
    val text: String,
    val author: String,
    val colorHex: String,
    val acknowledged: Boolean
)

object WeatherAndCalRepository {
    suspend fun fetchWeather(cfg: KitchenConfig): WeatherSnapshot = withContext(Dispatchers.IO) {
        runCatching {
            val unit = if (cfg.useFahrenheit) "fahrenheit" else "celsius"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.latitude}&longitude=${cfg.longitude}&current_weather=true&daily=weathercode,temperature_2m_max,temperature_2m_min&timezone=auto&temperature_unit=$unit"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val root = JSONObject(body)
            val cw = root.getJSONObject("current_weather")
            val temp = cw.getDouble("temperature").toInt()
            val code = cw.getInt("weathercode")
            val sym = if (cfg.useFahrenheit) "°F" else "°C"

            val daily = root.optJSONObject("daily")
            val forecasts = mutableListOf<DailyForecast>()
            if (daily != null) {
                val dates = daily.optJSONArray("time")
                val codes = daily.optJSONArray("weathercode")
                val maxs = daily.optJSONArray("temperature_2m_max")
                val mins = daily.optJSONArray("temperature_2m_min")
                val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
                for (i in 0 until minOf(3, dates?.length() ?: 0)) {
                    val dStr = dates!!.getString(i)
                    val label = if (i == 0) "Today" else runCatching { outFmt.format(inFmt.parse(dStr)!!) }.getOrDefault(dStr)
                    val cond = weatherCodeToText(codes?.optInt(i) ?: 0)
                    val hi = maxs?.optDouble(i)?.toInt() ?: 0
                    val lo = mins?.optDouble(i)?.toInt() ?: 0
                    forecasts.add(DailyForecast(label, cond, "${hi}°/${lo}°"))
                }
            }
            WeatherSnapshot("$temp$sym", weatherCodeToText(code), forecasts)
        }.getOrDefault(WeatherSnapshot("--°", "Offline"))
    }

    suspend fun fetchDetailedWeather(cfg: KitchenConfig): DetailedWeather? = withContext(Dispatchers.IO) {
        runCatching {
            val unit = if (cfg.useFahrenheit) "fahrenheit" else "celsius"
            val sym = if (cfg.useFahrenheit) "°F" else "°C"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${cfg.latitude}&longitude=${cfg.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m,weather_code,pressure_msl,wind_speed_10m,uv_index,visibility" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset" +
                "&timezone=auto&temperature_unit=$unit"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val root = JSONObject(body)
            val cur = root.getJSONObject("current")
            val temp = cur.optDouble("temperature_2m", 0.0).toInt()
            val code = cur.optInt("weather_code", 0)
            val humidity = cur.optInt("relative_humidity_2m", 0)
            val dew = cur.optDouble("dew_point_2m", 0.0).toInt()
            val pressure = cur.optDouble("pressure_msl", 1013.0).toInt()
            val wind = cur.optDouble("wind_speed_10m", 0.0).toInt()
            val uv = cur.optDouble("uv_index", 0.0).toInt()
            val visKm = (cur.optDouble("visibility", 10000.0) / 1000.0).toInt().coerceAtLeast(1)

            val dailyObj = root.optJSONObject("daily")
            val dailyList = mutableListOf<DailyForecast>()
            var sunriseStr = "--:--"
            var sunsetStr = "--:--"
            if (dailyObj != null) {
                val dates = dailyObj.optJSONArray("time")
                val codes = dailyObj.optJSONArray("weather_code")
                val maxs = dailyObj.optJSONArray("temperature_2m_max")
                val mins = dailyObj.optJSONArray("temperature_2m_min")
                val pops = dailyObj.optJSONArray("precipitation_probability_max")
                val winds = dailyObj.optJSONArray("wind_speed_10m_max")
                val rises = dailyObj.optJSONArray("sunrise")
                val sets = dailyObj.optJSONArray("sunset")
                sunriseStr = rises?.optString(0)?.substringAfter("T")?.take(5) ?: sunriseStr
                sunsetStr = sets?.optString(0)?.substringAfter("T")?.take(5) ?: sunsetStr
                val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
                for (i in 0 until minOf(7, dates?.length() ?: 0)) {
                    val dStr = dates!!.getString(i)
                    val label = if (i == 0) "Today" else runCatching { outFmt.format(inFmt.parse(dStr)!!) }.getOrDefault(dStr)
                    val hi = maxs?.optDouble(i)?.toInt() ?: 0
                    val lo = mins?.optDouble(i)?.toInt() ?: 0
                    dailyList.add(
                        DailyForecast(
                            dayLabel = label,
                            condition = weatherCodeToText(codes?.optInt(i) ?: 0),
                            hiLo = "${hi}°/${lo}°",
                            hiTemp = hi,
                            loTemp = lo,
                            precipPct = pops?.optInt(i) ?: 0,
                            windKmh = winds?.optDouble(i)?.toInt() ?: 0,
                            dateLabel = dStr.substring(5)
                        )
                    )
                }
            }

            val hourlyObj = root.optJSONObject("hourly")
            val hourlyList = mutableListOf<HourlyForecast>()
            if (hourlyObj != null) {
                val times = hourlyObj.optJSONArray("time")
                val temps = hourlyObj.optJSONArray("temperature_2m")
                val codes = hourlyObj.optJSONArray("weather_code")
                val pops = hourlyObj.optJSONArray("precipitation_probability")
                val nowHourPrefix = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
                var startIdx = 0
                if (times != null) {
                    for (i in 0 until times.length()) {
                        if (times.getString(i) >= nowHourPrefix) {
                            startIdx = i
                            break
                        }
                    }
                    for (i in startIdx until minOf(startIdx + 12, times.length())) {
                        val hLabel = times.getString(i).substringAfter("T").take(5)
                        hourlyList.add(
                            HourlyForecast(
                                hourLabel = hLabel,
                                temp = temps?.optDouble(i)?.toInt() ?: temp,
                                condition = weatherCodeToText(codes?.optInt(i) ?: code),
                                precipPct = pops?.optInt(i) ?: 0
                            )
                        )
                    }
                }
            }

            DetailedWeather(
                location = cfg.locationName,
                tempDisplay = "$temp",
                unitSym = sym,
                condition = weatherCodeToText(code),
                dayHi = dailyList.firstOrNull()?.hiTemp ?: temp,
                nightLo = dailyList.firstOrNull()?.loTemp ?: temp,
                humidityPct = humidity,
                dewPoint = dew,
                uvIndex = uv,
                visibilityKm = visKm,
                pressureHpa = pressure,
                windKmh = wind,
                sunrise = sunriseStr,
                sunset = sunsetStr,
                daily = dailyList,
                hourly = hourlyList
            )
        }.getOrNull()
    }

    suspend fun fetchIcalEvents(icalUrl: String): List<CalendarEventItem> = withContext(Dispatchers.IO) {
        if (icalUrl.isBlank()) return@withContext emptyList()
        runCatching {
            val conn = (URL(icalUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
            }
            val lines = conn.inputStream.bufferedReader().readLines()
            conn.disconnect()
            parseIcs(lines)
        }.getOrDefault(emptyList())
    }

    private fun parseIcs(lines: List<String>): List<CalendarEventItem> {
        val out = mutableListOf<CalendarEventItem>()
        var inEvent = false
        var summary = ""
        var dtStart = ""
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; summary = ""; dtStart = "" }
                line == "END:VEVENT" -> {
                    if (inEvent && summary.isNotBlank()) out.add(CalendarEventItem(summary, dtStart.take(8)))
                    inEvent = false
                }
                inEvent && line.startsWith("SUMMARY:") -> summary = line.removePrefix("SUMMARY:").trim()
                inEvent && line.startsWith("DTSTART") -> dtStart = line.substringAfter(":").trim()
            }
            if (out.size >= 6) break
        }
        return out
    }

    private fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        95, 96, 99 -> "Storm"
        else -> "Mild"
    }
}

object StickyNoteStore {
    private const val PREFS = "kitchenhub_notes"
    private const val KEY_TEXT = "note_text"
    private const val KEY_AUTHOR = "note_author"
    private const val KEY_COLOR = "note_color"
    private const val KEY_ACK = "note_ack"
    private const val KEY_LIST = "notes_json"
    private var serverSocket: ServerSocket? = null

    fun get(context: Context): StickyNote? {
        val all = getAll(context)
        if (all.isNotEmpty()) return all.first()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val txt = p.getString(KEY_TEXT, null) ?: return null
        return StickyNote(
            id = 1L,
            text = txt,
            author = p.getString(KEY_AUTHOR, "Kyle") ?: "Kyle",
            colorHex = p.getString(KEY_COLOR, "#FFF59D") ?: "#FFF59D",
            acknowledged = p.getBoolean(KEY_ACK, false)
        )
    }

    fun getAll(context: Context): List<StickyNote> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_LIST, null)
        if (!raw.isNullOrBlank()) {
            val out = mutableListOf<StickyNote>()
            val needsReset = !p.getBoolean("migrated_lock_v3", false)
            runCatching {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val txt = o.optString("text", "")
                    if (txt.isNotBlank()) {
                        out.add(
                            StickyNote(
                                id = o.optLong("id", i.toLong() + 1L),
                                text = txt,
                                author = o.optString("author", "Kyle"),
                                colorHex = o.optString("color", "#FFF3B0"),
                                acknowledged = if (needsReset) false else o.optBoolean("ack", false)
                            )
                        )
                    }
                }
            }
            if (needsReset) {
                p.edit().putBoolean("migrated_lock_v3", true).putBoolean(KEY_ACK, false).apply()
                if (out.isNotEmpty()) persistList(context, out)
            }
            if (out.isNotEmpty()) return out
        }
        val legacyTxt = p.getString(KEY_TEXT, null) ?: return emptyList()
        return listOf(
            StickyNote(
                id = 1L,
                text = legacyTxt,
                author = p.getString(KEY_AUTHOR, "Kyle") ?: "Kyle",
                colorHex = p.getString(KEY_COLOR, "#FFF59D") ?: "#FFF59D",
                acknowledged = p.getBoolean(KEY_ACK, false)
            )
        )
    }

    fun save(context: Context, text: String, author: String, colorHex: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val existing = getAll(context).toMutableList()
        val newNote = StickyNote(
            id = System.currentTimeMillis(),
            text = trimmed,
            author = author,
            colorHex = colorHex,
            acknowledged = false
        )
        existing.add(0, newNote)
        persistList(context, existing.take(20))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, trimmed)
            .putString(KEY_AUTHOR, author)
            .putString(KEY_COLOR, colorHex)
            .putBoolean(KEY_ACK, false)
            .apply()
    }

    fun update(context: Context, id: Long, text: String, author: String, colorHex: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val updated = getAll(context).map {
            if (it.id == id) it.copy(text = trimmed, author = author, colorHex = colorHex, acknowledged = false) else it
        }
        persistList(context, updated)
        if (updated.firstOrNull()?.id == id) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEXT, trimmed)
                .putString(KEY_AUTHOR, author)
                .putString(KEY_COLOR, colorHex)
                .putBoolean(KEY_ACK, false)
                .apply()
        }
    }

    fun remove(context: Context, id: Long) {
        val remaining = getAll(context).filterNot { it.id == id }
        persistList(context, remaining)
        if (remaining.isEmpty()) {
            clear(context)
        } else {
            val top = remaining.first()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEXT, top.text)
                .putString(KEY_AUTHOR, top.author)
                .putString(KEY_COLOR, top.colorHex)
                .putBoolean(KEY_ACK, top.acknowledged)
                .apply()
        }
    }

    private fun persistList(context: Context, list: List<StickyNote>) {
        val arr = org.json.JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("text", n.text)
                put("author", n.author)
                put("color", n.colorHex)
                put("ack", n.acknowledged)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    fun acknowledge(context: Context, noteId: Long? = null) {
        val list = getAll(context).map {
            if (noteId == null || it.id == noteId) it.copy(acknowledged = true) else it
        }
        if (list.isNotEmpty()) persistList(context, list)
        val allAck = list.all { it.acknowledged }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACK, allAck)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun startHttpReceiver(context: Context, port: Int, token: String, onReceived: () -> Unit) {
        stopHttpReceiver()
        thread(isDaemon = true) {
            runCatching {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (!ss.isClosed) {
                    val sock = ss.accept()
                    runCatching {
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val first = reader.readLine() ?: ""
                        var contentLen = 0
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (line.startsWith("Content-Length:", true)) {
                                contentLen = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                            line = reader.readLine()
                        }
                        val buf = CharArray(contentLen.coerceAtMost(4096))
                        if (contentLen > 0) reader.read(buf)
                        val body = JSONObject(String(buf))
                        val ok = first.contains("/api/note") && body.optString("token") == token
                        val resp = if (ok) {
                            val txt = body.optString("text", "")
                            if (txt.isNotBlank()) {
                                save(context, txt, body.optString("author", "Kyle"), body.optString("color", "#FFF59D"))
                                onReceived()
                            }
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
                        } else {
                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 9\r\n\r\nForbidden"
                        }
                        sock.getOutputStream().write(resp.toByteArray())
                        sock.close()
                    }
                }
            }
        }
    }

    fun stopHttpReceiver() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun currentTimeLabel(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
