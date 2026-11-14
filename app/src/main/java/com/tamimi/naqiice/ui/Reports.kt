package com.tamimi.naqiice.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import com.tamimi.naqiice.core.niceTicks
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.reportRows
import com.tamimi.naqiice.core.reportTable
import com.tamimi.naqiice.core.routeSummary
import com.tamimi.naqiice.core.shiftDay
import com.tamimi.naqiice.core.stockLeft
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.tableColumnWidths
import com.tamimi.naqiice.core.tickLabel
import com.tamimi.naqiice.core.tickPlaces
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
        try {
            val result = attempt { current() }
            onMain { loaded = key to result }
        } finally { active-- }
    }
    return Query(loaded?.takeIf { it.first == key }?.second, active > 0) { tick++ }
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

// ---- shared pieces --------------------------------------------------------------------

private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** Data the web sets in its right-to-left paragraph whatever its first letter is (serials, plates, dates). */
private val TextStyle.rtl get() = copy(textDirection = TextDirection.Rtl)

/**
 * The web `<input type="date">` at any `h-*` and without a latest day (Field.kt's `DateField`
 * is fixed at h-12 and stops at today). A browser lays the control out left-to-right from
 * its left edge whatever its width: the date, then the calendar icon 139px in.
 */
@Composable
private fun DateInput(value: String, label: String, modifier: Modifier = Modifier, height: Dp = 36.dp, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val parts = value.split('-').mapNotNull { it.toIntOrNull() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier.height(height).smallShadow(Radius.md).box(Radius.md, null, Ice.input).clip(RoundedCornerShape(Radius.md - 1.dp)).tap {
                val (y, m, d) = if (parts.size == 3) parts else adenDay().split('-').map { it.toInt() }
                android.app.DatePickerDialog(context, { _, year, month, day -> onChange("%04d-%02d-%02d".format(Locale.US, year, month + 1, day)) }, y, m - 1, d).show()
            }.semantics { contentDescription = label },
        ) {
            Text(
                if (parts.size == 3) "%02d/%02d/%04d".format(Locale.US, parts[2], parts[1], parts[0]) else "",
                Modifier.align(Alignment.CenterStart).padding(start = 13.dp), style = T.base.ltr, maxLines = 1,
            )
            Icon(Lucide.Calendar, null, Modifier.align(Alignment.CenterStart).padding(start = 139.dp).size(15.dp))
        }
    }
}

/**
 * The web's `<select class="h-11 rounded-md border border-input bg-background px-3">` as a
 * browser draws it: 16px text 17px in from the edge, the arrow against the other edge, and —
 * unless it is to [fill] the row — just wide enough for its longest option.
 */
@Composable
private fun <V> WebSelect(options: List<Pair<V, String>>, selected: V, label: String, fill: Boolean = false, onSelect: (V) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(options, density) {
        with(density) { (options.maxOfOrNull { measurer.measure(it.second, T.base, softWrap = false, maxLines = 1, density = density).size.width } ?: 0).toDp() }
    }
    Box(if (fill) Modifier.fillMaxWidth() else Modifier) {
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier).height(44.dp).box(Radius.md, Ice.background, Ice.input).clip(RoundedCornerShape(Radius.md - 1.dp))
                .clickable { open = true }.semantics { contentDescription = label }.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "", if (fill) Modifier.weight(1f) else Modifier.width(widest + 12.dp), style = T.base, maxLines = 1)
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp), tint = Ice.foreground)
        }
        DropdownMenu(open, { open = false }, containerColor = Ice.card) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text, style = T.base) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

/**
 * An outline `Button` as wide as its label (`h-9 px-4`, or `size="sm"`: `h-8 px-3 text-xs`).
 * The side padding counts the 1px border in, as CSS does, and there is none above and below:
 * Common.kt's `Btn` leaves the label of a 36px button too little room and it drops 3px.
 */
@Composable
internal fun OutlineBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) = Btn(
    onClick, modifier, Variant.Outline, enabled, if (small) 32.dp else 36.dp,
    style = if (small) T.xs.medium else T.sm.medium, padding = PaddingValues(horizontal = if (small) 13.dp else 17.dp),
) { Text(text, maxLines = 1) }

