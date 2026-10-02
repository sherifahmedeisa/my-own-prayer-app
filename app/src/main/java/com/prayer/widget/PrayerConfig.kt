package com.prayer.widget

object PrayerConfig {
    const val API_KEY = "umh_3cce13d1ecd2356ee6ddd4ad30e2c8bd4b684a3f"
    const val BASE_URL = "https://www.ummahapi.com/api"

    // Default: Cairo, Egypt
    const val LAT = 30.0444
    const val LNG = 31.2357
    const val CITY_NAME = "Cairo, Egypt"
    const val METHOD = "Egyptian"
    const val MADHAB = "Shafi"

    val PRAYERS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    val ALL_EVENTS = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")

    val EN_NAMES = mapOf(
        "fajr" to "Fajr",
        "sunrise" to "Sunrise",
        "dhuhr" to "Dhuhr",
        "asr" to "Asr",
        "maghrib" to "Maghrib",
        "isha" to "Isha"
    )

    val AR_NAMES = mapOf(
        "fajr" to "الفجر",
        "sunrise" to "الشروق",
        "dhuhr" to "الظهر",
        "asr" to "العصر",
        "maghrib" to "المغرب",
        "isha" to "العشاء"
    )
}
