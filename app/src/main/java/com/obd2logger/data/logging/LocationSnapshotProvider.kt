package com.obd2logger.data.logging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.obd2logger.domain.model.GpsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional GPS provider using Android's native [LocationManager].
 * Never requests permissions itself; returns [GpsSnapshot.EMPTY] ("-" cells) unless
 * location permission is already granted.
 */
@Singleton
class LocationSnapshotProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var latestLocation: Location? = null

    @Volatile
    private var isListening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun startUpdatesIfPermitted() {
        if (!hasLocationPermission() || isListening) return
        val lm = locationManager ?: return

        runCatching {
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            latestLocation = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                else -> lastGps ?: lastNet
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            isListening = true
        }
    }

    fun stopUpdates() {
        if (!isListening) return
        isListening = false
        runCatching {
            locationManager?.removeUpdates(listener)
        }
        latestLocation = null
    }

    fun currentSnapshot(): GpsSnapshot {
        if (!hasLocationPermission()) return GpsSnapshot.EMPTY
        val loc = latestLocation ?: return GpsSnapshot.EMPTY
        // Only treat fixes within last 15 seconds as live GPS data
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > 15_000L) return GpsSnapshot.EMPTY

        return GpsSnapshot(
            gpsTime = CsvWriter.formatDeviceTime(loc.time),
            longitude = "%.6f".format(Locale.US, loc.longitude),
            latitude = "%.6f".format(Locale.US, loc.latitude),
            speedMps = if (loc.hasSpeed()) "%.2f".format(Locale.US, loc.speed.toDouble()) else "-",
            altitude = if (loc.hasAltitude()) "%.1f".format(Locale.US, loc.altitude) else "-"
        )
    }
}
