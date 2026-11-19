package com.tamimi.naqiice.ui

import com.tamimi.naqiice.core.bool
import kotlinx.serialization.json.JsonNull
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.campaignConflictMessage
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.openBlock
import com.tamimi.naqiice.core.openForTruck
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.remainingStock
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.ApiError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
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
    val operation = rememberOperation()
    val toast = rememberToast()
    var date by remember { mutableStateOf(adenDay()) }
    val pick = remember { mutableStateMapOf<String, String>() }
    val boxSupport = repo.snapshot.collectAsState().value.boxSupport

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

    // div.space-y-4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("الشاحنات") { DateInput(date, Modifier.width(160.dp)) { date = it } }
        if (boxSupport) Btn("الصناديق", { nav.go(Screen.Boxes) }, variant = Variant.Outline, height = 44.dp, icon = Lucide.Boxes)
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
            // space-y-3 rounded-2xl border border-border bg-card p-4 shadow-soft
            Column(Modifier.fillMaxWidth().box(Radius.xl2, shadow = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row { Inline("${truck.str("name") ?: ""} ", T.lg.bold, truck.str("code") ?: "", T.xs.bold.muted) }
                trips.forEachIndexed { index, trip ->
                    val total = trip.rows("sales").sumOf { it.num("total") }
                    val closing = trip.closing()
                    val state = when {
                        trip.str("status") == "closed" -> "مغلقة نهائياً"
                        closing != null -> if (closing.str("status") == "flagged") "أُعيدت للموزع" else "بانتظار اعتماد الإغلاق"
                        trip.str("loading_approved_at") != null -> "جارية (التحميل معتمد)"
                        else -> "بانتظار اعتماد الموزع للتحميل"
                    }
                    TripLink("حملة ${index + 1} · ${name(trip.str("distributor_id"))}", "$state · ${formatMoney(total)}") { trip.str("id")?.let { nav.go(Screen.Trip(it)) } }
                }
                otherOpen.forEach { trip ->
                    TripLink("حملة مفتوحة بتاريخ ${trip.str("trip_date")} · ${name(trip.str("distributor_id"))}", "يجب إغلاقها قبل فتح حملة جديدة", highlighted = true) {
                        trip.str("id")?.let { nav.go(Screen.Trip(it)) }
                    }
                }
                if (!hasOpen && distBlock != null) Text(distBlock, style = T.sm.color(Ice.destructive))
                // div.flex.gap-2: the h-10 select and the h-9 button, top-aligned
                if (!hasOpen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallSelect(
                        data.distributors.mapNotNull { d -> d.str("id")?.let { it to (d.str("full_name") ?: "") } }, chosen, Modifier.weight(1f), placeholder = "اختر الموزع",
                    ) { pick[truckId] = it }
                    SmallBtn("فتح حملة", { open(truckId, default) }, enabled = distBlock == null && !operation.busy)
                }
            }
        }
    }
}

/** a.flex.items-center.justify-between.rounded-xl.border.p-3.text-sm with a `size-4` chevron. */
@Composable
private fun TripLink(title: String, subtitle: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .box(Radius.xl, if (highlighted) Ice.primary.copy(alpha = 0.05f) else null, if (highlighted) Ice.primary.copy(alpha = 0.4f) else Ice.border)
            .tap(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.sm.bold)
            Text(subtitle, style = T.sm.muted)
        }
        Icon(Lucide.ChevronLeft, null, Modifier.size(16.dp), tint = Ice.foreground)
    }
}

// ---- campaign detail ------------------------------------------------------------------

private val LOAD_KINDS = listOf("load" to "تحميل", "return" to "مرتجع", "damaged" to "تالف/ذائب")

private class TripData(
    val trip: DataRow, val variants: List<DataRow>, val stock: List<DataRow>, val loads: List<DataRow>,
    val sales: List<DataRow>, val expenses: List<DataRow>, val payments: List<DataRow>,
)

