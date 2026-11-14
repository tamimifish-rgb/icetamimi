package com.tamimi.naqiice

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.SaleLine
import com.tamimi.naqiice.core.SaleTotals
import com.tamimi.naqiice.core.availableForReplacement
import com.tamimi.naqiice.core.campaignConflictMessage
import com.tamimi.naqiice.core.changeItems
import com.tamimi.naqiice.core.openBlock
import com.tamimi.naqiice.core.openForTruck
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.replacementPayload
import com.tamimi.naqiice.core.snapshotCash
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.validateReason
import com.tamimi.naqiice.core.validateReplacement
import com.tamimi.naqiice.ui.canReplace
import com.tamimi.naqiice.ui.changeBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the web app's src/test/invoice-change.test.ts and campaign-open.test.ts. */
class InvoiceChangeTest {
    private fun row(json: String): Row = parseObject(json)
    private val trip = row("""{"id":"trip-A","status":"open","distributor_id":"driver"}""")
    private val sale = row("""{"id":"s1","trip_id":"trip-A","created_by":"driver"}""")
    private fun sale(extra: String) = row("""{"id":"s1","trip_id":"trip-A","created_by":"driver",$extra}""")

    private fun throwsWith(part: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("expected a failure containing $part", error)
        assertTrue("${error!!.message} should contain $part", error.message!!.contains(part))
    }

    @Test fun owningDistributorAsksOnlyInsideOpenUnclosedCampaign() {
        assertNull(changeBlocker(sale, trip, false, "driver", false))
        assertTrue(changeBlocker(sale(""""_pending":true"""), trip, false, "driver", false)!!.contains("لم تُرفع"))
        assertTrue(changeBlocker(sale, trip, false, "other", false)!!.contains("حساباً آخر"))
        assertTrue(changeBlocker(sale, row("""{"id":"trip-A","status":"closed","distributor_id":"driver"}"""), false, "driver", false)!!.contains("مغلقة"))
        assertTrue(changeBlocker(sale, row("""{"id":"trip-B","status":"open","distributor_id":"driver"}"""), false, "driver", false)!!.contains("مغلقة"))
        assertTrue(changeBlocker(sale, null, false, "driver", false)!!.contains("مغلقة"))
        assertTrue(changeBlocker(sale, trip, true, "driver", false)!!.contains("إغلاق"))
        assertNotNull(changeBlocker(null, trip, false, "driver", false))
    }

    @Test fun managersSkipCampaignChecksButNeverForUnsyncedInvoices() {
        assertNull(changeBlocker(sale, null, true, "admin", true))
        assertTrue(changeBlocker(sale(""""_pending":true"""), null, false, "admin", true)!!.contains("لم تُرفع"))
    }

