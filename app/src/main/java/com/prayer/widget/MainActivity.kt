package com.prayer.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
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
    private var currentTheme: String = "dark"

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sp = getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
        currentTheme = sp.getString("theme", "dark") ?: "dark"

        setupListeners()
        applyTheme(currentTheme)
        loadData()

        // Ensure daily background sync job and reminders are scheduled
        PrayerSyncJobService.scheduleDailySync(this)
        PrayerScheduler.scheduleReminders(this)
        if (LocationHelper.hasLocationPermission(this)) {
            LocationHelper.updateDeviceLocation(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val sp = getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
        val savedTheme = sp.getString("theme", "dark") ?: "dark"
        if (savedTheme != currentTheme) {
            currentTheme = savedTheme
            applyTheme(currentTheme)
        }
        loadData()
        PrayerLiveTickerService.updateAodNotification(this)
        PrayerNetworkMonitor.register(this) {
            runOnUiThread { loadData() }
        }
        handler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        PrayerNetworkMonitor.unregister(this)
        handler.removeCallbacks(tickRunnable)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btn_add_widget)?.setOnClickListener {
            pinWidgetToHomeScreen()
        }

        findViewById<Button>(R.id.btn_sync)?.setOnClickListener {
            syncData()
        }

        findViewById<Button>(R.id.btn_location)?.setOnClickListener {
            detectLocationAndSync()
        }

        findViewById<Button>(R.id.btn_theme_toggle)?.setOnClickListener {
            toggleTheme()
        }

        setupReminderControls()
    }

    private fun setupReminderControls() {
        val sp = getSharedPreferences(PrayerScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val switchMaster = findViewById<Switch>(R.id.switch_reminders_master)
        val switchBefore = findViewById<Switch>(R.id.switch_remind_before)
        val switchAfter = findViewById<Switch>(R.id.switch_remind_after)
        val switchHaptic = findViewById<Switch>(R.id.switch_haptic)
        val switchAod = findViewById<Switch>(R.id.switch_aod_ticker)
        val btnTest = findViewById<Button>(R.id.btn_test_reminder)

        val masterEnabled = sp.getBoolean(PrayerScheduler.KEY_REMINDERS_ENABLED, true)
        val beforeEnabled = sp.getBoolean(PrayerScheduler.KEY_REMIND_BEFORE, true)
        val afterEnabled = sp.getBoolean(PrayerScheduler.KEY_REMIND_AFTER, true)
        val hapticEnabled = sp.getBoolean(PrayerScheduler.KEY_HAPTIC_ENABLED, true)
        val aodEnabled = sp.getBoolean(PrayerLiveTickerService.KEY_AOD_ENABLED, false)

        switchMaster?.isChecked = masterEnabled
        switchBefore?.isChecked = beforeEnabled
        switchAfter?.isChecked = afterEnabled
        switchHaptic?.isChecked = hapticEnabled
        switchAod?.isChecked = aodEnabled

        fun updateSubswitches(enabled: Boolean) {
            switchBefore?.isEnabled = enabled
            switchAfter?.isEnabled = enabled
            switchHaptic?.isEnabled = enabled
        }
        updateSubswitches(masterEnabled)

        switchMaster?.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(PrayerScheduler.KEY_REMINDERS_ENABLED, isChecked).apply()
            updateSubswitches(isChecked)
            if (isChecked) {
                checkNotificationPermission()
            }
            PrayerScheduler.scheduleReminders(this)
            Toast.makeText(this, if (isChecked) "Prayer Reminders Enabled" else "Prayer Reminders Disabled", Toast.LENGTH_SHORT).show()
        }

        switchBefore?.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(PrayerScheduler.KEY_REMIND_BEFORE, isChecked).apply()
            PrayerScheduler.scheduleReminders(this)
        }

        switchAfter?.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(PrayerScheduler.KEY_REMIND_AFTER, isChecked).apply()
            PrayerScheduler.scheduleReminders(this)
        }

        switchHaptic?.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(PrayerScheduler.KEY_HAPTIC_ENABLED, isChecked).apply()
        }

        switchAod?.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(PrayerLiveTickerService.KEY_AOD_ENABLED, isChecked).apply()
            if (isChecked) {
                checkNotificationPermission()
            }
            PrayerLiveTickerService.updateAodNotification(this)
            Toast.makeText(
                this,
                if (isChecked) "Lock Screen & AOD Ticker Enabled" else "Lock Screen & AOD Ticker Disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnTest?.setOnClickListener {
            checkNotificationPermission()
            sendBroadcast(Intent(this, PrayerNotificationReceiver::class.java).apply {
                action = PrayerScheduler.ACTION_PRAYER_BEFORE
                putExtra(PrayerScheduler.EXTRA_PRAYER_NAME, nextPrayerName.ifEmpty { "asr" })
                putExtra(PrayerScheduler.EXTRA_PRAYER_TIME, System.currentTimeMillis() + 15 * 60 * 1000L)
            })
            Toast.makeText(this, "Testing 15m reminder (check notification & haptic!)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun detectLocationAndSync() {
        if (!LocationHelper.hasLocationPermission(this)) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                201
            )
            return
        }

        val updated = LocationHelper.updateDeviceLocation(this)
        val city = PrayerRepository.getCityName(this)
        if (updated) {
            Toast.makeText(this, "Location updated: $city", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Current location: $city", Toast.LENGTH_SHORT).show()
        }
        syncData()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            LocationHelper.updateDeviceLocation(this)
            Toast.makeText(this, "Location permission granted: ${PrayerRepository.getCityName(this)}", Toast.LENGTH_SHORT).show()
            syncData()
        }
    }

    private fun toggleTheme() {
        currentTheme = if (currentTheme == "dark") "cream" else "dark"
        val sp = getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
        sp.edit().putString("theme", currentTheme).apply()

        applyTheme(currentTheme)

        // Broadcast to widget to refresh colors immediately
        sendBroadcast(Intent(this, PrayerWidget::class.java).setAction(PrayerWidget.ACTION_REFRESH))

        val label = if (currentTheme == "cream") "Cream Theme" else "Dark Emerald Theme"
        Toast.makeText(this, "Switched to $label", Toast.LENGTH_SHORT).show()
        loadData()
    }

    private fun applyTheme(theme: String) {
        val isCream = (theme == "cream")

        val colorPage = if (isCream) Color.parseColor("#E9E0CB") else Color.parseColor("#080B09")
        val colorSurface = if (isCream) Color.parseColor("#FAF5EA") else Color.parseColor("#0D1512")
        val colorGold = if (isCream) Color.parseColor("#8A6F3A") else Color.parseColor("#F5C030")
        val colorTextPrimary = if (isCream) Color.parseColor("#3A2E1A") else Color.parseColor("#F4F7F5")
        val colorTextSecondary = if (isCream) Color.parseColor("#6B5A3E") else Color.parseColor("#8FA898")
        val colorTextMuted = if (isCream) Color.parseColor("#9C8866") else Color.parseColor("#526B5C")

        // Window & navigation bars
        window.statusBarColor = colorPage
        window.navigationBarColor = colorPage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (isCream) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }

        // Backgrounds
        findViewById<View>(R.id.root_scroll)?.setBackgroundColor(colorPage)

        // Header
        findViewById<TextView>(R.id.tv_title)?.setTextColor(colorTextPrimary)
        findViewById<TextView>(R.id.tv_subtitle)?.setTextColor(colorTextSecondary)

        val btnTheme = findViewById<Button>(R.id.btn_theme_toggle)
        btnTheme?.text = if (isCream) "🎨 Cream" else "🎨 Dark"
        btnTheme?.setBackgroundResource(if (isCream) R.drawable.button_outline_bg_cream else R.drawable.button_outline_bg)
        btnTheme?.setTextColor(colorGold)

        val syncBadge = findViewById<TextView>(R.id.tv_sync_badge)
        syncBadge?.setBackgroundResource(if (isCream) R.drawable.badge_gold_bg_cream else R.drawable.badge_gold_bg)
        syncBadge?.setTextColor(colorGold)

        // Hero Card
        val cardHero = findViewById<View>(R.id.card_hero)
        cardHero?.setBackgroundResource(if (isCream) R.drawable.card_bg_cream else R.drawable.card_bg)
        findViewById<TextView>(R.id.tv_hero_next_label)?.setTextColor(colorTextSecondary)
        findViewById<TextView>(R.id.tv_hero_prayer_name)?.setTextColor(colorTextPrimary)
        findViewById<TextView>(R.id.tv_hero_countdown)?.setTextColor(colorGold)
        findViewById<TextView>(R.id.tv_hero_prayer_time)?.setTextColor(colorTextSecondary)

        // Schedule Card
        val cardSchedule = findViewById<View>(R.id.card_schedule)
        cardSchedule?.setBackgroundResource(if (isCream) R.drawable.card_bg_cream else R.drawable.card_bg)
        findViewById<TextView>(R.id.tv_schedule_title)?.setTextColor(colorTextSecondary)

        // Reminders Card
        val cardReminders = findViewById<View>(R.id.card_reminders)
        cardReminders?.setBackgroundResource(if (isCream) R.drawable.card_bg_cream else R.drawable.card_bg)
        findViewById<TextView>(R.id.tv_reminders_title)?.setTextColor(colorGold)
        findViewById<TextView>(R.id.tv_remind_master_label)?.setTextColor(colorTextPrimary)
        findViewById<TextView>(R.id.tv_remind_before_label)?.setTextColor(colorTextSecondary)
        findViewById<TextView>(R.id.tv_remind_after_label)?.setTextColor(colorTextSecondary)
        findViewById<TextView>(R.id.tv_haptic_label)?.setTextColor(colorTextSecondary)
        findViewById<TextView>(R.id.tv_aod_label)?.setTextColor(colorTextPrimary)
        findViewById<TextView>(R.id.tv_aod_sublabel)?.setTextColor(colorTextSecondary)

        val btnTest = findViewById<Button>(R.id.btn_test_reminder)
        btnTest?.setBackgroundResource(if (isCream) R.drawable.button_outline_bg_cream else R.drawable.button_outline_bg)
        btnTest?.setTextColor(colorGold)

        // Widget Card
        val cardWidget = findViewById<View>(R.id.card_widget)
        cardWidget?.setBackgroundResource(if (isCream) R.drawable.card_bg_cream else R.drawable.card_bg)
        findViewById<TextView>(R.id.tv_widget_title)?.setTextColor(colorGold)
        findViewById<TextView>(R.id.tv_widget_desc)?.setTextColor(colorTextSecondary)
        findViewById<TextView>(R.id.tv_widget_note)?.setTextColor(colorTextMuted)

        val btnAddWidget = findViewById<Button>(R.id.btn_add_widget)
        btnAddWidget?.setBackgroundResource(if (isCream) R.drawable.button_gold_bg_cream else R.drawable.button_gold_bg)
        btnAddWidget?.setTextColor(if (isCream) Color.parseColor("#FAF5EA") else Color.parseColor("#080B09"))

        // Actions Card
        val cardActions = findViewById<View>(R.id.card_actions)
        cardActions?.setBackgroundResource(if (isCream) R.drawable.card_bg_cream else R.drawable.card_bg)

        val btnLocation = findViewById<Button>(R.id.btn_location)
        btnLocation?.setBackgroundResource(if (isCream) R.drawable.button_outline_bg_cream else R.drawable.button_outline_bg)
        btnLocation?.setTextColor(colorGold)

        val btnSync = findViewById<Button>(R.id.btn_sync)
        btnSync?.setBackgroundResource(if (isCream) R.drawable.button_outline_bg_cream else R.drawable.button_outline_bg)
        btnSync?.setTextColor(colorGold)

        findViewById<TextView>(R.id.tv_info)?.setTextColor(colorTextMuted)
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

        if (!PrayerRepository.isNetworkAvailable(this)) {
            PrayerSyncJobService.scheduleImmediateOnlineSync(this)
            val city = PrayerRepository.getCityName(this)
            btn?.isEnabled = true
            btn?.text = "↻ Sync Now with UmmahAPI"
            Toast.makeText(
                this,
                "Offline: will automatically fetch prayer times for $city once internet reconnects!",
                Toast.LENGTH_LONG
            ).show()
            loadData()
            return
        }

        Thread {
            val today = LocalDate.now()
            LocationHelper.updateDeviceLocation(this)
            val dailySuccess = PrayerRepository.syncDaily(this, today)
            PrayerRepository.syncDaily(this, today.plusDays(1))
            val monthSuccess = PrayerRepository.syncMonth(this, today.year, today.monthValue)
            val success = dailySuccess || monthSuccess

            // Trigger widget refresh and reschedule reminders
            sendBroadcast(Intent(this, PrayerWidget::class.java).setAction(PrayerWidget.ACTION_REFRESH))
            PrayerLiveTickerService.updateAodNotification(this)
            PrayerScheduler.scheduleReminders(this)

            runOnUiThread {
                btn?.isEnabled = true
                btn?.text = "↻ Sync Now with UmmahAPI"
                val city = PrayerRepository.getCityName(this)
                if (success) {
                    Toast.makeText(this, "Successfully synced with UmmahAPI for $city!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sync completed (offline calculations ready).", Toast.LENGTH_SHORT).show()
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

        val isCream = (currentTheme == "cream")
        val colorGold = if (isCream) Color.parseColor("#8A6F3A") else Color.parseColor("#F5C030")
        val colorTextPrimary = if (isCream) Color.parseColor("#3A2E1A") else Color.parseColor("#F4F7F5")
        val colorTextMuted = if (isCream) Color.parseColor("#9C8866") else Color.parseColor("#526B5C")
        val bgActiveCard = if (isCream) R.drawable.card_active_bg_cream else R.drawable.card_active_bg

        // Subtitle & Status
        val cityName = PrayerRepository.getCityName(this)
        val lat = PrayerRepository.getLatitude(this)
        val lng = PrayerRepository.getLongitude(this)
        findViewById<TextView>(R.id.tv_subtitle)?.text = "$cityName · ${today.format(dateFormatter)}"
        findViewById<TextView>(R.id.tv_sync_badge)?.text = syncStatus
        findViewById<TextView>(R.id.tv_info)?.text = "Location: $cityName (${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lng)})\nDaily sync: Auto-waits for internet when offline."

        // Next prayer info
        nextPrayerAt = nextPrayer.at
        nextPrayerName = nextPrayer.name

        val enName = PrayerConfig.EN_NAMES[nextPrayer.name] ?: nextPrayer.name
        val arName = PrayerConfig.AR_NAMES[nextPrayer.name] ?: ""
        findViewById<TextView>(R.id.tv_hero_prayer_name)?.text = "$enName · $arName"

        val targetTimeStr = Instant.ofEpochMilli(nextPrayer.at).atZone(zone).format(timeFormatter)
        findViewById<TextView>(R.id.tv_hero_prayer_time)?.text = "at $targetTimeStr"

        updateCountdown()
        PrayerLiveTickerService.updateAodNotification(this)

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
                rowView?.setBackgroundResource(bgActiveCard)
                timeView?.setTextColor(colorGold)
            } else if (timeMs != null && timeMs < now) {
                rowView?.setBackgroundResource(0)
                timeView?.setTextColor(colorTextMuted)
            } else {
                rowView?.setBackgroundResource(0)
                timeView?.setTextColor(colorTextPrimary)
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
