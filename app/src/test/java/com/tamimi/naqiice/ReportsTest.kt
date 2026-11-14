package com.tamimi.naqiice

import com.tamimi.naqiice.core.CreditInvoice
import com.tamimi.naqiice.core.DebtPayment
import com.tamimi.naqiice.core.ExportTable
import com.tamimi.naqiice.core.ProfitTotals
import com.tamimi.naqiice.core.ReportData
import com.tamimi.naqiice.core.VisitSale
import com.tamimi.naqiice.core.Xlsx
import com.tamimi.naqiice.core.adenBounds
import com.tamimi.naqiice.core.cellText
import com.tamimi.naqiice.core.fifoAging
import com.tamimi.naqiice.core.inactiveCustomer
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.notVisitedToday
import com.tamimi.naqiice.core.parseIso
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.profitTotals
import com.tamimi.naqiice.core.reportRows
import com.tamimi.naqiice.core.reportTable
import com.tamimi.naqiice.core.shiftDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Mirrors the web app's src/test/report-calculations.test.ts, plus the table builders and the Excel writer. */
class ReportsTest {
    private val invoices = listOf(
        CreditInvoice("1", "A", 100.0, "2026-06-01T00:00:00Z"),
        CreditInvoice("2", "A", 200.0, "2026-10-20T00:00:00Z"),
        CreditInvoice("3", "B", 75.0, "2026-09-01T00:00:00Z"),
    )

    @Test fun paymentsRetireOldestCreditFirstPerCustomer() {
        val result = fifoAging(invoices, listOf(DebtPayment("A", 150.0, "2026-11-01T00:00:00Z")), "2026-11-10T20:59:59Z")
        assertEquals(listOf(150.0, 0.0, 0.0, 0.0), result["A"])
        assertEquals(listOf(0.0, 0.0, 75.0, 0.0), result["B"])
    }

    @Test fun ignoresPaymentsAfterCutoff() {
        val result = fifoAging(invoices, listOf(DebtPayment("A", 300.0, "2026-12-01T00:00:00Z")), "2026-11-10T20:59:59Z")
        assertEquals(listOf(200.0, 0.0, 0.0, 100.0), result["A"])
    }

    @Test fun usesExactDayBuckets() {
        val cutoff = "2026-11-10T12:00:00Z"
        for ((days, bucket) in listOf(30 to 0, 31 to 1, 60 to 1, 61 to 2, 90 to 2, 91 to 3)) {
            val createdAt = isoNow(parseIso(cutoff)!! - days * 86_400_000L)
            val row = fifoAging(listOf(CreditInvoice("x", "A", 10.0, createdAt)), emptyList(), cutoff)["A"]
            assertEquals("day $days", 10.0, row!![bucket], 0.0)
        }
    }

    @Test fun netIsSalesLessExpensesLessMaintenance() {
        assertEquals(ProfitTotals(1000.0, 200.0, 75.0, 725.0), profitTotals(listOf(1000.0), listOf(200.0), listOf(75.0)))
    }

    @Test fun inactivityIncludesNeverPurchasedAndExactThreshold() {
        for (days in listOf(7, 14, 30)) {
            assertTrue(inactiveCustomer(null, days, "2026-11-10"))
            val date = isoNow(parseIso("2026-11-10T12:00:00Z")!! - days * 86_400_000L)
            assertTrue(inactiveCustomer(date, days, "2026-11-10"))
            assertFalse(inactiveCustomer("2026-11-09T12:00:00Z", days, "2026-11-10"))
        }
    }

    @Test fun notVisitedScopesAssignedTruckAndAdenDay() {
        assertTrue(notVisitedToday("A", "T", "T", emptyList(), "2026-11-10"))
        assertFalse(notVisitedToday("A", "T", "B", emptyList(), "2026-11-10"))
        // 22:00 UTC on the 9th is already the 10th in Aden.
        assertFalse(notVisitedToday("A", "T", "T", listOf(VisitSale("A", "T", "2026-11-09T22:00:00Z")), "2026-11-10"))
    }

    @Test fun adenBoundsAndShiftDay() {
        assertEquals("2026-10-31T21:00:00.000Z" to "2026-11-10T21:00:00.000Z", adenBounds("2026-11-01", "2026-11-10"))
        assertEquals("2026-02-27", shiftDay("2026-03-05", -6))
        assertEquals("2027-01-01", shiftDay("2026-12-31", 1))
    }

    private fun rows(vararg json: String) = json.map { parseObject(it) }

