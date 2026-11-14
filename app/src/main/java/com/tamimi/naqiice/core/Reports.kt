package com.tamimi.naqiice.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
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

// ---- chart axis and table columns -----------------------------------------------------

/**
 * The value-axis ticks of the web chart (recharts' `getNiceTickValues`, as its bar chart
 * calls it for the domain `[0, largest value]` with five ticks): a step that is a round
 * multiple of 5% of its order of magnitude, widened until [count] ticks cover the range.
 */
fun niceTicks(low: Double, high: Double, count: Int = 5): List<Double> {
    val (lo, hi) = if (low > high) high to low else low to high
    if (!lo.isFinite() || !hi.isFinite()) return listOf(0.0)
    if (lo == hi) return singleValueTicks(lo, count)
    val min = BigDecimal.valueOf(lo)
    val max = BigDecimal.valueOf(hi)
    var correction = 0
    while (true) {
        val rough = (max - min).divide(BigDecimal(count - 1), 30, RoundingMode.HALF_UP)
        val digits = floor(log10(rough.toDouble())).toInt() + 1
        val magnitude = if (digits >= 0) BigDecimal.TEN.pow(digits) else BigDecimal.ONE.divide(BigDecimal.TEN.pow(-digits))
        val unit = if (digits != 1) BigDecimal("0.05") else BigDecimal("0.1")
        val ratio = rough.divide(magnitude, 30, RoundingMode.HALF_UP).divide(unit, 20, RoundingMode.HALF_UP)
        val step = (BigDecimal(ceil(ratio.toDouble()).toLong()) + BigDecimal(correction)) * unit * magnitude
        // Zero is a tick whenever it lies inside the range.
        val middle = if (min.signum() <= 0 && max.signum() >= 0) BigDecimal.ZERO else (min + max).divide(BigDecimal(2)).let { it - it.remainder(step) }
        var below = ceil((middle - min).divide(step, 20, RoundingMode.HALF_UP).toDouble()).toInt()
        var above = ceil((max - middle).divide(step, 20, RoundingMode.HALF_UP).toDouble()).toInt()
        val total = below + above + 1
        if (total > count) { correction++; continue }
        if (total < count) { if (hi > 0) above += count - total else below += count - total }
        return (-below..above).map { (middle + step * BigDecimal(it)).toDouble() }
    }
}

private fun singleValueTicks(value: Double, count: Int): List<Double> {
    var step = 1.0
    val middle = when {
        value == 0.0 -> floor((count - 1) / 2.0)
        value == Math.rint(value) -> value
        abs(value) < 1 -> { step = Math.pow(10.0, floor(log10(abs(value)))); floor(value / step) * step }
        else -> floor(value)
    }
    val index = (count - 1) / 2
    return List(count) { BigDecimal.valueOf(middle).add(BigDecimal.valueOf(step).multiply(BigDecimal(it - index))).toDouble() }
}

/** A tick value as JavaScript prints a number: no trailing zeros, no exponent at these sizes. */
fun tickLabel(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

/**
 * Where the web chart draws each axis label, or null where it leaves the tick out (recharts'
 * default `interval="preserveEnd"`). Walking back from the last tick, a label is kept when
 * it fits between [start] and the label kept after it, [gap] apart; the last label is first
 * moved back inside [end] when it would stick out. [ticks] run from [start] towards [end]
 * (in either direction) and share one unit with [sizes], the labels' extent along the axis.
 */
fun tickPlaces(ticks: List<Float>, sizes: List<Float>, start: Float, end: Float, gap: Float = 5f): List<Float?> {
    val sign = if (ticks.size >= 2 && ticks[1] < ticks[0]) -1 else 1
    val places = MutableList<Float?>(ticks.size) { null }
    var limit = end
    for (i in ticks.indices.reversed()) {
        val half = sign * sizes[i] / 2
        var at = ticks[i]
        if (i == ticks.lastIndex) { val over = sign * (at + half - limit); if (over > 0) at -= over * sign }
        if (sign * at < sign * start || sign * at > sign * limit) continue
        if (sign * (at - half - start) < 0 || sign * (at + half - limit) > 0) continue
        places[i] = at
        limit = at - half - sign * gap
    }
    return places
}

/**
 * Column widths of an HTML table with automatic layout, as browsers compute them: with room
 * to spare every column grows in proportion to its widest content; in between, each gets its
 * narrowest width plus a share of the rest; with less than that the table is wider than
 * [available] and scrolls.
 */
fun tableColumnWidths(narrowest: List<Float>, widest: List<Float>, available: Float): List<Float> {
    val least = narrowest.sum()
    val most = widest.sum()
    return when {
        most <= 0f -> List(widest.size) { available / widest.size.coerceAtLeast(1) }
        available >= most -> widest.map { it * available / most }
        available > least -> narrowest.indices.map { narrowest[it] + (widest[it] - narrowest[it]) * (available - least) / (most - least) }
        else -> narrowest
    }
}
