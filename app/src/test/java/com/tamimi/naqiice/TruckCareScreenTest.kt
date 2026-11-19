package com.tamimi.naqiice

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Truck service reminders and problem reports, on the distributor's phone. */
class TruckCareScreenTest : ScreenHarness() {
    @Before fun truckCareOn() { Fixture.truckCare = true }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun todayShowsServiceDueBanner() {
        // Truck t1: serviced at 118,000 km on 2026-10-01, plan every 5,000 km; now at 125,000 km.
        show(Screen.Today)
        waitFor("الشاحنة تحتاج صيانة")
        shot("today-service-due")
    }

    @Test fun reportListsOpenIssuesAndSends() {
        show(Screen.TruckIssue)
        waitFor("التكييف لا يبرد")
        compose.onNodeWithText("عنوان المشكلة").performTextInput("الفرامل")
        compose.onNodeWithText("عاجل").performClick()
        shot("truck-issue")
        compose.onNodeWithText("إرسال البلاغ").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_report_truck_issue" in it } }
        val call = Fixture.writes.first { "pureice_report_truck_issue" in it }
        assertTrue(call, "\"title\":\"الفرامل\"" in call && "\"severity\":\"urgent\"" in call && "\"truck_id\":\"t1\"" in call)
    }

    @Test fun reportIsReachableFromMore() {
        show(Screen.More)
        compose.onNodeWithText("بلاغ عطل في الشاحنة").performScrollTo().performClick()
        waitFor("عنوان المشكلة")
    }
}
