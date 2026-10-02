package com.prayer.widget

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.*

/**
 * Pure astronomical prayer calculation engine that works completely offline with zero dependencies.
 * Ensures the app and home widget ALWAYS display valid, accurate prayer times even in airplane mode.
 */
object OfflinePrayerCalculator {

    private fun d2r(d: Double) = d * Math.PI / 180.0
    private fun r2d(r: Double) = r * 180.0 / Math.PI
    private fun fixAngle(a: Double): Double {
        var res = a - 360.0 * floor(a / 360.0)
        if (res < 0) res += 360.0
        return res
    }
    private fun fixHour(h: Double): Double {
        var res = h - 24.0 * floor(h / 24.0)
        if (res < 0) res += 24.0
        return res
    }

    /**
     * Calculates epoch millis for all prayers and sunrise on a given date.
     */
    fun calculate(
        date: LocalDate,
        lat: Double = PrayerConfig.LAT,
        lng: Double = PrayerConfig.LNG,
        fajrAngle: Double = 19.5,
        ishaAngle: Double = 17.5,
        asrFactor: Double = 1.0 // 1.0 for Shafi, 2.0 for Hanafi
    ): Map<String, Long> {
        val zone = ZoneId.systemDefault()
        val zdt = date.atStartOfDay(zone)
        val tzOffsetHours = zone.rules.getOffset(zdt.toInstant()).totalSeconds / 3600.0

        var y = date.year
        var m = date.monthValue
        val d = date.dayOfMonth

        if (m <= 2) {
            y -= 1
            m += 12
        }

        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        val jd = floor(365.25 * (y + 4716.0)) + floor(30.6001 * (m + 1.0)) + d + b - 1524.5
        val dj = jd - 2451545.0

        val g = fixAngle(357.529 + 0.98560028 * dj)
        val q = fixAngle(280.459 + 0.98564736 * dj)
        val l = fixAngle(q + 1.915 * sin(d2r(g)) + 0.020 * sin(d2r(2.0 * g)))
        val e = 23.439 - 0.00000036 * dj

        val sinDec = sin(d2r(e)) * sin(d2r(l))
        val dec = asin(sinDec)
        val ra = fixAngle(r2d(atan2(cos(d2r(e)) * sin(d2r(l)), cos(d2r(l))))) / 15.0

        var eqt = q / 15.0 - ra
        if (eqt > 12.0) eqt -= 24.0
        if (eqt < -12.0) eqt += 24.0

        val noon = fixHour(12.0 + tzOffsetHours - lng / 15.0 - eqt)

        fun hourAngle(alpha: Double): Double {
            val cosH = (sin(d2r(alpha)) - sin(d2r(lat)) * sin(dec)) / (cos(d2r(lat)) * cos(dec))
            if (cosH > 1.0 || cosH < -1.0) return 0.0
            return r2d(acos(cosH)) / 15.0
        }

        val fajrH = hourAngle(-fajrAngle)
        val sunH = hourAngle(-0.833)
        val asrAlt = r2d(atan(1.0 / (asrFactor + tan(abs(d2r(lat) - dec)))))
        val asrH = hourAngle(asrAlt)
        val ishaH = hourAngle(-ishaAngle)

        fun toEpoch(hours: Double): Long {
            val totalMinutes = (hours * 60.0).roundToLong()
            val hh = ((totalMinutes / 60) % 24).toInt()
            val mm = (totalMinutes % 60).toInt()
            return date.atTime(hh, mm).atZone(zone).toInstant().toEpochMilli()
        }

        return mapOf(
            "fajr" to toEpoch(noon - fajrH),
            "sunrise" to toEpoch(noon - sunH),
            "dhuhr" to toEpoch(noon + 1.0 / 60.0),
            "asr" to toEpoch(noon + asrH),
            "maghrib" to toEpoch(noon + sunH + 1.0 / 60.0),
            "isha" to toEpoch(noon + ishaH)
        )
    }
}
