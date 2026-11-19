package com.tamimi.naqiice

import com.tamimi.naqiice.core.staffSummary
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffSummaryTest {
    private fun sale(trip: String, total: Int, credit: Int) = buildJsonObject {
        put("trip_id", trip); put("total", total); put("cash_paid", total - credit); put("credit_amount", credit)
    }
    private fun payment(amount: Int) = buildJsonObject { put("amount", amount) }
    private fun closing(id: String, difference: Int, status: String = "approved") = buildJsonObject {
        put("id", id); put("difference", difference); put("status", status)
    }
    private fun entry(kind: String, amount: Int, closing: String? = null) = buildJsonObject {
        put("kind", kind); put("amount", amount); if (closing == null) put("closing_id", JsonNull) else put("closing_id", closing)
    }

    @Test fun staffSummaryTotals() {
        val s = staffSummary(
            sales = listOf(sale("t1", 2500, 1000), sale("t1", 1500, 0), sale("t2", 4000, 4000)),
            payments = listOf(payment(700), payment(300)),
            closings = listOf(closing("k1", -2000), closing("k2", 300), closing("k3", -500), closing("k4", -900, "submitted")),
            ledger = listOf(entry("shortage", 2000, "k1"), entry("deduction", -1500), entry("adjustment", -200)),
        )
        assertEquals(8000.0, s.sales, 0.0)
        assertEquals(3, s.invoices)
        assertEquals(3000.0, s.cashSales, 0.0)
        assertEquals(5000.0, s.creditSales, 0.0)
        assertEquals(1000.0, s.collected, 0.0)
        assertEquals(2, s.campaigns)
        // Approved shortages only: k1 (2,000) and k3 (500); k4 is not approved, k2 is an overage.
        assertEquals(2500.0, s.shortages, 0.0)
        // The account: charged 2,000, less 1,500 deducted and a 200 adjustment.
        assertEquals(300.0, s.balance, 0.0)
    }

    @Test fun emptyHistory() {
        val s = staffSummary(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(0.0, s.sales, 0.0)
        assertEquals(0, s.invoices)
        assertEquals(0, s.campaigns)
        assertEquals(0.0, s.balance, 0.0)
    }
}