@Composable
fun TripScreen(id: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
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
            TripData(
                trip.await() ?: throw ApiError(404, null, "الحملة غير متاحة لهذا الحساب"),
                variants.await(), stock.await(), loads.await(), sales.await(), expenses.await(), payments.await(),
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

    // div.space-y-5
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageTitle("${trip.obj("trucks")?.str("name") ?: ""} — ${trip.str("trip_date") ?: ""}", spacing = 20.dp)
        // The route lives on the map page (web: /map?trip=<id>).
        Btn("عرض خط السير", { nav.go(Screen.RouteMap(id)) }, variant = Variant.Outline, height = 44.dp)
        Banner(if (closed) Ice.muted else if (pendingLoads > 0) Ice.warning.copy(alpha = 0.3f) else Ice.success.copy(alpha = 0.15f)) {
            Text(
                when {
                    closed -> "الحملة مغلقة نهائياً — لا يمكن تعديلها أو فتحها مجدداً."
                    closing != null -> if (closing.str("status") == "flagged") "أُعيد الإغلاق للموزع للتصحيح: ${closing.str("review_note") ?: ""}" else "أرسل الموزع الإغلاق — البيع متوقف. راجعه من صفحة الإغلاقات."
                    pendingLoads > 0 -> "يوجد $pendingLoads صنف بانتظار اعتماد الموزع."
                    loadingApproved -> "اعتمد الموزع التحميل. أي كمية جديدة تُسجَّل كتحميل إضافي وتحتاج اعتماده."
                    else -> "احفظ التحميل ثم ينتظر اعتماد الموزع."
                },
            )
        }
        StatGrid(listOf(
            Triple("المبيعات", formatMoney(data.sales.sumOf { it.num("total") }), Ice.foreground),
            Triple("النقد", formatMoney(data.sales.sumOf { it.num("cash_paid") }), Ice.success),
            Triple("تحصيل", formatMoney(data.payments.sumOf { it.num("amount") }), Ice.foreground),
            Triple("مصروفات", formatMoney(data.expenses.sumOf { it.num("amount") }), Ice.onWarning),
        ))

        // section.rounded-2xl.border.bg-card.p-4
        if (!closed) Column(Modifier.fillMaxWidth().box(Radius.xl2).padding(16.dp)) {
            // grid grid-cols-3 gap-2 of `rounded-lg border p-2 text-sm font-medium`
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOAD_KINDS.forEach { (value, label) ->
                    val on = kind == value
                    Box(
                        Modifier.weight(1f).box(Radius.lg, if (on) Ice.primary else null, if (on) Ice.primary else Ice.border).tap { kind = value }.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = T.sm.medium.color(if (on) Ice.onPrimary else Ice.foreground), maxLines = 1) }
                }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                data.variants.forEach { v ->
                    val variantId = v.str("id") ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(v.str("name") ?: "", Modifier.weight(1f), style = T.sm)
                        Input(qty[variantId] ?: "", { qty[variantId] = it }, Modifier.width(96.dp), placeholder = "0", height = 44.dp, numeric = true, center = true)
                    }
                }
            }
            Btn("حفظ " + if (kind == "load" && loadingApproved) "تحميل إضافي" else kindLabel(kind), {
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
            }, Modifier.padding(top = 12.dp).fillMaxWidth(), enabled = !operation.busy, height = 48.dp)
        }

        Column {
            SectionTitle("المخزون")
            StockTable(
                data.stock.filter { it.num("loaded") != 0.0 || it.num("sold") != 0.0 }.map { s ->
                    listOf(variantName(s.str("variant_id"))) + listOf("loaded", "sold", "returned", "damaged").map { formatNum(s.num(it)) } +
                        formatNum(remainingStock(s.num("loaded"), s.num("sold"), s.num("returned"), s.num("damaged")))
                },
                closed,
            )
        }

        Column {
            SectionHead("الحركات", data.loads.isEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                data.loads.forEach { l ->
                    val isLoad = l.str("kind") == "load"
                    val approved = l.str("approved_at") != null
                    // flex items-center justify-between rounded-lg border bg-card px-3 py-2 text-sm
                    Row(
                        Modifier.fillMaxWidth().box(Radius.lg).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(Modifier.weight(1f)) {
                            Inline(
                                "${kindLabel(l.str("kind"))} · ${variantName(l.str("variant_id"))} × ${formatNum(l.num("qty"))} ", T.sm,
                                if (isLoad) (if (approved) "(معتمد)" else "(بانتظار الموزع)") else null, T.xs.color(if (approved) Ice.success else Ice.onWarning),
                            )
                        }
                        if ((!isLoad || !approved) && !closed) Icon(
                            Lucide.Trash2, "حذف",
                            Modifier.size(16.dp).tap(enabled = !operation.busy) {
                                operation.run {
                                    repo.api.delete("trip_loads", "id=eq.${l.str("id")}", repo.api.token())
                                    load.reload()
                                }
                            },
                            tint = Ice.destructive,
                        )
                    }
                }
            }
        }

        Column {
            SectionHead("الفواتير (${data.sales.size})", data.sales.isEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                data.sales.forEach { s ->
                    // flex justify-between rounded-lg border bg-card px-3 py-2 text-sm
                    Row(
                        Modifier.fillMaxWidth().box(Radius.lg).tap { s.str("id")?.let { nav.go(Screen.Invoice(it)) } }.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(Modifier.weight(1f)) { Inline("${s.obj("customers")?.str("name") ?: ""} ", T.sm, formatDate(s.str("created_at")), T.xs.muted) }
                        Text(formatMoney(s.num("total")), style = T.sm.bold)
                    }
                }
            }
        }
    }
}

