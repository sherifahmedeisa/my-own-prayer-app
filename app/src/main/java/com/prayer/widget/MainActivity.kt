package com.prayer.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var nextPrayerAt: Long = 0L
    private var nextPrayerName: String = ""
    private var isUpdating = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupListeners()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        handler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btn_add_widget)?.setOnClickListener {
            pinWidgetToHomeScreen()
        }

        findViewById<Button>(R.id.btn_sync)?.setOnClickListener {
            syncData()
        }
    }

    private fun pinWidgetToHomeScreen() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, PrayerWidget::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
            Toast.makeText(this, "Placing Prayer Times widget on Home Screen...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Long-press an empty spot on your home screen, tap 'Widgets', and drag Prayer Times out.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun syncData() {
        val btn = findViewById<Button>(R.id.btn_sync)
        btn?.isEnabled = false
        btn?.text = "Syncing with UmmahAPI..."

        Thread {
            val today = LocalDate.now()
            val success = PrayerRepository.syncMonth(this, today.year, today.monthValue)

            // Trigger widget refresh
            sendBroadcast(Intent(this, PrayerWidget::class.java).setAction(PrayerWidget.ACTION_REFRESH))

            runOnUiThread {
                btn?.isEnabled = true
                btn?.text = "↻ Sync Month from UmmahAPI"
                if (success) {
                    Toast.makeText(this, "Successfully synced 30-day prayer times!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sync completed (offline fallback ready).", Toast.LENGTH_SHORT).show()
                }
                loadData()
            }
        }.start()
    }

    private fun loadData() {
        if (isUpdating) return
        isUpdating = true

        Thread {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val nextPrayerInfo = PrayerRepository.getNextPrayer(this, now)
            val syncStatus = PrayerRepository.getSyncStatusText(this)

            runOnUiThread {
                displayData(today, nextPrayerInfo, syncStatus)
                isUpdating = false
            }
        }.start()
    }

    private fun displayData(today: LocalDate, nextPrayer: NextPrayer, syncStatus: String) {
        val zone = ZoneId.systemDefault()
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

        // Subtitle & Status
        findViewById<TextView>(R.id.tv_subtitle)?.text = "${PrayerConfig.CITY_NAME} · ${today.format(dateFormatter)}"
        findViewById<TextView>(R.id.tv_sync_badge)?.text = syncStatus

        // Next prayer info
        nextPrayerAt = nextPrayer.at
        nextPrayerName = nextPrayer.name

        val enName = PrayerConfig.EN_NAMES[nextPrayer.name] ?: nextPrayer.name
        val arName = PrayerConfig.AR_NAMES[nextPrayer.name] ?: ""
        findViewById<TextView>(R.id.tv_hero_prayer_name)?.text = "$enName · $arName"

        val targetTimeStr = Instant.ofEpochMilli(nextPrayer.at).atZone(zone).format(timeFormatter)
        findViewById<TextView>(R.id.tv_hero_prayer_time)?.text = "at $targetTimeStr"

        updateCountdown()

        // Update schedule rows
        val schedule = nextPrayer.schedule
        val now = System.currentTimeMillis()

        val rows = listOf(
            Triple("fajr", R.id.row_fajr, R.id.time_fajr),
            Triple("sunrise", R.id.row_sunrise, R.id.time_sunrise),
            Triple("dhuhr", R.id.row_dhuhr, R.id.time_dhuhr),
            Triple("asr", R.id.row_asr, R.id.time_asr),
            Triple("maghrib", R.id.row_maghrib, R.id.time_maghrib),
            Triple("isha", R.id.row_isha, R.id.time_isha)
        )

        for ((p, rowId, timeViewId) in rows) {
            val timeMs = schedule[p]
            val timeView = findViewById<TextView>(timeViewId)
            val rowView = findViewById<View>(rowId)

            if (timeMs != null) {
                timeView?.text = Instant.ofEpochMilli(timeMs).atZone(zone).format(timeFormatter)
            } else {
                timeView?.text = "--:--"
            }

            if (p == nextPrayer.name) {
                rowView?.setBackgroundResource(R.drawable.card_active_bg)
                timeView?.setTextColor(Color.parseColor("#F5C030"))
            } else if (timeMs != null && timeMs < now) {
                rowView?.setBackgroundResource(0)
                timeView?.setTextColor(Color.parseColor("#526B5C"))
            } else {
                rowView?.setBackgroundResource(0)
                timeView?.setTextColor(Color.parseColor("#F4F7F5"))
            }
        }
    }

    private fun updateCountdown() {
        if (nextPrayerAt <= 0) return
        val now = System.currentTimeMillis()
        val diffSeconds = (nextPrayerAt - now) / 1000

        if (diffSeconds <= 0) {
            findViewById<TextView>(R.id.tv_hero_countdown)?.text = "00:00:00"
            loadData()
            return
        }

        val hours = diffSeconds / 3600
        val minutes = (diffSeconds % 3600) / 60
        val seconds = diffSeconds % 60

        val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        findViewById<TextView>(R.id.tv_hero_countdown)?.text = formatted
    }
}
