package com.tamimi.naqiice.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tamimi.naqiice.R
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.Me
import com.tamimi.naqiice.data.OutboxOp
import com.tamimi.naqiice.data.canSetAside
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

    Column(Modifier.fillMaxSize().background(Ice.gradient).imePadding().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(120.dp))
            Text("ثلج نقي", style = T.xl3.bold.color(Ice.onPrimary))
            Text("نظام إدارة التوزيع — المكلا", style = T.sm.color(Ice.onPrimary.copy(alpha = 0.8f)))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 600.dp).softShadow(Radius.xl3).background(Ice.card, RoundedCornerShape(topStart = Radius.xl3, topEnd = Radius.xl3))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("تسجيل الدخول", style = T.lg.bold)
            notice?.let { Banner(Ice.warning.copy(alpha = 0.3f)) { Text(it) } }
            Labeled(if (legacy) "اسم المستخدم أو البريد السابق" else "اسم المستخدم / رمز الموظف") {
                Input(username, { username = it }, height = 48.dp, ltr = true, keyboard = KeyboardType.Email)
            }
            Labeled(if (legacy) "كلمة المرور الحالية" else "رمز الدخول (٦ أرقام)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Input(
                        credential, { credential = it }, Modifier.weight(1f), height = 48.dp, ltr = true, password = !visible,
                        keyboard = if (legacy) KeyboardType.Password else KeyboardType.NumberPassword,
                    )
                    Btn({ visible = !visible }, Modifier.size(48.dp), Variant.Outline, height = 48.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(if (visible) Lucide.EyeOff else Lucide.Eye, if (visible) "إخفاء الرمز" else "إظهار الرمز", Modifier.size(16.dp))
                    }
                }
            }
            Btn(if (busy) "جارٍ الدخول…" else "دخول", {
                if (username.isBlank() || credential.isEmpty()) toast("أدخل اسم المستخدم ورمز الدخول") else {
                    busy = true
                    scope.launch {
                        try { repo.signIn(username, credential) } catch (e: Exception) { toast(e.userMessage("تعذّر تسجيل الدخول")) } finally { busy = false }
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy, height = 48.dp, style = T.base.medium)
            Btn("نسيت رمز الدخول؟", { toast("نسيت رمز الدخول؟ تواصل مع المدير لإعادة تعيينه؛ لا تحتاج بريداً إلكترونياً.") }, Modifier.fillMaxWidth(), Variant.Ghost, height = 44.dp)
            Btn(if (legacy) "الدخول برمز الموظف" else "لدي حساب سابق", { legacy = !legacy }, Modifier.fillMaxWidth(), Variant.Link, height = 44.dp)
        }
    }
}

@Composable
fun PendingScreen() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    IceCard(Modifier.padding(top = 40.dp), padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("حسابك بانتظار التفعيل", style = T.xl.bold)
            Text("اطلب من المدير تحديد دورك (موزع أو مشرف) وربطك بشاحنة.", style = T.sm.muted, textAlign = TextAlign.Center)
            Btn("تحقق مرة أخرى", { scope.launch { repo.reloadMe() } })
        }
    }
}

// ---- shell ----------------------------------------------------------------------------

private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

