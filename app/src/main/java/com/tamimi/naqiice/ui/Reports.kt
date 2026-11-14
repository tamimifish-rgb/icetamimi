package com.tamimi.naqiice.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.CHART_TABS
import com.tamimi.naqiice.core.ExportTable
import com.tamimi.naqiice.core.NamedValue
import com.tamimi.naqiice.core.REPORT_TABS
import com.tamimi.naqiice.core.ReportData
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.Xlsx
import com.tamimi.naqiice.core.adenBounds
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.adenTime
import com.tamimi.naqiice.core.cellText
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.reportRows
import com.tamimi.naqiice.core.reportTable
import com.tamimi.naqiice.core.routeSummary
import com.tamimi.naqiice.core.shiftDay
import com.tamimi.naqiice.core.stockLeft
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.Api
import com.tamimi.naqiice.data.enc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

// ---- online reads ---------------------------------------------------------------------

private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

private class Query<T>(val result: Result<T>?, val fetching: Boolean, val refetch: () -> Unit)

/**
 * An online-only read keyed like the web app's queries: a result is shown only for the
 * key that produced it, so changed filters fall back to "loading" while a refresh of the
 * same key keeps the current rows on screen.
 */
@Composable
private fun <T> rememberQuery(key: Any, enabled: Boolean, load: suspend () -> T): Query<T> {
    var loaded by remember { mutableStateOf<Pair<Any, Result<T>>?>(null) }
    var active by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(load)
    LaunchedEffect(key, enabled, tick) {
        if (!enabled) return@LaunchedEffect
        active++
        try { loaded = key to attempt { current() } } finally { active-- }
    }
    return Query(loaded?.takeIf { it.first == key }?.second, active > 0) { tick++ }
}

private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private fun dayInput(value: String) = value.filter { it.isDigit() || it == '-' }.take(10)

@Composable
private fun DateRange(from: String, to: String, onFrom: (String) -> Unit, onTo: (String) -> Unit, fromLabel: String, toLabel: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(from, { onFrom(dayInput(it)) }, fromLabel, Modifier.weight(1f), ltr = true)
        Field(to, { onTo(dayInput(it)) }, toLabel, Modifier.weight(1f), ltr = true)
    }
}

// ---- reports --------------------------------------------------------------------------

private suspend fun loadReports(api: Api, from: String, to: String, today: String): ReportData = coroutineScope {
    val bearer = api.token()
    fun all(table: String, query: String): Deferred<List<DataRow>> = async { api.selectAll(table, query, bearer) }
    val (a, b) = adenBounds(from, to).let { enc(it.first) to enc(it.second) }
    val (dayStart, dayEnd) = adenBounds(today, today).let { enc(it.first) to enc(it.second) }
    val sales = all("sales", "select=id,total,cash_paid,credit_amount,created_at,truck_id,customer_id&created_at=gte.$a&created_at=lt.$b&order=id")
    val items = all("sale_items", "select=id,variant_id,qty,line_total,sales!inner(created_at)&sales.created_at=gte.$a&sales.created_at=lt.$b&order=id")
    val customers = all("customers", "select=id,name,region_id,truck_id,phone,active&order=id")
    val regions = all("regions", "select=id,name&order=id")
    val trucks = all("trucks", "select=id,name&order=id")
    val variants = all("product_variants", "select=id,name&order=id")
    val bal = all("customer_balances", "select=*&order=customer_id")
    val freezers = all("freezers", "select=*,freezer_models(name)&order=id")
    val trips = all("trips", "select=id,truck_id,trip_date,status,trucks(name)&trip_date=gte.$from&trip_date=lte.$to&order=id").await()
    val first = listOf(sales, items, customers, regions, trucks, variants, bal, freezers).map { it.await() }
    val second = listOf(
        all("expenses", "select=*&created_at=gte.$a&created_at=lt.$b&order=id"),
        all("maintenance_jobs", "select=*&job_date=gte.$from&job_date=lte.$to&order=id"),
        // Debt ageing and last-purchase dates need history from before the selected period.
        all("sales", "select=id,customer_id,credit_amount,created_at&created_at=lt.$b&credit_amount=gt.0&order=id"),
        all("payments", "select=customer_id,amount,created_at&created_at=lt.$b&order=id"),
        all("sales", "select=id,customer_id,created_at&order=id"),
        all("sales", "select=id,customer_id,truck_id,created_at&created_at=gte.$dayStart&created_at=lt.$dayEnd&order=id"),
    ).map { it.await() }
    val support = attempt { api.rpc("pureice_deposit_support", JsonObject(emptyMap()), bearer) }.getOrNull()
    val deposits = if ((support as? JsonPrimitive)?.contentOrNull == "1")
        api.selectAll("pureice_deposits", "select=*,trips(truck_id,trucks(name))&created_at=gte.$a&created_at=lt.$b&order=id", bearer) else emptyList()
    // Stock is read per campaign id; chunks keep the URL bounded for long periods.
    val stock = trips.mapNotNull { it.str("id") }.chunked(100).flatMap {
        api.selectAll("trip_stock", "select=*&trip_id=in.(${it.joinToString(",")})&order=trip_id,variant_id", bearer)
    }
    ReportData(first[0], first[1], first[2], first[3], first[4], first[5], first[6], first[7], trips, stock, second[0], second[1], second[2], second[3], second[4], second[5], deposits)
}

