package com.tamimi.naqiice

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A distributor's profile: sales figures and his account (charged shortages, deductions). */
class StaffProfileScreenTest : ScreenHarness() {
    @Before fun ledgerOn() { Fixture.staffLedger = true }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun profileShowsBalance() {
        show(Screen.StaffProfile(FX_OWNER), "admin")
        waitFor("المستحق للخصم من الراتب")
        // The fixture owner's account: an 800 shortage less a 500 deduction; sup-d6's entry is not his.
        compose.onNodeWithText("المستحق للخصم من الراتب: ${formatMoney(300.0)}").assertExists()
        compose.onNodeWithText("خصم راتب نوفمبر", substring = true).assertExists()
        // Nobody records deductions on his own account, not even the admin.
        compose.onAllNodesWithText("تسجيل خصم من الراتب").assertCountEquals(0)
        shot("staff-profile")
    }

    @Test fun adminRecordsADeduction() {
        // In the fixture the signed-in admin is FX_OWNER, so record one for another distributor.
        show(Screen.StaffProfile("sup-d6"), "admin")
        waitFor("المستحق للخصم من الراتب")
        compose.onNodeWithText("المستحق للخصم من الراتب: ${formatMoney(1200.0)}").assertExists()
        compose.onNodeWithText("تسجيل خصم من الراتب").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("المبلغ").performTextInput("300")
        compose.onNodeWithText("حفظ").performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_add_staff_entry" in it } }
        val call = Fixture.writes.first { "pureice_add_staff_entry" in it }
        assertTrue(call, "\"p_kind\":\"deduction\"" in call && "\"p_amount\":300" in call && "\"p_staff\":\"sup-d6\"" in call)
    }

    @Test fun distributorSeesOnlyOwnProfile() {
        show(Screen.More)
        compose.onNodeWithText("حسابي").performScrollTo().performClick()
        waitFor("المستحق للخصم من الراتب")
        compose.onNodeWithText("المستحق للخصم من الراتب: ${formatMoney(300.0)}").assertExists()
        // Read-only: no deduction button for the distributor himself.
        compose.onAllNodesWithText("تسجيل خصم من الراتب").assertCountEquals(0)
    }

    @Test fun distributorCannotOpenSomeoneElsesProfile() {
        show(Screen.StaffProfile("sup-d6"))
        compose.waitForIdle()
        compose.onAllNodesWithText("المستحق للخصم من الراتب").assertCountEquals(0)
    }
}
