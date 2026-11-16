package com.tamimi.naqiice.tracking

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val time: Long)

object Locations {
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun providers(manager: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * One position from the phone's own location providers (no Google services needed).
     * A known fix newer than [maxAgeMs] is used immediately; otherwise waits up to
     * [timeoutMs] for a new one. Null when permission is missing or nothing arrives.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long, maxAgeMs: Long): Fix? {
        if (!granted(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = providers(manager)
        if (providers.isEmpty()) return null
        val now = System.currentTimeMillis()
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageOf(it, now) <= maxAgeMs }.minByOrNull { ageOf(it, now) }?.let { return it.toFix() }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location.toFix())
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                for (provider in providers) runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            }
        }
    }

    /**
     * How old a fix is, measured on the phone's uptime clock so that a wrong date or time
     * setting cannot make an old position look fresh. Falls back to the wall clock.
     */
    fun ageOf(location: Location, now: Long = System.currentTimeMillis()): Long {
        val nanos = location.elapsedRealtimeNanos
        return if (nanos > 0) ((android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000).coerceAtLeast(0) else now - location.time
    }

    /** [Fix.time] is "now minus the fix's age" on the phone's own clock. */
    fun Location.toFix(): Fix {
        val now = System.currentTimeMillis()
        return Fix(latitude, longitude, if (hasAccuracy()) accuracy else Float.MAX_VALUE, now - ageOf(this, now))
    }
}