private fun navItems(role: String?): List<NavItem> = when (role) {
    "distributor" -> listOf(
        NavItem(Screen.Today, "اليوم", Lucide.Home), NavItem(Screen.Customers, "العملاء", Lucide.Users),
        NavItem(Screen.Sale(), "بيع", Lucide.ShoppingCart), NavItem(Screen.More, "المزيد", Lucide.Menu),
    )
    "supervisor" -> listOf(
        NavItem(Screen.Trips, "الشاحنات", Lucide.Truck), NavItem(Screen.Closings, "الإغلاقات", Lucide.ClipboardCheck),
        NavItem(Screen.Reports, "التقارير", Lucide.BarChart3), NavItem(Screen.RouteMap(), "الخريطة", Lucide.Map),
        NavItem(Screen.Customers, "العملاء", Lucide.Users),
    )
    "admin" -> listOf(
        NavItem(Screen.Admin, "الرئيسية", Lucide.Home), NavItem(Screen.Trips, "الشاحنات", Lucide.Truck),
        NavItem(Screen.Reports, "التقارير", Lucide.BarChart3), NavItem(Screen.RouteMap(), "الخريطة", Lucide.Map),
        NavItem(Screen.AdminSetup, "الإعدادات", Lucide.Settings),
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
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // "Keep the screen on" applies on every screen while the distributor's campaign is open.
    val view = LocalView.current
    val snap by repo.snapshot.collectAsState()
    val wake by repo.keepAwake.collectAsState()
    val awake = wake && me.primaryRole == "distributor" && snap.ownOpenTrip(me.id) && !snap.closingSent
    androidx.compose.runtime.DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(Ice.background)) {
        // header: sticky bg-ice px-4 py-3 shadow-soft, continued up behind the status bar
        Row(
            Modifier.fillMaxWidth().softShadow(0.dp).background(Ice.gradient).padding(start = 16.dp, end = 16.dp, top = statusBar + 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(44.dp))
            Column(Modifier.weight(1f)) {
                Text("ثلج نقي", style = T.base.bold.copy(lineHeight = 20.sp, color = Ice.onPrimary))
                Text(me.fullName.ifBlank { me.email ?: "" }, style = T.xs.color(Ice.onPrimary.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ice.onPrimary) { SyncButton() }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable {
                    val waiting = repo.pendingCount()
                    if (waiting > 0) confirmSignOut = waiting else scope.launch { repo.signOut() }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.LogOut, "تسجيل الخروج", Modifier.size(20.dp), tint = Ice.onPrimary) }
        }
        if (me.isManager) InvoiceChangeNotice()
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            // Each screen keeps its own form state while it is on top of the stack.
            androidx.compose.runtime.key(nav.current, nav.stack.size) {
                // main: px-4 pt-4, with room below the last control
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp)) {
                    ScreenContent(nav.current, me)
                }
            }
        }
        if (items.isEmpty()) Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        if (items.isNotEmpty()) {
            // nav: border-t bg-card; items flex-col gap-1 py-2.5 text-xs with size-6 icons
            Divider()
            Row(Modifier.fillMaxWidth().background(Ice.card).windowInsetsPadding(WindowInsets.navigationBars)) {
                items.forEach { item ->
                    val active = nav.stack.first()::class == item.screen::class || nav.current::class == item.screen::class
                    Column(
                        Modifier.weight(1f).tap { nav.home(item.screen) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(item.icon, null, Modifier.size(24.dp), tint = if (active) Ice.accent else Ice.mutedText)
                        Text(item.label, style = if (active) T.xs.semibold.color(Ice.primary) else T.xs.muted)
                    }
                }
            }
        }
    }

    confirmSignOut?.let { count ->
        AlertDialog(
            onDismissRequest = { confirmSignOut = null },
            text = { Text("يوجد $count سجل محفوظ على الهاتف لم تكتمل مزامنته. سيبقى محفوظاً لهذا الحساب. تسجيل الخروج؟") },
            confirmButton = { TextButton({ confirmSignOut = null; scope.launch { repo.signOut() } }) { Text("موافق") } },
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
        is Screen.RouteMap -> Guard(me.isManager) { RouteMapScreen(screen.trip) }
        Screen.Admin -> Guard(me.isAdmin) { AdminHomeScreen() }
        Screen.AdminSetup -> Guard(me.isAdmin) { AdminSetupScreen() }
        Screen.AdminProducts -> Guard(me.isAdmin) { AdminProductsScreen() }
        Screen.AdminRegions -> Guard(me.isAdmin) { AdminRegionsScreen() }
        Screen.AdminTrucks -> Guard(me.isAdmin) { AdminTrucksScreen() }
        Screen.AdminCustomers -> Guard(me.isManager) { AdminCustomersScreen() }
        Screen.AdminFreezers -> Guard(me.isManager) { AdminFreezersScreen() }
        Screen.AdminUsers -> Guard(me.isAdmin) { AdminUsersScreen() }
        Screen.AdminCompany -> Guard(me.isAdmin) { AdminCompanyScreen() }
        Screen.Boxes -> Guard(fieldRoles) { BoxesScreen() }
        is Screen.Box -> Guard(fieldRoles) { BoxScreen(screen.id) }
        Screen.AdminBoxes -> Guard(me.isAdmin) { AdminBoxesScreen() }
    }
}

@Composable
private fun Guard(allowed: Boolean, content: @Composable () -> Unit) {
    // RequireRole: rounded-xl bg-muted p-6 text-center text-muted-foreground
    if (allowed) content() else Box(Modifier.fillMaxWidth().background(Ice.muted, RoundedCornerShape(Radius.xl)).padding(24.dp), contentAlignment = Alignment.Center) {
        Text("ليست لديك صلاحية لهذه الصفحة.", style = T.base.muted, textAlign = TextAlign.Center)
    }
}

// ---- dialogs --------------------------------------------------------------------------

/** The web `Dialog`: full-width sheet in the middle, black/80 backdrop, centred title, close ×. */
@Composable
fun WebDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        // A dialog opened from the header must not inherit the header's white text.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ice.foreground) {
        Box(Modifier.fillMaxWidth().heightIn(max = 620.dp).largeShadow(0.dp).background(Ice.background).border(1.dp, Ice.border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, Modifier.fillMaxWidth(), style = T.lg.semibold.copy(lineHeight = 18.sp), textAlign = TextAlign.Center)
                content()
            }
            // `right-4 top-4`: the physical right, i.e. the start edge in this RTL layout.
            Icon(Lucide.X, "إغلاق", Modifier.align(Alignment.TopStart).padding(16.dp).size(16.dp).alpha(0.7f).tap(onClick = onClose))
        }
        }
    }
}

// ---- sync status ----------------------------------------------------------------------