    private fun data() = ReportData(
        sales = rows(
            """{"id":"s1","total":1000,"truck_id":"t1","customer_id":"c1","created_at":"2026-11-01T22:00:00+00:00"}""",
            """{"id":"s2","total":"500","truck_id":"t2","customer_id":"c2","created_at":"2026-11-01T10:00:00+00:00"}""",
            """{"id":"s3","total":250,"truck_id":"t1","customer_id":"c1","created_at":"2026-11-02T10:00:00+00:00"}""",
        ),
        items = rows("""{"id":"i1","variant_id":"v1","qty":4}""", """{"id":"i2","variant_id":"v1","qty":6}"""),
        customers = rows(
            """{"id":"c1","name":"بقالة","region_id":"r1","truck_id":"t1","phone":"","active":true}""",
            """{"id":"c2","name":"مطعم","region_id":null,"truck_id":"t2","phone":"777000000","active":true}""",
            """{"id":"c3","name":"موقوف","region_id":"r1","truck_id":"t1","phone":null,"active":false}""",
        ),
        regions = rows("""{"id":"r1","name":"الديس"}"""),
        trucks = rows("""{"id":"t1","name":"شاحنة ١"}""", """{"id":"t2","name":"شاحنة ٢"}"""),
        variants = rows("""{"id":"v1","name":"كيس"}"""),
        bal = emptyList(),
        freezers = rows("""{"id":"f1","serial":"F-1","customer_id":"c1","status":"installed","freezer_models":{"name":"كبير"}}"""),
        trips = rows(
            """{"id":"p1","truck_id":"t1","trip_date":"2026-11-02","status":"closed","trucks":{"name":"شاحنة ١"}}""",
            """{"id":"p2","truck_id":"t1","trip_date":"2026-11-02","status":"open","trucks":{"name":"شاحنة ١"}}""",
        ),
        stock = rows(
            """{"trip_id":"p1","variant_id":"v1","loaded":10,"sold":7,"returned":1,"damaged":0}""",
            """{"trip_id":"p2","variant_id":"v1","loaded":5,"sold":5,"returned":0,"damaged":0}""",
        ),
        expenses = rows("""{"id":"e1","truck_id":"t1","category":"وقود","amount":200,"notes":null,"created_at":"2026-11-02T08:00:00+00:00"}"""),
        maintenance = rows("""{"id":"m1","truck_id":null,"title":"إطار","cost":75,"job_date":"2026-11-02","notes":"x"}"""),
        creditHistory = rows("""{"id":"s1","customer_id":"c1","credit_amount":300,"created_at":"2026-11-01T22:00:00+00:00"}"""),
        paymentHistory = rows("""{"customer_id":"c1","amount":100,"created_at":"2026-11-02T09:00:00+00:00"}"""),
        purchaseHistory = rows(
            """{"id":"s1","customer_id":"c1","created_at":"2026-11-01T22:00:00+00:00"}""",
            """{"id":"s3","customer_id":"c1","created_at":"2026-11-02T10:00:00+00:00"}""",
        ),
        todaySales = rows("""{"id":"s9","customer_id":"c1","truck_id":"t1","created_at":"2026-11-10T05:00:00+00:00"}"""),
        deposits = rows(
            """{"amount":400,"created_at":"2026-11-02T11:00:00+00:00","trips":{"truck_id":"t1","trucks":{"name":"شاحنة ١"}}}""",
            """{"amount":100,"created_at":"2026-11-02T12:00:00+00:00","trips":{"truck_id":"t1","trucks":{"name":"شاحنة ١"}}}""",
        ),
    )

    private fun table(tab: String, visitTruck: String = "") = reportTable(data(), tab, "2026-11-01", "2026-11-10", "2026-11-10", 7, visitTruck)

    @Test fun dayTabGroupsByAdenDayWithDeposits() {
        val t = table("day")
        assertEquals(listOf("البيان", "القيمة (ر.ي)", "إيداعات"), t.headers)
        // s1 at 22:00 UTC belongs to 2 November in Aden.
        assertEquals(listOf(listOf("2026-11-01", 500.0, 0.0), listOf("2026-11-02", 1250.0, 500.0)), t.rows)
    }

    @Test fun truckRegionProductTabs() {
        assertEquals(listOf(listOf("شاحنة ١", 1250.0, 500.0), listOf("شاحنة ٢", 500.0, 0.0)), table("truck").rows)
        assertEquals(listOf(listOf("الديس", 1250.0), listOf("—", 500.0)), table("region").rows)
        val product = table("product")
        assertEquals(listOf("البيان", "الكمية"), product.headers)
        assertEquals(listOf(listOf("كيس", 10.0)), product.rows)
        assertEquals(2, reportRows(data(), "customer").size)
    }

