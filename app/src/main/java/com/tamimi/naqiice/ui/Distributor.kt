package com.tamimi.naqiice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.CustomerContact
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.adenTime
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.customerPage
import com.tamimi.naqiice.core.filterCustomers
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.jnumOrNull
import com.tamimi.naqiice.core.jstr
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.core.yemenPhone
import com.tamimi.naqiice.data.Snapshot
import com.tamimi.naqiice.tracking.Locations
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---- today ----------------------------------------------------------------------------

private data class Quick(val screen: Screen, val label: String, val icon: ImageVector)

@Composable
fun TodayScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val online by repo.online.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    val location = rememberLocationGate()
    val day = snap.day
    val trip = snap.trip
    val truck = snap.truck(trip?.str("truck_id") ?: me?.truckId)
    val toApprove = snap.unapprovedLoads
    val failed = snap.pending.filter { it.lastError != null }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageTitle(truck?.str("name")?.let { "$it — الحملة" } ?: "الحملة")
        TrackingStatus(snap)
        if (toApprove.isNotEmpty()) location.prompt()
        if (trip == null) Empty("لا توجد حملة مفتوحة لك حالياً. انتظر المشرف لتعيين حملة وتحميل الشاحنة.")
        if (trip != null && toApprove.isNotEmpty()) {
            Banner(Ice.warning.copy(alpha = 0.2f), borderColor = Ice.warning) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (trip.str("loading_approved_at") != null) "تحميل إضافي بانتظار اعتمادك" else "التحميل بانتظار اعتمادك", fontWeight = FontWeight.Bold)
                    toApprove.forEach { LineItem(snap.variant(it.str("variant_id"))?.str("name") ?: "صنف", formatNum(it.num("qty"))) }
                    Text("تأكد من مطابقة الكميات في الشاحنة. بعد الاعتماد لا يمكن للمشرف تعديلها.", fontSize = 12.sp, color = Ice.mutedText)
                    MidButton("اعتماد التحميل", Modifier.fillMaxWidth(), enabled = !operation.busy && !location.blocked && online) {
                        operation.run {
                            val tripId = snap.tripId ?: throw Notice("لا توجد حملة للاعتماد")
                            if (location.required && location.check() == null) throw Notice("الموقع مطلوب قبل اعتماد التحميل")
                            repo.api.rpc("approve_trip_loads", buildJsonObject { put("_trip", tripId) }, repo.api.token())
                            toast("تم اعتماد التحميل")
                            repo.syncNow()
                        }
                    }
                }
            }
        }
        snap.closing?.let { closing ->
            val status = closing.str("status")
            Banner(if (status == "flagged") Ice.destructive.copy(alpha = 0.1f) else Ice.secondary) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("تم إرسال إغلاق الحملة — الحالة: " + when (status) { "approved" -> "معتمد"; "flagged" -> "يحتاج مراجعة"; else -> "بانتظار المشرف" }, fontSize = 14.sp)
                    if (status == "flagged") {
                        closing.str("review_note")?.let { Text("ملاحظة المشرف: $it", fontSize = 14.sp) }
                        MidButton("تصحيح وإعادة الإرسال", Modifier.fillMaxWidth()) { nav.go(Screen.Closing) }
                    }
                }
            }
        }

        StatGrid(listOf(
            Triple("مبيعات الحملة", formatMoney(day.totalSales), Ice.foreground),
            Triple("النقد المتوقع", formatMoney(day.expected), Ice.success),
            Triple("آجل الحملة", formatMoney(day.credit), Ice.onWarning),
            Triple("تحصيل ديون", formatMoney(day.collected), Ice.foreground),
            Triple("إيداعات", formatMoney(day.deposits), Ice.foreground),
        ))

        val quick = listOf(
            Quick(Screen.Sale(), "فاتورة بيع", Icons.Filled.ShoppingCart), Quick(Screen.Payment(), "تحصيل دين", Icons.Filled.AccountBalanceWallet),
            Quick(Screen.FreezerAction(), "ثلاجة", Icons.Filled.AcUnit), Quick(Screen.Expenses, "مصروف", Icons.AutoMirrored.Filled.ReceiptLong),
            Quick(Screen.Maintenance, "صيانة", Icons.Filled.Build), Quick(Screen.Closing, "إغلاق الحملة", Icons.Filled.FactCheck),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { q ->
                        Surface(Modifier.weight(1f).clickable { nav.go(q.screen) }, shape = RoundedCornerShape(16.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(q.icon, null, tint = Ice.accent, modifier = Modifier.size(28.dp))
                                Text(q.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }

        if (failed.isNotEmpty()) Banner(Ice.destructive.copy(alpha = 0.1f), borderColor = Ice.destructive.copy(alpha = 0.4f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, null, tint = Ice.destructive, modifier = Modifier.size(20.dp))
                Column {
                    Text("تعذّر رفع ${failed.size} سجل. سيُعاد المحاولة تلقائياً.", fontSize = 14.sp)
                    Text(failed.first().lastError ?: "", fontSize = 12.sp, color = Ice.mutedText)
                }
            }
        }

        if (trip != null) Column {
            SectionTitle("مخزون الشاحنة")
            IceCard {
                Column {
                    snap.variants.forEachIndexed { index, v ->
                        val q = snap.stockOf(v.str("id") ?: "")
                        if (index > 0) HorizontalDivider(color = Ice.border)
                        LineItem(v.str("name") ?: "", "${formatNum(q)} ${v.str("unit") ?: ""}", if (q <= 0) Ice.destructive else Ice.foreground)
                    }
                }
            }
        }

        Column {
            SectionTitle("فواتير الحملة")
            if (day.sales.isEmpty()) Empty("لا توجد فواتير بعد.") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                day.sales.forEach { s ->
                    Surface(Modifier.fillMaxWidth().clickable { nav.go(Screen.Invoice(s.str("id") ?: "")) }, shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snap.customer(s.str("customer_id"))?.str("name") ?: "عميل", fontWeight = FontWeight.Medium)
                                Text(formatDate(s.str("created_at")) + if (s.bool("_pending") == true) " · بانتظار الرفع" else "", fontSize = 12.sp, color = Ice.mutedText)
                            }
                            Text(formatMoney(s.num("total")), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingStatus(snap: Snapshot) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val me by repo.me.collectAsState()
    val state by repo.tracking.collectAsState()
    val trip = snap.trip ?: return
    if (trip.str("status") != "open" || me?.primaryRole != "distributor" || snap.closingSent) return
    val s = state?.takeIf { it.owner == me?.id && it.trip == snap.tripId }
    val denied = !Locations.granted(context)
    Column {
        HorizontalDivider(color = Ice.border)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when { denied -> "إذن الموقع مرفوض"; s?.status == "unavailable" -> "الموقع غير متاح"; s?.status == "active" -> "التتبع نشط"; else -> "بانتظار الموقع" },
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (denied) Ice.destructive else Ice.foreground,
            )
            Text("آخر نقطة: ${adenTime(s?.last)}", fontSize = 14.sp)
        }
        if (s?.status == "unavailable") Text("شغّل موقع الهاتف (GPS) ليستمر تسجيل خط السير.", fontSize = 13.sp, color = Ice.mutedText)
        HorizontalDivider(color = Ice.border)
    }
}

// ---- more -----------------------------------------------------------------------------

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val items = listOf(
        Quick(Screen.Payment(), "تحصيل دين", Icons.Filled.AccountBalanceWallet), Quick(Screen.NewCustomer, "إضافة عميل", Icons.Filled.PersonAdd),
        Quick(Screen.FreezerAction(), "حركة ثلاجة", Icons.Filled.AcUnit), Quick(Screen.Expenses, "المصروفات", Icons.AutoMirrored.Filled.ReceiptLong),
        Quick(Screen.Maintenance, "الصيانة", Icons.Filled.Build), Quick(Screen.InvoiceChanges, "طلبات إلغاء وتعديل الفواتير", Icons.Filled.EditNote),
        Quick(Screen.Closing, "إغلاق اليوم", Icons.Filled.FactCheck),
    )
    Column {
        PageTitle("المزيد")
        Surface(shape = RoundedCornerShape(16.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Ice.border)
                    Row(Modifier.fillMaxWidth().clickable { nav.go(item.screen) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(item.icon, null, tint = Ice.accent, modifier = Modifier.size(24.dp))
                        Text(item.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Ice.mutedText)
                    }
                }
            }
        }
    }
}

