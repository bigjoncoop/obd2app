package com.obd2logger.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper for checking and explaining Bluetooth & Location permissions on Android 12+ (API 31+)
 * and Android 8–11 (API 26–30).
 */
object BluetoothPermissionHelper {

    data class PermissionRequirement(
        val permissions: List<String>,
        val title: String,
        val plainEnglishExplanation: String
    )

    fun getRequiredBluetoothPermissions(): PermissionRequirement {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionRequirement(
                permissions = listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                title = "Bluetooth & Nearby Adapter Access",
                plainEnglishExplanation = "To find and connect to your OBD2 adapter, Android 12+ needs Nearby Devices (Bluetooth Scan & Connect) permission. Location permission is also requested because Android requires it to discover un-paired Bluetooth Classic OBD2 adapters."
            )
        } else {
            PermissionRequirement(
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                title = "Location Permission for Bluetooth Discovery",
                plainEnglishExplanation = "On Android 11 and older, Android requires Location permission in order to scan for Bluetooth Classic and Bluetooth LE OBD2 adapters. Your location is never tracked or shared."
            )
        }
    }

    fun hasAllBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            hasScan && hasConnect
        } else {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            hasFine || hasCoarse
        }
    }
}
