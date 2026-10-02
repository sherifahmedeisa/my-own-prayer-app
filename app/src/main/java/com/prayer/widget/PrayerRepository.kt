package com.prayer.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class DayPrayers(
    val times: Map<String, Long>,
    val isFromApi: Boolean
)

data class NextPrayer(
    val name: String,
    val at: Long,
    val schedule: Map<String, Long>,
    val isFromApi: Boolean
)

object PrayerRepository {

    private const val PREFS_NAME = "prayer_cache"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Synchronizes full month data from UmmahAPI and stores in local cache.
     */
    fun syncMonth(context: Context, year: Int, month: Int): Boolean {
        return try {
            val urlString = "${PrayerConfig.BASE_URL}/prayer-times/month?" +
                "lat=${PrayerConfig.LAT}&lng=${PrayerConfig.LNG}" +
                "&month=$month&year=$year" +
                "&method=${PrayerConfig.METHOD}&madhab=${PrayerConfig.MADHAB}" +
                "&apikey=${PrayerConfig.API_KEY}"

            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("X-API-Key", PrayerConfig.API_KEY)
            conn.setRequestProperty("Accept", "application/json")

            val responseText = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val root = JSONObject(responseText)
            if (!root.optBoolean("success", false)) return false

            val dataArray = root.getJSONArray("data")
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()

            for (i in 0 until dataArray.length()) {
                val dayObj = dataArray.getJSONObject(i)
                val dateStr = dayObj.getString("date")
                val datetimesObj = dayObj.optJSONObject("prayer_datetimes")
                val timesObj = dayObj.optJSONObject("prayer_times")
                val dayMap = JSONObject()
                dayMap.put("source", "api")

                for (p in PrayerConfig.ALL_EVENTS) {
                    var ms: Long? = null
                    if (datetimesObj != null && datetimesObj.has(p)) {
                        ms = runCatching {
                            OffsetDateTime.parse(datetimesObj.getString(p)).toInstant().toEpochMilli()
                        }.getOrNull()
                    }
                    if (ms == null && timesObj != null && timesObj.has(p)) {
                        val d = LocalDate.parse(dateStr)
                        val t = LocalTime.parse(timesObj.getString(p))
                        ms = d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    if (ms != null) {
                        dayMap.put(p, ms)
                    }
                }
                editor.putString("d_$dateStr", dayMap.toString())
            }

            editor.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            editor.apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Returns prayer times for the specified date.
     * Guaranteed to NEVER fail: checks cache -> fetches month/day -> falls back to offline astronomical engine.
     */
    fun getDayTimes(context: Context, date: LocalDate): DayPrayers {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "d_$date"
        val cached = sp.getString(key, null)

        if (cached != null) {
            try {
                val j = JSONObject(cached)
                val map = mutableMapOf<String, Long>()
                for (p in PrayerConfig.ALL_EVENTS) {
                    if (j.has(p)) map[p] = j.getLong(p)
                }
                val isApi = j.optString("source", "offline") == "api"
                if (map.size >= 5) {
                    return DayPrayers(map, isApi)
                }
            } catch (e: Exception) {
                // fall through to recalculate or refetch
            }
        }

        // If not in cache and network is available, attempt to fetch month
        if (isNetworkAvailable(context)) {
            syncMonth(context, date.year, date.monthValue)
            val updated = sp.getString(key, null)
            if (updated != null) {
                try {
                    val j = JSONObject(updated)
                    val map = mutableMapOf<String, Long>()
                    for (p in PrayerConfig.ALL_EVENTS) {
                        if (j.has(p)) map[p] = j.getLong(p)
                    }
                    if (map.size >= 5) {
                        return DayPrayers(map, true)
                    }
                } catch (e: Exception) { }
            }
        }

        // Offline Astronomical Fallback: 100% reliable anytime, anywhere
        val calculated = OfflinePrayerCalculator.calculate(date)
        val fallbackJson = JSONObject().apply {
            put("source", "offline")
            for ((p, ms) in calculated) put(p, ms)
        }
        sp.edit().putString(key, fallbackJson.toString()).apply()
        return DayPrayers(calculated, isFromApi = false)
    }

    /**
     * Resolves the next upcoming prayer and target timestamp.
     */
    fun getNextPrayer(context: Context, now: Long = System.currentTimeMillis()): NextPrayer {
        val today = LocalDate.now()
        val todayPrayers = getDayTimes(context, today)
        val times = todayPrayers.times

        val upcoming = PrayerConfig.PRAYERS.firstOrNull { times[it]?.let { t -> t > now } == true }

        return if (upcoming != null) {
            NextPrayer(
                name = upcoming,
                at = times[upcoming] ?: (now + 3600_000L),
                schedule = times,
                isFromApi = todayPrayers.isFromApi
            )
        } else {
            // All today's prayers passed; next is tomorrow's Fajr
            val tomorrow = today.plusDays(1)
            val tomorrowPrayers = getDayTimes(context, tomorrow)
            val tomorrowFajr = tomorrowPrayers.times["fajr"] ?: (times["fajr"]?.plus(86_400_000L) ?: (now + 3600_000L))
            NextPrayer(
                name = "fajr",
                at = tomorrowFajr,
                schedule = times,
                isFromApi = todayPrayers.isFromApi
            )
        }
    }

    fun getSyncStatusText(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = sp.getLong(KEY_LAST_SYNC, 0L)
        return if (lastSync > 0) {
            "● Synced with UmmahAPI"
        } else {
            "● Offline Ready"
        }
    }
}
