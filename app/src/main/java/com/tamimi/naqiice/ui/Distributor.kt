package com.tamimi.naqiice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.CustomerContact
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.adenTime
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.customerPage
import com.tamimi.naqiice.core.filterCustomers
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.jstr
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.core.yemenPhone
import com.tamimi.naqiice.data.Snapshot
import com.tamimi.naqiice.tracking.Locations
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
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
    val failed = snap.pending.filter { it.lastError != null && it.state != "set_aside" }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageTitle(truck?.str("name")?.let { "$it — الحملة" } ?: "الحملة", spacing = 20.dp)
        TrackingStatus(snap)
        if (toApprove.isNotEmpty()) location.prompt()
        if (trip == null) Empty("لا توجد حملة مفتوحة لك حالياً. انتظر المشرف لتعيين حملة وتحميل الشاحنة.")
        if (trip != null && toApprove.isNotEmpty()) {
            // space-y-2 rounded-2xl border-2 border-warning bg-warning/20 p-4
            Banner(Ice.warning.copy(alpha = 0.2f), borderColor = Ice.warning, radius = Radius.xl2, padding = 16.dp, borderWidth = 2.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (trip.str("loading_approved_at") != null) "تحميل إضافي بانتظار اعتمادك" else "التحميل بانتظار اعتمادك", style = T.base.bold)
                    toApprove.forEach { LineItem(snap.variant(it.str("variant_id"))?.str("name") ?: "", formatNum(it.num("qty")), style = T.sm, vertical = 0.dp) }
                    Text("تأكد من مطابقة الكميات في الشاحنة. بعد الاعتماد لا يمكن للمشرف تعديلها.", style = T.xs.muted)
                    Btn("اعتماد التحميل", {
                        operation.run {
                            val tripId = snap.tripId ?: throw Notice("لا توجد حملة للاعتماد")
                            if (location.required && location.check() == null) throw Notice("الموقع مطلوب قبل اعتماد التحميل")
                            repo.api.rpc("approve_trip_loads", buildJsonObject { put("_trip", tripId) }, repo.api.token())
                            toast("تم اعتماد التحميل")
                            repo.syncNow()
                        }
                    }, Modifier.fillMaxWidth(), enabled = !operation.busy && !location.blocked && online, height = 48.dp)
                }
            }
        }
        snap.closing?.let { closing ->
            val status = closing.str("status")
            Banner(if (status == "flagged") Ice.destructive.copy(alpha = 0.1f) else Ice.secondary) {
                Column {
                    Row {
                        Text("تم إرسال إغلاق الحملة — الحالة: ")
                        Text(when (status) { "approved" -> "معتمد"; "flagged" -> "يحتاج مراجعة"; else -> "بانتظار المشرف" }, style = T.sm.bold)
                    }
                    if (status == "flagged") {
                        closing.str("review_note")?.let { Text("ملاحظة المشرف: $it", Modifier.padding(top = 4.dp)) }
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().box(Radius.lg, Ice.primary, null).tap { nav.go(Screen.Closing) }.padding(8.dp), contentAlignment = Alignment.Center) {
                            Text("تصحيح وإعادة الإرسال", style = T.sm.bold.color(Ice.onPrimary))
                        }
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

        // grid grid-cols-3 gap-2
        val quick = listOf(
            Quick(Screen.Sale(), "فاتورة بيع", Lucide.ShoppingCart), Quick(Screen.Payment(), "تحصيل دين", Lucide.Wallet),
            Quick(Screen.FreezerAction(), "ثلاجة", Lucide.Snowflake), Quick(Screen.Expenses, "مصروف", Lucide.Receipt),
            Quick(Screen.Maintenance, "صيانة", Lucide.Wrench), Quick(Screen.Closing, "إغلاق الحملة", Lucide.ClipboardCheck),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { q ->
                        Column(
                            Modifier.weight(1f).box(Radius.xl2, shadow = true).tap { nav.go(q.screen) }.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(q.icon, null, Modifier.size(28.dp), tint = Ice.accent)
                            Text(q.label, style = T.sm.medium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        if (failed.isNotEmpty()) Banner(Ice.destructive.copy(alpha = 0.1f), borderColor = Ice.destructive.copy(alpha = 0.4f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.AlertTriangle, null, Modifier.size(20.dp), tint = Ice.destructive)
                Column {
                    Text("تعذّر رفع ${failed.size} سجل. سيُعاد المحاولة تلقائياً.")
                    Text(failed.first().lastError ?: "", style = T.xs.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (trip != null) Column {
            SectionTitle("مخزون الشاحنة")
            ListCard {
                snap.variants.forEachIndexed { index, v ->
                    val q = snap.stockOf(v.str("id") ?: "")
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(v.str("name") ?: "")
                        Text("${formatNum(q)} ${v.str("unit") ?: ""}", style = T.base.bold.color(if (q <= 0) Ice.destructive else Ice.foreground))
                    }
                }
            }
        }

        Column {
            SectionTitle("فواتير الحملة")
            if (day.sales.isEmpty()) Empty("لا توجد فواتير بعد.") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                day.sales.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().box(Radius.xl).tap { nav.go(Screen.Invoice(s.str("id") ?: "")) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(snap.customer(s.str("customer_id"))?.str("name") ?: "عميل", style = T.base.medium)
                            Row {
                                Text("${s.str("invoice_no") ?: ""} · ${formatDate(s.str("created_at"))} ", style = T.xs.muted)
                                if (s.bool("_pending") == true) Text("· بانتظار الرفع", style = T.xs.color(Ice.onWarning))
                            }
                        }
                        Text(formatMoney(s.num("total")), style = T.base.bold)
                    }
                }
            }
        }
    }
}

/** section.space-y-2.border-y.border-border.py-3.text-sm */
@Composable
private fun TrackingStatus(snap: Snapshot) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val me by repo.me.collectAsState()
    val state by repo.tracking.collectAsState()
    val wake by repo.keepAwake.collectAsState()
    val trip = snap.trip ?: return
    if (trip.str("status") != "open") return
    val s = state?.takeIf { it.owner == me?.id && it.trip == snap.tripId }
    val denied = me?.primaryRole == "distributor" && !Locations.granted(context)
    Column {
        Divider()
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when { denied -> "إذن الموقع مرفوض"; s?.status == "unavailable" -> "الموقع غير متاح"; s?.status == "active" -> "التتبع نشط"; else -> "بانتظار الموقع" },
                    style = T.sm.bold,
                )
                Text("آخر نقطة: ${adenTime(s?.last)}", style = T.sm)
            }
            if (denied) Text("فعّل إذن الموقع لهذا التطبيق من إعدادات الهاتف.", style = T.sm)
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("إبقاء الشاشة مضاءة", style = T.sm)
                Toggle(wake) { repo.keepAwake.value = it }
            }
        }
        Divider()
    }
}