// ---- customers ------------------------------------------------------------------------

fun DataRow.toContact() = CustomerContact(str("id") ?: "", str("name") ?: "", str("phone"), str("region_id"), str("truck_id"), str("location_notes"))

/** Shared, searchable customer directory in pages of 50. */
@Composable
fun CustomerPicker(onPick: (String) -> Unit) {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    var query by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("all") }
    var requested by remember { mutableStateOf(1) }
    val filtered = remember(snap.customers, query, region) { filterCustomers(snap.customers, { it.toContact() }, query, region) }
    val page = customerPage(filtered, requested)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("قائمة العملاء مشتركة بين جميع الموزعين.", fontSize = 12.sp, color = Ice.mutedText)
        Field(query, { query = it; requested = 1 }, "ابحث بالاسم أو الهاتف")
        val regions = listOf("all" to "الكل") + snap.regions.map { (it.str("id") ?: "") to (it.str("name") ?: "") }
        regions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, name) -> MidButton(name, outlined = region != id) { region = id; requested = 1 } }
            }
        }
        Text("${formatNum(filtered.size.toDouble())} مطابق من ${formatNum(snap.customers.size.toDouble())} عميل", fontSize = 14.sp, color = Ice.mutedText)
        page.rows.forEach { c ->
            val id = c.str("id") ?: ""
            val balance = snap.balanceOf(id)
            Surface(Modifier.fillMaxWidth().clickable { onPick(id) }, shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.str("name") ?: "", fontWeight = FontWeight.Medium)
                        Text("${snap.regionName(c.str("region_id"))} · ${c.str("phone") ?: "الهاتف غير متوفر"}", fontSize = 12.sp, color = Ice.mutedText)
                    }
                    if (balance > 0) Text(formatMoney(balance), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ice.destructive)
                }
            }
        }
        if (filtered.isEmpty()) Text("لا يوجد عملاء مطابقون", Modifier.fillMaxWidth().padding(16.dp), fontSize = 14.sp, color = Ice.mutedText, textAlign = TextAlign.Center)
        if (page.pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            MidButton("السابق", outlined = true, enabled = page.page > 1) { requested = page.page - 1 }
            Text("${formatNum(page.page.toDouble())} / ${formatNum(page.pages.toDouble())}", fontSize = 14.sp)
            MidButton("التالي", outlined = true, enabled = page.page < page.pages) { requested = page.page + 1 }
        }
    }
}

