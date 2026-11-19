package com.tamimi.naqiice

import android.content.Intent
import com.tamimi.naqiice.tracking.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows

class TrackingTest : ScreenHarness() {
    /** Stopping before startForeground kills the app on Android 9+, so it must come first. */
    @Test fun serviceCallsStartForegroundEvenWhenItStopsAtOnce() {
        val service = Robolectric.buildService(TrackingService::class.java, Intent()).create().startCommand(0, 1).get()
        val shadow = Shadows.shadowOf(service)
        // Robolectric clears the notification on stopForeground; the service type it was started with stays.
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        assertTrue(shadow.isForegroundStopped && shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
    }

    /** From the background a location service gets no fixes (Android 11) or is refused (12+). */
    @Test fun onlyAVisibleAppStartsTracking() {
        signedIn("distributor")
        val me = repo.me.value!!
        val snapshot = repo.snapshot.value
        assertTrue(snapshot.ownOpenTrip(me.id) && !snapshot.closingSent) // tracking is wanted
        val app = Shadows.shadowOf(this.app)
        app.clearStartedServices()
        compose.runOnUiThread {
            TrackingService.visible = false
            TrackingService.update(this.app, repo)
        }
        assertNull(app.nextStartedService)
        compose.runOnUiThread {
            TrackingService.visible = true
            TrackingService.update(this.app, repo)
            TrackingService.visible = false
        }
        assertEquals(TrackingService::class.java.name, app.nextStartedService?.component?.className)
    }

    /** One tracker per truck: the main distributor's phone records the route, never the assistant's. */
    @Test fun assistantDoesNotTrack() {
        Fixture.assistant = "assistant"
        signedIn("distributor")
        val snapshot = repo.snapshot.value
        assertTrue(snapshot.ownOpenTrip(repo.me.value!!.id) && snapshot.isAssistant) // he may sell
        val app = Shadows.shadowOf(this.app)
        app.clearStartedServices()
        compose.runOnUiThread {
            TrackingService.visible = true
            TrackingService.update(this.app, repo)
            TrackingService.visible = false
        }
        assertNull(app.nextStartedService)
        assertNull(repo.tracking.value)
    }
}
