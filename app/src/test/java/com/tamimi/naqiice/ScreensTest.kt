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
import androidx.compose.ui.test.onAllNodesWithText
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
@Config(sdk = [34], qualifiers = "ar-rYE-w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PureIceApp>()
    private val repo get() = app.repo
    private lateinit var nav: Nav

    private fun sampleRef() = buildJsonObject {
        put("ownerId", OWNER)
        put("includedIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("s0")) })
        put("variants", buildJsonArray {
            add(buildJsonObject { put("id", "v1"); put("name", "ثلج مجروش ٥ كجم"); put("unit", "كيس"); put("price", 500) })
            add(buildJsonObject { put("id", "v2"); put("name", "قالب ثلج"); put("unit", "قالب"); put("price", 1500) })
        })
        put("regions", buildJsonArray { add(buildJsonObject { put("id", "r1"); put("name", "المكلا") }); add(buildJsonObject { put("id", "r2"); put("name", "فوة") }) })
        put("customers", buildJsonArray {
            add(buildJsonObject { put("id", "c1"); put("name", "بقالة النور"); put("phone", "771234567"); put("region_id", "r1"); put("credit_limit", 0); put("active", true) })
            add(buildJsonObject { put("id", "c2"); put("name", "مطعم البحر"); put("phone", JsonNull); put("region_id", "r2"); put("credit_limit", 5000); put("active", true) })
        })
        put("prices", buildJsonArray { add(buildJsonObject { put("customer_id", "c1"); put("variant_id", "v1"); put("price", 450) }) })
        put("balances", buildJsonArray { add(buildJsonObject { put("customer_id", "c1"); put("balance", 2000); put("month_sales", 40000); put("total_sales", 90000) }) })
        put("freezers", buildJsonArray {
            add(buildJsonObject { put("id", "f1"); put("serial", "FZ-001"); put("status", "placed"); put("customer_id", "c1"); put("freezer_models", buildJsonObject { put("name", "٣٠٠ لتر") }) })
            add(buildJsonObject { put("id", "f2"); put("serial", "FZ-002"); put("status", "warehouse"); put("customer_id", JsonNull) })
        })
        put("models", buildJsonArray { })
        put("trucks", buildJsonArray { add(buildJsonObject { put("id", "t1"); put("code", "T1"); put("name", "شاحنة ١") }) })
        put("trip", buildJsonObject { put("id", TRIP); put("truck_id", "t1"); put("distributor_id", OWNER); put("status", "open"); put("loading_approved_at", "2026-11-14T05:00:00+00:00") })
        put("stock", buildJsonArray {
            add(buildJsonObject { put("variant_id", "v1"); put("loaded", 100); put("sold", 10); put("returned", 0); put("damaged", 0) })
            add(buildJsonObject { put("variant_id", "v2"); put("loaded", 20); put("sold", 20); put("returned", 0); put("damaged", 0) })
        })
        put("unapprovedLoads", buildJsonArray { add(buildJsonObject { put("id", "l1"); put("variant_id", "v1"); put("qty", 30) }) })
        put("salesToday", buildJsonArray {
            add(buildJsonObject {
                put("id", "s0"); put("invoice_no", "T1-20261114-abc"); put("trip_id", TRIP); put("customer_id", "c1"); put("total", 4500); put("cash_paid", 2500); put("credit_amount", 2000)
                put("lat", 14.53); put("lng", 49.12); put("created_by", OWNER); put("created_at", "2026-11-14T06:00:00+00:00")
                put("sale_items", buildJsonArray { add(buildJsonObject { put("id", "i0"); put("variant_id", "v1"); put("qty", 10); put("unit_price", 450); put("line_total", 4500) }) })
            })
        })
        put("paymentsToday", buildJsonArray { })
        put("expensesToday", buildJsonArray { add(buildJsonObject { put("id", "e0"); put("category", "وقود"); put("amount", 3000); put("created_at", "2026-11-14T07:00:00+00:00") }) })
        put("closing", JsonNull)
        put("day", "2026-11-14"); put("pulledAt", 1)
    }

    @Before fun signedInDistributor() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        repo.me.value = Me(OWNER, null, "سالم الموزع", "t1", listOf("distributor"))
        repo.ref.value = sampleRef()
        repo.loading.value = false
        repo.online.value = true
    }

    private fun show(home: Screen) {
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
        compose.onAllNodesWithText("+").onFirst().performClick()
        compose.onAllNodesWithText("+").onFirst().performClick()
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
        repo.store.enqueue(OWNER, "payment", buildJsonObject { put("id", "p1"); put("customer_id", "c1"); put("trip_id", TRIP); put("amount", 700); put("created_by", OWNER) })
        repo.pending.value = repo.store.outboxFor(OWNER)
        show(Screen.Today)
        compose.onNodeWithText("متصل (1)", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("حالة المزامنة").assertExists()
        compose.onNodeWithText("محفوظ على الهاتف").assertExists()
        shot("sync-panel")
    }

    @Test fun signInScreenRenders() {
        repo.me.value = null
        compose.setContent { PureIceTheme { CompositionLocalProvider(LocalRepo provides repo) { LoginScreen() } } }
        compose.onNodeWithText("تسجيل الدخول").assertExists()
        compose.onNodeWithText("دخول").performScrollTo().assertExists()
        shot("login")
    }

    /** Manager screens read online only; without a session they must show an error, not crash. */
    @Test fun managerScreensRenderWithoutAConnection() {
        repo.me.value = Me(OWNER, null, "المدير", null, listOf("admin"))
        show(Screen.Admin)
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