@Composable
fun CustomersScreen() {
    val nav = LocalNav.current
    Column {
        PageTitle("العملاء") { MidButton("عميل جديد") { nav.go(Screen.NewCustomer) } }
        CustomerPicker { nav.go(Screen.Customer(it)) }
    }
}

@Composable
fun CustomerDetailScreen(id: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val online by repo.online.collectAsState()
    var history by remember { mutableStateOf<List<DataRow>>(emptyList()) }
    val customer = snap.customer(id)
    LaunchedEffect(id, online) {
        if (!online) return@LaunchedEffect
        runCatching {
            val bearer = repo.api.token()
            val sales = repo.api.select("sales", "select=id,invoice_no,total,credit_amount,created_at&customer_id=eq.$id&order=created_at.desc&limit=30", bearer)
                .map { it.with("type" to JsonPrimitive("sale")) }
            val payments = repo.api.select("payments", "select=id,amount,created_at&customer_id=eq.$id&order=created_at.desc&limit=30", bearer)
                .map { it.with("type" to JsonPrimitive("payment")) }
            history = (sales + payments).sortedByDescending { it.str("created_at") ?: "" }
        }
    }
    if (customer == null) { Empty("العميل غير موجود في البيانات المحفوظة."); return }
    val waiting = snap.pending.filter { (it.kind == "sale" || it.kind == "payment") && it.payload.str("customer_id") == id }
        .map { it.payload.with("type" to JsonPrimitive(it.kind), "_pending" to JsonPrimitive(true)) }
    val rows = waiting + history.filter { h -> waiting.none { it.str("id") == h.str("id") } }
    val balance = snap.ref?.let { r -> (r["balances"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? DataRow }?.firstOrNull { it.str("customer_id") == id } }
    val freezers = snap.freezers.filter { it.str("customer_id") == id }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IceCard {
            Column {
                Text(customer.str("name") ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(snap.regionName(customer.str("region_id")) + (customer.str("location_notes")?.let { " · $it" } ?: ""), fontSize = 14.sp, color = Ice.mutedText)
                yemenPhone(customer.str("phone"))?.let { phone ->
                    Text(customer.str("phone") ?: "", Modifier.clickable { dial(context, phone) }.padding(top = 10.dp), color = Ice.primary, style = TextStyle(textDirection = TextDirection.Ltr))
                }
            }
        }
        StatGrid(listOf(
            Triple("الدين الحالي", formatMoney(snap.balanceOf(id)), Ice.destructive),
            Triple("حد الائتمان", if (customer.num("credit_limit") > 0) formatMoney(customer.num("credit_limit")) else "بدون", Ice.foreground),
            Triple("مبيعات الشهر", formatMoney(balance?.num("month_sales")), Ice.foreground),
            Triple("إجمالي المبيعات", formatMoney(balance?.num("total_sales")), Ice.foreground),
        ))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MidButton("بيع", Modifier.weight(1f)) { nav.go(Screen.Sale(id)) }
            MidButton("تحصيل", Modifier.weight(1f), outlined = true) { nav.go(Screen.Payment(id)) }
            MidButton("ثلاجة", Modifier.weight(1f), outlined = true) { nav.go(Screen.FreezerAction(id)) }
        }
        Column {
            SectionTitle("الثلاجات لدى العميل")
            if (freezers.isEmpty()) Empty("لا توجد ثلاجات") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                freezers.forEach { f ->
                    IceCard { Text(listOfNotNull(f.str("serial"), f.obj("freezer_models")?.str("name"), f.str("condition")).joinToString(" · "), fontSize = 14.sp) }
                }
            }
        }
        Column {
            SectionTitle("السجل")
            if (!online) Text("السجل الكامل يظهر عند توفر الإنترنت.", fontSize = 12.sp, color = Ice.mutedText, modifier = Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { r ->
                    val sale = r.str("type") == "sale"
                    Surface(
                        Modifier.fillMaxWidth().clickable(enabled = sale) { nav.go(Screen.Invoice(r.str("id") ?: "")) },
                        shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (sale) "فاتورة" else "تحصيل", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(formatDate(r.str("created_at")) + if (r.bool("_pending") == true) " · بانتظار الرفع" else "", fontSize = 12.sp, color = Ice.mutedText)
                            }
                            Text(formatMoney(if (sale) r.num("total") else r.num("amount")), fontWeight = FontWeight.Bold, color = if (sale) Ice.foreground else Ice.success)
                        }
                    }
                }
                if (rows.isEmpty() && online) Empty("لا يوجد سجل")
            }
        }
    }
}

