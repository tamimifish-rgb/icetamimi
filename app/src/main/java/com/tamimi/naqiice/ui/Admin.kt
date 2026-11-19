package com.tamimi.naqiice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.jnumOrNull
import com.tamimi.naqiice.core.jstr
import com.tamimi.naqiice.core.normalizeUsername
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.Company
import com.tamimi.naqiice.data.ServerFn
import com.tamimi.naqiice.data.USERNAME_HINT
import com.tamimi.naqiice.data.enc
import com.tamimi.naqiice.data.staffFormError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import com.tamimi.naqiice.core.Row as DataRow

// Administrator screens (online only). Every rule is enforced by the server; these
// screens only read and call the same tables, RPCs and server functions as the web app.

// ---- dashboard ------------------------------------------------------------------------

private class Dashboard(
    val sales: Double, val invoices: Int, val cash: Double, val deposits: Double, val credit: Double, val collected: Double,
    val debt: Double, val customers: Int, val placed: Int, val freezers: Int, val pendingClosings: Int,
)

/** Midnight of the Aden business day read in the phone's own time zone, as the web app does. */
private fun dayStartIso(): String =
    isoNow(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse("${adenDay()}T00:00:00")!!.time)

@Composable
fun AdminHomeScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val loaded = rememberLoad(Unit) { bearer ->
        coroutineScope {
            val api = repo.api
            val since = "created_at=gte.${enc(dayStartIso())}"
            val sales = async { api.selectAll("sales", "select=total,cash_paid,credit_amount&$since&order=created_at,id", bearer) }
            val payments = async { api.selectAll("payments", "select=amount&$since&order=created_at,id", bearer) }
            val balances = async { api.selectAll("customer_balances", "select=balance&order=customer_id", bearer) }
            val freezers = async { api.selectAll("freezers", "select=status&order=id", bearer) }
            val customers = async { api.count("customers", "select=id", bearer) }
            val closings = async { api.count("closings", "select=id&status=eq.submitted", bearer) }
            // Deposits are an optional server feature: anything but a clear "1" means none.
            val supported = try {
                (api.rpc("pureice_deposit_support", JsonObject(emptyMap()), bearer) as? JsonPrimitive)?.contentOrNull == "1"
            } catch (e: ApiError) { false }
            val deposits = if (supported) api.selectAll("pureice_deposits", "select=kind,amount&$since&order=id", bearer) else emptyList()
            fun transfer(kind: String) = deposits.filter { it.str("kind") == kind }.sumOf { it.num("amount") }
            val s = sales.await()
            Dashboard(
                sales = s.sumOf { it.num("total") }, invoices = s.size, cash = s.sumOf { it.num("cash_paid") } - transfer("sale"),
                deposits = transfer("sale") + transfer("payment"), credit = s.sumOf { it.num("credit_amount") },
                collected = payments.await().sumOf { it.num("amount") } - transfer("payment"),
                debt = balances.await().sumOf { maxOf(0.0, it.num("balance")) }, customers = customers.await(),
                placed = freezers.await().count { it.str("status") == "placed" }, freezers = freezers.await().size,
                pendingClosings = closings.await(),
            )
        }
    }
    val d = loaded.value
    // div.space-y-4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("لوحة اليوم")
        Btn("بيانات المصنع والفاتورة", { nav.go(Screen.AdminCompany) }, variant = Variant.Outline, height = 44.dp)
        // Button outline: h-auto min-h-14 w-full justify-between bg-card px-4 py-3 text-base
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).smallShadow(Radius.md).box(Radius.md, Ice.card, Ice.input).clip(RoundedCornerShape(Radius.md))
                .tap { nav.go(Screen.AdminCustomers) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Lucide.Users, null, Modifier.size(16.dp))
            Text("العملاء", Modifier.weight(1f), style = T.base.medium)
            Icon(Lucide.ChevronLeft, null, Modifier.size(16.dp))
        }
        loaded.error?.let { Text(it, style = T.sm.color(Ice.destructive)) }
        // As on the web the tiles are there from the start; the figures fill in once loaded.
        StatGrid(listOf(
            Triple("مبيعات اليوم", formatMoney(d?.sales), Ice.foreground),
            Triple("عدد الفواتير", d?.invoices?.toString() ?: "—", Ice.foreground),
            Triple("نقد اليوم", formatMoney(d?.cash), Ice.success),
            Triple("إيداعات اليوم", formatMoney(d?.deposits), Ice.foreground),
            Triple("آجل اليوم", formatMoney(d?.credit), Ice.onWarning),
            Triple("تحصيل اليوم", formatMoney(d?.collected), Ice.foreground),
            Triple("إجمالي ديون العملاء", formatMoney(d?.debt), Ice.destructive),
            Triple("العملاء", d?.customers?.toString() ?: "—", Ice.foreground),
            Triple("ثلاجات لدى العملاء", if (d != null) "${d.placed} / ${d.freezers}" else "—", Ice.foreground),
        ))
        // a.block.rounded-xl.bg-warning/30.p-3.text-sm.font-medium
        if (d != null && d.pendingClosings > 0) Banner(Ice.warning.copy(alpha = 0.3f), Modifier.tap { nav.go(Screen.Closings) }) {
            Text("${d.pendingClosings} إغلاق بانتظار المراجعة ←", style = T.sm.medium)
        }
    }
}

