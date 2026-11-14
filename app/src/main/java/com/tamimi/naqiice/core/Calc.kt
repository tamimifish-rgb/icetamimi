package com.tamimi.naqiice.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// Pure money / stock / debt rules. Ported 1:1 from the web app's src/lib/calc.ts and
// friends; covered by app/src/test. The server stays authoritative for every write.

data class SaleLine(val variantId: String, val qty: Double, val unitPrice: Double)
data class SaleTotals(val total: Double, val cashPaid: Double, val creditAmount: Double)

fun lineTotal(l: SaleLine): Double = (l.qty * l.unitPrice).roundToLong().toDouble()

fun saleTotals(lines: List<SaleLine>, cashPaid: Double): SaleTotals {
    val total = lines.sumOf { lineTotal(it) }
    val cash = max(0.0, min(cashPaid, total))
    return SaleTotals(total, cash, total - cash)
}

/** Customer-specific price wins over the default variant price. */
fun priceFor(variantId: String, defaultPrice: Double, customerPrices: Map<String, Double>): Double =
    customerPrices[variantId] ?: defaultPrice

/** Zero means no configured cap, not a prohibition on credit. */
fun exceedsCreditLimit(limit: Double, debt: Double, newCredit: Double): Boolean =
    newCredit > 0 && limit > 0 && debt + newCredit > limit

/** Truck stock = loaded − sold − returned − damaged. */
fun remainingStock(loaded: Double, sold: Double, returned: Double, damaged: Double): Double =
    loaded - sold - returned - damaged

/** Expected cash at closing = cash sales + debt collected − expenses paid from cash. */
fun expectedCash(cashSales: Double, collected: Double, expenses: Double): Double =
    cashSales + collected - expenses

// ---- deposits -------------------------------------------------------------------------

data class DepositTotals(val total: Double, val depositAmount: Double, val cashPaid: Double, val creditAmount: Double)

fun depositTotals(total: Double, deposit: Double, cash: Double, hasSlip: Boolean): DepositTotals {
    if (!hasSlip) throw IllegalStateException("صورة إيصال الإيداع مطلوبة")
    val finite = listOf(total, deposit, cash).all { it.isFinite() }
    if (!finite || total < 0 || deposit <= 0 || cash < 0 || deposit + cash > total) {
        throw IllegalStateException("مبلغ الإيداع أو النقد غير صحيح")
    }
    return DepositTotals(total, deposit, cash, total - deposit - cash)
}

// ---- input normalisation --------------------------------------------------------------

/** Normalize both digit alphabets without silently changing negative or decimal values. */
fun normalizeDigits(value: String): String {
    val sb = StringBuilder(value.length)
    for (ch in value) {
        when (ch) {
            in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
            in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
            '٫' -> sb.append('.')
            '٬' -> {}
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

fun numericInput(value: String): String = normalizeDigits(value).filter { it in '0'..'9' || it == '.' || it == '-' }
fun normalizeUsername(value: String): String = normalizeDigits(value).trim().lowercase(Locale.ROOT)
fun validPin(value: String): Boolean = Regex("^\\d{6}$").matches(normalizeDigits(value))

/** Number("") is 0 in the web app, but every caller checks for "" first; here blank is NaN. */
fun parseNumber(value: String): Double = value.trim().toDoubleOrNull() ?: Double.NaN

// ---- dates (Asia/Aden, UTC+3, no DST) -------------------------------------------------

private val ADEN: TimeZone = TimeZone.getTimeZone("Asia/Aden")
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

fun adenDay(at: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ADEN }.format(Date(at))

fun isoNow(at: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = UTC }.format(Date(at))

private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}(?::?\\d{2})?)?$")

/** Parses the ISO timestamps produced by this app and by Postgres; null when unreadable. */
fun parseIso(value: String?): Long? {
    val m = ISO.find(value?.trim() ?: return null) ?: return null
    val g = m.groupValues
    val cal = java.util.Calendar.getInstance(UTC)
    cal.clear()
    cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
    var millis = cal.timeInMillis
    if (g[7].isNotEmpty()) millis += (g[7] + "000").substring(0, 3).toLong()
    val zone = g[8]
    if (zone.isNotEmpty() && zone != "Z") {
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        millis -= sign * (hours * 3_600_000L + minutes * 60_000L)
    }
    return millis
}

fun uniqueInvoiceNumber(truckCode: String, id: String, now: Long = System.currentTimeMillis()): String =
    "$truckCode-${adenDay(now).replace("-", "")}-${id.replace("-", "")}"

// ---- formatting -----------------------------------------------------------------------

// Same output as the web's Intl formatters for ar-YE, built by hand so every Android
// version (and the JVM tests) prints identical text.

private fun arabic(text: String): String = buildString {
    for (ch in text) append(when (ch) { in '0'..'9' -> '\u0660' + (ch - '0'); ',' -> '\u066C'; '.' -> '\u066B'; '-' -> "\u061C-"; else -> ch })
}

private fun grouped(n: Double?, fractionDigits: Int): String {
    val value = n?.takeIf { it.isFinite() } ?: 0.0
    val format = java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(Locale.US)).apply {
        maximumFractionDigits = fractionDigits
        roundingMode = java.math.RoundingMode.HALF_EVEN
    }
    val text = format.format(value)
    return arabic(if (text == "-0") "0" else text)
}

