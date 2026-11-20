package com.tamimi.naqiice

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.tamimi.naqiice.data.Me
import com.tamimi.naqiice.data.enqueue
import com.tamimi.naqiice.ui.AppShell
import com.tamimi.naqiice.ui.LocalNav
import com.tamimi.naqiice.ui.LocalRepo
import com.tamimi.naqiice.ui.LoginScreen
import com.tamimi.naqiice.ui.Nav
import com.tamimi.naqiice.ui.PureIceTheme
import com.tamimi.naqiice.ui.Screen
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

const val FX_OWNER = "11111111-1111-1111-1111-111111111111"
const val FX_TRIP = "33333333-3333-3333-3333-333333333333"

/**
 * Renders the real screens on the JVM (411×891 dp at 2×, Arabic) with the shared fixture
 * behind a real sign-in and sync, and saves each as `app/build/screens/<name>.png` for
 * comparison with the web reference (tools/web-reference).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-rYE-w411dp-h891dp-xhdpi", application = FixtureApp::class)
abstract class ScreenHarness {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    /** On a failure, saves what was on screen as `failed-<test>.png` and prints the app state. */
    @get:Rule(order = 0) val onFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            println("FAILED-STATE ${description.methodName}: me=${repo.me.value?.primaryRole} ref=${repo.ref.value != null} syncing=${repo.syncing.value} online=${repo.online.value} error=${repo.lastError.value}")
            runCatching { shot("failed-${description.methodName}") }
        }
    }

    protected val app get() = ApplicationProvider.getApplicationContext<FixtureApp>()
    protected val repo get() = app.repo
    protected lateinit var nav: Nav

    @Before fun signedInDistributor() {
        Fixture.boxes = false
        Fixture.customerMap = false
        Fixture.handover = false
        Fixture.staffLedger = false
        Fixture.truckCare = false
        Fixture.assistant = null
        Fixture.sharedCharges = false
        Fixture.writes.clear()
        com.tamimi.naqiice.ui.scannerAsksForCamera = false
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // A fresh position, so the location gate is open as it is for a working distributor.
        val manager = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        Shadows.shadowOf(manager).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        Shadows.shadowOf(manager).setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 14.53; longitude = 49.12; accuracy = 10f; time = System.currentTimeMillis()
        })
        repo.tracking.value = com.tamimi.naqiice.data.TrackingState(FX_OWNER, FX_TRIP, "active", "2026-11-14T16:41:59.000Z")
        repo.online.value = true
    }

    /** Signs in through the fixture and waits for the first full sync, as on a real phone. */
    protected fun signedIn(role: String = "distributor") {
        Fixture.role = role
        compose.runOnUiThread { repo.online.value = true; repo.start() }
        compose.waitUntil(30_000) { pump(); repo.me.value?.primaryRole == role }
        // The app was created offline, so its first sync was skipped; run one now.
        compose.runOnUiThread { repo.scope.launch { repo.syncNow() } }
        compose.waitUntil(30_000) { pump(); repo.ref.value != null && !repo.syncing.value }
        compose.runOnUiThread { repo.tracking.value = com.tamimi.naqiice.data.TrackingState(FX_OWNER, FX_TRIP, "active", "2026-11-14T16:41:59.000Z") }
    }

    /** Lets work finished on background threads continue on the (paused) main looper. */
    protected fun pump() { Thread.sleep(20); Shadows.shadowOf(Looper.getMainLooper()).idle() }

    protected fun show(home: Screen, role: String = "distributor") {
        signedIn(role)
        compose.setContent {
            PureIceTheme {
                CompositionLocalProvider(LocalRepo provides repo) {
                    val n = remember { Nav(home).also { nav = it } }
                    CompositionLocalProvider(LocalNav provides n) { AppShell(repo.me.value!!) }
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun open(screen: Screen, name: String) {
        compose.runOnUiThread { nav.home(screen) }
        compose.waitForIdle()
        shot(name)
    }

    protected fun shot(name: String) {
        val dir = File("build/screens").apply { mkdirs() }
        val window = compose.activity.window
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, {}, Handler(Looper.getMainLooper()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A dialog lives in its own window; saves the latest one. */
    protected fun shotDialog(name: String) {
        compose.waitForIdle()
        val window = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window ?: return
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, {}, Handler(Looper.getMainLooper()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
