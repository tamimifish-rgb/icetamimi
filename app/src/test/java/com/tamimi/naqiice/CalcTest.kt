package com.tamimi.naqiice

import com.tamimi.naqiice.core.CustomerContact
import com.tamimi.naqiice.core.LocationDecision
import com.tamimi.naqiice.core.SaleLine
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.acceptPosition
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.customerPage
import com.tamimi.naqiice.core.depositTotals
import com.tamimi.naqiice.core.exceedsCreditLimit
import com.tamimi.naqiice.core.expectedCash
import com.tamimi.naqiice.core.filterCustomers
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.locationDecision
import com.tamimi.naqiice.core.normalizeDigits
import com.tamimi.naqiice.core.normalizeUsername
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.core.parseIso
import com.tamimi.naqiice.core.priceFor
import com.tamimi.naqiice.core.remainingStock
import com.tamimi.naqiice.core.routeSummary
import com.tamimi.naqiice.core.saleTotals
import com.tamimi.naqiice.core.uniqueInvoiceNumber
import com.tamimi.naqiice.core.validPin
import com.tamimi.naqiice.core.yemenPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {
    @Test fun saleTotalsRoundLinesAndClampCash() {
        val lines = listOf(SaleLine("a", 3.0, 500.0), SaleLine("b", 1.5, 333.0))
        val full = saleTotals(lines, Double.POSITIVE_INFINITY)
        assertEquals(2000.0, full.total, 0.0) // 1500 + round(499.5) = 500
        assertEquals(2000.0, full.cashPaid, 0.0)
        assertEquals(0.0, full.creditAmount, 0.0)
        val partial = saleTotals(lines, 700.0)
        assertEquals(1300.0, partial.creditAmount, 0.0)
        assertEquals(0.0, saleTotals(lines, -5.0).cashPaid, 0.0)
    }

    @Test fun customerPriceWinsOverDefault() {
        assertEquals(450.0, priceFor("a", 500.0, mapOf("a" to 450.0)), 0.0)
        assertEquals(500.0, priceFor("b", 500.0, mapOf("a" to 450.0)), 0.0)
    }

    @Test fun zeroCreditLimitMeansNoCap() {
        assertFalse(exceedsCreditLimit(0.0, 1_000_000.0, 5000.0))
        assertTrue(exceedsCreditLimit(10_000.0, 8000.0, 2500.0))
        assertFalse(exceedsCreditLimit(10_000.0, 8000.0, 2000.0))
        assertFalse(exceedsCreditLimit(10_000.0, 50_000.0, 0.0))
    }

    @Test fun stockAndExpectedCash() {
        assertEquals(60.0, remainingStock(100.0, 30.0, 8.0, 2.0), 0.0)
        assertEquals(12_500.0, expectedCash(10_000.0, 4000.0, 1500.0), 0.0)
    }

    @Test fun depositRules() {
        val t = depositTotals(10_000.0, 6000.0, 1000.0, true)
        assertEquals(3000.0, t.creditAmount, 0.0)
        assertThrows(IllegalStateException::class.java) { depositTotals(10_000.0, 6000.0, 1000.0, false) }
        assertThrows(IllegalStateException::class.java) { depositTotals(10_000.0, 9500.0, 1000.0, true) }
        assertThrows(IllegalStateException::class.java) { depositTotals(10_000.0, 0.0, 0.0, true) }
    }

    @Test fun arabicAndPersianDigits() {
        assertEquals("123456", normalizeDigits("١٢٣٤٥٦"))
        assertEquals("789", normalizeDigits("۷۸۹"))
        assertEquals("1250.5", numericInput("١٬٢٥٠٫٥ ر.ي".replace("ر.ي", "")))
        assertEquals("ali-7", normalizeUsername("  ALI-٧ "))
        assertTrue(validPin("١٢٣٤٥٦"))
        assertFalse(validPin("12345"))
    }

    @Test fun yemenPhones() {
        assertEquals("967771234567", yemenPhone("771234567"))
        assertEquals("967771234567", yemenPhone("0771234567"))
        assertEquals("967771234567", yemenPhone("+967 771 234 567"))
        assertEquals("967771234567", yemenPhone("00967771234567"))
        assertEquals("9675123456", yemenPhone("05123456"))
        assertNull(yemenPhone("12345"))
        assertNull(yemenPhone(null))
    }

    @Test fun adenDatesAndInvoiceNumbers() {
        // 2026-11-14 22:30 UTC is already the 15th in Aden (UTC+3).
        val at = parseIso("2026-11-14T22:30:00.000Z")!!
        assertEquals("2026-11-15", adenDay(at))
        assertEquals("T1-20261115-aaaabbbbccccddddeeeeffff00001111", uniqueInvoiceNumber("T1", "aaaabbbb-cccc-dddd-eeee-ffff00001111", at))
        assertEquals("2026-11-14T22:30:00.000Z", isoNow(at))
    }

    @Test fun parsesPostgresTimestamps() {
        val z = parseIso("2026-11-14T10:00:00.000Z")
        assertEquals(z, parseIso("2026-11-14T10:00:00+00:00"))
        assertEquals(z, parseIso("2026-11-14T13:00:00.000000+03:00"))
        assertEquals(z!! + 123, parseIso("2026-11-14T10:00:00.123456+00"))
        assertNull(parseIso("yesterday"))
    }

    @Test fun wholeAmountsSerializeAsIntegers() {
        assertEquals("5000", jnum(5000.0).toString())
        assertEquals("2.5", jnum(2.5).toString())
    }

    @Test fun locationGate() {
        assertEquals(LocationDecision.BLOCKED, locationDecision(false, true, null, 0, true))
        assertEquals(LocationDecision.READY, locationDecision(true, true, null, 0, false))
        assertEquals(LocationDecision.BLOCKED, locationDecision(true, false, 0, 119_999, true))
        assertEquals(LocationDecision.WITHOUT_LOCATION, locationDecision(true, false, 0, 120_000, true))
        // Only invoices may be saved without a fix.
        assertEquals(LocationDecision.BLOCKED, locationDecision(true, false, 0, 500_000, false))
    }

    @Test fun trackingPolicy() {
        assertTrue(acceptPosition(30f, 1000, 2000))
        assertFalse(acceptPosition(150f, 1000, 200_000))
        assertTrue(acceptPosition(150f, 1000, 301_000))
        val summary = routeSummary(listOf(
            TrackPoint(14.5300, 49.1200, "2026-11-14T06:00:00.000Z"),
            TrackPoint(14.5400, 49.1200, "2026-11-14T06:01:00.000Z"),
            TrackPoint(14.5400, 49.1300, "2026-11-14T06:11:00.000Z"),
        ))
        assertEquals(3, summary.count)
        assertEquals(1, summary.gaps)
        assertEquals(10.0, summary.untrackedMinutes, 0.001)
        assertEquals(11.0, summary.minutes, 0.001)
        assertTrue(summary.km > 2.0 && summary.km < 2.4)
    }

    @Test fun customerSearchAndPages() {
        val rows = (1..120).map { CustomerContact("id$it", "بقالة $it", if (it == 7) "0771 234 567" else null, if (it % 2 == 0) "r1" else "r2", null, null) }
        assertEquals(60, filterCustomers(rows, { it }, "", "r1").size)
        assertEquals(1, filterCustomers(rows, { it }, "٧٧١٢٣٤").size)
        assertEquals("id7", filterCustomers(rows, { it }, "771-234").single().id)
        assertEquals(listOf("id12", "id120"), filterCustomers(rows, { it }, "بقالة  12").map { it.id })
        val last = customerPage(rows, 99)
        assertEquals(3, last.page)
        assertEquals(101, last.start)
        assertEquals(120, last.end)
        assertEquals(20, last.rows.size)
        assertEquals(0, customerPage(emptyList<CustomerContact>(), 1).start)
    }
}