// ---- settings menu --------------------------------------------------------------------

@Composable
fun AdminSetupScreen() {
    val nav = LocalNav.current
    val boxSupport = LocalRepo.current.snapshot.collectAsState().value.boxSupport
    val items = listOf(
        Triple(Lucide.Package, "المنتجات والأسعار", Screen.AdminProducts),
        Triple(Lucide.Store, "العملاء والأسعار الخاصة", Screen.AdminCustomers),
        Triple(Lucide.MapPinned, "المناطق", Screen.AdminRegions),
        Triple(Lucide.Truck, "الشاحنات", Screen.AdminTrucks),
        Triple(Lucide.Users, "الموظفون والصلاحيات", Screen.AdminUsers),
        Triple(Lucide.Snowflake, "الثلاجات", Screen.AdminFreezers),
        Triple(Lucide.ClipboardCheck, "إغلاقات اليوم", Screen.Closings),
    ) + if (boxSupport) listOf(Triple(Lucide.Boxes, "الصناديق", Screen.Boxes), Triple(Lucide.QrCode, "إعداد الصناديق", Screen.AdminBoxes)) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("الإعدادات")
        NavList(items.map { (icon, label, screen) -> Triple(icon, label) { nav.go(screen) } })
    }
}

// ---- simple reference tables ----------------------------------------------------------

private val TRUE = JsonPrimitive(true)
private val ZERO = JsonPrimitive(0)

@Composable
fun AdminProductsScreen() {
    var version by remember { mutableStateOf(0) }
    val products = rememberOptions("products", version = version)
    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Crud(
            "products", "المنتجات", order = "sort", defaults = mapOf("active" to TRUE, "sort" to ZERO), onSaved = { version++ },
            fields = listOf(
                CrudField("name", "الاسم", required = true), CrudField("sort", "الترتيب", CrudType.NUMBER), CrudField("active", "مفعّل", CrudType.BOOL),
            ),
        )
        Crud(
            "product_variants", "الأصناف والأسعار", order = "sort", defaults = mapOf("active" to TRUE, "sort" to ZERO, "unit" to JsonPrimitive("كيس")),
            fields = listOf(
                CrudField("name", "اسم الصنف", required = true),
                CrudField("product_id", "المنتج", CrudType.SELECT, products, required = true),
                CrudField("unit", "الوحدة", required = true),
                CrudField("price", "السعر الافتراضي", CrudType.NUMBER, required = true, format = { formatMoney(it.num("price")) }),
                CrudField("sort", "الترتيب", CrudType.NUMBER, hideInList = true),
                CrudField("active", "مفعّل", CrudType.BOOL),
            ),
        )
    }
}

@Composable
fun AdminRegionsScreen() {
    Crud(
        "regions", "المناطق", order = "sort", defaults = mapOf("active" to TRUE, "sort" to ZERO),
        fields = listOf(CrudField("name", "الاسم", required = true), CrudField("sort", "الترتيب", CrudType.NUMBER), CrudField("active", "مفعّلة", CrudType.BOOL)),
    )
}

@Composable
fun AdminTrucksScreen() {
    Column {
        Crud(
            "trucks", "الشاحنات", order = "code", defaults = mapOf("active" to TRUE),
            fields = listOf(
                CrudField("name", "الاسم", required = true), CrudField("code", "الرمز (للفواتير)", required = true),
                CrudField("plate", "رقم اللوحة"), CrudField("active", "مفعّلة", CrudType.BOOL),
            ),
        )
        // p.mt-3.text-xs.text-muted-foreground
        Text("لربط موزع بشاحنة افتح صفحة الموظفين.", Modifier.padding(top = 12.dp), style = T.xs.muted)
    }
}