private val KIND_LABELS = mapOf(
    "sale" to "فاتورة", "payment" to "تحصيل", "expense" to "مصروف", "closing" to "إغلاق",
    "customer" to "عميل", "maintenance" to "صيانة", "freezer_event" to "ثلاجة", "gps" to "موقع",
    "box_move" to "صناديق", "customer_location" to "موقع عميل",
)

@Composable
private fun Spinning(icon: ImageVector, spin: Boolean) {
    val angle by rememberInfiniteTransition(label = "sync").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "sync")
    Icon(icon, null, Modifier.size(16.dp).rotate(if (spin) angle else 0f))
}

@Composable
private fun SyncButton() {
    val repo = LocalRepo.current
    val pending by repo.pending.collectAsState()
    val online by repo.online.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val lastError by repo.lastError.collectAsState()
    var open by remember { mutableStateOf(false) }
    val active = pending.count { it.state != "set_aside" }
    val label = when {
        pending.any { it.state == "review" } -> "طلب يحتاج مراجعة"
        lastError != null || pending.any { it.lastError != null && it.state != "set_aside" } -> "تعذّرت المزامنة"
        !online -> "بدون إنترنت"
        else -> "متصل"
    }
    Btn({ open = true }, variant = Variant.Ghost, height = 44.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (syncing) Spinning(Lucide.RefreshCw, true) else Icon(Lucide.Cloud, null, Modifier.size(16.dp))
            Text(label + if (active > 0) " ($active)" else "")
        }
    }
    if (open) SyncDialog(pending, online, syncing, lastError) { open = false }
}

@Composable
private fun SyncDialog(pending: List<OutboxOp>, online: Boolean, syncing: Boolean, lastError: String?, close: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmAside by remember { mutableStateOf<OutboxOp?>(null) }
    WebDialog("حالة المزامنة", close) {
        lastError?.let { Text(it, style = T.sm.color(Ice.destructive)) }
        // In the app's scope: closing this dialog must not cut the sync short.
        Btn({ repo.scope.launch { repo.syncNow() } }, Modifier.fillMaxWidth(), enabled = !syncing && online) {
            Icon(Lucide.RefreshCw, null, Modifier.size(16.dp))
            Text("مزامنة الآن")
        }
        if (pending.isEmpty()) Text("لا توجد سجلات بانتظار المزامنة")
        // Route points can pile up offline; the newest fifty are listed, the rest counted.
        val points = pending.filter { it.kind == "gps" && it.lastError == null }
        val shown = pending.filter { it.kind != "gps" || it.lastError != null } + points.takeLast(50)
        Column {
            shown.forEach { op ->
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(KIND_LABELS[op.kind] ?: op.kind, Modifier.weight(1f), style = T.sm.bold)
                        Text(
                            when (op.state) {
                                "review" -> "لم يُرفع — يحتاج مراجعة"
                                "set_aside" -> "مُنحّى عن المزامنة — محفوظ للمراجعة"
                                "reconciled" -> "طلب زائد مطابق؛ بانتظار الأرشفة المحلية"
                                "acknowledged" -> "تم الرفع؛ بانتظار تأكيد النسخة المحلية"
                                else -> "محفوظ على الهاتف"
                            },
                            style = T.sm,
                        )
                    }
                    Text(op.payload.str("invoice_no") ?: op.id, Modifier.fillMaxWidth(), style = T.xs.muted.ltr, textAlign = TextAlign.Left)
                    op.lastError?.let { Text(it, style = T.sm.color(Ice.destructive)) }
                    if (op.lastError != null && op.state != "review" && op.state != "set_aside") {
                        Btn("إعادة المحاولة", { repo.scope.launch { repo.retry(op) } }, variant = Variant.Outline, enabled = !syncing && online, height = 44.dp)
                    }
                    if (op.canSetAside()) Btn("تنحية هذا الطلب مع الاحتفاظ به", { confirmAside = op }, variant = Variant.Outline, enabled = !syncing)
                    if (op.state in listOf("review", "set_aside", "reconciled")) {
                        Btn("عرض بيانات الطلب", { selected = if (selected == op.id) null else op.id }, variant = Variant.Outline)
                        if (selected == op.id) Text(op.payload.toString(), style = T.xs.ltr.copy(fontFamily = FontFamily.Monospace))
                    }
                    if (op.lastError != null) Text("السجل محفوظ ولم يُحذف. راجع المدير عند رفض المخزون أو السعر أو الحملة.", style = T.xs.muted)
                }
                Divider()
            }
        }
        if (points.size > 50) Text("و${points.size - 50} نقطة موقع أخرى محفوظة على الهاتف", style = T.sm.muted)
    }
    confirmAside?.let { op ->
        AlertDialog(
            onDismissRequest = { confirmAside = null },
            text = { Text("تنحية هذا الطلب وحده عن المزامنة؟ لن يُعتبر مرفوعاً ولن تُحذف بياناته.") },
            confirmButton = { TextButton({ confirmAside = null; scope.launch { runCatching { repo.setAside(op) } } }) { Text("موافق") } },
            dismissButton = { TextButton({ confirmAside = null }) { Text("إلغاء") } },
        )
    }
}
