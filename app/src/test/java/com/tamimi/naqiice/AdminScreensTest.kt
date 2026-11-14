package com.tamimi.naqiice

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tamimi.naqiice.ui.Screen
import org.junit.Test

/**
 * The administrator screens in the states the web reference captures
 * (tools/web-reference/steps.admin.js, which also lists the routes): lists, add/edit dialogs,
 * the special-price editor and the company form. Names match the web shots so `rows.py` /
 * `pair.py` can pair them; they start with `adm-` so the smoke pass in ScreensTest, which
 * saves `admin-*` without waiting for data, cannot overwrite them.
 */
class AdminScreensTest : ScreenHarness() {
    private fun add(n: Int = 0) { compose.onAllNodesWithText("إضافة")[n].performClick(); compose.waitForIdle() }
    private fun edit(n: Int = 0) { compose.onAllNodesWithContentDescription("تعديل")[n].performClick(); compose.waitForIdle() }

    /** Saves the open dialog and closes it again. */
    private fun box(name: String) {
        // The resting state, as the web shot is taken: no field focused.
        compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.currentFocus?.clearFocus() }
        compose.waitForIdle()
        shotDialog(name)
        compose.onNodeWithContentDescription("إغلاق").performClick()
        compose.waitForIdle()
    }

    @Test fun dashboardAndSettings() {
        show(Screen.Admin, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("مبيعات اليوم").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("إغلاق بانتظار المراجعة", substring = true).assertExists()
        compose.waitUntil(30_000) { pump(); !repo.syncing.value }
        compose.waitForIdle()
        shot("adm-home")
        open(Screen.AdminSetup, "adm-setup")
        compose.onNodeWithText("العملاء والأسعار الخاصة").assertExists()
    }

    @Test fun productsAndVariants() {
        show(Screen.AdminProducts, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("قوالب ثلج").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ثلج مجروش ٥ كجم").assertExists()
        shot("adm-products")
        add(0); compose.onNodeWithText("إضافة — المنتجات").assertExists(); box("adm-products-add")
        edit(0); compose.onNodeWithText("تعديل — المنتجات").assertExists(); box("adm-products-edit")
        add(1); box("adm-variants-add")
        edit(3); compose.onNodeWithText("تعديل — الأصناف والأسعار").assertExists(); box("adm-variants-edit")
    }

    @Test fun regionsAndTrucks() {
        show(Screen.AdminRegions, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("فوة").fetchSemanticsNodes().isNotEmpty() }
        shot("adm-regions")
        edit(0); box("adm-regions-edit")
        open(Screen.AdminTrucks, "adm-trucks")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("شاحنة ١").fetchSemanticsNodes().isNotEmpty() }
        shot("adm-trucks")
        add(0); box("adm-trucks-add")
        edit(0); box("adm-trucks-edit")
    }

    @Test fun freezers() {
        show(Screen.AdminFreezers, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("FZ-001").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("مبيعات العميل", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("adm-freezers")
        edit(0); box("adm-freezers-edit")
        add(1); box("adm-models-add")
    }

    @Test fun customers() {
        show(Screen.AdminCustomers, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("مطعم البحر").fetchSemanticsNodes().isNotEmpty() }
        shot("adm-customers")
        compose.onNodeWithContentDescription("تعديل بقالة النور").performClick(); box("adm-customers-edit")
        add(0); box("adm-customers-add")
        compose.onAllNodesWithText("أسعار خاصة لهذا العميل").onFirst().performClick()
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("ثلج مجروش ٥ كجم", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("adm-customers-prices")
    }

    @Test fun employees() {
        show(Screen.AdminUsers, "admin")
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText("أحمد باوزير", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("adm-users")
        compose.onNodeWithText("موظف جديد").performClick(); compose.onNodeWithText("حساب موظف جديد").assertExists(); box("adm-users-new")
        compose.onAllNodesWithText("تعديل").onFirst().performClick(); compose.onNodeWithText("تعديل الموظف").assertExists(); box("adm-users-edit")
        compose.onNodeWithText("موظف جديد").performClick()
        compose.waitForIdle()
        val inputs = compose.onAllNodes(hasSetTextAction())
        inputs[0].performTextInput("سعيد"); inputs[1].performTextInput("saeed"); inputs[2].performTextInput("123")
        compose.onNodeWithContentDescription("إظهار الرمز").performClick()
        box("adm-users-error")
    }

    @Test fun company() {
        show(Screen.AdminCompany, "admin")
        compose.waitForIdle()
        shot("adm-company")
        val inputs = compose.onAllNodes(hasSetTextAction())
        inputs[2].performTextInput("05-123456"); inputs[3].performTextInput("شكراً لتعاملكم معنا")
        // The resting state, as the web shot is taken: no field focused.
        compose.runOnUiThread { compose.activity.window.decorView.findFocus()?.clearFocus() }
        compose.waitForIdle()
        shot("adm-company-filled")
    }
}
