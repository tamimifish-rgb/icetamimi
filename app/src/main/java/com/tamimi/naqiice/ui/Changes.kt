package com.tamimi.naqiice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
    Text(
        "$waiting طلب إلغاء أو تعديل فاتورة بانتظار الاعتماد ←",
        Modifier.fillMaxWidth().background(Ice.warning).clickable { nav.go(Screen.InvoiceChanges) }.padding(horizontal = 16.dp, vertical = 8.dp),
        color = Ice.onWarning, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
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
    if (user == null || user.primaryRole == null) { Empty("ليست لديك صلاحية لهذه الصفحة."); return }
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

    @Composable
    fun Items(doc: DataRow) {
        changeItems(doc).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Ice.border)
            LineItem(
                "${variantName(item.str("variant_id"))} × ${formatNum(item.num("qty"))}",
                formatMoney(item.numOrNull("line_total") ?: (item.num("qty") * item.num("unit_price")).roundToLong().toDouble()),
                bold = false,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("طلبات إلغاء وتعديل الفواتير")
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
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(snap?.str("customer_name") ?: "عميل", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("${snap?.str("truck_name") ?: ""} · ${c.str("invoice_no") ?: ""}", fontSize = 12.sp, color = Ice.mutedText, style = TextStyle(textDirection = TextDirection.Content))
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
                    Banner(Ice.muted) { Text("السبب: ${c.str("reason") ?: ""}", fontSize = 14.sp) }
                    Text(who(c.str("requested_by")).let { if (it.isNotEmpty()) "$it · " else "" } + formatDate(c.str("created_at")), fontSize = 12.sp, color = Ice.mutedText)

                    DocumentBox(if (status == "approved") "الفاتورة الملغاة" else "الفاتورة الحالية", Ice.border) {
                        if (snap != null) Items(snap)
                        LineItem("الإجمالي", formatMoney(snap?.num("total")))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نقداً ${formatMoney(snapshotCash(snap))}", fontSize = 12.sp)
                            snap?.obj("deposit")?.let { Text("إيداع ${formatMoney(it.num("amount"))}", fontSize = 12.sp) }
                            Text("آجل ${formatMoney(snap?.num("credit_amount"))}", fontSize = 12.sp)
                        }
                        if (manager) DepositSlipButton(snap?.obj("deposit")?.str("slip_path"))
                    }
                    if (replacement != null) DocumentBox(if (status == "approved") "الفاتورة البديلة" else "الفاتورة المصحّحة المطلوبة", Ice.primary.copy(alpha = 0.4f)) {
                        Items(replacement)
                        LineItem("الإجمالي", formatMoney(replacement.num("total")))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نقداً ${formatMoney(replacement.num("cash_paid"))}", fontSize = 12.sp)
                            Text("آجل ${formatMoney(replacement.num("credit_amount"))}", fontSize = 12.sp)
                        }
                        val replacementId = replacement.str("id")
                        if (status == "approved" && replacementId != null) {
                            Text("فتح الفاتورة البديلة ←", color = Ice.primary, fontSize = 14.sp, modifier = Modifier.clickable { nav.go(Screen.Invoice(replacementId)) }.padding(vertical = 6.dp))
                        }
                    }
                    if (status == "pending") c.str("sale_id")?.let { saleId ->
                        Text("فتح الفاتورة ←", color = Ice.primary, fontSize = 14.sp, modifier = Modifier.clickable { nav.go(Screen.Invoice(saleId)) }.padding(vertical = 6.dp))
                    } else {
                        val tone = if (status == "rejected") Ice.destructive else Ice.foreground
                        Text("${if (status == "approved") "اعتمده" else "رفضه"} ${who(c.str("reviewed_by"))} · ${formatDate(c.str("reviewed_at"))}", fontSize = 14.sp, color = tone)
                        c.str("review_note")?.takeIf { it.isNotEmpty() }?.let { Text("ملاحظة: $it", fontSize = 14.sp, color = tone) }
                    }
                    if (manager && status == "pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MidButton("اعتماد", Modifier.weight(1f), enabled = !operation.busy) { approving = changeId }
                        MidButton("رفض", Modifier.weight(1f), enabled = !operation.busy, danger = true) { rejecting = changeId }
                    }
                }
            }
        }
    }

    approving?.let { changeId ->
        AlertDialog(
            onDismissRequest = { approving = null },
            text = { Text("اعتماد الطلب يلغي الفاتورة الحالية نهائياً. متابعة؟") },
            confirmButton = { TextButton({ approving = null; review(changeId, "approved", null) }) { Text("اعتماد") } },
            dismissButton = { TextButton({ approving = null }) { Text("إلغاء") } },
        )
    }
    rejecting?.let { changeId ->
        ReasonDialog("سبب الرفض (يظهر للموزع)", "رفض", { rejecting = null }) { note -> rejecting = null; review(changeId, "rejected", note) }
    }
}

