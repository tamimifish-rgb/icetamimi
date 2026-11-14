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

private const val OWNER = "11111111-1111-1111-1111-111111111111"
private const val TRIP = "33333333-3333-3333-3333-333333333333"

/** Renders the real screens on the JVM with a sample campaign: no crash, key content shown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-rYE-w411dp-h891dp-xhdpi", application = FixtureApp::class)
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<FixtureApp>()
    private val repo get() = app.repo
    private lateinit var nav: Nav

    @Before fun signedInDistributor() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // A fresh position, so the location gate is open as it is for a working distributor.
        val manager = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        Shadows.shadowOf(manager).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        Shadows.shadowOf(manager).setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 14.53; longitude = 49.12; accuracy = 10f; time = System.currentTimeMillis()
        })
        repo.tracking.value = com.tamimi.naqiice.data.TrackingState(OWNER, TRIP, "active", "2026-11-14T16:41:59.000Z")
        repo.online.value = true
    }

    /** Signs in through the fixture and waits for the first full sync, as on a real phone. */
    private fun signedIn(role: String = "distributor") {
        Fixture.role = role
        compose.runOnUiThread { repo.online.value = true; repo.start() }
        compose.waitUntil(30_000) { pump(); repo.me.value?.primaryRole == role }
        // The app was created offline, so its first sync was skipped; run one now.
        compose.runOnUiThread { repo.scope.launch { repo.syncNow() } }
        compose.waitUntil(30_000) { pump(); repo.ref.value != null && !repo.syncing.value }
        compose.runOnUiThread { repo.tracking.value = com.tamimi.naqiice.data.TrackingState(OWNER, TRIP, "active", "2026-11-14T16:41:59.000Z") }
    }

    /** Lets work finished on background threads continue on the (paused) main looper. */
    private fun pump() { Thread.sleep(20); Shadows.shadowOf(Looper.getMainLooper()).idle() }

    private fun show(home: Screen, role: String = "distributor") {
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

    private fun open(screen: Screen, name: String) {
        compose.runOnUiThread { nav.home(screen) }
        compose.waitForIdle()
        shot(name)
    }

    private fun shot(name: String) {
        val dir = File("build/screens").apply { mkdirs() }
        val window = compose.activity.window
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, {}, Handler(Looper.getMainLooper()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun distributorScreensRender() {
        show(Screen.Today)
        compose.onNodeWithText("شاحنة ١ — الحملة").assertExists()
        compose.onNodeWithText("تحميل إضافي بانتظار اعتمادك").assertExists()
        compose.onNodeWithText("مخزون الشاحنة").assertExists()
        shot("today")

        open(Screen.Customers, "customers")
        compose.onNodeWithText("بقالة النور").assertExists()
        compose.onNodeWithText("مطعم البحر").assertExists()

        open(Screen.Customer("c1"), "customer")
        compose.onNodeWithText("الدين الحالي").assertExists()

        open(Screen.Sale("c1"), "sale")
        compose.onNodeWithText("ثلج مجروش ٥ كجم").assertExists()
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.waitForIdle()
        shot("sale-filled")
        compose.onNodeWithText("حفظ الفاتورة").assertExists()

        open(Screen.Payment("c1"), "payment")
        compose.onNodeWithText("حفظ التحصيل").assertExists()
        open(Screen.Expenses, "expenses")
        compose.onNodeWithText("صورة العداد (مطلوبة)").assertExists()
        open(Screen.Maintenance, "maintenance")
        open(Screen.FreezerAction(), "freezer")
        open(Screen.Closing, "closing")
        compose.onNodeWithText("النقد المتوقع").assertExists()
        open(Screen.More, "more")
        open(Screen.NewCustomer, "new-customer")
        open(Screen.Invoice("s0"), "invoice")
        compose.onNodeWithText("إجمالي دين العميل").assertExists()
    }

    @Test fun waitingRecordsShowInTheSyncPanel() {
        show(Screen.Today)
        repo.store.enqueue(OWNER, "gps", buildJsonObject { put("id", "b5d7526c-e484-49ec-9dc0-3eba8db8a489"); put("trip_id", TRIP); put("lat", 14.53); put("lng", 49.12); put("created_by", OWNER) })
        compose.runOnUiThread { repo.pending.value = repo.store.outboxFor(OWNER) }
        compose.waitForIdle()
        compose.onNodeWithText("متصل (1)", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("حالة المزامنة").assertExists()
        compose.onNodeWithText("محفوظ على الهاتف").assertExists()
        shot("sync-panel")
        // The dialog lives in its own window; capture that too.
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.let { window ->
            val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, {}, Handler(Looper.getMainLooper()))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            File("build/screens/sync-dialog.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun signInScreenRenders() {
        compose.setContent { PureIceTheme { CompositionLocalProvider(LocalRepo provides repo) { LoginScreen() } } }
        compose.onNodeWithText("تسجيل الدخول").assertExists()
        compose.onNodeWithText("دخول").performScrollTo().assertExists()
        shot("login")
    }

    @Test fun managerScreensRender() {
        show(Screen.Admin, "admin")
        val screens = listOf(
            Screen.Admin to "admin", Screen.Trips to "trips", Screen.Trip(TRIP) to "trip", Screen.Closings to "closings",
            Screen.Reports to "reports", Screen.RouteMap to "map", Screen.AdminSetup to "admin-setup", Screen.AdminProducts to "admin-products",
            Screen.AdminRegions to "admin-regions", Screen.AdminTrucks to "admin-trucks", Screen.AdminCustomers to "admin-customers",
            Screen.AdminFreezers to "admin-freezers", Screen.AdminUsers to "admin-users", Screen.AdminCompany to "admin-company",
            Screen.InvoiceChanges to "invoice-changes", Screen.InvoiceChange("s0", "cancel") to "invoice-change",
        )
        for ((screen, name) in screens) open(screen, name)
        compose.onNodeWithText("الإعدادات").assertExists()
    }
}