// ---- customers and their special prices -----------------------------------------------

@Composable
fun AdminCustomersScreen() {
    val regions = rememberOptions("regions")
    val trucks = rememberOptions("trucks")
    Crud(
        "customers", "العملاء", customerList = true, defaults = mapOf("active" to TRUE, "credit_limit" to ZERO),
        fields = listOf(
            CrudField("name", "الاسم", required = true),
            CrudField("phone", "الهاتف", format = { it.str("phone")?.trim()?.ifEmpty { null } ?: "غير متوفر" }),
            CrudField("region_id", "المنطقة", CrudType.SELECT, regions),
            CrudField("truck_id", "الشاحنة", CrudType.SELECT, trucks, format = { row ->
                val truck = row.str("truck_id")
                if (truck.isNullOrEmpty()) "غير مسند لشاحنة" else trucks.firstOrNull { it.first == truck }?.second ?: "—"
            }),
            CrudField("credit_limit", "حد الائتمان", CrudType.NUMBER, format = { if (it.num("credit_limit") != 0.0) formatMoney(it.num("credit_limit")) else "بدون" }),
            CrudField("location_notes", "وصف الموقع", hideInList = true),
            CrudField("active", "نشط", CrudType.BOOL, hideInList = true),
        ),
        extra = { PriceEditor(it.str("id") ?: ""); CustomerLocationEditor(it) },
    )
}

@Composable
private fun PriceEditor(customerId: String) {
    val repo = LocalRepo.current
    val me by repo.me.collectAsState()
    val toast = rememberToast()
    val operation = rememberOperation()
    var open by remember(customerId) { mutableStateOf(false) }
    if (!open) {
        // Button link: mt-2 min-h-11 px-0 text-xs
        Btn({ open = true }, Modifier.padding(top = 8.dp), Variant.Link, height = 44.dp, style = T.xs.medium, padding = PaddingValues(0.dp)) { Text("أسعار خاصة لهذا العميل") }
        return
    }
    // Variant id → typed price; only variants with a saved or edited price are present.
    var values by remember(customerId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val loaded = rememberLoad(customerId) { bearer ->
        val variants = repo.api.select("product_variants", "select=id,name,price&active=eq.true&order=sort", bearer)
        val prices = repo.api.select("customer_prices", "select=*&customer_id=eq.$customerId", bearer)
        values = prices.associate { (it.str("variant_id") ?: "") to jnum(it.num("price")).content }
        variants
    }
    // div.mt-2.space-y-2.rounded-lg.bg-muted.p-2
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(Ice.muted, RoundedCornerShape(Radius.lg)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loaded.error?.let { Text(it, style = T.xs.color(Ice.destructive)) }
        val variants = loaded.value
        variants?.forEach { v ->
            val id = v.str("id") ?: ""
            // div.flex.items-center.gap-2.text-xs > span.flex-1 + Input.h-9.w-24
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RichText(buildAnnotatedString {
                    append("${v.str("name") ?: ""} ")
                    withStyle(SpanStyle(color = Ice.mutedText)) { append("(${formatMoney(v.num("price"))})") }
                }, Modifier.weight(1f), T.xs)
                Input(values[id] ?: "", { values = values + (id to it) }, Modifier.width(96.dp), "افتراضي", numeric = true)
            }
        }
        // Button size=sm: h-8 px-3 text-xs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val small = PaddingValues(horizontal = 12.dp)
            Btn({
                operation.run {
                    val bearer = repo.privilegedToken(me?.id)
                    val cleared = values.filterValues { it.isEmpty() }.keys
                    val rows = values.filterValues { it.isNotEmpty() }.map { (variant, price) ->
                        buildJsonObject {
                            put("customer_id", customerId); put("variant_id", variant)
                            put("price", jnumOrNull(parseNumber(price).takeIf { it.isFinite() }))
                        }
                    }
                    if (cleared.isNotEmpty()) repo.api.delete("customer_prices", "customer_id=eq.$customerId&variant_id=in.(${cleared.joinToString(",")})", bearer)
                    if (rows.isNotEmpty()) repo.api.upsert("customer_prices", JsonArray(rows), "customer_id,variant_id", bearer)
                    toast("تم حفظ الأسعار")
                    open = false
                    repo.refreshSoon()
                }
            }, enabled = variants != null && !operation.busy, height = 32.dp, style = T.xs.medium, padding = small) { Text("حفظ") }
            Btn({ open = false }, variant = Variant.Ghost, enabled = !operation.busy, height = 32.dp, style = T.xs.medium, padding = small) { Text("إلغاء") }
        }
    }
}

