package com.tamimi.naqiice

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tamimi.naqiice.data.enqueue
import com.tamimi.naqiice.ui.Screen
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** The distributor's map of customers, marking those invoiced in this campaign. */
class CustomerMapScreenTest : ScreenHarness() {
    /** An invoice still waiting on the phone for [customer] in the open campaign. */
    private fun pendingSale(customer: String) {
        repo.store.enqueue(FX_OWNER, "sale", buildJsonObject {
            put("id", "9d0c7a52-5d0e-4f43-9a3f-3f1b2c6d7e8f"); put("invoice_no", "T1-9"); put("trip_id", FX_TRIP); put("truck_id", "t1")
            put("customer_id", customer); put("total", 500); put("cash_paid", 500); put("credit_amount", 0)
            put("created_by", FX_OWNER); put("created_at", "2026-11-14T09:00:00.000Z")
            put("items", buildJsonArray { add(buildJsonObject { put("id", "i9"); put("variant_id", "v1"); put("qty", 1); put("unit_price", 500); put("line_total", 500) }) })
        })
        compose.runOnUiThread { repo.pending.value = repo.store.outboxFor(FX_OWNER) }
        compose.waitForIdle()
    }

    @Test fun mapShowsVisitedCount() {
        Fixture.customerMap = true
        show(Screen.CustomerMap)
        pendingSale("c3")
        // c3, c4 and c5 have a location; c1, c2 and the three report customers do not.
        compose.onNodeWithText("تمت زيارتهم 1 من 3", substring = true).assertExists()
        compose.onNodeWithText("بلا موقع: 5", substring = true).assertExists()
        compose.onNodeWithContentDescription("خريطة العملاء").assertExists()
        shot("customer-map")
    }

    @Test fun emptyStateWhenNoLocations() {
        show(Screen.CustomerMap)
        compose.onNodeWithText("لا يوجد عملاء بمواقع محفوظة", substring = true).assertExists()
        compose.onNodeWithText("بلا موقع: 5", substring = true).assertExists()
    }

    @Test fun mapIsReachableFromCustomers() {
        show(Screen.Customers)
        compose.onNodeWithText("الخريطة").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("خريطة العملاء", substring = true).assertExists()
    }
}
