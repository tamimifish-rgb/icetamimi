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


/** Distributor screens, the sync panel, sign-in and a smoke pass over the manager screens. */
class ScreensTest : ScreenHarness() {
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
        repo.store.enqueue(FX_OWNER, "gps", buildJsonObject { put("id", "b5d7526c-e484-49ec-9dc0-3eba8db8a489"); put("trip_id", FX_TRIP); put("lat", 14.53); put("lng", 49.12); put("created_by", FX_OWNER) })
        compose.runOnUiThread { repo.pending.value = repo.store.outboxFor(FX_OWNER) }
        compose.waitForIdle()
        compose.onNodeWithText("متصل (1)", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("حالة المزامنة").assertExists()
        compose.onNodeWithText("محفوظ على الهاتف").assertExists()
        shot("sync-panel")
        shotDialog("sync-dialog")
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
            Screen.Admin to "admin", Screen.Trips to "trips", Screen.Trip(FX_TRIP) to "trip", Screen.Closings to "closings",
            Screen.Reports to "reports", Screen.RouteMap() to "map", Screen.AdminSetup to "admin-setup", Screen.AdminProducts to "admin-products",
            Screen.AdminRegions to "admin-regions", Screen.AdminTrucks to "admin-trucks", Screen.AdminCustomers to "admin-customers",
            Screen.AdminFreezers to "admin-freezers", Screen.AdminUsers to "admin-users", Screen.AdminCompany to "admin-company",
            Screen.InvoiceChanges to "invoice-changes", Screen.InvoiceChange("s0", "cancel") to "invoice-change",
        )
        for ((screen, name) in screens) open(screen, name)
        compose.onNodeWithText("الإعدادات").assertExists()
    }
}