/** Lines shown under the freezer and stock tables: text plus an optional green suffix. */
private fun reportNotes(d: ReportData, tab: String): List<Pair<String, String?>> = when (tab) {
    "freezers" -> d.freezers.map { f ->
        val b = d.bal.firstOrNull { it.str("customer_id") == f.str("customer_id") }
        "${f.str("serial") ?: ""} · الشهر ${formatMoney(b?.numOrNull("month_sales"))} · الإجمالي ${formatMoney(b?.numOrNull("total_sales"))}" to null
    }
    "stock" -> d.trips.map { t ->
        val clean = d.stock.none { it.str("trip_id") == t.str("id") && stockLeft(it) != 0.0 }
        "${t.obj("trucks")?.str("name") ?: ""} — ${t.str("trip_date") ?: ""} · ${if (t.str("status") == "closed") "مغلقة — فروقات تاريخية محفوظة" else "مفتوحة"}" to if (clean) " · لا فروقات" else null
    }
    else -> emptyList()
}

private fun printable(table: ExportTable, from: String, to: String, notes: List<Pair<String, String?>>): String = buildString {
    appendLine(table.title)
    appendLine("الفترة: $from — $to")
    appendLine()
    appendLine(table.headers.joinToString(" | "))
    table.rows.forEach { row -> appendLine(row.joinToString(" | ") { cellText(it) }) }
    if (table.rows.isEmpty()) appendLine("لا توجد بيانات")
    if (notes.isNotEmpty()) appendLine()
    notes.forEach { appendLine(it.first + (it.second ?: "")) }
}

private suspend fun shareExcel(context: Context, table: ExportTable) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, table.title.replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".xlsx").also { it.outputStream().use { out -> Xlsx.write(table, out) } }
    }
    val uri = Photos.uriFor(context, file)
    val send = Intent(Intent.ACTION_SEND).setType(Xlsx.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser only passes the read grant on when the file is also the clip.
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, "تصدير Excel").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ReportsScreen() {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val me by repo.me.collectAsState()
    val today = remember { adenDay() }
    var inactiveDays by remember { mutableIntStateOf(14) }
    var visitTruck by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(today.take(8) + "01") }
    var to by remember { mutableStateOf(today) }
    var tab by remember { mutableStateOf("day") }
    val userId = me?.id
    val query = rememberQuery(listOf(userId, from, to), userId != null && DAY.matches(from) && DAY.matches(to) && from <= to) { loadReports(repo.api, from, to, today) }
    val data = query.result?.getOrNull()
    val error = query.result?.exceptionOrNull()
    val table = remember(data, tab, from, to, inactiveDays, visitTruck) { data?.let { reportTable(it, tab, from, to, today, inactiveDays, visitTruck) } }
    val notes = remember(data, tab) { data?.let { reportNotes(it, tab) } ?: emptyList() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("التقارير")
        DateRange(from, to, { from = it }, { to = it }, "من تاريخ", "إلى تاريخ")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REPORT_TABS.forEach { (value, label) -> MidButton(label, outlined = tab != value) { tab = value } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MidButton("تصدير Excel", Modifier.weight(1f), enabled = table != null, outlined = true) {
                scope.launch { attempt { shareExcel(context, table!!) }.onFailure { toast("تعذّر تصدير الملف") } }
            }
            MidButton("طباعة / PDF", Modifier.weight(1f), enabled = table != null, outlined = true) { printText(context, table!!.title, printable(table, from, to, notes)) }
        }
        if (tab == "inactive") {
            Text("مدة عدم الشراء", fontSize = 14.sp)
            Choice(listOf(7, 14, 30).map { it to "$it يوم" }, inactiveDays) { inactiveDays = it }
        }
        if (tab == "visits") Picker("شاحنة الزيارات", listOf("" to "اختر الشاحنة") + (data?.trucks?.map { (it.str("id") ?: "") to (it.str("name") ?: "") } ?: emptyList()), visitTruck) { visitTruck = it }
        Text("الفترة: $from — $to", fontSize = 14.sp, color = Ice.mutedText)
        if (from > to) Text("تاريخ البداية يجب أن يسبق النهاية", fontSize = 14.sp, color = Ice.destructive)
        when {
            error != null -> {
                Empty("تعذّر تحميل التقارير: ${error.message ?: ""}")
                MidButton("إعادة المحاولة", outlined = true, enabled = !query.fetching) { query.refetch() }
            }
            data == null || table == null -> Empty("جارٍ التحميل…")
            else -> {
                if (tab in CHART_TABS) BarChart(remember(data, tab) { reportRows(data, tab).take(15) })
                ReportTable(table)
                notes.forEach { (text, good) ->
                    Row { Text(text, fontSize = 14.sp); if (good != null) Text(good, fontSize = 14.sp, color = Ice.success) }
                }
            }
        }
    }
}

