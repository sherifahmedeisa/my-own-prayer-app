package com.prayer.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate

object PrayerScheduler {

    const val PREFS_NAME = "prayer_cache"
    const val KEY_REMINDERS_ENABLED = "reminders_enabled"
    const val KEY_REMIND_BEFORE = "remind_before"
    const val KEY_REMIND_AFTER = "remind_after"
    const val KEY_HAPTIC_ENABLED = "haptic_enabled"

    const val ACTION_PRAYER_BEFORE = "com.prayer.widget.ACTION_PRAYER_BEFORE"
    const val ACTION_PRAYER_AFTER = "com.prayer.widget.ACTION_PRAYER_AFTER"

    const val EXTRA_PRAYER_NAME = "extra_prayer_name"
    const val EXTRA_PRAYER_TIME = "extra_prayer_time"

    const val REMINDER_OFFSET_MS = 15 * 60 * 1000L // 15 minutes

    fun scheduleReminders(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remindersEnabled = sp.getBoolean(KEY_REMINDERS_ENABLED, true)
        val remindBefore = sp.getBoolean(KEY_REMIND_BEFORE, true)
        val remindAfter = sp.getBoolean(KEY_REMIND_AFTER, true)

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // If all disabled, cancel everything
        if (!remindersEnabled || (!remindBefore && !remindAfter)) {
            cancelAllReminders(context, am)
            return
        }

        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val daysToSchedule = listOf(today, today.plusDays(1))

        for (date in daysToSchedule) {
            val dayTimes = PrayerRepository.getDayTimes(context, date).times
            for (p in PrayerConfig.PRAYERS) {
                val prayerTimeMs = dayTimes[p] ?: continue

                // 15 min before
                val timeBefore = prayerTimeMs - REMINDER_OFFSET_MS
                if (remindBefore && timeBefore > now) {
                    val code = getRequestCode(date, p, isBefore = true)
                    val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
                        action = ACTION_PRAYER_BEFORE
                        putExtra(EXTRA_PRAYER_NAME, p)
                        putExtra(EXTRA_PRAYER_TIME, prayerTimeMs)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        code,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    scheduleAlarm(am, timeBefore, pi)
                }

                // 15 min after
                val timeAfter = prayerTimeMs + REMINDER_OFFSET_MS
                if (remindAfter && timeAfter > now) {
                    val code = getRequestCode(date, p, isBefore = false)
                    val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
                        action = ACTION_PRAYER_AFTER
                        putExtra(EXTRA_PRAYER_NAME, p)
                        putExtra(EXTRA_PRAYER_TIME, prayerTimeMs)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        code,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    scheduleAlarm(am, timeAfter, pi)
                }
            }
        }
    }

    private fun scheduleAlarm(am: AlarmManager, triggerAtMs: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelAllReminders(context: Context, am: AlarmManager? = null) {
        val manager = am ?: (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager) ?: return
        val today = LocalDate.now()
        val days = listOf(today, today.plusDays(1), today.plusDays(2))

        for (date in days) {
            for (p in PrayerConfig.PRAYERS) {
                for (isBefore in listOf(true, false)) {
                    val code = getRequestCode(date, p, isBefore)
                    val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
                        action = if (isBefore) ACTION_PRAYER_BEFORE else ACTION_PRAYER_AFTER
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        code,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pi != null) {
                        manager.cancel(pi)
                        pi.cancel()
                    }
                }
            }
        }
    }

    private fun getRequestCode(date: LocalDate, prayer: String, isBefore: Boolean): Int {
        val dayOffset = (date.dayOfYear % 30) * 100
        val prayerIndex = PrayerConfig.PRAYERS.indexOf(prayer).coerceAtLeast(0) * 10
        val typeOffset = if (isBefore) 1 else 2
        return dayOffset + prayerIndex + typeOffset
    }
}
