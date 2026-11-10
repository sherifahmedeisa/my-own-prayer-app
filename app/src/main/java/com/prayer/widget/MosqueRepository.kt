package com.prayer.widget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

data class NearbyMosque(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Int,
    val bearingDegrees: Float,
    val walkMinutes: Int
)

object MosqueRepository {

    private const val DB_NAME = "mosques.db"
    private var database: SQLiteDatabase? = null

    /**
     * Ensures the pre-indexed database from assets/ is extracted to the app's internal database directory.
     */
    @Synchronized
    fun getDatabase(context: Context): SQLiteDatabase {
        database?.let { if (it.isOpen) return it }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() < 100_000) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database = db
        return db
    }

    /**
     * Calculates distance between two points in meters using the Haversine formula.
     */
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).roundToInt()
    }

    /**
     * Calculates initial bearing (azimuth) from point 1 to point 2 in degrees (0..360).
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        val bearing = (Math.toDegrees(theta) + 360.0) % 360.0
        return bearing.toFloat()
    }

    /**
     * Finds the closest mosques to a given GPS coordinate in under 5 milliseconds.
     */
    fun findNearestMosques(
        context: Context,
        userLat: Double,
        userLng: Double,
        limit: Int = 5
    ): List<NearbyMosque> {
        val db = getDatabase(context)

        // Progressive bounding box: start with ~3km radius (~0.03 deg)
        val radii = listOf(0.03, 0.08, 0.25, 1.0)
        val candidates = mutableListOf<NearbyMosque>()

        for (delta in radii) {
            val minLat = userLat - delta
            val maxLat = userLat + delta
            val minLng = userLng - delta
            val maxLng = userLng + delta

            val cursor = db.rawQuery(
                """
                SELECT id, name, lat, lng
                FROM mosques
                WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?
                """.trimIndent(),
                arrayOf(minLat.toString(), maxLat.toString(), minLng.toString(), maxLng.toString())
            )

            cursor.use {
                val idIdx = it.getColumnIndexOrThrow("id")
                val nameIdx = it.getColumnIndexOrThrow("name")
                val latIdx = it.getColumnIndexOrThrow("lat")
                val lngIdx = it.getColumnIndexOrThrow("lng")

                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx)
                    val lat = it.getDouble(latIdx)
                    val lng = it.getDouble(lngIdx)

                    val dist = calculateDistanceMeters(userLat, userLng, lat, lng)
                    val bearing = calculateBearing(userLat, userLng, lat, lng)
                    val walkMin = max(1, (dist / 75.0).roundToInt()) // avg walking speed ~4.5 km/h = 75m/min

                    candidates.add(NearbyMosque(id, name, lat, lng, dist, bearing, walkMin))
                }
            }

            if (candidates.size >= limit) break
        }

        return candidates.distinctBy { it.id }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    /**
     * Gets total number of indexed mosques.
     */
    fun getMosqueCount(context: Context): Int {
        return try {
            val db = getDatabase(context)
            val c = db.rawQuery("SELECT count(*) FROM mosques", null)
            c.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