fun formatNum(n: Double?): String = grouped(n, 2)

fun formatMoney(n: Double?): String = grouped(n, 0) + " ر.ي"

private fun adenCalendar(at: Long) = java.util.Calendar.getInstance(ADEN, Locale.US).apply { timeInMillis = at }
private fun meridiem(cal: java.util.Calendar) = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "ص" else "م"
private fun hour12(cal: java.util.Calendar) = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }

/** e.g. ١٤‏/١١‏/٢٠٢٦، ٩:٠٠ ص (medium date, short time, Aden). */
fun formatDate(iso: String?): String {
    val cal = adenCalendar(parseIso(iso) ?: return iso ?: "")
    val date = "${cal.get(java.util.Calendar.DAY_OF_MONTH)}\u200F/${cal.get(java.util.Calendar.MONTH) + 1}\u200F/${cal.get(java.util.Calendar.YEAR)}"
    val time = "${hour12(cal)}:${"%02d".format(Locale.US, cal.get(java.util.Calendar.MINUTE))}"
    return arabic("$date، $time") + " " + meridiem(cal)
}

/** e.g. ٠٧:٤١:٥٩ م */
fun adenTime(iso: String?): String {
    val cal = adenCalendar(parseIso(iso) ?: return "—")
    return arabic("%02d:%02d:%02d".format(Locale.US, hour12(cal), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))) + " " + meridiem(cal)
}

// ---- phones ---------------------------------------------------------------------------

/** Yemen country prefix once; reject incomplete numbers instead of building broken links. */
fun yemenPhone(value: String?): String? {
    var n = normalizeDigits(value ?: "").filter { it.isDigit() }
    if (n.startsWith("00967")) n = n.substring(2)
    else if (n.startsWith("0")) n = "967" + n.substring(1)
    else if (Regex("^7\\d{8}$").matches(n)) n = "967$n"
    while (n.startsWith("967967")) n = n.substring(3)
    return if (Regex("^967(?:7\\d{8}|[1-6]\\d{6,7})$").matches(n)) n else null
}

// ---- location policy ------------------------------------------------------------------

const val LOCATION_GRACE_MS = 120_000L

enum class LocationDecision { READY, BLOCKED, WITHOUT_LOCATION }

fun locationDecision(permissionGranted: Boolean, hasFix: Boolean, missingSince: Long?, now: Long, allowNoFix: Boolean): LocationDecision {
    if (!permissionGranted) return LocationDecision.BLOCKED
    if (hasFix) return LocationDecision.READY
    if (allowNoFix && missingSince != null && now - missingSince >= LOCATION_GRACE_MS) return LocationDecision.WITHOUT_LOCATION
    return LocationDecision.BLOCKED
}

// ---- tracking -------------------------------------------------------------------------

const val TRACKING_INTERVAL = 60_000L
const val POOR_ACCURACY_TIMEOUT = 300_000L