    @Test fun profitAndExpensesTabs() {
        assertEquals(
            listOf(
                listOf("الإجمالي", 1750.0, 200.0, 75.0, 1475.0), listOf("شاحنة ١", 1250.0, 200.0, 0.0, 1050.0),
                listOf("شاحنة ٢", 500.0, 0.0, 0.0, 500.0), listOf("فئة: وقود", null, 200.0, null, null),
            ),
            table("profit").rows,
        )
        val e = table("expenses").rows
        assertEquals(listOf("شاحنة ١", "مصروف", "وقود", "2026-11-02", 200.0, null), e[0])
        assertEquals(listOf("غير مسند", "صيانة", "إطار", "2026-11-02", 75.0, "x"), e[1])
        assertEquals(listOf("الإجمالي", "", "", "", 275.0, ""), e[2])
    }

    @Test fun debtsStockFreezersTabs() {
        val debts = table("debts")
        assertEquals("أعمار الديون حتى 2026-11-10", debts.title)
        assertEquals(listOf(listOf("الإجمالي", 200.0, 0.0, 0.0, 0.0, 200.0), listOf("بقالة", 200.0, 0.0, 0.0, 0.0, 200.0)), debts.rows)
        // Two campaigns of one truck on one day stay separate; only the one with a difference is listed.
        assertEquals(listOf(listOf("شاحنة ١", "2026-11-02", "مغلقة", "كيس", 2.0)), table("stock").rows)
        assertEquals(listOf(listOf("F-1", "كبير", "بقالة", "installed")), table("freezers").rows)
    }

    @Test fun inactiveAndVisitsTabs() {
        val inactive = table("inactive")
        assertEquals("عملاء غير نشطين — 7 يوم", inactive.title)
        assertEquals(
            listOf(
                listOf("بقالة", "الديس", "شاحنة ١", "غير متوفر", "2026-11-02", "نعم"),
                listOf("مطعم", "—", "شاحنة ٢", "777000000", "لم يشترِ مطلقاً", "لا"),
            ),
            inactive.rows,
        )
        assertTrue(table("visits").rows.isEmpty())
        assertEquals("لم تتم زيارتهم اليوم — غير مسند", table("visits").title)
        assertTrue(table("visits", "t1").rows.isEmpty())
        assertEquals(listOf("مطعم"), table("visits", "t2").rows.map { it[0] })
    }

    @Test fun cellTextPrintsWholeNumbersPlainly() {
        assertEquals("—", cellText(null))
        assertEquals("1500", cellText(1500.0))
        assertEquals("2.5", cellText(2.5))
        assertEquals("نص", cellText("نص"))
    }

    @Test fun xlsxHoldsRightToLeftSheetWithNumericAndInlineCells() {
        val out = ByteArrayOutputStream()
        Xlsx.write(ExportTable("أعمار/الديون [1]", listOf("البيان", "القيمة"), listOf(listOf("a<b & \"c\"", 1500.0), listOf("=SUM(A1)", 2.5), listOf(null, -3.0), listOf("", null))), out)
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"),
            parts.keys,
        )
        assertTrue(parts["xl/workbook.xml"]!!.contains("<sheet name=\"أعمار الديون  1 \" sheetId=\"1\" r:id=\"rId1\"/>"))
        val sheet = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("<sheetView rightToLeft=\"1\" workbookViewId=\"0\"/>"))
        assertTrue(sheet.contains("<dimension ref=\"A1:B5\"/>"))
        assertTrue(sheet.contains("<autoFilter ref=\"A1:B1\"/>"))
        assertTrue(sheet.contains("<c r=\"A1\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">البيان</t></is></c>"))
        assertTrue(sheet.contains("<c r=\"A2\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">a&lt;b &amp; &quot;c&quot;</t></is></c><c r=\"B2\" s=\"1\"><v>1500</v></c>"))
        // Formula-looking text is neutralised; negative numbers stay numeric; empty cells are omitted.
        assertTrue(sheet.contains("<t xml:space=\"preserve\">'=SUM(A1)</t></is></c><c r=\"B3\" s=\"1\"><v>2.5</v></c>"))
        assertTrue(sheet.contains("<row r=\"4\"><c r=\"B4\" s=\"1\"><v>-3</v></c></row><row r=\"5\"></row>"))
        // Every part must be well-formed XML.
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for (xml in parts.values) factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals("AB", Xlsx.column(27))
    }
}
