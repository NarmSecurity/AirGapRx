package com.airgaprx.app.platform

import android.content.Context
import android.content.pm.PackageManager

/**
 * Turns R1 from a build-time claim into a runtime assertion (M1 Section 6.5): at startup the app
 * inspects its own installed package and refuses to run if any network permission is declared.
 * The app fails closed: a build that could reach the network is treated as an integrity failure.
 */
object EgressGuard {
    val NETWORK_PERMISSIONS = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
    )

    data class Status(val requestedPermissions: List<String>, val violations: List<String>) {
        val ok: Boolean get() = violations.isEmpty()
    }

    fun check(context: Context): Status {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        val granted = NETWORK_PERMISSIONS.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        return Status(requested, (requested.filter { it in NETWORK_PERMISSIONS } + granted).distinct())
    }
}
