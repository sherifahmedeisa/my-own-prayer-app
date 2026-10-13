package com.prayer.widget

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.time.LocalDate

object PrayerNetworkMonitor {

    private var isRegistered = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register(context: Context, onNetworkAvailable: (() -> Unit)? = null) {
        if (isRegistered) return
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Connection restored: verify if today has been synced with UmmahAPI
                val today = LocalDate.now()
                val needsSync = !PrayerRepository.isSyncedForDate(appContext, today)

                if (needsSync) {
                    Thread {
                        try {
                            LocationHelper.updateDeviceLocation(appContext)
                            PrayerRepository.syncDaily(appContext, today)
                            PrayerRepository.syncDaily(appContext, today.plusDays(1))
                            PrayerRepository.syncMonth(appContext, today.year, today.monthValue)

                            appContext.sendBroadcast(
                                Intent(appContext, PrayerWidget::class.java)
                                    .setAction(PrayerWidget.ACTION_REFRESH)
                            )
                            PrayerLiveTickerService.updateAodNotification(appContext)
                            PrayerScheduler.scheduleReminders(appContext)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                }

                onNetworkAvailable?.invoke()
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback!!)
            isRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregister(context: Context) {
        if (!isRegistered || callback == null) return
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isRegistered = false
            callback = null
        }
    }
}
