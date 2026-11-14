package com.tamimi.naqiice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("لوحة اليوم")
        MidButton("بيانات المصنع والفاتورة", outlined = true) { nav.go(Screen.AdminCompany) }
        NavCard { NavRow(Icons.Filled.Group, "العملاء") { nav.go(Screen.AdminCustomers) } }
        loaded.error?.let { Text(it, fontSize = 14.sp, color = Ice.destructive) }
        val d = loaded.value
        if (d == null) { if (loaded.error == null) Empty("جارٍ التحميل…") } else {
            StatGrid(listOf(
                Triple("مبيعات اليوم", formatMoney(d.sales), Ice.foreground),
                Triple("عدد الفواتير", d.invoices.toString(), Ice.foreground),
                Triple("نقد اليوم", formatMoney(d.cash), Ice.success),
                Triple("إيداعات اليوم", formatMoney(d.deposits), Ice.foreground),
                Triple("آجل اليوم", formatMoney(d.credit), Ice.warning),
                Triple("تحصيل اليوم", formatMoney(d.collected), Ice.foreground),
                Triple("إجمالي ديون العملاء", formatMoney(d.debt), Ice.destructive),
                Triple("العملاء", d.customers.toString(), Ice.foreground),
                Triple("ثلاجات لدى العملاء", "${d.placed} / ${d.freezers}", Ice.foreground),
            ))
            if (d.pendingClosings > 0) Banner(Ice.warning.copy(alpha = 0.3f), Modifier.clickable { nav.go(Screen.Closings) }) {
                Text("${d.pendingClosings} إغلاق بانتظار المراجعة ←", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---- settings menu --------------------------------------------------------------------

@Composable
private fun NavCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) { Column { content() } }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Ice.accent)
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Icon(Icons.Filled.ChevronLeft, null, tint = Ice.mutedText)
    }
}

