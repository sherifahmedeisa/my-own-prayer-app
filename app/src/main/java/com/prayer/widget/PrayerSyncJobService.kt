package com.prayer.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate

class PrayerSyncJobService : JobService() {

    companion object {
        const val JOB_ID_PERIODIC = 1001
        const val JOB_ID_IMMEDIATE = 1002

        /**
         * Schedules a daily background sync job with the Android OS.
         * The OS ensures it runs daily and WAITS until the device is online.
         */
        fun scheduleDailySync(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, PrayerSyncJobService::class.java)

            val builder = JobInfo.Builder(JOB_ID_PERIODIC, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // WAITS FOR INTERNET!
                .setPeriodic(24 * 60 * 60 * 1000L) // Daily periodic
                .setPersisted(true) // Survives device reboots

            js.schedule(builder.build())
        }

        /**
         * Schedules a one-time immediate sync that waits for network connectivity.
         * If the device is currently offline, Android OS holds this job and executes it
         * immediately once the connection is restored.
         */
        fun scheduleImmediateOnlineSync(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, PrayerSyncJobService::class.java)

            val builder = JobInfo.Builder(JOB_ID_IMMEDIATE, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // Runs as soon as online!
                .setMinimumLatency(1000L) // Execute right after network connects

            js.schedule(builder.build())
        }
    }

    private var workerThread: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        workerThread = Thread {
            try {
                // 1. Try to refresh location if GPS/network permission is granted
                LocationHelper.updateDeviceLocation(applicationContext)

                // 2. Fetch daily prayer times for today and tomorrow
                val today = LocalDate.now()
                PrayerRepository.syncDaily(applicationContext, today)
                PrayerRepository.syncDaily(applicationContext, today.plusDays(1))

                // 3. Sync full month calendar
                PrayerRepository.syncMonth(applicationContext, today.year, today.monthValue)

                // 4. Update home widget
                sendBroadcast(
                    Intent(applicationContext, PrayerWidget::class.java)
                        .setAction(PrayerWidget.ACTION_REFRESH)
                )

                // 5. Update Always On Display (AOD) ticker
                PrayerLiveTickerService.updateAodNotification(applicationContext)

                // 6. Reschedule reminders
                PrayerScheduler.scheduleReminders(applicationContext)

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                jobFinished(params, false)
            }
        }.apply { start() }

        return true // Work is ongoing asynchronously on background thread
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        workerThread?.interrupt()
        return true // Reschedule if cancelled before completion
    }
}