// ---- freezers -------------------------------------------------------------------------

private val FREEZER_STATUS = listOf("warehouse" to "في المستودع", "placed" to "لدى عميل", "maintenance" to "صيانة", "retired" to "خارج الخدمة")
private val FREEZER_EVENT = mapOf("place" to "تسليم", "move" to "نقل", "remove" to "سحب", "condition" to "حالة", "maintenance" to "صيانة")

@Composable
fun AdminFreezersScreen() {
    val repo = LocalRepo.current
    var version by remember { mutableStateOf(0) }
    val models = rememberOptions("freezer_models", version = version)
    val customers = rememberOptions("customers", all = true)
    // Supplementary lines under each freezer; the list still works when these fail to load.
    val balances = rememberLoad(Unit) { bearer ->
        repo.api.selectAll("customer_balances", "select=*&order=customer_id", bearer).associateBy { it.str("customer_id") }
    }.value ?: emptyMap()
    val events = rememberLoad(version) { bearer ->
        repo.api.select("freezer_events", "select=*&order=created_at.desc&limit=500", bearer).groupBy { it.str("freezer_id") }
    }.value ?: emptyMap()
    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Crud(
            "freezers", "الثلاجات", order = "serial", defaults = mapOf("status" to JsonPrimitive("warehouse")), onSaved = { version++ },
            fields = listOf(
                CrudField("serial", "الرقم التسلسلي", required = true),
                CrudField("model_id", "الموديل", CrudType.SELECT, models),
                CrudField("status", "الحالة", CrudType.SELECT, FREEZER_STATUS, required = true),
                CrudField("customer_id", "العميل", CrudType.SELECT, customers),
                CrudField("condition", "الحالة الفنية"),
                CrudField("notes", "ملاحظات", hideInList = true),
            ),
            extra = { r ->
                val balance = r.str("customer_id")?.let { balances[it] }
                val recent = events[r.str("id")]?.take(3) ?: emptyList()
                // div.mt-2.space-y-1.border-t.pt-2.text-xs.text-muted-foreground (there even when empty)
                Column(Modifier.padding(top = 8.dp)) {
                    Divider()
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (balance != null) Text("مبيعات العميل: الشهر ${formatMoney(balance.num("month_sales"))} · الإجمالي ${formatMoney(balance.num("total_sales"))}", style = T.xs.muted)
                        recent.forEach { e ->
                            val type = e.str("event_type") ?: ""
                            Text("${formatDate(e.str("created_at"))} — ${FREEZER_EVENT[type] ?: type} ${e.str("notes") ?: ""}", style = T.xs.muted)
                        }
                    }
                }
            },
        )
        Crud(
            "freezer_models", "موديلات الثلاجات", onSaved = { version++ },
            fields = listOf(CrudField("name", "الموديل", required = true), CrudField("capacity", "السعة")),
        )
    }
}

// ---- employees ------------------------------------------------------------------------

private val ROLE_LABEL = listOf("admin" to "مدير", "supervisor" to "مشرف", "distributor" to "موزع")

private data class StaffForm(
    val editing: String? = null, val fullName: String = "", val username: String = "", val pin: String = "",
    val role: String = "distributor", val truckId: String = "", val active: Boolean = true,
)

private class Staff(val profiles: List<DataRow>, val roles: List<DataRow>, val trucks: List<DataRow>)