@Composable
fun NewCustomerScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(snap.regions.firstOrNull()?.str("id")) }
    var notes by remember { mutableStateOf("") }
    var position by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("عميل جديد")
        Field(name, { name = it }, "الاسم")
        Field(phone, { phone = it }, "الهاتف", ltr = true, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
        Picker("المنطقة", snap.regions.map { it.str("id") to (it.str("name") ?: "") }, region) { region = it }
        Field(notes, { notes = it }, "وصف الموقع")
        MidButton(if (position != null) "تم حفظ الموقع ✓" else if (locating) "جارٍ تحديد الموقع…" else "حفظ موقعي الحالي", Modifier.fillMaxWidth(), outlined = true, enabled = !locating) {
            locating = true
            scope.launchSafely {
                val fix = Locations.current(context, 6_000, 60_000)
                position = fix?.let { it.lat to it.lng }
                locating = false
                toast(if (fix != null) "تم تحديد الموقع" else "تعذّر تحديد الموقع")
            }
        }
        BigButton("حفظ العميل", enabled = !operation.busy) {
            operation.run { id ->
                val user = me
                if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
                if (snap.closingSent) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
                if (name.isBlank()) throw Notice("أدخل اسم العميل")
                repo.enqueue("customer", buildJsonObject {
                    put("id", id); put("name", name); put("phone", jstr(phone.ifEmpty { null })); put("region_id", jstr(region?.ifEmpty { null }))
                    put("location_notes", notes); put("lat", jnumOrNull(position?.first).asCoordinate(position?.first)); put("lng", jnumOrNull(position?.second).asCoordinate(position?.second))
                    put("truck_id", jstr(snap.trip?.str("truck_id"))); put("created_by", user.id)
                })
                toast("تمت إضافة العميل")
                nav.replace(Screen.Sale(id))
            }
        }
    }
}

/** Coordinates keep their decimals even when they happen to be whole numbers. */
private fun kotlinx.serialization.json.JsonElement.asCoordinate(value: Double?): kotlinx.serialization.json.JsonElement =
    if (value == null) this else JsonPrimitive(value)

fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch { runCatching { block() } }
}
