package com.prayer.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale

object LocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Attempts to read device location and update PrayerRepository.
     * Returns true if a fresh device location was successfully detected.
     */
    fun updateDeviceLocation(context: Context): Boolean {
        if (!hasLocationPermission(context)) return false

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false

        var bestLocation: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.time > bestLocation.time || loc.accuracy < bestLocation.accuracy) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (e: SecurityException) {
                // Permission not granted
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val loc = bestLocation ?: return false

        val lat = loc.latitude
        val lng = loc.longitude
        var cityName = "${String.format(Locale.US, "%.2f", lat)}, ${String.format(Locale.US, "%.2f", lng)}"

        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    val country = addr.countryName
                    cityName = when {
                        locality != null && country != null -> "$locality, $country"
                        locality != null -> locality
                        country != null -> country
                        else -> cityName
                    }
                }
            }
        } catch (e: Exception) {
            // Geocoder may fail when network is offline; keep lat/lng string
        }

        PrayerRepository.setLocation(context, lat, lng, cityName)
        return true
    }
}