private val STOCK_HEADERS = listOf("الصنف", "محمّل", "مباع", "مرتجع", "تالف", "المتبقي")

/**
 * `table.w-full.text-sm` inside `overflow-x-auto rounded-2xl border bg-card`. A browser gives
 * each column a share of the width in proportion to its widest cell; so do the weights here.
 */
@Composable
private fun StockTable(rows: List<List<String>>, closed: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val weights = remember(rows, density) {
        STOCK_HEADERS.indices.map { c ->
            fun width(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1).size.width.toFloat()
            val widest = maxOf(width(STOCK_HEADERS[c], T.sm.bold), rows.maxOfOrNull { width(it[c], if (c == 5) T.sm.bold else T.sm) } ?: 0f)
            (widest + if (c == 0) with(density) { 16.dp.toPx() } else 0f).coerceAtLeast(1f)
        }
    }
    @Composable
    fun RowScope.Cell(c: Int, text: String, style: TextStyle) {
        // Only the first column has `p-2 text-right`; the others are bare and centred.
        Text(text, Modifier.weight(weights[c]).padding(if (c == 0) 8.dp else 0.dp), style = style, textAlign = if (c == 0) TextAlign.Start else TextAlign.Center)
    }
    Column(Modifier.fillMaxWidth().box(Radius.xl2).clip(RoundedCornerShape(Radius.xl2 - 1.dp))) {
        Row(Modifier.fillMaxWidth().background(Ice.muted), verticalAlignment = Alignment.CenterVertically) {
            STOCK_HEADERS.forEachIndexed { c, header -> Cell(c, header, T.sm.bold.muted) }
        }
        rows.forEach { row ->
            Divider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                row.forEachIndexed { c, text ->
                    Cell(c, text, if (c == 5) T.sm.bold.color(if (closed && text != formatNum(0.0)) Ice.destructive else Ice.foreground) else T.sm)
                }
            }
        }
    }
}

// ---- closings -------------------------------------------------------------------------

private val CLOSING_STATUS = mapOf("submitted" to "بانتظار المراجعة", "approved" to "معتمد", "flagged" to "يحتاج مراجعة")

/** e.g. "نقد 6,500 ر.ي · إيداع 5,000 ر.ي"; only the parts that are not zero. */
internal fun handoverText(cash: Double, transfer: Double): String = listOfNotNull(
    if (cash > 0 || transfer == 0.0) "نقد ${formatMoney(cash)}" else null,
    if (transfer > 0) "إيداع ${formatMoney(transfer)}" else null,
).joinToString(" · ")