@Composable
fun AdminUsersScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val me by repo.me.collectAsState()
    val toast = rememberToast()
    val operation = rememberOperation()
    var version by remember { mutableStateOf(0) }
    var form by remember { mutableStateOf<StaffForm?>(null) }
    var visible by remember { mutableStateOf(false) }
    val loaded = rememberLoad(version) { bearer ->
        Staff(
            repo.api.select("profiles", "select=*&order=created_at", bearer),
            repo.api.select("user_roles", "select=*", bearer),
            repo.api.select("trucks", "select=id,name&order=code", bearer),
        )
    }

    fun submit(f: StaffForm) = operation.run {
        staffFormError(f.fullName, f.username, f.pin, f.editing != null)?.let { throw Notice(it) }
        // Reads accept a cached token, but these actions revalidate the session on the server.
        val owner = me?.id
        val bearer = repo.privilegedToken(owner)
        if (f.editing != null) {
            repo.api.rpc("manage_staff", buildJsonObject {
                put("_id", f.editing); put("_name", f.fullName); put("_username", normalizeUsername(f.username)); put("_role", f.role)
                put("_truck", jstr(f.truckId.ifEmpty { null })); put("_active", f.active)
            }, bearer)
            if (f.pin.isNotEmpty()) {
                if (repo.api.currentUserId() != owner) throw Notice(ACCOUNT_SWITCHED)
                ServerFn.call(repo.http, "setStaffPin", linkedMapOf("id" to f.editing, "pin" to f.pin), repo.api.token())
            }
        } else {
            ServerFn.call(repo.http, "createEmployee", linkedMapOf("full_name" to f.fullName, "username" to f.username, "pin" to f.pin, "role" to f.role, "truck_id" to f.truckId.ifEmpty { null }), bearer)
        }
        toast(if (f.editing != null) "تم تحديث الموظف" else "تم إنشاء الحساب")
        form = null
        version++
        // In the app's own scope: a change to my own roles may close this screen mid-reload.
        repo.scope.launch { repo.reloadMe() }
    }

    // div.space-y-3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // div.flex.items-center > h1.flex-1.text-xl.font-bold + Button (h-9)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("الموظفون", Modifier.weight(1f), style = T.xl.bold)
            Btn("موظف جديد", { visible = false; form = StaffForm() }, icon = Lucide.UserPlus)
        }
        loaded.error?.let {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it, style = T.sm.color(Ice.destructive))
                Btn("إعادة المحاولة", { version++ }, variant = Variant.Outline, height = 44.dp)
            }
        }
        val staff = loaded.value
        if (staff == null) { if (loaded.error == null) Empty("جارٍ التحميل…") } else staff.profiles.forEach { p ->
            val id = p.str("id")
            val role = staff.roles.firstOrNull { it.str("user_id") == id }?.str("role") ?: ""
            // div.space-y-2.rounded-xl.border.bg-card.p-3
            Column(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // <b>name</b> <span class="text-xs text-muted-foreground break-all" dir="ltr">username</span>
                RichText(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.str("full_name")?.ifEmpty { null } ?: "بدون اسم") }
                    append(" ")
                    // An isolated left-to-right run that may break after any character.
                    withStyle(SpanStyle(fontSize = 12.sp, color = Ice.mutedText)) { append("\u2066" + (p.str("username") ?: "").toList().joinToString("\u200B") + "\u2069") }
                })
                // div.flex.items-center.justify-between.gap-2.text-sm
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (ROLE_LABEL.firstOrNull { it.first == role }?.second ?: "بدون صلاحية") + " · " + (if (p.bool("active") == true) "نشط" else "موقوف") +
                            (if (p.bool("pin_enabled") != true) " · يستخدم بيانات الدخول السابقة" else ""),
                        Modifier.weight(1f), style = T.sm,
                    )
                    if (id != null) Btn("الملف", { nav.go(Screen.StaffProfile(id)) }, variant = Variant.Outline, height = 44.dp)
                    Btn("تعديل" + if (id == me?.id) " حسابي" else "", {
                        visible = false
                        form = StaffForm(id, p.str("full_name") ?: "", p.str("username") ?: "", "", role.ifEmpty { "distributor" }, p.str("truck_id") ?: "", p.bool("active") == true)
                    }, variant = Variant.Outline, height = 44.dp)
                }
            }
        }
    }

    form?.let { f ->
        val formError = staffFormError(f.fullName, f.username, f.pin, f.editing != null)
        FormDialog(if (f.editing != null) "تعديل الموظف" else "حساب موظف جديد", { if (!operation.busy) form = null }) {
            // div.space-y-3 of div.space-y-1 (Label + Input h-9)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("الاسم") { Input(f.fullName, { form = f.copy(fullName = it) }) }
                Column {
                    FieldLabel("اسم المستخدم / رمز الموظف") {
                        Ltr { Input(f.username, { form = f.copy(username = it) }, placeholder = "ahmed-01", ltr = true, keyboard = KeyboardType.Ascii) }
                    }
                    Text(USERNAME_HINT, Modifier.padding(top = 4.dp), style = T.xs.muted)
                }
                FieldLabel(if (f.editing != null) "رمز جديد (اتركه فارغاً للحفاظ على الحالي)" else "رمز الدخول (٦ أرقام)") {
                    // div.flex.gap-2 > Input (h-9, at the top) + Button outline size-11
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Ltr(Modifier.weight(1f)) { Input(f.pin, { form = f.copy(pin = it) }, ltr = true, password = !visible, keyboard = KeyboardType.NumberPassword) }
                        Btn({ visible = !visible }, Modifier.size(44.dp), Variant.Outline, height = 44.dp, padding = PaddingValues(0.dp)) {
                            Icon(if (visible) Lucide.EyeOff else Lucide.Eye, if (visible) "إخفاء الرمز" else "إظهار الرمز", Modifier.size(16.dp))
                        }
                    }
                }
                // select.h-10.w-full.rounded-md.border.border-input.bg-background.px-2
                WebSelect(ROLE_LABEL, f.role, height = 40.dp, padding = 8.dp) { form = f.copy(role = it) }
                WebSelect(
                    listOf("" to "بدون شاحنة") + (loaded.value?.trucks ?: emptyList()).map { (it.str("id") ?: "") to (it.str("name") ?: "") },
                    f.truckId, height = 40.dp, padding = 8.dp,
                ) { form = f.copy(truckId = it) }
                // Label.flex.min-h-11.items-center.gap-2 > input[type=checkbox]
                if (f.editing != null) Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).tap { form = f.copy(active = !f.active) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WebCheckbox(f.active)
                    Text("الحساب نشط", style = T.sm.medium.copy(lineHeight = 14.sp))
                }
                if (formError != null && (f.fullName.isNotEmpty() || f.username.isNotEmpty() || f.pin.isNotEmpty())) Text(formError, style = T.sm.color(Ice.destructive))
                Btn(if (operation.busy) "جارٍ الحفظ…" else "حفظ", { submit(f) }, Modifier.fillMaxWidth(), enabled = !operation.busy, height = 44.dp)
            }
        }
    }
}