@Composable
private fun DocumentBox(title: String, border: Color, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Ice.card, border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            content()
        }
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(if (replace) "طلب تعديل فاتورة" else "طلب إلغاء فاتورة")
        IceCard {
            Column {
                Text(customer?.str("name") ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("فاتورة ${sale.str("invoice_no") ?: ""}", fontSize = 14.sp, color = Ice.mutedText, style = TextStyle(textDirection = TextDirection.Content))
                oldItems.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Ice.border)
                    LineItem("${variantName(item.str("variant_id"))} × ${formatNum(item.num("qty"))}", formatMoney(item.num("line_total")))
                }
                LineItem("الإجمالي الحالي", formatMoney(sale.num("total")))
            }
        }

        if (replace) {
            SectionTitle("الفاتورة المصحّحة")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.variants.forEach { v ->
                    val variantId = v.str("id") ?: return@forEach
                    Surface(shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(v.str("name") ?: "", fontWeight = FontWeight.Medium)
                                Text("${formatMoney(priceFor(variantId, v.num("price"), prices))} / ${v.str("unit") ?: ""} · المتاح ${formatNum(available(variantId))}", fontSize = 12.sp, color = Ice.mutedText)
                            }
                            QtyStepper(qty[variantId] ?: 0.0) { qty[variantId] = it }
                        }
                    }
                }
            }
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الإجمالي الجديد", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(formatMoney(totals.total), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Field(cash ?: "", { cash = it }, "المبلغ المدفوع نقداً", numeric = true, large = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("نقداً: ${formatMoney(totals.cashPaid)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("آجل: ${formatMoney(totals.creditAmount)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ice.destructive)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field(reason, { reason = it.take(500) }, "سبب الطلب (يظهر للمشرف)", singleLine = false)
            Text(if (replace) "مثال: الكمية الصحيحة ٥ وليست ٨" else "مثال: الفاتورة سُجلت على عميل خطأ", fontSize = 12.sp, color = Ice.mutedText)
        }
        Text(
            (if (replace) "تبقى الفاتورة الحالية سارية حتى الاعتماد. عند الاعتماد تُلغى وتحل مكانها الفاتورة المصحّحة برقم جديد."
            else "تبقى الفاتورة سارية حتى يعتمد المشرف أو المدير الطلب. عند الاعتماد تُلغى وتعود كمياتها لمخزون الشاحنة.") +
                " لا يمكن إرسال إغلاق الحملة قبل مراجعة الطلب.",
            fontSize = 12.sp, color = Ice.mutedText,
        )
        if (!online) Banner(Ice.warning.copy(alpha = 0.3f)) {
            Text("إرسال الطلب يحتاج اتصالاً بالإنترنت.", Modifier.fillMaxWidth(), fontSize = 14.sp, textAlign = TextAlign.Center)
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
        if (replace) BigButton("إرسال طلب التعديل", enabled = enabled, onClick = submit)
        else MidButton("إرسال طلب الإلغاء", Modifier.fillMaxWidth(), enabled = enabled, danger = true, onClick = submit)
        MidButton("رجوع للفاتورة", Modifier.fillMaxWidth(), outlined = true) {
            // Normally the invoice is the previous screen; otherwise open it in place.
            if (nav.stack.getOrNull(nav.stack.lastIndex - 1) == Screen.Invoice(id)) nav.back() else nav.replace(Screen.Invoice(id))
        }
    }
}
