package com.tamimi.naqiice.core

import kotlin.math.max
import kotlin.math.min

// Pure report rules and table builders. Ported 1:1 from the web app's
// src/lib/report-calculations.ts and the table logic of routes/_authenticated/reports.tsx;
// covered by app/src/test/.../ReportsTest.kt.

private const val DAY_MS = 86_400_000L

// ---- dates ----------------------------------------------------------------------------

fun adenDayOf(iso: String?): String = parseIso(iso)?.let { adenDay(it) } ?: ""

private fun dayStart(day: String): Long = parseIso("${day}T00:00:00Z") ?: 0L

/** UTC instants of the first Aden midnight of [from] and the midnight after [to]. */
fun adenBounds(from: String, to: String): Pair<String, String> =
    isoNow(parseIso("${from}T00:00:00+03:00") ?: 0L) to isoNow((parseIso("${to}T00:00:00+03:00") ?: 0L) + DAY_MS)

fun shiftDay(day: String, delta: Int): String = isoNow((parseIso("${day}T12:00:00Z") ?: 0L) + delta * DAY_MS).take(10)

// ---- calculations ---------------------------------------------------------------------

data class CreditInvoice(val id: String, val customerId: String, val creditAmount: Double, val createdAt: String)
data class DebtPayment(val customerId: String, val amount: Double, val createdAt: String)

val AGING_LABELS = listOf("0-30 يوم", "31-60 يوم", "61-90 يوم", "+90 يوم")

/** All history through cutoff is required, not just invoices in the selected period. */
fun fifoAging(invoices: List<CreditInvoice>, payments: List<DebtPayment>, cutoff: String): Map<String, List<Double>> {
    val end = parseIso(cutoff) ?: return emptyMap()
    val cutoffDay = dayStart(adenDay(end))
    val buckets = LinkedHashMap<String, MutableList<Double>>()
    val paid = HashMap<String, Double>()
    for (p in payments) if ((parseIso(p.createdAt) ?: Long.MAX_VALUE) <= end) paid[p.customerId] = (paid[p.customerId] ?: 0.0) + p.amount
    val due = invoices.filter { (parseIso(it.createdAt) ?: Long.MAX_VALUE) <= end }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    for (s in due) {
        val available = paid[s.customerId] ?: 0.0
        val used = min(available, s.creditAmount)
        paid[s.customerId] = available - used
        val remaining = s.creditAmount - used
        if (remaining <= 0) continue
        val days = max(0L, Math.round((cutoffDay - dayStart(adenDayOf(s.createdAt))) / DAY_MS.toDouble()))
        val index = if (days <= 30) 0 else if (days <= 60) 1 else if (days <= 90) 2 else 3
        val b = buckets.getOrPut(s.customerId) { mutableListOf(0.0, 0.0, 0.0, 0.0) }
        b[index] += remaining
    }
    return buckets
}

data class ProfitTotals(val revenue: Double, val expense: Double, val repairs: Double, val net: Double)

fun profitTotals(sales: List<Double>, expenses: List<Double>, maintenance: List<Double>): ProfitTotals {
    val revenue = sales.sum()
    val expense = expenses.sum()
    val repairs = maintenance.sum()
    return ProfitTotals(revenue, expense, repairs, revenue - expense - repairs)
}

fun inactiveCustomer(lastPurchase: String?, days: Int, today: String): Boolean =
    lastPurchase.isNullOrEmpty() || dayStart(today) - dayStart(adenDayOf(lastPurchase)) >= days * DAY_MS

data class VisitSale(val customerId: String?, val truckId: String?, val createdAt: String?)

fun notVisitedToday(customerId: String, customerTruck: String?, truck: String, sales: List<VisitSale>, today: String): Boolean =
    customerTruck == truck && sales.none { it.customerId == customerId && it.truckId == truck && adenDayOf(it.createdAt) == today }

// ---- tables ---------------------------------------------------------------------------

/** A cell is a String, a Double or null. */
data class ExportTable(val title: String, val headers: List<String>, val rows: List<List<Any?>>)

/** Whole numbers without a decimal point, the way the web table prints them. */
fun cellText(value: Any?): String = when (value) {
    null -> "—"
    is Double -> if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 9e15) value.toLong().toString() else value.toString()
    else -> value.toString()
}

data class NamedValue(val name: String, val value: Double)

