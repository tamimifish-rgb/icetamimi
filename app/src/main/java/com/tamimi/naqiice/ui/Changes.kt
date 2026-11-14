package com.tamimi.naqiice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.CHANGE_KIND_LABEL
import com.tamimi.naqiice.core.CHANGE_STATUS_LABEL
import com.tamimi.naqiice.core.SaleLine
import com.tamimi.naqiice.core.availableForReplacement
import com.tamimi.naqiice.core.changeItems
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.priceFor
import com.tamimi.naqiice.core.replacementPayload
import com.tamimi.naqiice.core.saleTotals
import com.tamimi.naqiice.core.snapshotCash
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.uniqueInvoiceNumber
import com.tamimi.naqiice.core.validateReason
import com.tamimi.naqiice.core.validateReplacement
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.userMessage
import com.tamimi.naqiice.data.withDeposit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToLong
import com.tamimi.naqiice.core.Row as DataRow

private const val CHANGES = "pureice_invoice_changes"
private const val NOT_ENABLED = "طلبات إلغاء وتعديل الفواتير غير مفعّلة حالياً."

/** `<span dir="ltr">`: an invoice number keeps its own left-to-right order inside Arabic text. */
private fun ltr(text: String?) = "⁦${text ?: ""}⁩"

// ---- manager banner -------------------------------------------------------------------

/** Number of requests waiting for a decision, shown to managers above every screen. */
@Composable
fun InvoiceChangeNotice() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val supported by repo.changeSupport.collectAsState()
    val me by repo.me.collectAsState()
    val onList = nav.current == Screen.InvoiceChanges
    var waiting by remember(me?.id) { mutableIntStateOf(0) }
    // Re-counted every minute and when the manager leaves the list, where reviews happen.
    LaunchedEffect(supported, me?.id, onList) {
        if (!supported || me == null) { waiting = 0; return@LaunchedEffect }
        while (true) {
            try {
                waiting = repo.api.count(CHANGES, "select=id&status=eq.pending", repo.api.token())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last known count; the next round retries.
            }
            delay(60_000)
        }
    }
    if (waiting == 0 || onList) return
    // a.block.bg-warning.px-4.py-2.text-center.text-sm.font-medium.text-warning-foreground
    ArrowText(
        "$waiting طلب إلغاء أو تعديل فاتورة بانتظار الاعتماد", T.sm.medium.color(Ice.onWarning),
        Modifier.fillMaxWidth().background(Ice.warning).tap { nav.go(Screen.InvoiceChanges) }.padding(horizontal = 16.dp, vertical = 8.dp), center = true,
    )
}

// ---- list -----------------------------------------------------------------------------