// ---- more -----------------------------------------------------------------------------

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val snap by LocalRepo.current.snapshot.collectAsState()
    val items = listOfNotNull(
        Quick(Screen.Payment(), "تحصيل دين", Lucide.Wallet), Quick(Screen.NewCustomer, "إضافة عميل", Lucide.UserPlus), Quick(Screen.CustomerMap, "خريطة العملاء", Lucide.Map),
        Quick(Screen.FreezerAction(), "حركة ثلاجة", Lucide.Snowflake), Quick(Screen.Expenses, "المصروفات", Lucide.Receipt),
        Quick(Screen.Maintenance, "الصيانة", Lucide.Wrench), Quick(Screen.InvoiceChanges, "طلبات إلغاء وتعديل الفواتير", Lucide.FileX2),
        if (snap.boxSupport) Quick(Screen.Boxes, "الصناديق", Lucide.Boxes) else null,
        Quick(Screen.Closing, "إغلاق اليوم", Lucide.ClipboardCheck),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("المزيد")
        NavList(items.map { Triple(it.icon, it.label) { nav.go(it.screen) } })
    }
}

/** divide-y rounded-2xl border bg-card; rows `flex items-center gap-3 px-4 py-4` with a chevron. */
@Composable
fun NavList(items: List<Triple<ImageVector, String, () -> Unit>>) {
    ListCard {
        items.forEachIndexed { index, (icon, label, open) ->
            if (index > 0) Divider()
            Row(Modifier.fillMaxWidth().tap(onClick = open).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = Ice.accent)
                Text(label, Modifier.weight(1f), style = T.base.medium)
                Icon(Lucide.ChevronLeft, null, Modifier.size(20.dp), tint = Ice.mutedText)
            }
        }
    }
}

// ---- customers ------------------------------------------------------------------------

fun DataRow.toContact() = CustomerContact(str("id") ?: "", str("name") ?: "", str("phone"), str("region_id"), str("truck_id"), str("location_notes"))

