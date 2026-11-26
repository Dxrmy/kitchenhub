package com.kyle.kitchenhub.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import com.kyle.kitchenhub.core.env.DeviceEnvironment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfficialAlarmItem(
    val timeText: String,
    val subtitle: String,
    val showIntent: PendingIntent? = null
)

object SystemAlarmsRepository {

    fun getEnabledOfficialAlarms(context: Context): List<OfficialAlarmItem> {
        val results = mutableListOf<OfficialAlarmItem>()
        val is24 = DateFormat.is24HourFormat(context)
        val timePattern = if (is24) "HH:mm" else "h:mm a"

        // 1. Primary official Android SDK source: AlarmManager.getNextAlarmClock()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val nextInfo = am?.nextAlarmClock
        if (nextInfo != null && nextInfo.triggerTime > 0L) {
            val triggerDate = Date(nextInfo.triggerTime)
            val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(triggerDate)
            val dayStr = SimpleDateFormat("EEE", Locale.getDefault()).format(triggerDate)
            val sysFormatted = runCatching {
                Settings.System.getString(context.contentResolver, Settings.System.NEXT_ALARM_FORMATTED)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val sub = if (sysFormatted != null) "$sysFormatted • Official Alarm" else "$dayStr • Official Alarm"
            results.add(OfficialAlarmItem(timeStr, sub, nextInfo.showIntent))
        }

        // 2. On rooted physical target (Echo Show 8 / LineageOS), also enumerate any additional enabled DeskClock alarms
        if (DeviceEnvironment.hasRoot && !DeviceEnvironment.isEmulator) {
            runCatching {
                val dbPaths = listOf(
                    "/data/user_de/0/com.android.deskclock/databases/alarms.db",
                    "/data/user_de/0/com.google.android.deskclock/databases/alarms.db"
                )
                for (db in dbPaths) {
                    val cmd = "sqlite3 $db 'SELECT hour, minutes, label FROM alarm_templates WHERE enabled=1 ORDER BY hour, minutes;'"
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    val lines = proc.inputStream.bufferedReader().readLines()
                    proc.waitFor()
                    for (line in lines) {
                        val parts = line.split('|')
                        if (parts.size >= 2) {
                            val h = parts[0].toIntOrNull() ?: continue
                            val m = parts[1].toIntOrNull() ?: continue
                            val lbl = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Official Alarm"
                            val formatted = formatHourMin(h, m, is24)
                            if (results.none { it.timeText.equals(formatted, ignoreCase = true) }) {
                                results.add(OfficialAlarmItem(formatted, lbl, nextInfo?.showIntent))
                            }
                        }
                    }
                }
            }
        }

        return results
    }

    private fun formatHourMin(hour24: Int, min: Int, is24: Boolean): String {
        val mm = min.toString().padStart(2, '0')
        return if (is24) {
            "${hour24.toString().padStart(2, '0')}:$mm"
        } else {
            val amPm = if (hour24 < 12) "AM" else "PM"
            val h12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            "$h12:$mm $amPm"
        }
    }

    fun openOfficialAlarmsUi(context: Context, specificShowIntent: PendingIntent? = null) {
        if (specificShowIntent != null) {
            if (runCatching { specificShowIntent.send() }.isSuccess) return
        }

        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(showAlarms) }.isSuccess) return

        val setAlarm = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(setAlarm) }.isSuccess) return

        for (pkg in listOf("com.google.android.deskclock", "com.android.deskclock")) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null && runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return
            }
        }
        Toast.makeText(context, "No default Alarms app found", Toast.LENGTH_SHORT).show()
    }

    fun startOfficialTimer(context: Context, label: String, minutes: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, (minutes * 60).coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
