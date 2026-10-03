package com.prayer.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PrayerLiveTickerService {

    const val CHANNEL_AOD_ID = "prayer_aod_ticker_channel"
    const val NOTIFICATION_ID = 9999
    const val KEY_AOD_ENABLED = "aod_live_countdown"

    fun updateAodNotification(context: Context) {
        val sp = context.getSharedPreferences(PrayerScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val aodEnabled = sp.getBoolean(KEY_AOD_ENABLED, false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (!aodEnabled) {
            nm.cancel(NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_AOD_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_AOD_ID,
                    "Lock Screen & AOD Live Countdown",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live countdown ticker on Lock Screen and Always On Display"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        val now = System.currentTimeMillis()
        val nextInfo = PrayerRepository.getNextPrayer(context, now)
        val nextName = nextInfo.name
        val enName = PrayerConfig.EN_NAMES[nextName] ?: nextName.replaceFirstChar { it.uppercase() }
        val arName = PrayerConfig.AR_NAMES[nextName] ?: ""
        val targetTimeStr = Instant.ofEpochMilli(nextInfo.at)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_AOD_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setContentTitle("Next: $enName · $arName (at $targetTimeStr)")
            .setContentText("Prayer times live countdown")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true)
        }
        builder.setWhen(nextInfo.at)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(Color.parseColor("#F5C030"))
            builder.setCategory(Notification.CATEGORY_STATUS)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }
}
