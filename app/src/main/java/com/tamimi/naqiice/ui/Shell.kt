package com.tamimi.naqiice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.R
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.Me
import com.tamimi.naqiice.data.OutboxOp
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.launch

// ---- sign-in --------------------------------------------------------------------------

@Composable
fun LoginScreen() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val notice by repo.notice.collectAsState()
    var username by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Ice.gradient).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(110.dp))
            Text("ثلج نقي", color = Ice.onPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("نظام إدارة التوزيع — المكلا", color = Ice.onPrimary.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Ice.card) {
            Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("تسجيل الدخول", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                notice?.let { Banner(Ice.warning.copy(alpha = 0.25f)) { Text(it, fontSize = 14.sp) } }
                Field(username, { username = it }, if (legacy) "اسم المستخدم أو البريد السابق" else "اسم المستخدم / رمز الموظف", ltr = true, keyboard = KeyboardType.Email)
                OutlinedTextField(
                    value = credential, onValueChange = { credential = it },
                    label = { Text(if (legacy) "كلمة المرور الحالية" else "رمز الدخول (٦ أرقام)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontSize = 18.sp, textDirection = TextDirection.Ltr),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (legacy) KeyboardType.Password else KeyboardType.NumberPassword),
                    trailingIcon = {
                        IconButton({ visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "إخفاء الرمز" else "إظهار الرمز") }
                    },
                )
                error?.let { Text(it, color = Ice.destructive, fontSize = 14.sp) }
                BigButton(if (busy) "جارٍ الدخول…" else "دخول", enabled = !busy && username.isNotBlank() && credential.isNotEmpty()) {
                    busy = true; error = null
                    scope.launch {
                        try { repo.signIn(username, credential) } catch (e: Exception) { error = e.userMessage("تعذّر تسجيل الدخول") } finally { busy = false }
                    }
                }
                TextButton({ toast("نسيت رمز الدخول؟ تواصل مع المدير لإعادة تعيينه؛ لا تحتاج بريداً إلكترونياً.") }, Modifier.fillMaxWidth()) { Text("نسيت رمز الدخول؟") }
                TextButton({ legacy = !legacy }, Modifier.fillMaxWidth()) { Text(if (legacy) "الدخول برمز الموظف" else "لدي حساب سابق") }
            }
        }
    }
}

@Composable
fun PendingScreen() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    IceCard(Modifier.padding(top = 32.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("حسابك بانتظار التفعيل", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("اطلب من المدير تحديد دورك (موزع أو مشرف) وربطك بشاحنة.", fontSize = 14.sp, color = Ice.mutedText, textAlign = TextAlign.Center)
            MidButton("تحقق مرة أخرى") { scope.launch { repo.reloadMe() } }
        }
    }
}

// ---- shell ----------------------------------------------------------------------------

private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

private fun navItems(role: String?): List<NavItem> = when (role) {
    "distributor" -> listOf(
        NavItem(Screen.Today, "اليوم", Icons.Filled.Home), NavItem(Screen.Customers, "العملاء", Icons.Filled.Group),
        NavItem(Screen.Sale(), "بيع", Icons.Filled.ShoppingCart), NavItem(Screen.More, "المزيد", Icons.Filled.Menu),
    )
    "supervisor" -> listOf(
        NavItem(Screen.Trips, "الشاحنات", Icons.Filled.LocalShipping), NavItem(Screen.Closings, "الإغلاقات", Icons.Filled.FactCheck),
        NavItem(Screen.Reports, "التقارير", Icons.Filled.Assessment), NavItem(Screen.RouteMap, "الخريطة", Icons.Filled.Map),
        NavItem(Screen.Customers, "العملاء", Icons.Filled.Group),
    )
    "admin" -> listOf(
        NavItem(Screen.Admin, "الرئيسية", Icons.Filled.Home), NavItem(Screen.Trips, "الشاحنات", Icons.Filled.LocalShipping),
        NavItem(Screen.Reports, "التقارير", Icons.Filled.Assessment), NavItem(Screen.RouteMap, "الخريطة", Icons.Filled.Map),
        NavItem(Screen.AdminSetup, "الإعدادات", Icons.Filled.Settings),
    )
    else -> emptyList()
}

@Composable
fun AppShell(me: Me) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf<Int?>(null) }
    val items = navItems(me.primaryRole)

    Column(Modifier.fillMaxSize().background(Ice.background)) {
        Row(
            Modifier.fillMaxWidth().background(Ice.gradient).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("ثلج نقي", color = Ice.onPrimary, fontWeight = FontWeight.Bold)
                Text(me.fullName.ifBlank { me.email ?: "" }, color = Ice.onPrimary.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SyncButton()
            IconButton({
                val waiting = repo.pendingCount()
                if (waiting > 0) confirmSignOut = waiting else scope.launch { repo.signOut() }
            }) { Icon(Icons.AutoMirrored.Filled.Logout, "تسجيل الخروج", tint = Ice.onPrimary) }
        }
        if (me.isManager) InvoiceChangeNotice()
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            // Each screen keeps its own form state while it is on top of the stack.
            androidx.compose.runtime.key(nav.current, nav.stack.size) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp)) {
                    ScreenContent(nav.current, me)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (items.isEmpty()) Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        if (items.isNotEmpty()) {
            HorizontalDivider(color = Ice.border)
            Row(Modifier.fillMaxWidth().background(Ice.card).windowInsetsPadding(WindowInsets.navigationBars)) {
                items.forEach { item ->
                    val active = nav.stack.first()::class == item.screen::class || nav.current::class == item.screen::class
                    Column(
                        Modifier.weight(1f).clickable { nav.home(item.screen) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, null, tint = if (active) Ice.accent else Ice.mutedText, modifier = Modifier.size(24.dp))
                        Text(item.label, fontSize = 12.sp, color = if (active) Ice.primary else Ice.mutedText, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }

    confirmSignOut?.let { count ->
        AlertDialog(
            onDismissRequest = { confirmSignOut = null },
            title = { Text("تسجيل الخروج؟") },
            text = { Text("يوجد $count سجل محفوظ على الهاتف لم تكتمل مزامنته. سيبقى محفوظاً لهذا الحساب.") },
            confirmButton = { TextButton({ confirmSignOut = null; scope.launch { repo.signOut() } }) { Text("تسجيل الخروج") } },
            dismissButton = { TextButton({ confirmSignOut = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun ScreenContent(screen: Screen, me: Me) {
    val fieldRoles = me.isDistributor || me.isManager
    when (screen) {
        Screen.Pending -> PendingScreen()
        Screen.Today -> Guard(fieldRoles) { TodayScreen() }
        Screen.Customers -> CustomersScreen()
        Screen.NewCustomer -> NewCustomerScreen()
        is Screen.Customer -> CustomerDetailScreen(screen.id)
        is Screen.Sale -> Guard(fieldRoles) { SaleScreen(screen.customer) }
        is Screen.Payment -> PaymentScreen(screen.customer)
        is Screen.FreezerAction -> FreezerActionScreen(screen.customer)
        Screen.Expenses -> ExpensesScreen()
        Screen.Maintenance -> MaintenanceScreen()
        Screen.Closing -> ClosingScreen()
        Screen.More -> MoreScreen()
        is Screen.Invoice -> InvoiceScreen(screen.id)
        Screen.InvoiceChanges -> InvoiceChangesScreen()
        is Screen.InvoiceChange -> InvoiceChangeScreen(screen.id, screen.kind)
        Screen.Trips -> Guard(me.isManager) { TripsScreen() }
        is Screen.Trip -> Guard(me.isManager) { TripScreen(screen.id) }
        Screen.Closings -> Guard(me.isManager) { ClosingsScreen() }
        Screen.Reports -> Guard(me.isManager) { ReportsScreen() }
        Screen.RouteMap -> Guard(me.isManager) { RouteMapScreen() }
        Screen.Admin -> Guard(me.isAdmin) { AdminHomeScreen() }
        Screen.AdminSetup -> Guard(me.isAdmin) { AdminSetupScreen() }
        Screen.AdminProducts -> Guard(me.isAdmin) { AdminProductsScreen() }
        Screen.AdminRegions -> Guard(me.isAdmin) { AdminRegionsScreen() }
        Screen.AdminTrucks -> Guard(me.isAdmin) { AdminTrucksScreen() }
        Screen.AdminCustomers -> Guard(me.isAdmin) { AdminCustomersScreen() }
        Screen.AdminFreezers -> Guard(me.isAdmin) { AdminFreezersScreen() }
        Screen.AdminUsers -> Guard(me.isAdmin) { AdminUsersScreen() }
        Screen.AdminCompany -> Guard(me.isAdmin) { AdminCompanyScreen() }
    }
}

@Composable
private fun Guard(allowed: Boolean, content: @Composable () -> Unit) {
    if (allowed) content() else Empty("ليست لديك صلاحية لهذه الصفحة.")
}

// ---- sync status ----------------------------------------------------------------------

private val KIND_LABELS = mapOf(
    "sale" to "فاتورة", "payment" to "تحصيل", "expense" to "مصروف", "closing" to "إغلاق",
    "customer" to "عميل", "maintenance" to "صيانة", "freezer_event" to "ثلاجة", "gps" to "موقع",
)

@Composable
private fun SyncButton() {
    val repo = LocalRepo.current
    val pending by repo.pending.collectAsState()
    val online by repo.online.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val lastError by repo.lastError.collectAsState()
    var open by remember { mutableStateOf(false) }
    val active = pending.filter { it.state != "set_aside" }
    val label = when {
        pending.any { it.state == "review" } -> "طلب يحتاج مراجعة"
        lastError != null || pending.any { it.lastError != null && it.state != "set_aside" } -> "تعذّرت المزامنة"
        !online -> "بدون إنترنت"
        syncing -> "مزامنة…"
        else -> "متصل"
    }
    val failing = label == "تعذّرت المزامنة" || label == "طلب يحتاج مراجعة"
    Row(
        Modifier.background(if (!online) Ice.warning else if (failing) Ice.destructive else Ice.onPrimary.copy(alpha = 0.15f), RoundedCornerShape(50))
            .clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tint = if (!online) Ice.onWarning else Ice.onPrimary
        Icon(if (syncing) Icons.Filled.Sync else if (online) Icons.Filled.Cloud else Icons.Filled.CloudOff, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label + if (active.isNotEmpty()) " (${active.size})" else "", color = tint, fontSize = 12.sp)
    }
    if (open) SyncDialog(pending, online, syncing, lastError) { open = false }
}

@Composable
private fun SyncDialog(pending: List<OutboxOp>, online: Boolean, syncing: Boolean, lastError: String?, close: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmAside by remember { mutableStateOf<OutboxOp?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("حالة المزامنة") },
        confirmButton = { TextButton(close) { Text("إغلاق") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lastError?.let { Text(it, color = Ice.destructive, fontSize = 14.sp) }
                MidButton("مزامنة الآن", enabled = !syncing && online) { scope.launch { repo.syncNow() } }
                // Route points are many and need no review; show them as one line.
                val points = pending.count { it.kind == "gps" && it.lastError == null }
                val shown = pending.filter { it.kind != "gps" || it.lastError != null }
                if (pending.isEmpty()) Text("لا توجد سجلات بانتظار المزامنة")
                if (points > 0) Text("$points نقطة موقع محفوظة بانتظار الرفع", fontSize = 14.sp, color = Ice.mutedText)
                shown.forEach { op ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(KIND_LABELS[op.kind] ?: op.kind, fontWeight = FontWeight.Bold)
                            Text(
                                when (op.state) {
                                    "review" -> "لم يُرفع — يحتاج مراجعة"
                                    "set_aside" -> "مُنحّى عن المزامنة — محفوظ للمراجعة"
                                    "reconciled" -> "طلب زائد مطابق؛ بانتظار الأرشفة المحلية"
                                    "acknowledged" -> "تم الرفع؛ بانتظار تأكيد النسخة المحلية"
                                    else -> "محفوظ على الهاتف"
                                },
                                fontSize = 13.sp,
                            )
                        }
                        Text(op.payload.str("invoice_no") ?: op.id, fontSize = 11.sp, color = Ice.mutedText, style = TextStyle(textDirection = TextDirection.Ltr))
                        op.lastError?.let { Text(it, color = Ice.destructive, fontSize = 14.sp) }
                        if (op.lastError != null && op.state != "review" && op.state != "set_aside") {
                            MidButton("إعادة المحاولة", outlined = true, enabled = !syncing && online) { scope.launch { repo.retry(op) } }
                        }
                        if (op.state == "review") MidButton("تنحية هذا الطلب مع الاحتفاظ به", outlined = true, enabled = !syncing) { confirmAside = op }
                        if (op.state in listOf("review", "set_aside", "reconciled")) {
                            TextButton({ selected = if (selected == op.id) null else op.id }) { Text("عرض بيانات الطلب") }
                            if (selected == op.id) Text(op.payload.toString(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, style = TextStyle(textDirection = TextDirection.Ltr))
                        }
                        if (op.lastError != null) Text("السجل محفوظ ولم يُحذف. راجع المدير عند رفض المخزون أو السعر أو الحملة.", fontSize = 12.sp, color = Ice.mutedText)
                        HorizontalDivider(color = Ice.border)
                    }
                }
            }
        },
    )
    confirmAside?.let { op ->
        AlertDialog(
            onDismissRequest = { confirmAside = null },
            text = { Text("تنحية هذا الطلب وحده عن المزامنة؟ لن يُعتبر مرفوعاً ولن تُحذف بياناته.") },
            confirmButton = { TextButton({ confirmAside = null; scope.launch { runCatching { repo.setAside(op) } } }) { Text("تنحية") } },
            dismissButton = { TextButton({ confirmAside = null }) { Text("إلغاء") } },
        )
    }
}
