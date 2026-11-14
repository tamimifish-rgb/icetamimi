package com.tamimi.naqiice.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.adenTime
import com.tamimi.naqiice.core.campaignConflictMessage
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.openBlock
import com.tamimi.naqiice.core.openForTruck
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.remainingStock
import com.tamimi.naqiice.core.routeSummary
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.ApiError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToLong
import com.tamimi.naqiice.core.Row as DataRow

/** PostgREST embeds a closing as an object or a one-element list depending on the relation. */
private fun DataRow.closing(): DataRow? = obj("closings") ?: rows("closings").firstOrNull()

// ---- trucks and campaigns -------------------------------------------------------------

private class TripsData(
    val trucks: List<DataRow>, val trips: List<DataRow>, val profiles: List<DataRow>,
    val openTrips: List<DataRow>, val distributors: List<DataRow>,
)

@Composable
fun TripsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val operation = rememberOperation()
    val toast = rememberToast()
    var date by remember { mutableStateOf(adenDay()) }
    val pick = remember { mutableStateMapOf<String, String>() }

    val load = rememberLoaded(date) { bearer ->
        coroutineScope {
            val api = repo.api
            val trucks = async { api.select("trucks", "select=*&active=eq.true&order=code", bearer) }
            val trips = async { api.select("trips", "select=*,sales(total),closings(status)&trip_date=eq.$date", bearer) }
            val profiles = async { api.select("profiles", "select=id,full_name,truck_id", bearer) }
            val openTrips = async { api.selectAll("trips", "select=id,truck_id,distributor_id,trip_date,status&status=neq.closed&order=trip_date,id", bearer) }
            val roles = async { api.select("user_roles", "select=user_id&role=eq.distributor", bearer) }
            val ids = roles.await().mapNotNull { it.str("user_id") }.toHashSet()
            TripsData(trucks.await(), trips.await(), profiles.await(), openTrips.await(), profiles.await().filter { it.str("id") in ids })
        }
    }

    fun open(truckId: String, default: String?) = operation.run {
        val data = load.data ?: return@run
        val distributor = pick[truckId] ?: default
        if (distributor.isNullOrEmpty()) throw Notice("اختر الموزع للحملة")
        openBlock(data.openTrips, truckId, distributor)?.let { throw Notice(it) }
        try {
            repo.api.insert("trips", buildJsonObject { put("truck_id", truckId); put("trip_date", date); put("distributor_id", distributor) }, repo.api.token())
        } catch (e: ApiError) {
            // Another device may have opened a campaign meanwhile: show the current state.
            load.reload()
            throw Notice(campaignConflictMessage(e.message ?: ""))
        }
        load.reload()
        toast("تم فتح الحملة")
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("الشاحنات") {
            MidButton(date, outlined = true) {
                val parts = date.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3) DatePickerDialog(context, { _, y, m, d -> date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d) }, parts[0], parts[1] - 1, parts[2]).show()
            }
        }
        val data = load.data
        if (load.error != null) LoadError(load.error, load.reload)
        else if (data == null) Empty("جارٍ التحميل…")
        else data.trucks.forEach { truck ->
            val truckId = truck.str("id") ?: return@forEach
            val trips = data.trips.filter { it.str("truck_id") == truckId }.sortedBy { it.str("created_at") ?: "" }
            val default = data.profiles.firstOrNull { it.str("truck_id") == truckId }?.str("id")
            val otherOpen = openForTruck(data.openTrips, truckId).filter { it.str("trip_date") != date }
            val hasOpen = trips.any { it.str("status") != "closed" } || otherOpen.isNotEmpty()
            val chosen = pick[truckId] ?: default
            val distBlock = if (chosen.isNullOrEmpty()) null else openBlock(data.openTrips.filter { it.str("truck_id") != truckId }, truckId, chosen)
            fun name(id: String?) = data.profiles.firstOrNull { it.str("id") == id }?.str("full_name") ?: "بدون موزع"
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(truck.str("name") ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(truck.str("code") ?: "", fontSize = 12.sp, color = Ice.mutedText)
                    }
                    trips.forEachIndexed { index, trip ->
                        val total = trip.rows("sales").sumOf { it.num("total") }
                        val closing = trip.closing()
                        val state = when {
                            trip.str("status") == "closed" -> "مغلقة نهائياً"
                            closing != null -> if (closing.str("status") == "flagged") "أُعيدت للموزع" else "بانتظار اعتماد الإغلاق"
                            trip.str("loading_approved_at") != null -> "جارية (التحميل معتمد)"
                            else -> "بانتظار اعتماد الموزع للتحميل"
                        }
                        LinkRow("حملة ${index + 1} · ${name(trip.str("distributor_id"))}", "$state · ${formatMoney(total)}") { trip.str("id")?.let { nav.go(Screen.Trip(it)) } }
                    }
                    otherOpen.forEach { trip ->
                        LinkRow("حملة مفتوحة بتاريخ ${trip.str("trip_date")} · ${name(trip.str("distributor_id"))}", "يجب إغلاقها قبل فتح حملة جديدة", highlighted = true) {
                            trip.str("id")?.let { nav.go(Screen.Trip(it)) }
                        }
                    }
                    if (!hasOpen) {
                        if (distBlock != null) Text(distBlock, fontSize = 14.sp, color = Ice.destructive)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Picker("اختر الموزع", data.distributors.mapNotNull { d -> d.str("id")?.let { it to (d.str("full_name") ?: "") } }, chosen, Modifier.weight(1f)) { pick[truckId] = it }
                            MidButton("فتح حملة", enabled = distBlock == null && !operation.busy) { open(truckId, default) }
                        }
                    }
                }
            }
        }
    }
}

