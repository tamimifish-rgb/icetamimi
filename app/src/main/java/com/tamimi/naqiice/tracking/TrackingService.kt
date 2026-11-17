package com.tamimi.naqiice.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tamimi.naqiice.MainActivity
import com.tamimi.naqiice.PureIceApp
import com.tamimi.naqiice.R
import com.tamimi.naqiice.core.TRACKING_INTERVAL
import com.tamimi.naqiice.core.acceptPosition
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.data.Repo
import com.tamimi.naqiice.data.TrackingState

/**
 * Records the truck's route while a campaign is open, including with the screen off.
 * Points are written straight to the phone's outbox (owner + campaign scoped, one UUID
 * each) and uploaded by the normal sync, so nothing is lost without internet.
 */
class TrackingService : Service(), LocationListener {
    private var owner: String? = null
    private var trip: String? = null
    private var truck: String? = null
    private var last = 0L
    private val repo: Repo get() = (application as PureIceApp).repo

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService: Android kills the app if the service stops
        // before calling startForeground, so that always comes first, even when it then stops.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION, notification())
        } catch (e: Exception) {
            // Refused (location permission withdrawn, or not allowed from the background).
            stopSelf(); return START_NOT_STICKY
        }
        val nextOwner = intent?.getStringExtra(OWNER)
        val nextTrip = intent?.getStringExtra(TRIP)
        if (nextOwner == null || nextTrip == null || !Locations.granted(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val changed = nextOwner != owner || nextTrip != trip
        owner = nextOwner; trip = nextTrip; truck = intent.getStringExtra(TRUCK)
        if (changed) {
            last = repo.store.getCache("$nextOwner:tracking:$nextTrip")?.toLongOrNull() ?: 0L
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.removeUpdates(this)
            val providers = Locations.providers(manager)
            for (provider in providers) runCatching { manager.requestLocationUpdates(provider, TRACKING_INTERVAL / 2, 0f, this, Looper.getMainLooper()) }
            publish(if (providers.isEmpty()) "unavailable" else "waiting")
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val o = owner ?: return
        val t = trip ?: return
        // Never attribute a point to an account or campaign that is no longer current.
        val me = repo.me.value
        val snapshot = repo.snapshot.value
        if (me?.id != o || snapshot.tripId != t || snapshot.closingSent) { stopSelf(); return }
        val now = System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        if (now - last < TRACKING_INTERVAL || !acceptPosition(accuracy, last, now)) return
        try {
            repo.recordPoint(o, t, truck, location.latitude, location.longitude, isoNow(if (location.time > 0) location.time else now))
            last = now
            repo.store.putCache("$o:tracking:$t", now.toString())
            publish("active")
        } catch (e: Exception) { publish("waiting") }
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Locations.providers(manager).isEmpty()) publish("unavailable")
    }

    private fun publish(status: String) {
        val o = owner ?: return
        val t = trip ?: return
        repo.tracking.value = TrackingState(o, t, status, if (last > 0) isoNow(last) else null)
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "تتبع الرحلة", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ثلج نقي — تتبع الرحلة يعمل")
            .setContentText("يُسجَّل خط سير الحملة المفتوحة")
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setOngoing(true).setContentIntent(open).build()
    }

    override fun onDestroy() {
        running = false
        runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
        repo.tracking.value = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION = 41
        private const val OWNER = "owner"
        private const val TRIP = "trip"
        private const val TRUCK = "truck"
        @Volatile var running = false
            private set
        private var startedFor: String? = null

        /**
         * True while the app is on screen (MainActivity onStart to onStop). A location service
         * started from the background gets no locations (Android 11) or is refused (12+), so
         * the background sync may stop tracking but only a visible app starts it.
         */
        @Volatile var visible = false

        /**
         * Tracks only a distributor's own open campaign that has no closing sent; stops
         * for every other state (sign-out, closing, campaign closed, another role).
         */
        fun update(context: Context, repo: Repo) {
            val me = repo.me.value
            val snapshot = repo.snapshot.value
            val trip = snapshot.trip
            val wanted = me != null && me.primaryRole == "distributor" && snapshot.ownOpenTrip(me.id) && !snapshot.closingSent && Locations.granted(context)
            if (!wanted) {
                startedFor = null
                if (running) context.stopService(Intent(context, TrackingService::class.java))
                if (me == null || trip == null || snapshot.closingSent) repo.tracking.value = null
                else if (me.primaryRole == "distributor" && !Locations.granted(context)) repo.tracking.value = TrackingState(me.id, snapshot.tripId ?: "", "denied", null)
                return
            }
            val key = "${me!!.id}:${snapshot.tripId}"
            if (running && startedFor == key) return
            if (!visible) return
            val intent = Intent(context, TrackingService::class.java)
                .putExtra(OWNER, me.id).putExtra(TRIP, snapshot.tripId).putExtra(TRUCK, trip?.get("truck_id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
            // Android only allows starting a location service while the app is visible.
            runCatching { ContextCompat.startForegroundService(context, intent); startedFor = key }
        }
    }
}