@Composable
fun InvoiceChangesScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val operation = rememberOperation()
    val toast = rememberToast()
    val me by repo.me.collectAsState()
    val supported by repo.changeSupport.collectAsState()
    var approving by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf<String?>(null) }

    val user = me
    if (user == null || user.primaryRole == null) { NotAllowed(); return }
    if (!supported) { Empty(NOT_ENABLED); return }
    val manager = user.isManager

    // Own requests for a distributor, all for a manager (the server decides which).
    val load = rememberLoaded(user.id, every = 60_000) { bearer -> repo.api.select(CHANGES, "select=*&order=created_at.desc&limit=200", bearer) }
    val names = rememberLoaded(user.id) { bearer ->
        suspend fun map(table: String, column: String): Map<String, String> = try {
            repo.api.select(table, "select=id,$column", bearer).mapNotNull { r -> r.str("id")?.let { it to (r.str(column) ?: "") } }.toMap()
        } catch (e: ApiError) { emptyMap() } catch (e: IOException) { emptyMap() }
        map("product_variants", "name") to map("profiles", "full_name")
    }

    fun review(id: String, status: String, note: String?) = operation.run {
        repo.api.rpc(
            "pureice_review_invoice_change",
            buildJsonObject { put("p_id", id); put("p_status", status); put("p_note", note?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull) },
            repo.api.token(),
        )
        toast(if (status == "approved") "تم اعتماد الطلب" else "تم رفض الطلب")
        load.reload()
        repo.scope.launch { repo.syncNow() }
    }

    fun variantName(id: String?) = id?.let { names.data?.first?.get(it) }?.takeIf { it.isNotEmpty() } ?: "صنف"
    fun who(id: String?) = id?.let { names.data?.second?.get(it) } ?: ""

    /** div.divide-y of `flex justify-between py-1` rows. */
    @Composable
    fun Items(doc: DataRow) {
        changeItems(doc).forEachIndexed { index, item ->
            if (index > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${variantName(item.str("variant_id"))} × ${formatNum(item.num("qty"))}", style = T.sm)
                Text(formatMoney(item.numOrNull("line_total") ?: (item.num("qty") * item.num("unit_price")).roundToLong().toDouble()), style = T.sm)
            }
        }
    }

    /** `mt-1 flex justify-between font-bold`. */
    @Composable
    fun Total(amount: Double?) {
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("الإجمالي", style = T.sm.bold)
            Text(formatMoney(amount), style = T.sm.bold)
        }
    }

    // div.space-y-3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("طلبات إلغاء وتعديل الفواتير", spacing = 12.dp)
        val data = load.data
        val rows = data.orEmpty().sortedWith(compareByDescending<DataRow> { it.str("status") == "pending" }.thenByDescending { it.str("created_at") ?: "" })
        if (load.error != null) LoadError("تعذّر تحميل الطلبات: ${load.error}", load.reload)
        else if (data == null) Empty("جارٍ التحميل…")
        else if (rows.isEmpty()) Empty(if (manager) "لا توجد طلبات." else "لم ترسل أي طلب. افتح الفاتورة من «اليوم» لطلب إلغائها أو تعديلها.")
        else rows.forEach { c ->
            val changeId = c.str("id") ?: return@forEach
            val snap = c.obj("snapshot")
            val status = c.str("status")
            val replacement = c.obj("replacement")?.takeIf { c.str("kind") == "replace" }
            // rounded-2xl border border-border bg-card p-4 text-sm shadow-soft
            Column(Modifier.fillMaxWidth().box(Radius.xl2, shadow = true).padding(16.dp)) {
                // flex items-start justify-between gap-2
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(snap?.str("customer_name") ?: "عميل", style = T.base.bold)
                        Text("${snap?.str("truck_name") ?: ""} · ${ltr(c.str("invoice_no"))}", style = T.xs.muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (c.str("kind") == "cancel") Chip(CHANGE_KIND_LABEL["cancel"]!!, Ice.destructive.copy(alpha = 0.1f), Ice.destructive)
                        else Chip(CHANGE_KIND_LABEL[c.str("kind")] ?: "", Ice.secondary)
                        when (status) {
                            "approved" -> Chip(CHANGE_STATUS_LABEL[status] ?: "", Ice.success.copy(alpha = 0.15f), Ice.success)
                            "rejected" -> Chip(CHANGE_STATUS_LABEL[status] ?: "", Ice.destructive.copy(alpha = 0.1f), Ice.destructive)
                            else -> Chip(CHANGE_STATUS_LABEL[status] ?: "", Ice.warning.copy(alpha = 0.3f))
                        }
                    }
                }
                Banner(Ice.muted, Modifier.padding(top = 8.dp), radius = Radius.lg, padding = 8.dp) { Text("السبب: ${c.str("reason") ?: ""}") }
                Text(who(c.str("requested_by")).let { if (it.isNotEmpty()) "$it · " else "" } + formatDate(c.str("created_at")), Modifier.padding(top = 8.dp), style = T.xs.muted)

                // mt-3 grid gap-3 (one column at phone width)
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DocumentBox(if (status == "approved") "الفاتورة الملغاة" else "الفاتورة الحالية", Ice.border) {
                        if (snap != null) Items(snap)
                        Total(snap?.num("total"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نقداً ${formatMoney(snapshotCash(snap))}", style = T.xs)
                            snap?.obj("deposit")?.let { Text("إيداع ${formatMoney(it.num("amount"))}", style = T.xs) }
                            Text("آجل ${formatMoney(snap?.num("credit_amount"))}", style = T.xs)
                        }
                        val slip = snap?.obj("deposit")?.str("slip_path")
                        if (manager && !slip.isNullOrEmpty()) Box(Modifier.padding(top = 4.dp)) { DepositSlipButton(slip) }
                    }
                    if (replacement != null) DocumentBox(if (status == "approved") "الفاتورة البديلة" else "الفاتورة المصحّحة المطلوبة", Ice.primary.copy(alpha = 0.4f)) {
                        Items(replacement)
                        Total(replacement.num("total"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نقداً ${formatMoney(replacement.num("cash_paid"))}", style = T.xs)
                            Text("آجل ${formatMoney(replacement.num("credit_amount"))}", style = T.xs)
                        }
                        val replacementId = replacement.str("id")
                        if (status == "approved" && replacementId != null) {
                            ArrowText("فتح الفاتورة البديلة", T.sm.color(Ice.primary), Modifier.padding(top = 4.dp).fillMaxWidth().tap { nav.go(Screen.Invoice(replacementId)) })
                        }
                    }
                }
                if (status == "pending") c.str("sale_id")?.let { saleId ->
                    ArrowText("فتح الفاتورة", T.sm.color(Ice.primary), Modifier.padding(top = 8.dp).fillMaxWidth().tap { nav.go(Screen.Invoice(saleId)) })
                } else {
                    val tone = if (status == "rejected") Ice.destructive else Ice.foreground
                    Text("${if (status == "approved") "اعتمده" else "رفضه"} ${who(c.str("reviewed_by"))} · ${formatDate(c.str("reviewed_at"))}", Modifier.padding(top = 8.dp), style = T.sm.color(tone))
                    c.str("review_note")?.takeIf { it.isNotEmpty() }?.let { Text("ملاحظة: $it", style = T.sm.color(tone)) }
                }
                if (manager && status == "pending") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("اعتماد", { approving = changeId }, Modifier.weight(1f), enabled = !operation.busy, height = 48.dp)
                    Btn("رفض", { rejecting = changeId }, Modifier.weight(1f), Variant.Destructive, enabled = !operation.busy, height = 48.dp)
                }
            }
        }
    }

    approving?.let { changeId ->
        AlertDialog(
            onDismissRequest = { approving = null },
            text = { Text("اعتماد الطلب يلغي الفاتورة الحالية نهائياً. متابعة؟") },
            confirmButton = { TextButton({ approving = null; review(changeId, "approved", null) }) { Text("موافق") } },
            dismissButton = { TextButton({ approving = null }) { Text("إلغاء") } },
        )
    }
    rejecting?.let { changeId ->
        ReasonDialog("سبب الرفض (يظهر للموزع)", "موافق", { rejecting = null }) { note -> rejecting = null; review(changeId, "rejected", note) }
    }
}