/** Search box, region filter and count (`CustomerFilters` on the web). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomerFilters(query: String, onQuery: (String) -> Unit, regions: List<Pair<String, String>>, region: String, onRegion: (String) -> Unit, matched: Int, total: Int, extra: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Input(query, onQuery, placeholder = "ابحث بالاسم أو الهاتف", height = 48.dp, leading = {
            Icon(Lucide.Search, null, Modifier.padding(end = 8.dp).size(20.dp), tint = Ice.mutedText)
        })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("all" to "الكل") + regions).forEach { (id, name) ->
                Btn(name, { onRegion(id) }, variant = if (region == id) Variant.Default else Variant.Outline, height = 44.dp)
            }
        }
        extra?.invoke()
        Text("${formatNum(matched.toDouble())} مطابق من ${formatNum(total.toDouble())} عميل", style = T.sm.muted)
    }
}

/** nav.space-y-2.border-t.pt-3 with previous / next. */
@Composable
fun CustomerPagination(page: Int, pages: Int, start: Int, end: Int, matched: Int, onPage: (Int) -> Unit) {
    Column {
        Divider()
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${formatNum(start.toDouble())}–${formatNum(end.toDouble())} من ${formatNum(matched.toDouble())}", Modifier.fillMaxWidth(), style = T.sm.muted, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Btn({ onPage(page - 1) }, variant = Variant.Outline, enabled = page > 1, height = 44.dp) { Icon(Lucide.ChevronRight, null, Modifier.size(16.dp)); Text("السابق") }
                Text("${formatNum(page.toDouble())} / ${formatNum(pages.toDouble())}", style = T.sm)
                Btn({ onPage(page + 1) }, variant = Variant.Outline, enabled = page < pages, height = 44.dp) { Text("التالي"); Icon(Lucide.ChevronLeft, null, Modifier.size(16.dp)) }
            }
        }
    }
}

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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // p.mb-2: an 8px gap below the caption instead of the column's 12px
        Text("قائمة العملاء مشتركة بين جميع الموزعين.", style = T.xs.muted, modifier = Modifier.padding(bottom = 0.dp).layout { m, c ->
            val p = m.measure(c); layout(p.width, p.height - 4.dp.roundToPx()) { p.place(0, 0) }
        })
        CustomerFilters(
            query, { query = it; requested = 1 }, snap.regions.map { (it.str("id") ?: "") to (it.str("name") ?: "") },
            region, { region = it; requested = 1 }, filtered.size, snap.customers.size,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            page.rows.forEach { c ->
                val id = c.str("id") ?: ""
                val balance = snap.balanceOf(id)
                // Button outline: min-h-14 w-full justify-between gap-3 rounded-lg bg-card p-3
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).smallShadow(Radius.lg).box(Radius.lg, Ice.card, Ice.input).tap { onPick(id) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.str("name") ?: "", style = T.sm.medium)
                        Text("${snap.regionName(c.str("region_id"))} · ${c.str("phone") ?: "الهاتف غير متوفر"}", style = T.xs.muted)
                    }
                    if (balance > 0) Text(formatMoney(balance), style = T.sm.bold.color(Ice.destructive))
                }
            }
            if (filtered.isEmpty()) Text("لا يوجد عملاء مطابقون", Modifier.fillMaxWidth().padding(16.dp), style = T.sm.muted, textAlign = TextAlign.Center)
        }
        CustomerPagination(page.page, page.pages, page.start, page.end, filtered.size) { requested = it }
    }
}