/** Sums per key, largest first. */
fun <T> groupSum(rows: List<T>, key: (T) -> String, value: (T) -> Double): List<NamedValue> {
    val sums = LinkedHashMap<String, Double>()
    for (r in rows) { val k = key(r); sums[k] = (sums[k] ?: 0.0) + value(r) }
    return sums.map { NamedValue(it.key, it.value) }.sortedByDescending { it.value }
}

val REPORT_TABS = listOf(
    "day" to "حسب اليوم", "truck" to "الشاحنة", "region" to "المنطقة", "product" to "المنتج",
    "customer" to "العميل", "debts" to "الديون", "freezers" to "الثلاجات", "stock" to "فروقات المخزون",
    "profit" to "الأرباح", "expenses" to "المصروفات والصيانة", "inactive" to "عملاء غير نشطين", "visits" to "لم تتم زيارتهم اليوم",
)
val CHART_TABS = setOf("day", "truck", "region", "product", "customer")

class ReportData(
    val sales: List<Row>, val items: List<Row>, val customers: List<Row>, val regions: List<Row>, val trucks: List<Row>,
    val variants: List<Row>, val bal: List<Row>, val freezers: List<Row>, val trips: List<Row>, val stock: List<Row>,
    val expenses: List<Row>, val maintenance: List<Row>, val creditHistory: List<Row>, val paymentHistory: List<Row>,
    val purchaseHistory: List<Row>, val todaySales: List<Row>, val deposits: List<Row>,
) {
    private fun names(rows: List<Row>) = rows.associate { (it.str("id") ?: "") to it.str("name") }
    val customerById = customers.associateBy { it.str("id") ?: "" }
    val regionNames = names(regions)
    val truckNames = names(trucks)
    val variantNames = names(variants)
}

fun stockLeft(s: Row): Double = remainingStock(s.num("loaded"), s.num("sold"), s.num("returned"), s.num("damaged"))

/** The grouped totals behind the first five tabs (also drawn as a chart). */
fun reportRows(d: ReportData, tab: String): List<NamedValue> {
    fun total(s: Row) = s.num("total")
    return when (tab) {
        "day" -> groupSum(d.sales, { adenDayOf(it.str("created_at")) }, ::total).sortedBy { it.name }
        "truck" -> groupSum(d.sales, { d.truckNames[it.str("truck_id")] ?: "—" }, ::total)
        "region" -> groupSum(d.sales, { d.regionNames[d.customerById[it.str("customer_id")]?.str("region_id")] ?: "—" }, ::total)
        "product" -> groupSum(d.items, { d.variantNames[it.str("variant_id")] ?: "—" }) { it.num("qty") }
        "customer" -> groupSum(d.sales, { d.customerById[it.str("customer_id")]?.str("name") ?: "—" }, ::total)
        else -> emptyList()
    }
}

