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
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    const val PREFS_NAME = "prayer_cache"
    const val KEY_LAST_SYNC = "last_sync_timestamp"
    const val KEY_LAST_DAILY_SYNC_DATE = "last_daily_sync_date"
    const val KEY_LAT = "loc_lat"
    const val KEY_LNG = "loc_lng"
    const val KEY_CITY = "loc_city"

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getLatitude(context: Context): Double {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_LAT, PrayerConfig.LAT.toFloat()).toDouble()
    }

    fun getLongitude(context: Context): Double {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_LNG, PrayerConfig.LNG.toFloat()).toDouble()
    }

    fun getCityName(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_CITY, PrayerConfig.CITY_NAME) ?: PrayerConfig.CITY_NAME
    }

    fun setLocation(context: Context, lat: Double, lng: Double, cityName: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LNG, lng.toFloat())
            .putString(KEY_CITY, cityName)
            .apply()
    }

    /**
     * Checks if prayer data for a given date was fetched from UmmahAPI.
     */
    fun isSyncedForDate(context: Context, date: LocalDate): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = sp.getString("d_$date", null) ?: return false
        return try {
            val j = JSONObject(cached)
            j.optString("source", "") == "api"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Synchronizes daily prayer times for a specific date from UmmahAPI.
     */
    fun syncDaily(context: Context, date: LocalDate = LocalDate.now()): Boolean {
        return try {
            val lat = getLatitude(context)
            val lng = getLongitude(context)
            val dateStr = date.toString()

            val urlString = "${PrayerConfig.BASE_URL}/prayer-times?" +
                "lat=$lat&lng=$lng" +
                "&date=$dateStr" +
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

            val dataObj = root.optJSONObject("data") ?: return false
            val parsedMap = parseDayObject(dataObj, dateStr) ?: return false

            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dayJson = JSONObject().apply {
                put("source", "api")
                for ((p, ms) in parsedMap) put(p, ms)
            }
            sp.edit()
                .putString("d_$dateStr", dayJson.toString())
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_LAST_DAILY_SYNC_DATE, dateStr)
                .apply()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Synchronizes full month data from UmmahAPI and stores in local cache.
     * Robustly handles both object with "days" array and flat array responses.
     */
    fun syncMonth(context: Context, year: Int, month: Int): Boolean {
        return try {
            val lat = getLatitude(context)
            val lng = getLongitude(context)

            val urlString = "${PrayerConfig.BASE_URL}/prayer-times/month?" +
                "lat=$lat&lng=$lng" +
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

            // UmmahAPI returns { success: true, data: { days: [ ... ] } }
            val daysArray: JSONArray? = if (root.has("data")) {
                val dataVal = root.get("data")
                when (dataVal) {
                    is JSONObject -> dataVal.optJSONArray("days")
                    is JSONArray -> dataVal
                    else -> null
                }
            } else null

            if (daysArray == null || daysArray.length() == 0) return false

            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()

            for (i in 0 until daysArray.length()) {
                val dayObj = daysArray.optJSONObject(i) ?: continue
                val dateStr = dayObj.optString("date", "")
                if (dateStr.isEmpty()) continue

                val map = parseDayObject(dayObj, dateStr)
                if (map != null && map.size >= 5) {
                    val dayJson = JSONObject().apply {
                        put("source", "api")
                        for ((p, ms) in map) put(p, ms)
                    }
                    editor.putString("d_$dateStr", dayJson.toString())
                }
            }

            val todayStr = LocalDate.now().toString()
            editor.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            editor.putString(KEY_LAST_DAILY_SYNC_DATE, todayStr)
            editor.apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseDayObject(dayObj: JSONObject, dateStr: String): Map<String, Long>? {
        val datetimesObj = dayObj.optJSONObject("prayer_datetimes")
        val timesObj = dayObj.optJSONObject("prayer_times")
        val map = mutableMapOf<String, Long>()

        for (p in PrayerConfig.ALL_EVENTS) {
            var ms: Long? = null
            if (datetimesObj != null && datetimesObj.has(p)) {
                ms = runCatching {
                    OffsetDateTime.parse(datetimesObj.getString(p)).toInstant().toEpochMilli()
                }.getOrNull()
            }
            if (ms == null && timesObj != null && timesObj.has(p)) {
                ms = runCatching {
                    val d = LocalDate.parse(dateStr)
                    val t = LocalTime.parse(timesObj.getString(p))
                    d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            val validMs = ms
            if (validMs != null) {
                map[p] = validMs
            }
        }

        return if (map.size >= 5) map else null
    }

    /**
     * Returns prayer times for the specified date.
     * 1. Checks cache for API data.
     * 2. If online and not yet synced from API today, fetches from UmmahAPI.
     * 3. If offline, returns cached data (or astronomical calculation for current coordinates).
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

                // If already synced from UmmahAPI, return directly!
                if (isApi && map.size >= 5) {
                    return DayPrayers(map, true)
                }

                // If it was only an offline calculation, check if internet is now available to upgrade!
                if (!isApi && isNetworkAvailable(context)) {
                    val ok = syncDaily(context, date)
                    if (ok) {
                        val refreshed = sp.getString(key, null)
                        if (refreshed != null) {
                            val rj = JSONObject(refreshed)
                            val rmap = mutableMapOf<String, Long>()
                            for (p in PrayerConfig.ALL_EVENTS) {
                                if (rj.has(p)) rmap[p] = rj.getLong(p)
                            }
                            if (rmap.size >= 5) return DayPrayers(rmap, true)
                        }
                    }
                }

                if (map.size >= 5) {
                    return DayPrayers(map, isApi)
                }
            } catch (e: Exception) {
                // fall through
            }
        }

        // Not in cache: if network available, fetch daily & month
        if (isNetworkAvailable(context)) {
            val dailyOk = syncDaily(context, date)
            if (dailyOk) {
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
        }

        // Offline Astronomical Fallback using user's location
        val lat = getLatitude(context)
        val lng = getLongitude(context)
        val calculated = OfflinePrayerCalculator.calculate(date, lat = lat, lng = lng)
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
        val online = isNetworkAvailable(context)
        val today = LocalDate.now()
        val syncedToday = isSyncedForDate(context, today)
        val city = getCityName(context).split(",")[0].trim()

        return when {
            syncedToday -> "● Synced with UmmahAPI · $city"
            online -> "● Updating with UmmahAPI · $city..."
            else -> "⚠ Offline · Waiting for connection to update $city"
        }
    }
}