@Composable
fun AdminSetupScreen() {
    val nav = LocalNav.current
    val items = listOf(
        Triple(Screen.AdminProducts, "المنتجات والأسعار", Icons.Filled.Inventory2),
        Triple(Screen.AdminCustomers, "العملاء والأسعار الخاصة", Icons.Filled.Store),
        Triple(Screen.AdminRegions, "المناطق", Icons.Filled.Map),
        Triple(Screen.AdminTrucks, "الشاحنات", Icons.Filled.LocalShipping),
        Triple(Screen.AdminUsers, "الموظفون والصلاحيات", Icons.Filled.Group),
        Triple(Screen.AdminFreezers, "الثلاجات", Icons.Filled.AcUnit),
        Triple(Screen.Closings, "إغلاقات اليوم", Icons.Filled.FactCheck),
    )
    Column {
        PageTitle("الإعدادات")
        NavCard {
            items.forEachIndexed { index, (screen, label, icon) ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ice.border))
                NavRow(icon, label) { nav.go(screen) }
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Crud(
            "trucks", "الشاحنات", order = "code", defaults = mapOf("active" to TRUE),
            fields = listOf(
                CrudField("name", "الاسم", required = true), CrudField("code", "الرمز (للفواتير)", required = true),
                CrudField("plate", "رقم اللوحة"), CrudField("active", "مفعّلة", CrudType.BOOL),
            ),
        )
        Text("لربط موزع بشاحنة افتح صفحة الموظفين.", fontSize = 12.sp, color = Ice.mutedText)
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
        extra = { PriceEditor(it.str("id") ?: "") },
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
        TextButton({ open = true }) { Text("أسعار خاصة لهذا العميل", fontSize = 12.sp) }
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
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(Ice.muted, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loaded.error?.let { Text(it, fontSize = 12.sp, color = Ice.destructive) }
        val variants = loaded.value
        if (variants == null) { if (loaded.error == null) Text("جارٍ التحميل…", fontSize = 12.sp, color = Ice.mutedText) }
        else variants.forEach { v ->
            val id = v.str("id") ?: ""
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${v.str("name") ?: ""} (${formatMoney(v.num("price"))})", Modifier.weight(1f), fontSize = 12.sp)
                Field(values[id] ?: "", { values = values + (id to it) }, "افتراضي", Modifier.width(120.dp), numeric = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MidButton("حفظ", enabled = variants != null && !operation.busy) {
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
            }
            MidButton("إلغاء", outlined = true, enabled = !operation.busy) { open = false }
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
                if (balance != null || recent.isNotEmpty()) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Ice.border))
                    if (balance != null) Text("مبيعات العميل: الشهر ${formatMoney(balance.num("month_sales"))} · الإجمالي ${formatMoney(balance.num("total_sales"))}", fontSize = 12.sp, color = Ice.mutedText)
                    recent.forEach { e ->
                        val type = e.str("event_type") ?: ""
                        Text("${formatDate(e.str("created_at"))} — ${FREEZER_EVENT[type] ?: type} ${e.str("notes") ?: ""}", fontSize = 12.sp, color = Ice.mutedText)
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("الموظفون") { MidButton("موظف جديد") { visible = false; form = StaffForm() } }
        loaded.error?.let {
            Text(it, fontSize = 14.sp, color = Ice.destructive)
            MidButton("إعادة المحاولة", outlined = true) { version++ }
        }
        val staff = loaded.value
        if (staff == null) { if (loaded.error == null) Empty("جارٍ التحميل…") } else staff.profiles.forEach { p ->
            val id = p.str("id")
            val role = staff.roles.firstOrNull { it.str("user_id") == id }?.str("role") ?: ""
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.str("full_name")?.ifEmpty { null } ?: "بدون اسم", fontWeight = FontWeight.Bold)
                        Text(p.str("username") ?: "", fontSize = 12.sp, color = Ice.mutedText, style = TextStyle(textDirection = TextDirection.Ltr))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            (ROLE_LABEL.firstOrNull { it.first == role }?.second ?: "بدون صلاحية") + " · " + (if (p.bool("active") == true) "نشط" else "موقوف") +
                                (if (p.bool("pin_enabled") != true) " · يستخدم بيانات الدخول السابقة" else ""),
                            Modifier.weight(1f), fontSize = 14.sp,
                        )
                        MidButton("تعديل" + if (id == me?.id) " حسابي" else "", outlined = true) {
                            visible = false
                            form = StaffForm(id, p.str("full_name") ?: "", p.str("username") ?: "", "", role.ifEmpty { "distributor" }, p.str("truck_id") ?: "", p.bool("active") == true)
                        }
                    }
                }
            }
        }
    }

    form?.let { f ->
        val formError = staffFormError(f.fullName, f.username, f.pin, f.editing != null)
        AlertDialog(
            onDismissRequest = { if (!operation.busy) form = null },
            title = { Text(if (f.editing != null) "تعديل الموظف" else "حساب موظف جديد", fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(f.fullName, { form = f.copy(fullName = it) }, "الاسم")
                    Field(f.username, { form = f.copy(username = it) }, "اسم المستخدم / رمز الموظف", ltr = true, keyboard = KeyboardType.Ascii)
                    Text(USERNAME_HINT, fontSize = 12.sp, color = Ice.mutedText)
                    OutlinedTextField(
                        value = f.pin, onValueChange = { form = f.copy(pin = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        label = { Text(if (f.editing != null) "رمز جديد (اتركه فارغاً للحفاظ على الحالي)" else "رمز الدخول (٦ أرقام)") },
                        textStyle = TextStyle(fontSize = 16.sp, textDirection = TextDirection.Ltr),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        trailingIcon = {
                            IconButton({ visible = !visible }) {
                                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "إخفاء الرمز" else "إظهار الرمز")
                            }
                        },
                    )
                    Picker("الصلاحية", ROLE_LABEL, f.role) { form = f.copy(role = it) }
                    Picker("الشاحنة", listOf("" to "بدون شاحنة") + (loaded.value?.trucks ?: emptyList()).map { (it.str("id") ?: "") to (it.str("name") ?: "") }, f.truckId) { form = f.copy(truckId = it) }
                    if (f.editing != null) Row(Modifier.fillMaxWidth().clickable { form = f.copy(active = !f.active) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.active, { form = f.copy(active = it) })
                        Text("الحساب نشط")
                    }
                    if (formError != null && (f.fullName.isNotEmpty() || f.username.isNotEmpty() || f.pin.isNotEmpty())) Text(formError, fontSize = 14.sp, color = Ice.destructive)
                }
            },
            confirmButton = { TextButton({ submit(f) }, enabled = !operation.busy) { Text(if (operation.busy) "جارٍ الحفظ…" else "حفظ") } },
            dismissButton = { TextButton({ form = null }, enabled = !operation.busy) { Text("إلغاء") } },
        )
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("بيانات المصنع")
        if (failed) Text("تعذّر تحميل البيانات", fontSize = 14.sp, color = Ice.destructive)
        Field(form.name, { form = form.copy(name = it) }, "اسم المصنع")
        Field(form.address, { form = form.copy(address = it) }, "العنوان")
        Field(form.phone, { form = form.copy(phone = it) }, "الهاتف (اختياري)")
        Field(form.footerNote, { form = form.copy(footerNote = it) }, "ملاحظة أسفل الفاتورة (اختيارية)")
        BigButton("حفظ", enabled = !operation.busy && form.name.isNotBlank()) {
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
        }
    }
}