/** `dir="ltr"` on an input: the field itself is laid out left to right. */
@Composable
private fun Ltr(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content) }
}

// ---- factory details printed on invoices ----------------------------------------------

@Composable
fun AdminCompanyScreen() {
    val repo = LocalRepo.current
    val me by repo.me.collectAsState()
    val toast = rememberToast()
    val operation = rememberOperation()
    val cached = remember { repo.company.value }
    var form by remember { mutableStateOf(cached) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { repo.api.maybeSingle("pureice_company_settings", "select=name,address,phone,footer_note&id=eq.true", repo.api.token()) }
            .onSuccess { row ->
                val fresh = if (row == null) Company.DEFAULT else Company(
                    row.str("name") ?: Company.DEFAULT.name, row.str("address") ?: Company.DEFAULT.address, row.str("phone") ?: "", row.str("footer_note") ?: "",
                )
                // Never replace what the admin has already started typing.
                if (form == cached) form = fresh
            }
            // The settings table is an optional server addition; without it the cached values stay.
            .onFailure { if (it !is CancellationException && (it as? ApiError)?.code !in listOf("PGRST205", "42P01")) failed = true }
    }
    // div.space-y-4 of label.block.space-y-2.text-sm (span + Input h-9) and Button.min-h-12.w-full
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("بيانات المصنع")
        if (failed) Text("تعذّر تحميل البيانات", style = T.sm.color(Ice.destructive))
        Column { Text("اسم المصنع", style = T.sm); Input(form.name, { form = form.copy(name = it) }) }
        Column { Text("العنوان", style = T.sm); Input(form.address, { form = form.copy(address = it) }) }
        Column { Text("الهاتف (اختياري)", style = T.sm); Input(form.phone, { form = form.copy(phone = it) }) }
        Column { Text("ملاحظة أسفل الفاتورة (اختيارية)", style = T.sm); Input(form.footerNote, { form = form.copy(footerNote = it) }) }
        Btn("حفظ", {
            operation.run {
                val bearer = repo.privilegedToken(me?.id)
                try {
                    repo.api.upsert("pureice_company_settings", buildJsonObject {
                        put("id", true); put("name", form.name); put("address", form.address); put("phone", form.phone)
                        put("footer_note", form.footerNote); put("updated_at", isoNow())
                    }, "id", bearer)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    throw Notice("تعذّر حفظ بيانات المصنع؛ تحقق من الاتصال وتفعيل إعدادات المصنع في قاعدة البيانات")
                }
                toast("تم حفظ بيانات المصنع")
                repo.refreshSoon()
            }
        }, Modifier.fillMaxWidth(), enabled = !operation.busy && form.name.isNotBlank(), height = 48.dp)
    }
}