@Composable
fun ClosingsScreen() {
    val repo = LocalRepo.current
    val operation = rememberOperation()
    val toast = rememberToast()
    val me by repo.me.collectAsState()
    val depositSupport by repo.depositSupport.collectAsState()
    val handoverSupport by repo.handoverSupport.collectAsState()
    val ledgerSupport by repo.ledgerSupport.collectAsState()
    var flagging by remember { mutableStateOf<String?>(null) }
    var handovers by remember { mutableStateOf<List<DataRow>>(emptyList()) }
    /** closing id → charged (true) or absorbed (false). */
    var decisions by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    /** An approved shortage the supervisor is asked about right after approving it. */
    var deciding by remember { mutableStateOf<String?>(null) }

    val load = rememberLoaded(me?.id, depositSupport, handoverSupport, ledgerSupport) { bearer ->
        decisions = if (!ledgerSupport) emptyMap() else try {
            repo.api.selectAll("pureice_shortage_decision", "select=closing_id,charged&order=closing_id", bearer)
                .mapNotNull { row -> row.str("closing_id")?.let { it to (row.bool("charged") == true) } }.toMap()
        } catch (e: ApiError) { emptyMap() } catch (e: IOException) { emptyMap() }
        // How each closing's cash was handed over (optional feature, like deposits).
        handovers = if (!handoverSupport) emptyList() else try {
            repo.api.selectAll("pureice_closing_handover", "select=*&order=closing_id", bearer)
        } catch (e: ApiError) { emptyList() } catch (e: IOException) { emptyList() }
        val closings = repo.api.selectAll("closings", "select=*,trips(trip_date,trucks(name))&order=created_at.desc,id", bearer)
        // Deposits are an optional feature: without them the closings still show.
        val deposits = if (!depositSupport) emptyList() else try {
            repo.api.selectAll("pureice_deposits", "select=*&order=id", bearer)
        } catch (e: ApiError) { emptyList() } catch (e: IOException) { emptyList() }
        closings to deposits
    }

    fun review(id: String, status: String, note: String?, short: Boolean = false) = operation.run {
        repo.api.rpc("review_closing", buildJsonObject { put("p_id", id); put("p_status", status); put("p_note", note ?: "") }, repo.api.token())
        toast(if (status == "approved") "تم الاعتماد" else "أُعيد للموزع للتصحيح")
        // A shortage is charged or absorbed once the closing is approved.
        if (status == "approved" && short && ledgerSupport) deciding = id
        load.reload()
    }

    fun decide(id: String, charge: Boolean) = operation.run {
        repo.api.rpc("pureice_decide_shortage", buildJsonObject { put("p_closing", id); put("p_charge", charge); put("p_note", JsonNull) }, repo.api.token())
        toast(if (charge) "أُضيف العجز إلى حساب الموزع" else "سُجّل العجز على الشركة")
        load.reload()
    }

    // div.space-y-3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("إغلاقات اليوم", spacing = 12.dp)
        val data = load.data
        if (load.error != null) LoadError(load.error, load.reload)
        // As on the web, the empty box also stands in while the list is loading.
        else if (data == null || data.first.isEmpty()) Empty("لا توجد إغلاقات")
        else {
            val undecided = if (!ledgerSupport) 0 else data.first.count { it.str("status") == "approved" && it.num("difference") < 0 && it.str("id") !in decisions }
            if (undecided > 0) Banner(Ice.warning.copy(alpha = 0.3f)) { Text("عجز بانتظار القرار: $undecided — اختر من يتحمل كل عجز معتمد.") }
        }
        if (data != null) data.first.forEach { c ->
            val closingId = c.str("id") ?: return@forEach
            val status = c.str("status")
            val deposits = data.second.filter { it.str("trip_id") != null && it.str("trip_id") == c.str("trip_id") }
            // rounded-2xl border border-border bg-card p-4 shadow-soft
            Column(Modifier.fillMaxWidth().box(Radius.xl2, shadow = true).padding(16.dp)) {
                // div.flex.justify-between: the chip stretches to the title's line height
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${c.obj("trips")?.obj("trucks")?.str("name") ?: ""} — ${c.obj("trips")?.str("trip_date") ?: ""}", Modifier.weight(1f, fill = false), style = T.base.bold)
                    when (status) {
                        "approved" -> Chip(CLOSING_STATUS[status] ?: "", Ice.success.copy(alpha = 0.15f), Ice.success, Modifier.fillMaxHeight())
                        "flagged" -> Chip(CLOSING_STATUS[status] ?: "", Ice.destructive.copy(alpha = 0.1f), Ice.destructive, Modifier.fillMaxHeight())
                        else -> Chip(CLOSING_STATUS[status] ?: "", Ice.warning.copy(alpha = 0.3f), modifier = Modifier.fillMaxHeight())
                    }
                }
                // mt-2 grid grid-cols-3 gap-2 text-sm
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("المتوقع" to "expected_cash", "المعدود" to "counted_cash", "الفرق" to "difference").forEach { (label, key) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = T.sm)
                            Text(formatMoney(c.num(key)), style = T.sm.bold.color(if (key == "difference" && c.num(key) < 0) Ice.destructive else Ice.foreground))
                        }
                    }
                }
                c.str("review_note")?.takeIf { it.isNotEmpty() }?.let { Text("ملاحظة المراجعة: $it", Modifier.padding(top = 8.dp), style = T.sm.color(Ice.destructive)) }
                c.str("notes")?.takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(top = 8.dp), style = T.sm.muted) }
                Row(Modifier.padding(top = 8.dp)) {
                    Text("إيداعات: ", style = T.sm)
                    Text(formatMoney(deposits.sumOf { it.num("amount") }), style = T.sm.bold)
                }
                deposits.forEach { DepositSlipButton(it.str("slip_path")) }
                if (handoverSupport) {
                    val handover = handovers.firstOrNull { it.str("closing_id") == closingId }
                    Text(
                        "طريقة التسليم: " + if (handover == null) "لم تُحدد" else handoverText(handover.num("cash_amount"), handover.num("transfer_amount")),
                        Modifier.padding(top = 4.dp), style = T.sm,
                    )
                    if (handover != null && handover.num("transfer_amount") > 0) DepositSlipButton(handover.str("slip_path"), "عرض إشعار الإيداع")
                }
                Text(formatDate(c.str("created_at")), Modifier.padding(top = 4.dp), style = T.xs.muted)
                if (ledgerSupport && status == "approved" && c.num("difference") < 0) {
                    when (decisions[closingId]) {
                        true -> Text("العجز: على حساب الموزع", Modifier.padding(top = 4.dp), style = T.sm.bold.color(Ice.destructive))
                        false -> Text("العجز: تتحمله الشركة", Modifier.padding(top = 4.dp), style = T.sm.muted)
                        null -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("عجز بانتظار القرار: من يتحمله؟", style = T.sm.bold.color(Ice.destructive))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallBtn("تحميل العجز على الموزع", { decide(closingId, true) }, Modifier.weight(1f), Variant.Destructive, enabled = !operation.busy)
                                SmallBtn("تتحمله الشركة", { decide(closingId, false) }, Modifier.weight(1f), Variant.Outline, enabled = !operation.busy)
                            }
                        }
                    }
                }
                if (status == "submitted") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("اعتماد", { review(closingId, "approved", null, c.num("difference") < 0) }, Modifier.weight(1f), enabled = !operation.busy)
                    SmallBtn("يحتاج مراجعة", { flagging = closingId }, Modifier.weight(1f), Variant.Destructive, enabled = !operation.busy)
                }
            }
        }
    }

    deciding?.let { closingId ->
        val short = load.data?.first?.firstOrNull { it.str("id") == closingId }?.num("difference")?.let { -it } ?: 0.0
        AlertDialog(
            onDismissRequest = { deciding = null },
            text = { Text("في هذا الإغلاق عجز ${formatMoney(short)}. من يتحمل العجز؟") },
            confirmButton = { TextButton({ deciding = null; decide(closingId, true) }) { Text("تحميل العجز على الموزع") } },
            dismissButton = {
                Row {
                    TextButton({ deciding = null; decide(closingId, false) }) { Text("تتحمله الشركة") }
                    TextButton({ deciding = null }) { Text("لاحقاً") }
                }
            },
        )
    }

    flagging?.let { closingId ->
        ReasonDialog("سبب الإعادة للموزع (ما الذي يجب تصحيحه؟)", "موافق", { flagging = null }) { note -> flagging = null; review(closingId, "flagged", note) }
    }
}
