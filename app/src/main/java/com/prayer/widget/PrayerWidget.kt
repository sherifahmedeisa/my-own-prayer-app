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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PrayerWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.prayer.widget.ACTION_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try {
                        refresh(appContext)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                refresh(appContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun pendingBroadcast(context: Context, code: Int): PendingIntent {
        val intent = Intent(context, PrayerWidget::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingActivity(context: Context, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refresh(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget)

        // Tapping the widget body opens MainActivity
        views.setOnClickPendingIntent(R.id.widget_root, pendingActivity(context, 100))

        // Tapping the refresh text triggers instant refresh
        views.setOnClickPendingIntent(R.id.btn_refresh, pendingBroadcast(context, 101))

        try {
            val now = System.currentTimeMillis()
            val nextPrayerInfo = PrayerRepository.getNextPrayer(context, now)
            val schedule = nextPrayerInfo.schedule
            val nextName = nextPrayerInfo.name
            val nextAt = nextPrayerInfo.at

            val zone = ZoneId.systemDefault()
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm")

            // Update title
            views.setTextViewText(R.id.widget_title, "Prayer Times · ${PrayerConfig.CITY_NAME.split(",")[0]}")

            // Update Next label
            val enName = PrayerConfig.EN_NAMES[nextName] ?: nextName.replaceFirstChar { it.uppercase() }
            val arName = PrayerConfig.AR_NAMES[nextName] ?: ""
            views.setTextViewText(R.id.label, "NEXT: ${enName.uppercase()} · $arName")

            // Configure live chronometer countdown
            views.setViewVisibility(R.id.count, View.VISIBLE)
            views.setChronometerCountDown(R.id.count, true)
            val remainingRealtime = SystemClock.elapsedRealtime() + (nextAt - now)
            views.setChronometer(R.id.count, remainingRealtime, null, true)

            // Update prayer columns
            val rowViews = mapOf(
                "fajr" to R.id.p_fajr,
                "dhuhr" to R.id.p_dhuhr,
                "asr" to R.id.p_asr,
                "maghrib" to R.id.p_maghrib,
                "isha" to R.id.p_isha
            )

            for ((p, viewId) in rowViews) {
                val prayerTimeMs = schedule[p]
                val timeStr = if (prayerTimeMs != null) {
                    Instant.ofEpochMilli(prayerTimeMs).atZone(zone).format(timeFormatter)
                } else "--:--"

                val label = (PrayerConfig.EN_NAMES[p] ?: p).replaceFirstChar { it.uppercase() }
                views.setTextViewText(viewId, "$label\n$timeStr")

                if (p == nextName) {
                    views.setTextColor(viewId, Color.parseColor("#F5C030"))
                    views.setInt(viewId, "setBackgroundResource", R.drawable.widget_active_prayer_bg)
                } else if (prayerTimeMs != null && prayerTimeMs < now) {
                    views.setTextColor(viewId, Color.parseColor("#526B5C"))
                    views.setInt(viewId, "setBackgroundResource", 0)
                } else {
                    views.setTextColor(viewId, Color.parseColor("#F4F7F5"))
                    views.setInt(viewId, "setBackgroundResource", 0)
                }
            }

            // Schedule alarm for the exact moment the next prayer arrives
            scheduleNextAlarm(context, nextAt + 1200L)

        } catch (e: Exception) {
            e.printStackTrace()
            views.setTextViewText(R.id.label, "Prayer Times · Tap to refresh")
            views.setViewVisibility(R.id.count, View.GONE)
            scheduleNextAlarm(context, System.currentTimeMillis() + 15 * 60_000L)
        }

        mgr.updateAppWidget(ids, views)
    }

    private fun scheduleNextAlarm(context: Context, triggerAtMillis: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingBroadcast(context, 200)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