    @Test fun depositInvoicesCanBeCancelledButNotEdited() {
        assertTrue(canReplace(sale))
        assertFalse(canReplace(sale(""""payment_method":"deposit"""")))
        assertFalse(canReplace(sale(""""deposit_amount":500""")))
    }

    @Test fun replacementMayReuseStockHeldByOldInvoice() {
        val old = listOf(row("""{"variant_id":"v1","qty":4}"""), row("""{"variant_id":"v2","qty":1}"""))
        assertEquals(10.0, availableForReplacement(6.0, old, "v1"), 0.0)
        assertEquals(0.0, availableForReplacement(0.0, old, "v3"), 0.0)
    }

    @Test fun requiresShortNonEmptyReason() {
        assertEquals("خطأ", validateReason("  خطأ  "))
        throwsWith("سبب") { validateReason("   ") }
        throwsWith("طويل") { validateReason("x".repeat(501)) }
        assertEquals(500, validateReason("x".repeat(500)).length)
    }

    @Test fun validatesStockCashAndCreditWithoutTheOldInvoice() {
        val lines = listOf(SaleLine("v1", 8.0, 1000.0))
        fun check(l: List<SaleLine> = lines, cash: Double, creditLimit: Double = 0.0) =
            validateReplacement(l, cash, { 10.0 }, debt = 4000.0, oldCredit = 4000.0, creditLimit = creditLimit)
        assertEquals(SaleTotals(8000.0, 2000.0, 6000.0), check(cash = 2000.0))
        throwsWith("صنفاً") { check(emptyList(), 0.0) }
        throwsWith("المخزون") { check(listOf(SaleLine("v1", 11.0, 1000.0)), 0.0) }
        throwsWith("النقد") { check(cash = 9000.0) }
        throwsWith("النقد") { check(cash = -1.0) }
        throwsWith("النقد") { check(cash = Double.NaN) }
        throwsWith("الكمية") { check(listOf(SaleLine("v1", 0.0, 1000.0)), 0.0) }
        throwsWith("الائتمان") { check(cash = 0.0, creditLimit = 5000.0) }
        assertEquals(5000.0, check(cash = 3000.0, creditLimit = 5000.0).creditAmount, 0.0)
    }

    @Test fun refusesToShrinkBelowWhatWasAlreadyCollected() {
        // 4000 credit given, 3000 collected -> debt 1000; a 1000-all-cash replacement would leave -3000.
        throwsWith("تحصيل") { validateReplacement(listOf(SaleLine("v1", 1.0, 1000.0)), 1000.0, { 10.0 }, 1000.0, 4000.0, 0.0) }
        assertEquals(3000.0, validateReplacement(listOf(SaleLine("v1", 3.0, 1000.0)), 0.0, { 10.0 }, 1000.0, 4000.0, 0.0).creditAmount, 0.0)
    }

    @Test fun buildsReplacementWithServerCompatibleTotalsAndStableIds() {
        var n = 0
        val p = replacementPayload("new", "T1-X", "c1", listOf(SaleLine("v1", 2.0, 1500.0), SaleLine("v2", 1.0, 700.0)), 1000.0) { "i${n++}" }
        assertEquals(
            row(
                """{"id":"new","invoice_no":"T1-X","customer_id":"c1","total":3700,"cash_paid":1000,"credit_amount":2700,"items":[
                {"id":"i0","sale_id":"new","variant_id":"v1","qty":2,"unit_price":1500,"line_total":3000},
                {"id":"i1","sale_id":"new","variant_id":"v2","qty":1,"unit_price":700,"line_total":700}]}""",
            ),
            p,
        )
        // Whole amounts must be serialised as integers, exactly like the web client.
        assertTrue(p.toString().contains("\"total\":3700,"))
        assertFalse(p.containsKey("payment_method"))
    }

    @Test fun readsItemsAndDriverCashFromStoredSnapshots() {
        assertEquals(1, changeItems(row("""{"items":[{"id":"a"}]}""")).size)
        assertEquals(2, changeItems(row("""{"sale_items":[{"id":"a"},{"id":"b"}]}""")).size)
        assertEquals(2, changeItems(row("""{"items":null,"sale_items":[{"id":"a"},{"id":"b"}]}""")).size)
        assertEquals(emptyList<Row>(), changeItems(null))
        assertEquals(2000.0, snapshotCash(row("""{"cash_paid":5000,"deposit":{"amount":3000}}""")), 0.0)
        assertEquals(5000.0, snapshotCash(row("""{"cash_paid":5000,"deposit":null}""")), 0.0)
        assertEquals(0.0, snapshotCash(null), 0.0)
    }

    // ---- campaign opening ----------------------------------------------------------------

    private val trips = listOf(
        row("""{"id":"a","truck_id":"t1","distributor_id":null,"trip_date":"2026-11-05","status":"open"}"""),
        row("""{"id":"b","truck_id":"t2","distributor_id":"d2","trip_date":"2026-11-08","status":"closed"}"""),
        row("""{"id":"c","truck_id":"t3","distributor_id":"d3","trip_date":"2026-11-07","status":"open"}"""),
    )

    @Test fun previousDateOpenTripIsVisibleAndBlocksTheTruck() {
        assertEquals(listOf("a"), openForTruck(trips, "t1").map { it.str("id") })
        assertTrue(openBlock(trips, "t1", "d9")!!.contains("2026-11-05"))
    }

    @Test fun distributorWithOpenCampaignOnAnotherTruckIsBlocked() {
        assertTrue(openBlock(trips, "t2", "d3")!!.contains("شاحنة أخرى"))
    }

    @Test fun closedHistoricalTripDoesNotBlock() {
        assertNull(openBlock(trips, "t2", "d2"))
        assertNull(openBlock(trips, "t2", null))
    }

    @Test fun uniqueIndexConflictsGetArabicMessages() {
        assertTrue(campaignConflictMessage("duplicate key value violates unique constraint \"finance_active_truck\"").contains("الشاحنة"))
        assertTrue(campaignConflictMessage("violates \"finance_active_distributor\"").contains("الموزع"))
    }
}