/** The web `Empty` with a retry button set after its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyRetry(text: String, enabled: Boolean, retry: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().dashedBorder(Radius.xl2).padding(24.dp), horizontalArrangement = Arrangement.Center) {
        Text(text, Modifier.align(Alignment.CenterVertically), style = T.sm.muted, textAlign = TextAlign.Center)
        // An outline button takes the text colour of the box it sits in.
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ice.mutedText) {
            OutlineBtn("إعادة المحاولة", Modifier.align(Alignment.CenterVertically), enabled, onClick = retry)
        }
    }
}

/**
 * A line with differently styled parts (Common.kt's `Text` takes plain strings only); it
 * gives up Cairo's extra line height in the same way, so its lines are the web's.
 */
@Composable
private fun RichText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(0)
    val extra = remember(style, density) {
        (measurer.measure("ا", style, density = density).size.height - with(density) { style.lineHeight.roundToPx() }).coerceAtLeast(0)
    }
    androidx.compose.material3.Text(text, modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -(extra / 2)) }
    }, style = style)
}

// ---- reports page ---------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
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

    // space-y-4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("التقارير")
        // flex gap-2 of two h-11 date inputs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateInput(from, "من تاريخ", Modifier.weight(1f), 44.dp) { from = it }
            DateInput(to, "إلى تاريخ", Modifier.weight(1f), 44.dp) { to = it }
        }
        // flex gap-2 overflow-x-auto pb-1 of rounded-full h-9 px-3 chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REPORT_TABS.forEach { (value, label) ->
                val on = tab == value
                Box(
                    Modifier.height(36.dp).smallShadow(18.dp).box(18.dp, if (on) Ice.primary else Ice.card, if (on) Ice.primary else Ice.border)
                        .clip(RoundedCornerShape(17.dp)).clickable { tab = value }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = T.sm.medium.color(if (on) Ice.onPrimary else Ice.foreground), maxLines = 1) }
            }
        }
        // flex flex-wrap gap-2
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineBtn("تصدير Excel", enabled = table != null) { scope.launch { attempt { shareExcel(context, table!!) }.onFailure { toast("تعذّر تصدير الملف") } } }
            OutlineBtn("طباعة / PDF", enabled = table != null) { printText(context, table!!.title, printable(table, from, to, notes)) }
            if (tab == "inactive") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مدة عدم الشراء", style = T.sm)
                WebSelect(listOf(7, 14, 30).map { it to "$it يوم" }, inactiveDays, "مدة عدم الشراء") { inactiveDays = it }
            }
            if (tab == "visits") WebSelect(
                listOf("" to "اختر الشاحنة") + (data?.trucks?.map { (it.str("id") ?: "") to (it.str("name") ?: "") } ?: emptyList()), visitTruck, "شاحنة الزيارات",
            ) { visitTruck = it }
        }
        Text("الفترة: $from — $to", style = T.sm.muted)
        if (from > to) Text("تاريخ البداية يجب أن يسبق النهاية", style = T.sm.color(Ice.destructive))
        when {
            error != null -> EmptyRetry("تعذّر تحميل التقارير: ${error.message ?: ""}", !query.fetching) { query.refetch() }
            data == null || table == null -> Empty("جارٍ التحميل…")
            else -> {
                val rows = remember(data, tab) { if (tab in CHART_TABS) reportRows(data, tab) else emptyList() }
                if (rows.isNotEmpty()) BarChart(rows.take(15))
                ReportTable(table)
                // one text-sm line per freezer / campaign
                notes.forEach { (text, good) ->
                    RichText(buildAnnotatedString { append(collapsed(text)); if (good != null) withStyle(SpanStyle(color = Ice.success)) { append(good) } }, T.sm.rtl)
                }
            }
        }
    }
}

// The web chart's own colours: `fill="var(--chart-1)"` bars on recharts' grey axes.
private val CHART_BAR = Color(0xFF007BB2)
private val CHART_AXIS = Color(0xFF666666)
private val CHART_TEXT = TextStyle(fontFamily = Cairo, fontSize = 10.sp, color = CHART_AXIS, platformStyle = PlatformTextStyle(includeFontPadding = false))

/**
 * The web page's recharts `BarChart` (`h-56`, left-to-right): 5px margins, a 60px value axis
 * with five round ticks, a 30px category axis, bars 80% of their band with 4px corners, 10px
 * axis text. A tap on a bar's band shows its value the way the web tooltip does.
 */