/** `rounded-lg border p-2` with a `mb-1 font-bold` title. */
@Composable
private fun DocumentBox(title: String, border: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().box(Radius.lg, null, border).padding(8.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp), style = T.sm.bold)
        content()
    }
}

// ---- request form ---------------------------------------------------------------------

@Composable
fun InvoiceChangeScreen(id: String, kind: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val operation = rememberOperation()
    val toast = rememberToast()
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val online by repo.online.collectAsState()
    val supported by repo.changeSupport.collectAsState()
    val replace = kind == "replace"
    // Kept across failed attempts so a retry sends the same replacement invoice and items.
    val replacementId = remember { UUID.randomUUID().toString() }
    val itemIds = remember { HashMap<Int, String>() }

    val local = snap.day.sales.firstOrNull { it.str("id") == id }
    var remote by remember(id) { mutableStateOf<DataRow?>(null) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, local == null) {
        if (local != null) return@LaunchedEffect
        try {
            val bearer = repo.api.token()
            val row = repo.api.maybeSingle("sales", "select=*,sale_items(*),customers(name)&id=eq.$id", bearer) ?: return@LaunchedEffect
            val entry = try { repo.api.maybeSingle("pureice_deposits", "select=*&id=eq.$id", bearer) } catch (e: ApiError) { null }
            remote = withDeposit(row, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage("تعذّر التحميل")
        }
    }
    val sale = local ?: remote
    val oldItems = remember(sale) { changeItems(sale) }

    var reason by remember { mutableStateOf("") }
    val qty = remember { mutableStateMapOf<String, Double>() }
    var cash by remember { mutableStateOf<String?>(null) }
    // The corrected invoice starts as a copy of the current one, once.
    LaunchedEffect(sale != null) {
        if (sale == null || cash != null) return@LaunchedEffect
        oldItems.forEach { item -> item.str("variant_id")?.let { qty[it] = item.num("qty") } }
        cash = plainNumber(sale.num("cash_paid"))
    }

    if (!supported) { Empty(NOT_ENABLED); return }
    if (sale == null || snap.ref == null) { Empty(loadError ?: "جارٍ تحميل الفاتورة…"); return }
    val user = me
    val closingSent = snap.closing != null || snap.pending.any { it.kind == "closing" && it.payload.str("trip_id") == sale.str("trip_id") }
    changeBlocker(sale, snap.trip, closingSent, user?.id, user?.isManager == true)?.let { Empty(it); return }
    if (replace && !canReplace(sale)) { Empty("فاتورة الإيداع لا تُعدَّل. اطلب إلغاءها ثم أصدر فاتورة جديدة."); return }
    if (replace && (sale.str("created_by") != user?.id || snap.tripId != sale.str("trip_id"))) {
        Empty("تعديل الفاتورة يطلبه الموزع صاحب الفاتورة من حملته المفتوحة. يمكنك طلب إلغائها."); return
    }

    val customerId = sale.str("customer_id") ?: ""
    val customer = snap.customer(customerId) ?: sale.obj("customers")
    val prices = remember(snap.ref, customerId) { snap.pricesFor(customerId) }
    fun available(variantId: String) = availableForReplacement(snap.stockOf(variantId), oldItems, variantId)
    val lines = snap.variants.mapNotNull { v ->
        val variantId = v.str("id") ?: return@mapNotNull null
        val n = qty[variantId] ?: 0.0
        if (n > 0) SaleLine(variantId, n, priceFor(variantId, v.num("price"), prices)) else null
    }
    val cashNum = cash.let { if (it.isNullOrEmpty()) 0.0 else parseNumber(it) }
    val totals = saleTotals(lines, if (cashNum.isNaN()) 0.0 else cashNum)
    val truck = snap.truck(sale.str("truck_id"))
    fun variantName(variantId: String?) = snap.variant(variantId)?.str("name") ?: "صنف"

    // div.space-y-4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle(if (replace) "طلب تعديل فاتورة" else "طلب إلغاء فاتورة")
        // rounded-2xl bg-card p-4 shadow-soft text-sm
        IceCard {
            Column {
                Text(customer?.str("name") ?: "", style = T.base.bold)
                Text("فاتورة ${ltr(sale.str("invoice_no"))}", style = T.sm.muted)
                // mt-2 divide-y of `flex justify-between py-1.5`
                Column(Modifier.padding(top = 8.dp)) {
                    oldItems.forEachIndexed { index, item ->
                        if (index > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${variantName(item.str("variant_id"))} × ${formatNum(item.num("qty"))}", style = T.sm)
                            Text(formatMoney(item.num("line_total")), style = T.sm.bold)
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الإجمالي الحالي", style = T.sm.bold)
                    Text(formatMoney(sale.num("total")), style = T.sm.bold)
                }
            }
        }

        if (replace) {
            Text("الفاتورة المصحّحة", style = T.base.bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.variants.forEach { v ->
                    val variantId = v.str("id") ?: return@forEach
                    // flex items-center gap-3 rounded-xl border bg-card p-3
                    Row(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(v.str("name") ?: "", style = T.base.medium)
                            Text("${formatMoney(priceFor(variantId, v.num("price"), prices))} / ${v.str("unit") ?: ""} · المتاح ${formatNum(available(variantId))}", style = T.xs.muted)
                        }
                        QtyStepper(qty[variantId] ?: 0.0) { qty[variantId] = it }
                    }
                }
            }
            // space-y-3 rounded-2xl bg-card p-4 shadow-soft
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الإجمالي الجديد", style = T.lg.bold)
                        Text(formatMoney(totals.total), style = T.lg.bold)
                    }
                    Column {
                        Text("المبلغ المدفوع نقداً", style = T.sm.muted.copy(lineHeight = 24.sp))
                        Input(cash ?: "", { cash = it }, placeholder = "0", height = 48.dp, style = T.lg, numeric = true)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row { Text("نقداً: ", style = T.sm); Text(formatMoney(totals.cashPaid), style = T.sm.bold) }
                        Row { Text("آجل: ", style = T.sm); Text(formatMoney(totals.creditAmount), style = T.sm.bold.color(Ice.destructive)) }
                    }
                }
            }
        }

        Column {
            Text("سبب الطلب (يظهر للمشرف)", style = T.sm.muted.copy(lineHeight = 24.sp))
            TextArea(reason, { reason = it.take(500) }, placeholder = if (replace) "مثال: الكمية الصحيحة ٥ وليست ٨" else "مثال: الفاتورة سُجلت على عميل خطأ", height = 96.dp)
        }
        Text(
            (if (replace) "تبقى الفاتورة الحالية سارية حتى الاعتماد. عند الاعتماد تُلغى وتحل مكانها الفاتورة المصحّحة برقم جديد."
            else "تبقى الفاتورة سارية حتى يعتمد المشرف أو المدير الطلب. عند الاعتماد تُلغى وتعود كمياتها لمخزون الشاحنة.") +
                " لا يمكن إرسال إغلاق الحملة قبل مراجعة الطلب.",
            style = T.xs.muted,
        )
        if (!online) Banner(Ice.warning.copy(alpha = 0.3f), radius = Radius.lg, padding = 8.dp) {
            Text("إرسال الطلب يحتاج اتصالاً بالإنترنت.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        val enabled = !operation.busy && online && reason.isNotBlank() && (!replace || lines.isNotEmpty())
        val submit = {
            operation.run { requestId ->
                if (user == null) throw Notice("سجّل الدخول أولاً")
                if (!online) throw Notice("إرسال الطلب يحتاج اتصالاً بالإنترنت")
                val why = validateReason(reason)
                val request = buildJsonObject {
                    put("id", requestId); put("sale_id", sale.str("id") ?: id); put("kind", if (replace) "replace" else "cancel"); put("reason", why)
                    if (replace) {
                        validateReplacement(lines, cashNum, ::available, snap.balanceOf(customerId), sale.num("credit_amount"), customer?.num("credit_limit") ?: 0.0)
                        var n = 0
                        put("replacement", replacementPayload(replacementId, uniqueInvoiceNumber(truck?.str("code") ?: "X", replacementId), customerId, lines, cashNum) {
                            itemIds.getOrPut(n++) { UUID.randomUUID().toString() }
                        })
                    }
                }
                // The request must be sent by the account this screen was opened for.
                if (repo.api.currentUserId() != user.id) throw Notice("تغير الحساب؛ أعد المحاولة")
                repo.api.rpc("pureice_request_invoice_change", buildJsonObject { put("p_request", request) }, repo.api.token())
                toast("أُرسل الطلب للمشرف")
                repo.scope.launch { repo.syncNow() }
                nav.replace(Screen.InvoiceChanges)
            }
        }
        // Button.h-14.w-full.text-lg, destructive for a cancellation
        Btn(
            if (replace) "إرسال طلب التعديل" else "إرسال طلب الإلغاء", submit, Modifier.fillMaxWidth(),
            if (replace) Variant.Default else Variant.Destructive, enabled, height = 56.dp, style = T.lg.medium,
        )
        Btn("رجوع للفاتورة", {
            // Normally the invoice is the previous screen; otherwise open it in place.
            if (nav.stack.getOrNull(nav.stack.lastIndex - 1) == Screen.Invoice(id)) nav.back() else nav.replace(Screen.Invoice(id))
        }, Modifier.fillMaxWidth(), Variant.Secondary, height = 48.dp)
    }
}