/** The web page draws the first fifteen rows as a bar chart; here as horizontal bars. */
@Composable
private fun BarChart(rows: List<NamedValue>) {
    val max = rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.name, Modifier.width(96.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth((r.value / max).toFloat().coerceIn(0f, 1f)).height(14.dp).background(Color(0xFF1B7BB5), RoundedCornerShape(4.dp)))
                }
                Text(formatNum(r.value), fontSize = 11.sp, color = Ice.mutedText)
            }
        }
    }
}

@Composable
private fun ReportTable(table: ExportTable) {
    @Composable
    fun cells(values: List<String>, header: Boolean) = Row(Modifier.height(IntrinsicSize.Min).background(if (header) Ice.muted else Color.Transparent)) {
        values.forEach {
            Box(Modifier.width(132.dp).fillMaxHeight().border(0.5.dp, Ice.border).padding(10.dp)) {
                Text(it, fontSize = 14.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(table.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            cells(table.headers, true)
            table.rows.forEach { row -> cells(row.map { cellText(it) }, false) }
        }
        if (table.rows.isEmpty()) Text("لا توجد بيانات", fontSize = 14.sp, color = Ice.mutedText)
    }
}

// ---- route history --------------------------------------------------------------------

private class RouteData(val trucks: List<DataRow>, val profiles: List<DataRow>, val trips: List<DataRow>, val gps: List<DataRow>, val sales: List<DataRow>, val selectedTrip: DataRow?) {
    // Everything is keyed by campaign id: a truck may run several campaigns in one day.
    val gpsByTrip = gps.groupBy { it.str("trip_id") }.mapValues { (_, rows) -> rows.map { TrackPoint(it.num("lat"), it.num("lng"), it.str("recorded_at") ?: "") } }
    val salesByTrip = sales.groupBy { it.str("trip_id") }
    fun truckName(id: String?) = trucks.firstOrNull { it.str("id") == id }?.str("name") ?: ""
}

private suspend fun loadRoutes(api: Api, from: String, to: String, truck: String, selected: String?): RouteData = coroutineScope {
    val bearer = api.token()
    val trucks = async { api.selectAll("trucks", "select=id,name&order=id", bearer) }
    val profiles = async { api.selectAll("profiles", "select=id,full_name&order=id", bearer) }
    val trips = api.selectAll(
        "trips", "select=*&trip_date=gte.$from&trip_date=lte.$to&order=trip_date.desc,created_at.desc,id" + if (truck.isNotEmpty()) "&truck_id=eq.$truck" else "", bearer,
    )
    val ids = (trips.mapNotNull { it.str("id") } + listOfNotNull(selected)).distinct()
    val gps = ArrayList<DataRow>()
    val sales = ArrayList<DataRow>()
    // Keep URL size bounded even for long history ranges.
    for (chunk in ids.chunked(100)) {
        val list = chunk.joinToString(",")
        val g = async { api.selectAll("gps_points", "select=id,trip_id,lat,lng,recorded_at&trip_id=in.($list)&order=recorded_at,id", bearer) }
        val s = async { api.selectAll("sales", "select=id,trip_id,lat,lng,total,invoice_no,created_at,customers(name)&trip_id=in.($list)&order=created_at,id", bearer) }
        gps.addAll(g.await()); sales.addAll(s.await())
    }
    val selectedTrip = selected?.let { id -> trips.firstOrNull { it.str("id") == id } ?: api.maybeSingle("trips", "select=*&id=eq.$id", bearer) }
    RouteData(trucks.await(), profiles.await(), trips, gps, sales, selectedTrip)
}

private fun status(trip: DataRow) = if (trip.str("status") == "closed") "مغلقة" else "مفتوحة"

@Composable
fun RouteMapScreen() {
    val repo = LocalRepo.current
    val me by repo.me.collectAsState()
    val today = remember { adenDay() }
    var from by remember { mutableStateOf(shiftDay(today, -6)) }
    var to by remember { mutableStateOf(today) }
    var truck by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val userId = me?.id
    val query = rememberQuery(listOf(userId, from, to, truck, selected), userId != null && DAY.matches(from) && DAY.matches(to) && from <= to) { loadRoutes(repo.api, from, to, truck, selected) }
    val data = query.result?.getOrNull()
    val error = query.result?.exceptionOrNull()
    BackHandler(selected != null) { selected = null }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("سجل خط السير")
        DateRange(from, to, { from = it }, { to = it }, "من", "إلى")
        Picker("الشاحنة", listOf("" to "جميع الشاحنات") + (data?.trucks?.map { (it.str("id") ?: "") to (it.str("name") ?: "") } ?: emptyList()), truck) { truck = it }
        if (from > to) Text("تاريخ البداية يجب أن يسبق النهاية", fontSize = 14.sp, color = Ice.destructive)
        if (error != null) {
            Empty("تعذّر تحميل المسارات: ${error.message ?: ""}")
            MidButton("إعادة المحاولة", outlined = true, enabled = !query.fetching) { query.refetch() }
        }
        val trip = data?.selectedTrip
        if (selected != null && data != null && trip != null) {
            val points = data.gpsByTrip[selected] ?: emptyList()
            val invoices = data.salesByTrip[selected] ?: emptyList()
            val summary = remember(points) { routeSummary(points) }
            val stops = remember(invoices) {
                invoices.mapNotNull { s ->
                    val lat = s.numOrNull("lat") ?: return@mapNotNull null
                    val lng = s.numOrNull("lng") ?: return@mapNotNull null
                    MapStop(lat, lng, "${s.obj("customers")?.str("name") ?: "عميل"} · ${s.str("invoice_no") ?: ""} · ${formatMoney(s.num("total"))} · ${adenTime(s.str("created_at"))}")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${data.truckName(trip.str("truck_id"))} · ${trip.str("trip_date") ?: ""} · ${status(trip)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                OutlinedIconButton({ query.refetch() }, enabled = !query.fetching) { Icon(Icons.Filled.Refresh, "تحديث الموقع والمسار") }
            }
            TripMap(points, stops, trip.str("status") == "open")
            if (points.isEmpty()) Text("لا توجد نقاط تتبع محفوظة لهذه الحملة", fontSize = 14.sp, color = Ice.mutedText)
            Text("فجوات التتبع: ${summary.gaps} · الوقت غير المتتبع: ${summary.untrackedMinutes.roundToLong()} دقيقة", fontSize = 14.sp)
            StatGrid(
                listOf(
                    "بداية التتبع" to adenTime(summary.start), "نهاية التتبع" to adenTime(summary.end),
                    "المدة" to "${summary.minutes.roundToLong()} دقيقة", "المسافة التقريبية" to "${String.format(Locale.US, "%.2f", summary.km)} كم",
                    "نقاط التتبع" to "${summary.count}", "التوقفات / الفواتير" to "${invoices.size}",
                    "آخر نقطة مسجلة" to adenTime(summary.end), "المبيعات" to formatMoney(invoices.sumOf { it.num("total") }),
                ).map { Triple(it.first, it.second, Ice.foreground) },
            )
        }
        if (selected != null && data != null && trip == null) Empty("الحملة غير متاحة لهذا الحساب")
        SectionTitle("الحملات (${data?.trips?.size ?: 0})")
        if (data == null && error == null) Empty("جارٍ التحميل…")
        data?.trips?.forEach { t ->
            val id = t.str("id")
            val s = remember(data, id) { routeSummary(data.gpsByTrip[id] ?: emptyList()) }
            val inv = data.salesByTrip[id] ?: emptyList()
            IceCard(Modifier.clickable { selected = id }, borderColor = if (id == selected) Ice.primary else Ice.border) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${data.truckName(t.str("truck_id"))} · ${t.str("trip_date") ?: ""}", fontWeight = FontWeight.Bold)
                        Text(status(t), fontWeight = FontWeight.Bold)
                    }
                    Text("الموزع: ${data.profiles.firstOrNull { it.str("id") == t.str("distributor_id") }?.str("full_name") ?: "—"}", fontSize = 14.sp)
                    Text(
                        listOf("بداية: ${adenTime(s.start)}", "نهاية: ${adenTime(s.end)}", "${s.count} نقطة", "${inv.size} فاتورة", formatMoney(inv.sumOf { it.num("total") })).joinToString("   "),
                        fontSize = 12.sp, color = Ice.mutedText,
                    )
                }
            }
        }
        if (data != null && data.trips.isEmpty()) Empty("لا توجد حملات في الفترة المختارة")
    }
}