fun reportTable(d: ReportData, tab: String, from: String, to: String, today: String, inactiveDays: Int, visitTruck: String): ExportTable {
    fun truckName(id: String?) = d.truckNames[id] ?: "غير مسند"
    fun customerName(id: String?) = d.customerById[id]?.str("name") ?: "—"
    fun amounts(rows: List<Row>, key: String, truck: String?) = rows.filter { truck == null || it.str("truck_id") == truck }.map { it.num(key) }
    fun profit(truck: String?) = profitTotals(amounts(d.sales, "total", truck), amounts(d.expenses, "amount", truck), amounts(d.maintenance, "cost", truck))
    return when (tab) {
        "profit" -> {
            fun totals(truck: String?) = profit(truck).let { listOf(it.revenue, it.expense, it.repairs, it.net) }
            ExportTable(
                "الأرباح", listOf("الشاحنة / الفئة", "المبيعات", "المصروفات", "الصيانة", "الصافي"),
                listOf(listOf("الإجمالي") + totals(null)) +
                    d.trucks.map { listOf(it.str("name")) + totals(it.str("id") ?: "") } +
                    groupSum(d.expenses, { it.str("category") ?: "" }) { it.num("amount") }.map { listOf("فئة: ${it.name}", null, it.value, null, null) },
            )
        }
        "expenses" -> ExportTable(
            "المصروفات والصيانة", listOf("الشاحنة", "النوع", "الفئة", "التاريخ", "المبلغ", "ملاحظات"),
            d.expenses.map { listOf(truckName(it.str("truck_id")), "مصروف", it.str("category"), adenDayOf(it.str("created_at")), it.num("amount"), it.str("notes")) } +
                d.maintenance.map { listOf(truckName(it.str("truck_id")), "صيانة", it.str("title"), it.str("job_date"), it.num("cost"), it.str("notes")) } +
                listOf(listOf("الإجمالي", "", "", "", profit(null).let { it.expense + it.repairs }, "")),
        )
        "debts" -> {
            val cutoff = isoNow((parseIso(adenBounds(from, to).second) ?: 0L) - 1)
            val aging = fifoAging(
                d.creditHistory.map { CreditInvoice(it.str("id") ?: "", it.str("customer_id") ?: "", it.num("credit_amount"), it.str("created_at") ?: "") },
                d.paymentHistory.map { DebtPayment(it.str("customer_id") ?: "", it.num("amount"), it.str("created_at") ?: "") },
                cutoff,
            )
            val total = List(4) { i -> aging.values.sumOf { it[i] } }
            ExportTable(
                "أعمار الديون حتى $to", listOf("العميل") + AGING_LABELS + "الإجمالي",
                listOf(listOf("الإجمالي") + total + total.sum()) + aging.map { (id, buckets) -> listOf(customerName(id)) + buckets + buckets.sum() },
            )
        }
        "inactive", "visits" -> {
            val latest = HashMap<String, String>()
            for (s in d.purchaseHistory) {
                val id = s.str("customer_id") ?: continue
                val at = s.str("created_at") ?: continue
                if (at > (latest[id] ?: "")) latest[id] = at
            }
            val visits = d.todaySales.map { VisitSale(it.str("customer_id"), it.str("truck_id"), it.str("created_at")) }
            val withFreezer = d.freezers.mapNotNull { it.str("customer_id") }.toSet()
            val list = d.customers.filter { c ->
                val id = c.str("id") ?: ""
                c.bool("active") == true && if (tab == "inactive") inactiveCustomer(latest[id], inactiveDays, today)
                else visitTruck.isNotEmpty() && notVisitedToday(id, c.str("truck_id"), visitTruck, visits, today)
            }
            ExportTable(
                if (tab == "inactive") "عملاء غير نشطين — $inactiveDays يوم" else "لم تتم زيارتهم اليوم — ${truckName(visitTruck)}",
                listOf("العميل", "المنطقة", "الشاحنة", "الهاتف", "آخر شراء", "ثلاجة"),
                list.map { c ->
                    val id = c.str("id") ?: ""
                    listOf(
                        c.str("name"), d.regionNames[c.str("region_id")] ?: "—", truckName(c.str("truck_id")), c.str("phone")?.ifEmpty { null } ?: "غير متوفر",
                        latest[id]?.let { adenDayOf(it) } ?: "لم يشترِ مطلقاً", if (id in withFreezer) "نعم" else "لا",
                    )
                },
            )
        }
        "freezers" -> ExportTable(
            "الثلاجات", listOf("الرقم", "الموديل", "العميل", "الحالة"),
            d.freezers.map { listOf(it.str("serial"), it.obj("freezer_models")?.str("name"), customerName(it.str("customer_id")), it.str("status")) },
        )
        "stock" -> ExportTable(
            "فروقات المخزون", listOf("الشاحنة", "تاريخ الحملة", "الحالة", "الصنف", "المتبقي"),
            d.trips.flatMap { t ->
                d.stock.filter { it.str("trip_id") == t.str("id") && stockLeft(it) != 0.0 }.map {
                    listOf(t.obj("trucks")?.str("name"), t.str("trip_date"), if (t.str("status") == "closed") "مغلقة" else "مفتوحة", d.variantNames[it.str("variant_id")], stockLeft(it))
                }
            },
        )
        else -> {
            val money = tab != "product"
            val rows = reportRows(d, tab)
            val title = REPORT_TABS.firstOrNull { it.first == tab }?.second ?: "التقرير"
            val headers = listOf("البيان", if (money) "القيمة (ر.ي)" else "الكمية")
            if (tab != "day" && tab != "truck") return ExportTable(title, headers, rows.map { listOf(it.name, it.value) })
            fun name(dep: Row) = if (tab == "day") adenDayOf(dep.str("created_at")) else dep.obj("trips")?.obj("trucks")?.str("name") ?: "—"
            val deposits = HashMap<String, Double>()
            for (dep in d.deposits) deposits[name(dep)] = (deposits[name(dep)] ?: 0.0) + dep.num("amount")
            ExportTable(title, headers + "إيداعات", rows.map { listOf(it.name, it.value, deposits[it.name] ?: 0.0) })
        }
    }
}
