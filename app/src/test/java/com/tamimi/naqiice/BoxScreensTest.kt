package com.tamimi.naqiice

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Returnable boxes and the customer-location confirmation, with the server feature on. */
class BoxScreensTest : ScreenHarness() {
    @Before fun boxesOn() { Fixture.boxes = true }

    private fun waitFor(text: String) = compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun ops(kind: String) = repo.store.outboxFor(FX_OWNER).filter { it.kind == kind }

    /** Types a box number in the open scan dialog (the camera has no picture in tests). */
    private fun typeBox(number: String, button: String = "إضافة") {
        compose.onNodeWithText("رقم الصندوق").performTextInput(number)
        compose.onNodeWithText(button).performClick()
        compose.waitForIdle()
    }

    @Test fun invoiceRecordsKeptBoxesAndAsksForTheLocation() {
        show(Screen.Sale("c1"))
        // No boxed product chosen yet, but the customer already holds a box.
        compose.onNodeWithText("الصناديق").assertExists()
        compose.onNodeWithText("صناديق مسجلة عند العميل الآن: 3").assertExists()
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("0 من 2").assertExists()
        shot("box-sale")

        compose.onAllNodesWithText("مسح").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        typeBox("1")
        compose.onNodeWithText("تمت إضافة الصندوق 1").assertExists()
        typeBox("1")
        compose.onNodeWithText("الصندوق 1 مضاف من قبل").assertExists()
        typeBox("999")
        compose.onNodeWithText("الصندوق TM-ICE-000999 غير مسجل في النظام").assertExists()
        typeBox("4") // recorded in the factory: accepted, with a note
        compose.onNodeWithText("مسجل: في المصنع").assertExists()
        typeBox("2")
        compose.onNodeWithText("لا يمكن إضافة أكثر من 2").assertExists()
        shotDialog("box-scan")
        compose.onNodeWithText("تأكيد (2)").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 من 2").assertExists()

        compose.onNodeWithText("حفظ الفاتورة").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); ops("sale").isNotEmpty() || Fixture.writes.any { "save_invoice" in it } }
        waitFor("هل أنت الآن عند موقع العميل")
        shotDialog("location-confirm")
        val move = Fixture.writes.firstOrNull { "pureice_box_move" in it } ?: ops("box_move").single().payload.toString()
        assertTrue("TM-ICE-000001" in move && "TM-ICE-000004" in move && "\"deliver\"" in move && "\"c1\"" in move)

        compose.onNodeWithText("نعم، احفظ الموقع").performClick()
        compose.waitUntil(30_000) { pump(); nav.current is Screen.Invoice }
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_set_customer_location" in it } }
        val location = Fixture.writes.first { "pureice_set_customer_location" in it }
        assertTrue("\"customer_id\":\"c1\"" in location && "14.53" in location && "49.12" in location && "\"accuracy\":10" in location)
    }

    @Test fun decliningTheLocationSavesNothingAndAsksNextTime() {
        show(Screen.Sale("c1"))
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("حفظ الفاتورة").performScrollTo().performClick()
        waitFor("هل أنت الآن عند موقع العميل")
        compose.onNodeWithText("لا، لست عنده").performClick()
        compose.waitUntil(30_000) { pump(); nav.current is Screen.Invoice }
        assertTrue(ops("customer_location").isEmpty())
        assertFalse(Fixture.writes.any { "pureice_set_customer_location" in it })
        assertNull(repo.snapshot.value.customer("c1")!!.str("lat"))
    }

    @Test fun customerPageCollectsEmptyBoxes() {
        show(Screen.Customer("c1"))
        compose.onNodeWithText("الصناديق لدى العميل").assertExists()
        compose.onNodeWithText("TM-ICE-000003").assertExists()
        compose.onNodeWithText("لم يُحفظ موقع العميل بعد", substring = true).assertExists()
        shot("box-customer")
        compose.onNodeWithText("استلام فارغة").performScrollTo().performClick()
        compose.waitForIdle()
        typeBox("3")
        compose.onNodeWithText("تأكيد (1)").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_box_move" in it } || ops("box_move").isNotEmpty() }
        val move = Fixture.writes.firstOrNull { "pureice_box_move" in it } ?: ops("box_move").single().payload.toString()
        assertTrue("\"collect\"" in move && "TM-ICE-000003" in move && "\"truck_id\":\"t1\"" in move && FX_TRIP in move)
    }

    @Test fun distributorSeesTheBoxesOnTheTruck() {
        show(Screen.More)
        compose.onNodeWithText("الصناديق").assertExists()
        open(Screen.Boxes, "box-distributor")
        compose.onNodeWithText("صناديق على شاحنتي", substring = true).assertExists()
        compose.onNodeWithText("TM-ICE-000001").assertExists()
        compose.onNodeWithText("TM-ICE-000002").assertExists()
        compose.onAllNodesWithText("TM-ICE-000004").assertCountEquals(0)
        open(Screen.Box("b3"), "box-detail-distributor")
        compose.onNodeWithText("عند العميل بقالة النور").assertExists()
        compose.onNodeWithText("استلام على شاحنتي").assertExists()
        compose.onAllNodesWithText("استبعاد").assertCountEquals(0)
    }

    @Test fun supervisorLoadsBoxesOnATruck() {
        show(Screen.Trips, "supervisor")
        compose.onNodeWithText("الصناديق").assertExists()
        open(Screen.Boxes, "box-manager")
        compose.onNodeWithText("على الشاحنات").assertExists()
        compose.onNodeWithText("لم تُمسح بعد").assertExists()
        compose.onNodeWithText("TM-ICE-000006").performScrollTo().assertExists()
        compose.onNodeWithText("تحميل على شاحنة").performScrollTo().performClick()
        compose.waitForIdle()
        // More than one truck in the sample data: the truck is asked for first.
        compose.onAllNodesWithText("شاحنة ١").onLast().performClick()
        compose.waitForIdle()
        typeBox("4"); typeBox("5"); typeBox("9")
        shotDialog("box-load")
        compose.onNodeWithText("تحميل (3)").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_box_move" in it } || ops("box_move").isNotEmpty() }
        val move = Fixture.writes.firstOrNull { "pureice_box_move" in it } ?: ops("box_move").single().payload.toString()
        assertTrue("\"load\"" in move && "TM-ICE-000004" in move && "TM-ICE-000009" in move && "\"truck_id\":\"t1\"" in move)

        open(Screen.Box("b3"), "box-detail")
        waitFor("سجل الصندوق")
        waitFor("تسليم للعميل بقالة النور")
        compose.onNodeWithText("تحميل على شاحنة ١").assertExists()
        shot("box-detail")
        compose.onNodeWithText("استبعاد").assertExists()
    }

    @Test fun adminChoosesBoxedProductsAndSeesTheMenu() {
        show(Screen.AdminSetup, "admin")
        compose.onNodeWithText("إعداد الصناديق").assertExists()
        open(Screen.AdminBoxes, "box-admin")
        compose.onNodeWithText("المنتجات المعبأة في صناديق").assertExists()
        compose.onNodeWithText("إضافة أرقام صناديق جديدة").assertExists()
    }

    @Test fun nothingAboutBoxesShowsWithoutTheServerFeature() {
        Fixture.boxes = false
        show(Screen.More)
        compose.onAllNodesWithText("الصناديق").assertCountEquals(0)
        open(Screen.Customer("c1"), "box-off-customer")
        compose.onAllNodesWithText("الصناديق لدى العميل").assertCountEquals(0)
        open(Screen.Sale("c1"), "box-off-sale")
        compose.onAllNodesWithContentDescription("زيادة").onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("الصناديق").assertCountEquals(0)
        compose.onNodeWithText("حفظ الفاتورة").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); nav.current is Screen.Invoice }
        assertNotNull(nav.current)
        assertEquals(0, ops("customer_location").size)
    }
}
