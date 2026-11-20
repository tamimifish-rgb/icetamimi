package com.tamimi.naqiice

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The supervisor settles missing returns before approving; charges are split with the assistant. */
class SharedChargesScreenTest : ScreenHarness() {
    @Before fun on() { Fixture.sharedCharges = true }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun missingStockIsChargedBeforeApproval() {
        // sup-cl-e (sup-trip-e): 3 bags of v1 (500 each) not returned; sup-d5 leads, sup-d7 assists.
        show(Screen.Closings, "supervisor")
        waitFor("نقص في المرتجع")
        waitFor("الإجمالي: ")
        waitFor("الإجمالي: ${formatMoney(1500.0)}")
        waitFor("ياسر بن شملان ${formatMoney(750.0)}")
        waitFor("فهد الكثيري ${formatMoney(750.0)}")
        shot("closings-missing-stock")
        compose.onNodeWithText("تسجيل النقص على الموزعين").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_settle_missing_stock" in it } }
        val call = Fixture.writes.first { "pureice_settle_missing_stock" in it }
        assertTrue(call, "\"p_closing\":\"sup-cl-e\"" in call)
    }

    @Test fun cashShortageNamesTheSplit() {
        show(Screen.Closings, "supervisor")
        waitFor("نقص في المرتجع")
        // The cash shortage of the same closing will be shared too once it is approved.
        waitFor("يُقسم العجز بالتساوي على: ياسر بن شملان، فهد الكثيري")
    }
}