/** Accurate fixes are always kept; a poor fix only after five minutes without any point. */
fun acceptPosition(accuracy: Float, last: Long, now: Long): Boolean =
    accuracy <= 100f || now - last >= POOR_ACCURACY_TIMEOUT

data class TrackPoint(val lat: Double, val lng: Double, val recordedAt: String)
data class RouteSummary(val start: String?, val end: String?, val minutes: Double, val km: Double, val count: Int, val gaps: Int, val untrackedMinutes: Double)

fun routeSummary(points: List<TrackPoint>): RouteSummary {
    val sorted = points.sortedBy { parseIso(it.recordedAt) ?: 0L }
    var km = 0.0
    var gaps = 0
    var untracked = 0.0
    fun rad(n: Double) = n * Math.PI / 180
    for (i in 1 until sorted.size) {
        val a = sorted[i - 1]
        val b = sorted[i]
        val interval = (parseIso(b.recordedAt) ?: 0L) - (parseIso(a.recordedAt) ?: 0L)
        if (interval > POOR_ACCURACY_TIMEOUT) { gaps++; untracked += interval / 60_000.0 }
        val h = sin(rad(b.lat - a.lat) / 2).pow(2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(rad(b.lng - a.lng) / 2).pow(2)
        km += 6371 * 2 * asin(sqrt(min(1.0, h)))
    }
    val start = sorted.firstOrNull()?.recordedAt
    val end = sorted.lastOrNull()?.recordedAt
    val minutes = if (start != null && end != null) max(0.0, ((parseIso(end) ?: 0L) - (parseIso(start) ?: 0L)) / 60_000.0) else 0.0
    return RouteSummary(start, end, minutes, km, sorted.size, gaps, untracked)
}

// ---- customer browsing ----------------------------------------------------------------

const val CUSTOMER_PAGE_SIZE = 50

private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

fun normalizeCustomerSearch(value: String): String =
    java.text.Normalizer.normalize(normalizeDigits(value), java.text.Normalizer.Form.NFC)
        .replace(INVISIBLE, "").replace(Regex("\\s+"), " ").trim().lowercase(Locale("ar"))

data class CustomerContact(val id: String, val name: String, val phone: String?, val regionId: String?, val truckId: String?, val locationNotes: String?)

fun <T> filterCustomers(rows: List<T>, contact: (T) -> CustomerContact, query: String, region: String = "all", assignment: String = "all"): List<T> {
    val search = normalizeCustomerSearch(query)
    val strip = Regex("[\\s()+-]")
    val phoneSearch = search.replace(strip, "")
    return rows.filter { row ->
        val c = contact(row)
        (region == "all" || c.regionId == region) &&
            (assignment != "unassigned" || c.truckId.isNullOrEmpty()) &&
            (search.isEmpty() || normalizeCustomerSearch(c.name).contains(search) ||
                (phoneSearch.isNotEmpty() && listOf(c.phone, c.locationNotes).any {
                    !it.isNullOrEmpty() && normalizeCustomerSearch(it).replace(strip, "").contains(phoneSearch)
                }))
    }
}

data class Page<T>(val page: Int, val pages: Int, val start: Int, val end: Int, val rows: List<T>)

fun <T> customerPage(rows: List<T>, requestedPage: Int): Page<T> {
    val pages = max(1, ceil(rows.size / CUSTOMER_PAGE_SIZE.toDouble()).toInt())
    val page = max(1, min(pages, requestedPage))
    val start = if (rows.isNotEmpty()) (page - 1) * CUSTOMER_PAGE_SIZE + 1 else 0
    val end = min(page * CUSTOMER_PAGE_SIZE, rows.size)
    return Page(page, pages, start, end, rows.subList(min((page - 1) * CUSTOMER_PAGE_SIZE, rows.size), end))
}

// ---- campaign opening -----------------------------------------------------------------

fun campaignConflictMessage(msg: String): String = when {
    msg.contains("finance_active_truck") -> "لدى هذه الشاحنة حملة مفتوحة (ربما من جهاز آخر). تم تحديث القائمة"
    msg.contains("finance_active_distributor") -> "هذا الموزع لديه حملة مفتوحة (ربما من جهاز آخر). تم تحديث القائمة"
    else -> msg
}