@Composable
fun CustomersScreen() {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("العملاء") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineBtn("الخريطة", small = true) { nav.go(Screen.CustomerMap) }
                Btn("عميل جديد", { nav.go(Screen.NewCustomer) }, height = 32.dp, style = T.xs.medium, icon = Lucide.UserPlus)
            }
        }
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
    val balance = snap.ref?.rows("balances")?.firstOrNull { it.str("customer_id") == id }
    val freezers = snap.freezers.filter { it.str("customer_id") == id }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IceCard {
            Column {
                Text(customer.str("name") ?: "", style = T.xl.bold)
                Text(snap.regionName(customer.str("region_id")) + (customer.str("location_notes")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), style = T.sm.muted)
                yemenPhone(customer.str("phone"))?.let { phone ->
                    // dir=ltr: the icon sits to the left of the number
                    Row(
                        Modifier.padding(top = 8.dp).heightIn(min = 44.dp).tap { dial(context, phone) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(customer.str("phone") ?: "", style = T.sm.ltr.color(Ice.primary))
                        Icon(Lucide.Phone, null, Modifier.size(16.dp), tint = Ice.primary)
                    }
                }
                CustomerLocationLink(customer)
            }
        }
        StatGrid(listOf(
            Triple("الدين الحالي", formatMoney(snap.balanceOf(id)), Ice.destructive),
            Triple("حد الائتمان", if (customer.num("credit_limit") > 0) formatMoney(customer.num("credit_limit")) else "بدون", Ice.foreground),
            Triple("مبيعات الشهر", formatMoney(balance?.num("month_sales")), Ice.foreground),
            Triple("إجمالي المبيعات", formatMoney(balance?.num("total_sales")), Ice.foreground),
        ))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("بيع", { nav.go(Screen.Sale(id)) }, Modifier.weight(1f), height = 48.dp, icon = Lucide.ShoppingCart)
            Btn("تحصيل", { nav.go(Screen.Payment(id)) }, Modifier.weight(1f), Variant.Secondary, height = 48.dp, icon = Lucide.Wallet)
            Btn("ثلاجة", { nav.go(Screen.FreezerAction(id)) }, Modifier.weight(1f), Variant.Outline, height = 48.dp, icon = Lucide.Snowflake)
        }
        Column {
            SectionTitle("الثلاجات لدى العميل")
            if (freezers.isEmpty()) Empty("لا توجد ثلاجات") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                freezers.forEach { f ->
                    Row(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp)) {
                        Text(f.str("serial") ?: "", style = T.sm.bold.ltr)
                        Text(" · ${f.obj("freezer_models")?.str("name") ?: ""} " + (f.str("condition")?.let { "· $it" } ?: ""), style = T.sm)
                    }
                }
            }
        }
        CustomerBoxes(id)
        Column {
            SectionTitle("السجل")
            if (!online) Text("السجل الكامل يظهر عند توفر الإنترنت.", style = T.xs.muted, modifier = Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { r ->
                    val sale = r.str("type") == "sale"
                    Row(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(if (sale) "فاتورة ${r.str("invoice_no") ?: ""}" else "تحصيل", style = T.sm.medium)
                            Text(formatDate(r.str("created_at")) + if (r.bool("_pending") == true) " · بانتظار الرفع" else "", style = T.xs.muted)
                        }
                        Text(formatMoney(if (sale) r.num("total") else r.num("amount")), style = T.sm.bold.color(if (sale) Ice.foreground else Ice.success))
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
    val scope = rememberCoroutineScope()
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(snap.regions.firstOrNull()?.str("id")) }
    var notes by remember { mutableStateOf("") }
    var position by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("عميل جديد")
        Labeled("الاسم") { Input(name, { name = it }, height = 48.dp) }
        Labeled("الهاتف") { Input(phone, { phone = it }, height = 48.dp, ltr = true, keyboard = KeyboardType.Phone) }
        Labeled("المنطقة") { Select(snap.regions.map { it.str("id") to (it.str("name") ?: "") }, region) { region = it } }
        Labeled("وصف الموقع") { Input(notes, { notes = it }, height = 48.dp) }
        Btn(if (position != null) "تم حفظ الموقع ✓" else "حفظ موقعي الحالي", {
            scope.launch {
                val fix = runCatching { Locations.current(context, 6_000, 60_000) }.getOrNull()
                position = fix?.let { it.lat to it.lng }
                toast(if (fix != null) "تم تحديد الموقع" else "تعذّر تحديد الموقع")
            }
        }, Modifier.fillMaxWidth(), Variant.Outline, height = 48.dp, icon = Lucide.MapPin)
        BigButton("حفظ العميل", enabled = !operation.busy) {
            operation.run { id ->
                val user = me
                if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
                if (snap.closingSent) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
                if (name.isBlank()) throw Notice("أدخل اسم العميل")
                repo.enqueue("customer", buildJsonObject {
                    put("id", id); put("name", name); put("phone", jstr(phone.ifEmpty { null })); put("region_id", jstr(region?.ifEmpty { null }))
                    put("location_notes", notes)
                    put("lat", position?.let { JsonPrimitive(it.first) } ?: JsonNull); put("lng", position?.let { JsonPrimitive(it.second) } ?: JsonNull)
                    put("truck_id", jstr(snap.trip?.str("truck_id"))); put("created_by", user.id)
                })
                toast("تمت إضافة العميل")
                nav.replace(Screen.Sale(id))
            }
        }
    }
}

fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch { runCatching { block() } }
}