@Composable
private fun BarChart(rows: List<NamedValue>) {
    val measurer = rememberTextMeasurer()
    val ticks = remember(rows) { niceTicks(0.0, rows.maxOf { it.value }.coerceAtLeast(0.0)) }
    var active by remember(rows) { mutableStateOf<Pair<Int, Offset>?>(null) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier.fillMaxWidth().height(224.dp).semantics { contentDescription = "الرسم البياني" }.pointerInput(rows) {
                detectTapGestures { at ->
                    val left = 65.dp.toPx()
                    val band = (size.width - 70.dp.toPx()) / rows.size
                    val index = floor((at.x - left) / band).toInt()
                    val inside = at.x >= left && index in rows.indices && at.y >= 5.dp.toPx() && at.y <= size.height - 35.dp.toPx()
                    active = if (inside && active?.first != index) index to at else null
                }
            },
        ) {
            val unit = 1.dp.toPx()
            val left = 65 * unit
            val right = size.width - 5 * unit
            val top = 5 * unit
            val bottom = size.height - 35 * unit
            val low = ticks.first()
            val high = ticks.last()
            fun y(value: Double) = if (high == low) bottom else (bottom - (value.coerceIn(low, high) - low) / (high - low) * (bottom - top)).toFloat()
            val band = (right - left) / rows.size
            // The band under the pointer, as recharts marks it.
            active?.let { (index, _) -> drawRect(Color(0xFFCCCCCC), Offset(left + index * band, top), Size(band, bottom - top)) }
            // value axis: line, 6px tick marks, labels ending 8px left of the line
            drawLine(CHART_AXIS, Offset(left, top), Offset(left, bottom), unit)
            // recharts takes a label for 1.5 × its font size tall when it decides which ones fit.
            val heights = tickPlaces(ticks.map { y(it) }, ticks.map { 15 * unit }, size.height, 0f, 5 * unit)
            ticks.forEachIndexed { i, value ->
                val at = y(value)
                val middle = heights[i] ?: return@forEachIndexed
                drawLine(CHART_AXIS, Offset(left - 6 * unit, at), Offset(left, at), unit)
                val label = measurer.measure(tickLabel(value), CHART_TEXT, softWrap = false, maxLines = 1)
                drawText(label, topLeft = Offset(left - 8 * unit - label.size.width, middle + 3.55f * unit - label.firstBaseline))
            }
            // category axis: line, tick marks, centred labels (those that fit, last one first)
            drawLine(CHART_AXIS, Offset(left, bottom), Offset(right, bottom), unit)
            val labels = rows.map { measurer.measure(it.name, CHART_TEXT, softWrap = false, maxLines = 1) }
            val centres = rows.indices.map { left + (it + 0.5f) * band }
            val places = tickPlaces(centres, labels.map { it.size.width.toFloat() }, 0f, size.width, 5 * unit)
            rows.indices.forEach { i ->
                val centre = places[i] ?: return@forEach
                drawLine(CHART_AXIS, Offset(centres[i], bottom), Offset(centres[i], bottom + 6 * unit), unit)
                drawText(labels[i], topLeft = Offset(centre - labels[i].size.width / 2f, bottom + 15.1f * unit - labels[i].firstBaseline))
            }
            // bars: 10% of the band free on each side, whole pixels wide
            val gap = band / unit * 0.1f
            val width = (band / unit - 2 * gap).let { if (it > 1) floor(it) else it } * unit
            val zero = y(0.0)
            rows.forEachIndexed { i, row ->
                val barTop = y(row.value)
                val barHeight = zero - barTop
                if (barHeight <= 0f) return@forEachIndexed
                val radius = min(4 * unit, min(width, barHeight) / 2)
                drawRoundRect(CHART_BAR, Offset(left + i * band + gap * unit, barTop), Size(width, barHeight), CornerRadius(radius))
            }
            // tooltip: white box, #ccc border, 10px padding; the name, then "value : n" in the bar colour
            active?.let { (index, at) ->
                val name = measurer.measure(rows[index].name, T.base.copy(color = Ice.foreground), softWrap = false, maxLines = 1)
                val value = measurer.measure("value : ${tickLabel(rows[index].value)}", T.base.copy(color = CHART_BAR), softWrap = false, maxLines = 1)
                val boxWidth = max(name.size.width, value.size.width) + 22 * unit
                val boxHeight = 24 * unit + 32 * unit + 22 * unit
                // 10px right of the bar's centre and below the pointer; on the other side where
                // it would leave the plot area
                fun place(from: Float, extent: Float, start: Float, end: Float) =
                    max(if (from + 10 * unit + extent > end) from - extent - 10 * unit else from + 10 * unit, start)
                val x = place(centres[index], boxWidth, left, right)
                val boxTop = place(at.y, boxHeight, top, bottom)
                drawRect(Color(0xFFCCCCCC), Offset(x, boxTop), Size(boxWidth, boxHeight))
                drawRect(Color.White, Offset(x + unit, boxTop + unit), Size(boxWidth - 2 * unit, boxHeight - 2 * unit))
                drawText(name, topLeft = Offset(x + 11 * unit, boxTop + 11 * unit + (24 * unit - name.size.height) / 2))
                drawText(value, topLeft = Offset(x + 11 * unit, boxTop + 39 * unit + (24 * unit - value.size.height) / 2))
            }
        }
    }
}