// ---- campaign detail ------------------------------------------------------------------

private val LOAD_KINDS = listOf("load" to "تحميل", "return" to "مرتجع", "damaged" to "تالف/ذائب")

private class TripData(
    val trip: DataRow, val variants: List<DataRow>, val stock: List<DataRow>, val loads: List<DataRow>,
    val sales: List<DataRow>, val expenses: List<DataRow>, val payments: List<DataRow>,
    /** Null when the route could not be read; the rest of the campaign still shows. */
    val points: List<TrackPoint>?,
)

@Composable
fun TripScreen(id: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val operation = rememberOperation()
    val toast = rememberToast()
    var kind by remember { mutableStateOf("load") }
    val qty = remember { mutableStateMapOf<String, String>() }

    val load = rememberLoaded(id) { bearer ->
        coroutineScope {
            val api = repo.api
            val trip = async { api.maybeSingle("trips", "select=*,trucks(name,code),closings(status,review_note)&id=eq.$id", bearer) }
            val variants = async { api.select("product_variants", "select=*&active=eq.true&order=sort", bearer) }
            val stock = async { api.select("trip_stock", "select=*&trip_id=eq.$id", bearer) }
            val loads = async { api.select("trip_loads", "select=*&trip_id=eq.$id&order=created_at.desc", bearer) }
            val sales = async { api.select("sales", "select=*,customers(name)&trip_id=eq.$id&order=created_at.desc", bearer) }
            val expenses = async { api.select("expenses", "select=*&trip_id=eq.$id", bearer) }
            val payments = async { api.select("payments", "select=*&trip_id=eq.$id", bearer) }
            val points = async {
                try {
                    api.selectAll("gps_points", "select=id,trip_id,lat,lng,recorded_at&trip_id=eq.$id&order=recorded_at,id", bearer).mapNotNull { p ->
                        TrackPoint(p.numOrNull("lat") ?: return@mapNotNull null, p.numOrNull("lng") ?: return@mapNotNull null, p.str("recorded_at") ?: return@mapNotNull null)
                    }
                } catch (e: ApiError) { null } catch (e: IOException) { null }
            }
            TripData(
                trip.await() ?: throw ApiError(404, null, "الحملة غير متاحة لهذا الحساب"),
                variants.await(), stock.await(), loads.await(), sales.await(), expenses.await(), payments.await(), points.await(),
            )
        }
    }

    val data = load.data
    if (load.error != null) { LoadError(load.error, load.reload); return }
    if (data == null) { Empty("جارٍ التحميل…"); return }
    val trip = data.trip
    fun variantName(variantId: String?) = data.variants.firstOrNull { it.str("id") == variantId }?.str("name") ?: ""
    fun kindLabel(value: String?) = LOAD_KINDS.firstOrNull { it.first == value }?.second ?: ""
    val closed = trip.str("status") == "closed"
    val closing = trip.closing()
    val loadingApproved = trip.str("loading_approved_at") != null
    val pendingLoads = data.loads.count { it.str("kind") == "load" && it.str("approved_at") == null }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("${trip.obj("trucks")?.str("name") ?: ""} — ${trip.str("trip_date") ?: ""}")
        Banner(if (closed) Ice.muted else if (pendingLoads > 0) Ice.warning.copy(alpha = 0.3f) else Ice.success.copy(alpha = 0.15f)) {
            Text(
                when {
                    closed -> "الحملة مغلقة نهائياً — لا يمكن تعديلها أو فتحها مجدداً."
                    closing != null -> if (closing.str("status") == "flagged") "أُعيد الإغلاق للموزع للتصحيح: ${closing.str("review_note") ?: ""}" else "أرسل الموزع الإغلاق — البيع متوقف. راجعه من صفحة الإغلاقات."
                    pendingLoads > 0 -> "يوجد $pendingLoads صنف بانتظار اعتماد الموزع."
                    loadingApproved -> "اعتمد الموزع التحميل. أي كمية جديدة تُسجَّل كتحميل إضافي وتحتاج اعتماده."
                    else -> "احفظ التحميل ثم ينتظر اعتماد الموزع."
                },
                fontSize = 14.sp,
            )
        }
        StatGrid(listOf(
            Triple("المبيعات", formatMoney(data.sales.sumOf { it.num("total") }), Ice.foreground),
            Triple("النقد", formatMoney(data.sales.sumOf { it.num("cash_paid") }), Ice.success),
            Triple("تحصيل", formatMoney(data.payments.sumOf { it.num("amount") }), Ice.foreground),
            Triple("مصروفات", formatMoney(data.expenses.sumOf { it.num("amount") }), Ice.onWarning),
        ))

        if (!closed) IceCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(LOAD_KINDS, kind) { kind = it }
                data.variants.forEach { v ->
                    val variantId = v.str("id") ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(v.str("name") ?: "", Modifier.weight(1f), fontSize = 14.sp)
                        Field(qty[variantId] ?: "", { qty[variantId] = it }, "الكمية", Modifier.width(120.dp), numeric = true)
                    }
                }
                BigButton("حفظ " + if (kind == "load" && loadingApproved) "تحميل إضافي" else kindLabel(kind), enabled = !operation.busy) {
                    operation.run {
                        val rows = qty.entries.filter { parseNumber(it.value) > 0 }.map { (variantId, value) ->
                            buildJsonObject { put("trip_id", id); put("variant_id", variantId); put("qty", jnum(parseNumber(value))); put("kind", kind) }
                        }
                        if (rows.isEmpty()) throw Notice("أدخل كمية")
                        repo.api.insert("trip_loads", JsonArray(rows), repo.api.token())
                        qty.clear()
                        toast("تم الحفظ")
                        load.reload()
                    }
                }
            }
        }

        Column {
            SectionTitle("المخزون")
            IceCard {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Text("الصنف", Modifier.weight(2f), fontSize = 13.sp, color = Ice.mutedText)
                        listOf("محمّل", "مباع", "مرتجع", "تالف", "المتبقي").forEach { Text(it, Modifier.weight(1f), fontSize = 13.sp, color = Ice.mutedText, textAlign = TextAlign.Center) }
                    }
                    data.stock.filter { it.num("loaded") != 0.0 || it.num("sold") != 0.0 }.forEach { s ->
                        val remaining = remainingStock(s.num("loaded"), s.num("sold"), s.num("returned"), s.num("damaged"))
                        HorizontalDivider(color = Ice.border)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(variantName(s.str("variant_id")), Modifier.weight(2f), fontSize = 14.sp)
                            listOf("loaded", "sold", "returned", "damaged").forEach { Text(formatNum(s.num(it)), Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.Center) }
                            Text(formatNum(remaining), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (remaining != 0.0 && closed) Ice.destructive else Ice.foreground)
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("الحركات")
            data.loads.forEach { l ->
                val isLoad = l.str("kind") == "load"
                val approved = l.str("approved_at") != null
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${kindLabel(l.str("kind"))} · ${variantName(l.str("variant_id"))} × ${formatNum(l.num("qty"))}", Modifier.padding(vertical = 6.dp), fontSize = 14.sp)
                        if (isLoad) Text(if (approved) " (معتمد)" else " (بانتظار الموزع)", fontSize = 12.sp, color = if (approved) Ice.success else Ice.onWarning)
                        Grow()
                        if ((!isLoad || !approved) && !closed) IconButton({
                            operation.run {
                                repo.api.delete("trip_loads", "id=eq.${l.str("id")}", repo.api.token())
                                load.reload()
                            }
                        }, enabled = !operation.busy) { Icon(Icons.Filled.Delete, "حذف", tint = Ice.destructive) }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("خط السير")
            val points = data.points
            if (points == null) Text("تعذّر تحميل المسارات", fontSize = 14.sp, color = Ice.destructive)
            else {
                val summary = routeSummary(points)
                if (points.isEmpty()) Text("لا توجد نقاط تتبع محفوظة لهذه الحملة", fontSize = 14.sp, color = Ice.mutedText)
                Text("فجوات التتبع: ${summary.gaps} · الوقت غير المتتبع: ${summary.untrackedMinutes.roundToLong()} دقيقة", fontSize = 14.sp)
                StatGrid(listOf(
                    Triple("بداية التتبع", adenTime(summary.start), Ice.foreground),
                    Triple("نهاية التتبع", adenTime(summary.end), Ice.foreground),
                    Triple("المدة", "${summary.minutes.roundToLong()} دقيقة", Ice.foreground),
                    Triple("المسافة التقريبية", "${String.format(Locale.US, "%.2f", summary.km)} كم", Ice.foreground),
                    Triple("نقاط التتبع", "${summary.count}", Ice.foreground),
                    Triple("التوقفات / الفواتير", "${data.sales.size}", Ice.foreground),
                ))
                // The query is ordered by time, so the last row is the newest position.
                points.lastOrNull()?.let { last ->
                    MidButton((if (trip.str("status") == "open") "آخر موقع للشاحنة" else "نهاية المسار") + " — فتح في الخرائط", Modifier.fillMaxWidth(), outlined = true) {
                        if (!openUrl(context, "geo:${last.lat},${last.lng}?q=${last.lat},${last.lng}")) toast("تعذّر فتح الخرائط")
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("الفواتير (${data.sales.size})")
            data.sales.forEach { s ->
                LinkRow(s.obj("customers")?.str("name") ?: "", formatDate(s.str("created_at")), end = formatMoney(s.num("total"))) { s.str("id")?.let { nav.go(Screen.Invoice(it)) } }
            }
        }
    }
}

// ---- closings -------------------------------------------------------------------------

private val CLOSING_STATUS = mapOf("submitted" to "بانتظار المراجعة", "approved" to "معتمد", "flagged" to "يحتاج مراجعة")

@Composable
fun ClosingsScreen() {
    val repo = LocalRepo.current
    val operation = rememberOperation()
    val toast = rememberToast()
    val me by repo.me.collectAsState()
    val depositSupport by repo.depositSupport.collectAsState()
    var flagging by remember { mutableStateOf<String?>(null) }

    val load = rememberLoaded(me?.id, depositSupport) { bearer ->
        val closings = repo.api.selectAll("closings", "select=*,trips(trip_date,trucks(name))&order=created_at.desc,id", bearer)
        // Deposits are an optional feature: without them the closings still show.
        val deposits = if (!depositSupport) emptyList() else try {
            repo.api.selectAll("pureice_deposits", "select=*&order=id", bearer)
        } catch (e: ApiError) { emptyList() } catch (e: IOException) { emptyList() }
        closings to deposits
    }

    fun review(id: String, status: String, note: String?) = operation.run {
        repo.api.rpc("review_closing", buildJsonObject { put("p_id", id); put("p_status", status); put("p_note", note ?: "") }, repo.api.token())
        toast(if (status == "approved") "تم الاعتماد" else "أُعيد للموزع للتصحيح")
        load.reload()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("إغلاقات اليوم")
        val data = load.data
        if (load.error != null) LoadError(load.error, load.reload)
        else if (data == null) Empty("جارٍ التحميل…")
        else if (data.first.isEmpty()) Empty("لا توجد إغلاقات")
        else data.first.forEach { c ->
            val closingId = c.str("id") ?: return@forEach
            val status = c.str("status")
            val deposits = data.second.filter { it.str("trip_id") != null && it.str("trip_id") == c.str("trip_id") }
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.obj("trips")?.obj("trucks")?.str("name") ?: ""} — ${c.obj("trips")?.str("trip_date") ?: ""}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        when (status) {
                            "approved" -> Chip(CLOSING_STATUS[status] ?: "", Ice.success.copy(alpha = 0.15f), Ice.success)
                            "flagged" -> Chip(CLOSING_STATUS[status] ?: "", Ice.destructive.copy(alpha = 0.1f), Ice.destructive)
                            else -> Chip(CLOSING_STATUS[status] ?: "", Ice.warning.copy(alpha = 0.3f))
                        }
                    }
                    Row {
                        listOf("المتوقع" to "expected_cash", "المعدود" to "counted_cash", "الفرق" to "difference").forEach { (label, key) ->
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 14.sp)
                                Text(formatMoney(c.num(key)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (key == "difference" && c.num(key) < 0) Ice.destructive else Ice.foreground)
                            }
                        }
                    }
                    c.str("review_note")?.takeIf { it.isNotEmpty() }?.let { Text("ملاحظة المراجعة: $it", fontSize = 14.sp, color = Ice.destructive) }
                    c.str("notes")?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, color = Ice.mutedText) }
                    Row { Text("إيداعات: ", fontSize = 14.sp); Text(formatMoney(deposits.sumOf { it.num("amount") }), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    deposits.forEach { DepositSlipButton(it.str("slip_path")) }
                    Text(formatDate(c.str("created_at")), fontSize = 12.sp, color = Ice.mutedText)
                    if (status == "submitted") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MidButton("اعتماد", Modifier.weight(1f), enabled = !operation.busy) { review(closingId, "approved", null) }
                        MidButton("يحتاج مراجعة", Modifier.weight(1f), enabled = !operation.busy, danger = true) { flagging = closingId }
                    }
                }
            }
        }
    }

    flagging?.let { closingId ->
        ReasonDialog("سبب الإعادة للموزع (ما الذي يجب تصحيحه؟)", "يحتاج مراجعة", { flagging = null }) { note -> flagging = null; review(closingId, "flagged", note) }
    }
}
