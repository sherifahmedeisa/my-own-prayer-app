package com.prayer.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Change these to your location, then rebuild. */
object Config {
    const val LAT = 30.0444
    const val LNG = 31.2357
    const val METHOD = "Egyptian"
    const val MADHAB = "Shafi"
}

object Data {
    val PRAYERS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

    private fun field(o: JSONObject?, k: String): String? {
        if (o == null) return null
        val keys = o.keys()
        while (keys.hasNext()) {
            val n = keys.next()
            if (n.equals(k, ignoreCase = true)) return o.optString(n)
        }
        return null
    }

    /** One request per day, cached on the phone. Returns prayer name -> epoch millis. */
    fun day(ctx: Context, d: LocalDate): Map<String, Long> {
        val sp = ctx.getSharedPreferences("pt", Context.MODE_PRIVATE)
        val key = "d_$d"
        val cached = sp.getString(key, null)
        if (cached != null) {
            val j = JSONObject(cached)
            return PRAYERS.associateWith { j.getLong(it) }
        }
        val u = "https://www.ummahapi.com/api/prayer-times?lat=${Config.LAT}&lng=${Config.LNG}" +
            "&method=${Config.METHOD}&madhab=${Config.MADHAB}&date=$d"
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val data = JSONObject(body).getJSONObject("data")
        val dt = data.optJSONObject("prayer_datetimes")
        val pt = data.optJSONObject("prayer_times")
        val out = JSONObject()
        for (p in PRAYERS) {
            var ms: Long? = field(dt, p)?.let {
                runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            }
            if (ms == null) {
                ms = field(pt, p)?.let {
                    runCatching {
                        d.atTime(LocalTime.parse(it)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                }
            }
            if (ms == null) throw IllegalStateException("missing $p")
            out.put(p, ms)
        }
        val edit = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("d_") && k < "d_${LocalDate.now()}") edit.remove(k)
        edit.putString(key, out.toString()).apply()
        return PRAYERS.associateWith { out.getLong(it) }
    }
}

class PrayerWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_REFRESH = "com.prayer.widget.REFRESH"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_REFRESH, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                val pr = goAsync()
                val app = ctx.applicationContext
                Thread {
                    try { refresh(app) } catch (e: Exception) { } finally { pr.finish() }
                }.start()
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun pending(ctx: Context, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, PrayerWidget::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrayerWidget::class.java))
        if (ids.isEmpty()) return
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        v.setOnClickPendingIntent(R.id.root, pending(ctx, 0))
        try {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val day = Data.day(ctx, today)
            val up = Data.PRAYERS.firstOrNull { day.getValue(it) > now }
            val next: String
            val nextAt: Long
            if (up != null) {
                next = up
                nextAt = day.getValue(up)
            } else {
                next = "fajr"
                nextAt = try {
                    Data.day(ctx, today.plusDays(1)).getValue("fajr")
                } catch (e: Exception) {
                    day.getValue("fajr") + 86_400_000L
                }
            }
            val fmt = DateTimeFormatter.ofPattern("h:mm")
            val zone = ZoneId.systemDefault()
            val rows = mapOf(
                "fajr" to R.id.p_fajr, "dhuhr" to R.id.p_dhuhr, "asr" to R.id.p_asr,
                "maghrib" to R.id.p_maghrib, "isha" to R.id.p_isha
            )
            for ((p, id) in rows) {
                val t = Instant.ofEpochMilli(day.getValue(p)).atZone(zone).format(fmt)
                v.setTextViewText(id, cap(p) + "\n" + t)
                v.setTextColor(id, Color.parseColor(if (p == next && up != null) "#f5c030" else "#f4f7f5"))
            }
            v.setViewVisibility(R.id.count, View.VISIBLE)
            v.setChronometerCountDown(R.id.count, true)
            v.setChronometer(R.id.count, SystemClock.elapsedRealtime() + (nextAt - now), null, true)
            v.setTextViewText(R.id.label, "Next: " + cap(next))
            schedule(ctx, nextAt + 1500)
        } catch (e: Exception) {
            v.setViewVisibility(R.id.count, View.GONE)
            v.setTextViewText(R.id.label, "Can't load times. Tap to retry")
            schedule(ctx, System.currentTimeMillis() + 15 * 60_000L)
        }
        mgr.updateAppWidget(ids, v)
    }

    private fun schedule(ctx: Context, at: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx, 1)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) { }
    }
}