/** Where a browser may break a cell's text: at spaces and after hyphens. */
private fun pieces(text: String): List<String> = text.split(' ').flatMap { it.split(Regex("(?<=-)")) }.filter { it.isNotEmpty() }

/** Text as HTML shows it: runs of white space are one space and none at either end. */
private fun collapsed(text: String): String = text.trim().replace(Regex("\\s+"), " ")

/**
 * `ReportTable`: an `h2.text-lg.font-bold`, then a `w-full border-collapse text-sm` table in
 * an `overflow-x-auto` box — `p-3` cells with a bottom border, nowrap headers on `bg-muted`.
 * The columns are sized the way a browser sizes an automatic table.
 */
@Composable
private fun ReportTable(table: ExportTable) {
    val header = T.sm.bold.rtl
    val body = T.sm.rtl
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(table.title, style = T.lg.bold.rtl)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val available = constraints.maxWidth
            val cells = remember(table) { table.rows.map { row -> List(table.headers.size) { collapsed(cellText(row.getOrNull(it))) } } }
            // Column edges in whole pixels, from the widest and the narrowest content of each column.
            val widths = remember(table, available, density) {
                fun width(text: String, style: TextStyle) = if (text.isEmpty()) 0f else measurer.measure(text, style, softWrap = false, maxLines = 1, density = density).size.width.toFloat()
                val padding = with(density) { 24.dp.toPx() }
                val heads = table.headers.map { width(it, header) }
                val widest = heads.mapIndexed { c, head -> max(head, cells.maxOfOrNull { width(it[c], body) } ?: 0f) + padding }
                val narrowest = heads.mapIndexed { c, head -> max(head, cells.maxOfOrNull { row -> pieces(row[c]).maxOfOrNull { width(it, body) } ?: 0f } ?: 0f) + padding }
                var edge = 0f
                tableColumnWidths(narrowest, widest, available.toFloat()).map { w -> val start = edge.roundToInt(); edge += w; edge.roundToInt() - start }
            }
            fun dp(px: Int) = with(density) { px.toDp() }
            @Composable
            fun line() = Box(Modifier.width(dp(widths.sum())).height(1.dp).background(Ice.border))
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(Ice.muted)) {
                    table.headers.forEachIndexed { c, text -> Box(Modifier.width(dp(widths[c])).padding(12.dp)) { Text(text, style = header, softWrap = false, maxLines = 1) } }
                }
                line()
                cells.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A zero-width space after a hyphen lets a line end there, as in a browser.
                        row.forEachIndexed { c, text -> Box(Modifier.width(dp(widths[c])).padding(12.dp)) { Text(text.replace(Regex("-(?=\\S)"), "-​"), style = body) } }
                    }
                    line()
                }
            }
        }
        if (table.rows.isEmpty()) Text("لا توجد بيانات", style = T.sm.muted)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteMapScreen(initialTrip: String? = null) {
    val repo = LocalRepo.current
    val me by repo.me.collectAsState()
    val today = remember { adenDay() }
    var from by remember { mutableStateOf(shiftDay(today, -6)) }
    var to by remember { mutableStateOf(today) }
    var truck by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialTrip) }
    val userId = me?.id
    val query = rememberQuery(listOf(userId, from, to, truck, selected), userId != null && DAY.matches(from) && DAY.matches(to) && from <= to) { loadRoutes(repo.api, from, to, truck, selected) }
    val data = query.result?.getOrNull()
    val error = query.result?.exceptionOrNull()
    BackHandler(selected != null) { selected = null }

    // space-y-4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("سجل خط السير")
        // grid grid-cols-2 gap-2 of text-sm labels over their controls
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text("من", style = T.sm); DateInput(from, "من", Modifier.fillMaxWidth()) { from = it } }
                Column(Modifier.weight(1f)) { Text("إلى", style = T.sm); DateInput(to, "إلى", Modifier.fillMaxWidth()) { to = it } }
            }
            Column {
                Text("الشاحنة", style = T.sm)
                WebSelect(listOf("" to "جميع الشاحنات") + (data?.trucks?.map { (it.str("id") ?: "") to (it.str("name") ?: "") } ?: emptyList()), truck, "الشاحنة", fill = true) { truck = it }
            }
        }
        if (from > to) Text("تاريخ البداية يجب أن يسبق النهاية", style = T.sm.color(Ice.destructive))
        if (error != null) EmptyRetry("تعذّر تحميل المسارات", !query.fetching) { query.refetch() }
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
            // section.space-y-3.border-y.border-border.py-4
            Column(
                Modifier.fillMaxWidth().drawBehind {
                    drawRect(Ice.border, Offset.Zero, Size(size.width, 1.dp.toPx()))
                    drawRect(Ice.border, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                }.padding(vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${data.truckName(trip.str("truck_id"))} · ${trip.str("trip_date") ?: ""} · ${status(trip)}", Modifier.weight(1f), style = T.base.bold.rtl)
                    Btn({ query.refetch() }, Modifier.size(36.dp), Variant.Outline, enabled = !query.fetching, padding = PaddingValues(0.dp)) {
                        Icon(Lucide.RefreshCw, "تحديث الموقع والمسار", Modifier.size(16.dp))
                    }
                }
                TripMap(points, stops, trip.str("status") == "open")
                if (points.isEmpty()) Text("لا توجد نقاط تتبع محفوظة لهذه الحملة", style = T.sm.muted)
                RichText(buildAnnotatedString {
                    append("فجوات التتبع: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${summary.gaps}") }
                    append(" · الوقت غير المتتبع: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${summary.untrackedMinutes.roundToLong()} دقيقة") }
                }, T.sm)
                // dl.grid.grid-cols-2.gap-3.text-sm of a muted term over its bold value
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        "بداية التتبع" to adenTime(summary.start), "نهاية التتبع" to adenTime(summary.end),
                        "المدة" to "${summary.minutes.roundToLong()} دقيقة", "المسافة التقريبية" to "${String.format(Locale.US, "%.2f", summary.km)} كم",
                        "نقاط التتبع" to "${summary.count}", "التوقفات / الفواتير" to "${invoices.size}",
                        "آخر نقطة مسجلة" to adenTime(summary.end), "المبيعات" to formatMoney(invoices.sumOf { it.num("total") }),
                    ).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (term, value) ->
                                Column(Modifier.weight(1f)) { Text(term, style = T.sm.muted); Text(value, style = T.sm.bold) }
                            }
                        }
                    }
                }
            }
        }
        if (selected != null && data != null && trip == null) Empty("الحملة غير متاحة لهذا الحساب")
        Text("الحملات (${data?.trips?.size ?: 0})", style = T.base.bold)
        if (data == null && error == null) Empty("جارٍ التحميل…")
        data?.trips?.forEach { t ->
            val id = t.str("id")
            val s = remember(data, id) { routeSummary(data.gpsByTrip[id] ?: emptyList()) }
            val inv = data.salesByTrip[id] ?: emptyList()
            // a.block.space-y-2.rounded-lg.border.border-border.bg-card.p-3
            Column(Modifier.fillMaxWidth().box(Radius.lg).tap { selected = id }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${data.truckName(t.str("truck_id"))} · ${t.str("trip_date") ?: ""}", style = T.base.bold.rtl)
                    Text(status(t), style = T.base.bold)
                }
                Text("الموزع: ${data.profiles.firstOrNull { it.str("id") == t.str("distributor_id") }?.str("full_name") ?: "—"}", style = T.sm)
                // flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("بداية: ${adenTime(s.start)}", "نهاية: ${adenTime(s.end)}", "${s.count} نقطة", "${inv.size} فاتورة", formatMoney(inv.sumOf { it.num("total") }))
                        .forEach { Text(it, style = T.xs.muted) }
                }
            }
        }
        if (data != null && data.trips.isEmpty()) Empty("لا توجد حملات في الفترة المختارة")
    }
}
