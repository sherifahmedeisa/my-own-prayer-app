package com.prayer.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PrayerNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "prayer_reminders_channel"
        const val CHANNEL_NAME = "Prayer Reminders"
        val VIBRATE_PATTERN = longArrayOf(0, 350, 150, 350, 150, 500)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                PrayerScheduler.scheduleReminders(context)
                return
            }
            PrayerScheduler.ACTION_PRAYER_BEFORE,
            PrayerScheduler.ACTION_PRAYER_AFTER -> {
                handleReminder(context, intent)
                // Re-queue subsequent reminders
                PrayerScheduler.scheduleReminders(context)
                PrayerLiveTickerService.updateAodNotification(context)
            }
        }
    }

    private fun handleReminder(context: Context, intent: Intent) {
        val sp = context.getSharedPreferences(PrayerScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val remindersEnabled = sp.getBoolean(PrayerScheduler.KEY_REMINDERS_ENABLED, true)
        if (!remindersEnabled) return

        val isBefore = (intent.action == PrayerScheduler.ACTION_PRAYER_BEFORE)
        val remindBefore = sp.getBoolean(PrayerScheduler.KEY_REMIND_BEFORE, true)
        val remindAfter = sp.getBoolean(PrayerScheduler.KEY_REMIND_AFTER, true)
        val hapticEnabled = sp.getBoolean(PrayerScheduler.KEY_HAPTIC_ENABLED, true)

        if (isBefore && !remindBefore) return
        if (!isBefore && !remindAfter) return

        val prayerName = intent.getStringExtra(PrayerScheduler.EXTRA_PRAYER_NAME) ?: "Prayer"
        val prayerTimeMs = intent.getLongExtra(PrayerScheduler.EXTRA_PRAYER_TIME, System.currentTimeMillis())

        val enName = PrayerConfig.EN_NAMES[prayerName] ?: prayerName.replaceFirstChar { it.uppercase() }
        val arName = PrayerConfig.AR_NAMES[prayerName] ?: ""

        val timeStr = Instant.ofEpochMilli(prayerTimeMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

        val title = if (isBefore) {
            "⏰ 15 min to $enName · $arName"
        } else {
            "🕌 15 min since $enName · $arName"
        }

        val text = if (isBefore) {
            "$enName is at $timeStr. Time to prepare and perform wudu."
        } else {
            "Have you performed $enName ($timeStr)? \"Indeed, prayer is decreed upon believers at specified times.\""
        }

        // Trigger Haptic Vibration
        if (hapticEnabled) {
            triggerVibration(context)
        }

        // Post Android Notification
        postNotification(context, title, text, prayerName.hashCode() + (if (isBefore) 1 else 2))
    }

    private fun triggerVibration(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vm?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(VIBRATE_PATTERN, -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun postNotification(context: Context, title: String, message: String, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        createNotificationChannel(nm)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setStyle(Notification.BigTextStyle().bigText(message))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(Color.parseColor("#F5C030"))
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        @Suppress("DEPRECATION")
        builder.setSound(soundUri)

        nm.notify(notificationId, builder.build())
    }

    private fun createNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "15-minute reminders before and after prayer times"
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                    enableLights(true)
                    lightColor = Color.parseColor("#F5C030")
                    setSound(soundUri, audioAttributes)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
