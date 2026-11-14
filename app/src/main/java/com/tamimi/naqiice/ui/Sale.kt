package com.tamimi.naqiice.ui

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.R
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.SaleLine
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.depositTotals
import com.tamimi.naqiice.core.exceedsCreditLimit
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.jstr
import com.tamimi.naqiice.core.lineTotal
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.priceFor
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.saleTotals
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.uniqueInvoiceNumber
import com.tamimi.naqiice.core.yemenPhone
import com.tamimi.naqiice.data.Backend
import com.tamimi.naqiice.data.enc
import com.tamimi.naqiice.data.userMessage
import com.tamimi.naqiice.data.withDeposit
import com.tamimi.naqiice.tracking.Locations
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// ---- sale -----------------------------------------------------------------------------

@Composable
fun SaleScreen(initialCustomer: String?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val supportsDeposit by repo.depositSupport.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    val location = rememberLocationGate(allowNoFix = true)
    var customerId by remember { mutableStateOf(initialCustomer) }
    var method by remember { mutableStateOf("cash") }
    var deposit by remember { mutableStateOf("") }
    var slip by remember { mutableStateOf<String?>(null) }
    var cash by remember { mutableStateOf("") }
    val qty = remember { mutableStateMapOf<String, Double>() }

    val user = me
    val trip = snap.trip
    if (snap.ref == null) { Empty("جارٍ تحميل البيانات…"); return }
    if (trip == null || user == null || !snap.ownOpenTrip(user.id) || trip.str("loading_approved_at") == null) {
        Empty("تحتاج حملة معينة لك وتحميلاً معتمداً قبل البيع."); return
    }
    if (snap.closingSent) { Empty("تم إغلاق الحملة — البيع متوقف."); return }
    val chosen = customerId
    if (chosen == null) {
        Column { PageTitle("اختر العميل"); CustomerPicker { customerId = it } }
        return
    }

    val customer = snap.customer(chosen)
    val prices = remember(snap.ref, chosen) { snap.pricesFor(chosen) }
    val lines = snap.variants.mapNotNull { v ->
        val id = v.str("id") ?: return@mapNotNull null
        val n = qty[id] ?: 0.0
        if (n > 0) SaleLine(id, n, priceFor(id, v.num("price"), prices)) else null
    }
    val cashNum = if (cash.isEmpty()) Double.NaN else parseNumber(cash)
    val base = saleTotals(lines, if (cashNum.isNaN()) Double.POSITIVE_INFINITY else cashNum)
    val depositAmount = if (method == "deposit") (if (deposit.isEmpty()) base.total else parseNumber(deposit)) else 0.0
    val cashPaid = if (method == "deposit") (if (cash.isEmpty()) 0.0 else cashNum) else base.cashPaid
    val credit = if (method == "deposit") base.total - depositAmount - cashPaid else base.creditAmount
    val debt = snap.balanceOf(chosen)
    val limit = customer?.num("credit_limit") ?: 0.0
    val overLimit = customer != null && exceedsCreditLimit(limit, debt, credit)
    val truck = snap.truck(trip.str("truck_id") ?: user.truckId)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        location.prompt()
        Text("→ تغيير العميل", color = Ice.primary, fontSize = 14.sp, modifier = Modifier.clickable { customerId = null })
        IceCard {
            Column {
                Text(customer?.str("name") ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row { Text("الدين الحالي: ", fontSize = 14.sp, color = Ice.mutedText); Text(formatMoney(debt), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (debt > 0) Ice.destructive else Ice.foreground) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snap.variants.forEach { v ->
                val id = v.str("id") ?: return@forEach
                val n = qty[id] ?: 0.0
                Surface(shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(v.str("name") ?: "", fontWeight = FontWeight.Medium)
                            Text("${formatMoney(priceFor(id, v.num("price"), prices))} / ${v.str("unit") ?: ""} · المتوفر ${formatNum(snap.stockOf(id))}", fontSize = 12.sp, color = Ice.mutedText)
                        }
                        RoundButton("−", Ice.secondary, Ice.primary) { qty[id] = maxOf(0.0, n - 1) }
                        BasicTextField(
                            value = if (n == 0.0) "" else if (n == Math.rint(n)) n.toLong().toString() else n.toString(),
                            onValueChange = { qty[id] = maxOf(0.0, numericInput(it).toDoubleOrNull() ?: 0.0) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Ice.foreground),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(56.dp).background(Ice.background, RoundedCornerShape(8.dp)).padding(vertical = 10.dp),
                            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (n == 0.0) Text("0", color = Ice.mutedText, fontSize = 18.sp); inner() } },
                        )
                        RoundButton("+", Ice.primary, Ice.onPrimary) { qty[id] = n + 1 }
                    }
                }
            }
        }

        IceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الإجمالي", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(formatMoney(base.total), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                val methods = listOf("cash" to "نقداً بالكامل", "credit" to "آجل بالكامل") + if (supportsDeposit) listOf("deposit" to "إيداع") else emptyList()
                Choice(methods, method) { method = it; cash = if (it == "credit") "0" else "" }
                if (method == "deposit") {
                    Field(deposit, { deposit = it }, "مبلغ الإيداع (${formatNum(base.total)})", numeric = true)
                    PhotoPick("صورة إيصال الإيداع (مطلوبة)", slip) { slip = it }
                }
                Field(cash, { cash = it }, "المبلغ المدفوع نقداً (للدفع الجزئي)", numeric = true, large = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("نقداً: ${formatMoney(cashPaid)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("آجل: ${formatMoney(credit)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ice.destructive)
                }
                if (overLimit) Banner(Ice.destructive.copy(alpha = 0.1f)) { Text("تجاوز حد الائتمان لهذا العميل (${formatMoney(limit)})", fontSize = 14.sp, color = Ice.destructive) }
                BigButton("حفظ الفاتورة", enabled = !operation.busy && !location.blocked && lines.isNotEmpty() && !overLimit && (method != "deposit" || (slip != null && supportsDeposit))) {
                    operation.run { id ->
                        if (lines.isEmpty()) throw Notice("أضف صنفاً واحداً على الأقل")
                        if (method == "deposit") {
                            if (!supportsDeposit) throw Notice("الإيداع غير مفعل حالياً")
                            depositTotals(base.total, depositAmount, cashPaid, slip != null)
                        }
                        if (customer == null || customer.bool("active") == false || snap.tripId == null) throw Notice("العميل غير متاح أو لا توجد حملة مفتوحة")
                        if (overLimit) throw Notice("تجاوز حد الائتمان")
                        if (cash.isNotEmpty() && (!cashNum.isFinite() || cashNum < 0 || cashNum > base.total)) throw Notice("النقد يجب أن يكون بين صفر والإجمالي")
                        if (lines.any { !it.qty.isFinite() || it.qty <= 0 || !it.unitPrice.isFinite() || it.unitPrice <= 0 || it.qty > snap.stockOf(it.variantId) }) {
                            throw Notice("الكمية أو السعر غير صحيح أو المخزون غير كاف")
                        }
                        val pos = if (location.required) location.check() ?: throw Notice("فعّل الموقع واسمح للتطبيق باستخدامه ثم أعد المحاولة")
                        else Locations.current(context, 4_000, 60_000).let { Position(it?.lat, it?.lng) }
                        val sale = buildJsonObject {
                            put("id", id)
                            put("invoice_no", uniqueInvoiceNumber(truck?.str("code") ?: "X", id))
                            put("trip_id", snap.tripId); put("truck_id", jstr(trip.str("truck_id"))); put("customer_id", chosen)
                            put("total", jnum(base.total)); put("cash_paid", jnum(cashPaid)); put("credit_amount", jnum(credit))
                            if (method == "deposit" && slip != null) {
                                put("payment_method", "deposit"); put("deposit_amount", jnum(depositAmount))
                                put("deposit_slip_path", "${user.id}/deposit-$id.jpg"); put("deposit_slip_blob", slip)
                            }
                            put("lat", pos.lat?.let { JsonPrimitive(it) } ?: JsonNull); put("lng", pos.lng?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("created_by", user.id); put("created_at", isoNow())
                            put("items", JsonArray(lines.map { l ->
                                buildJsonObject {
                                    put("id", UUID.randomUUID().toString()); put("sale_id", id); put("variant_id", l.variantId)
                                    put("qty", jnum(l.qty)); put("unit_price", jnum(l.unitPrice)); put("line_total", jnum(lineTotal(l)))
                                }
                            }))
                        }
                        repo.enqueue("sale", sale)
                        // Every invoice also marks the route, when a position is known.
                        if (pos.lat != null && pos.lng != null) runCatching { repo.recordPoint(user.id, snap.tripId!!, trip.str("truck_id"), pos.lat, pos.lng, isoNow()) }
                        toast("تم حفظ الفاتورة")
                        nav.replace(Screen.Invoice(id))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(symbol: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).background(background, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(symbol, color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- payment --------------------------------------------------------------------------

@Composable
fun PaymentScreen(initialCustomer: String?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val supportsDeposit by repo.depositSupport.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    val location = rememberLocationGate()
    var customerId by remember { mutableStateOf(initialCustomer) }
    var deposit by remember { mutableStateOf(false) }
    var slip by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    if (snap.ref == null) { Empty("جارٍ التحميل…"); return }
    val chosen = customerId
    if (chosen == null) {
        Column { PageTitle("تحصيل دين — اختر العميل"); CustomerPicker { customerId = it } }
        return
    }
    val debt = snap.balanceOf(chosen)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("تحصيل دين")
        location.prompt()
        if (supportsDeposit) Choice(listOf(false to "نقد", true to "إيداع"), deposit) { deposit = it }
        if (deposit) PhotoPick("صورة إيصال الإيداع (مطلوبة)", slip) { slip = it }
        IceCard {
            Column {
                Text(snap.customer(chosen)?.str("name") ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row { Text("الدين الحالي: ", fontSize = 14.sp); Text(formatMoney(debt), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ice.destructive) }
            }
        }
        Field(amount, { amount = it }, "المبلغ المحصّل", numeric = true, large = true)
        if (debt > 0) MidButton("تحصيل كامل الدين (${formatMoney(debt)})", Modifier.fillMaxWidth(), outlined = true) {
            amount = if (debt == Math.rint(debt)) debt.toLong().toString() else debt.toString()
        }
        Field(notes, { notes = it }, "ملاحظات (اختياري)")
        BigButton("حفظ التحصيل", enabled = !operation.busy && !location.blocked) {
            operation.run { id ->
                val user = me
                if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
                if (snap.closingSent) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
                val a = parseNumber(amount)
                if (!a.isFinite() || a <= 0 || a > debt) throw Notice("أدخل مبلغاً صحيحاً")
                if (deposit && (!supportsDeposit || slip == null)) throw Notice("الإيداع يتطلب صورة إيصال وتفعيل الخدمة")
                if (location.required && location.check() == null) throw Notice("الموقع مطلوب قبل التحصيل")
                repo.enqueue("payment", buildJsonObject {
                    put("id", id); put("customer_id", chosen); put("truck_id", jstr(snap.trip?.str("truck_id"))); put("trip_id", snap.tripId)
                    put("amount", jnum(a)); put("notes", jstr(notes.ifEmpty { null }))
                    if (deposit && slip != null) {
                        put("payment_method", "deposit"); put("deposit_amount", jnum(a))
                        put("deposit_slip_path", "${user.id}/deposit-$id.jpg"); put("deposit_slip_blob", slip)
                    }
                    put("created_by", user.id); put("created_at", isoNow())
                })
                toast("تم تسجيل تحصيل ${formatMoney(a)}")
                nav.replace(Screen.Customer(chosen))
            }
        }
    }
}

// ---- invoice --------------------------------------------------------------------------

@Composable
fun InvoiceScreen(id: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val company by repo.company.collectAsState()
    val changeSupport by repo.changeSupport.collectAsState()
    val local = snap.day.sales.firstOrNull { it.str("id") == id } ?: snap.pending.firstOrNull { it.kind == "sale" && it.id == id }?.payload
    var remote by remember(id) { mutableStateOf<DataRow?>(null) }
    var changes by remember(id) { mutableStateOf<List<DataRow>>(emptyList()) }

    LaunchedEffect(id, local == null) {
        if (local != null) return@LaunchedEffect
        repo.archivedOperation(id)?.obj("payload")?.let { remote = it }
        runCatching {
            val bearer = repo.api.token()
            val sale = repo.api.maybeSingle("sales", "select=*,sale_items(*),customers(name,phone)&id=eq.$id", bearer) ?: return@runCatching
            val entry = runCatching { repo.api.maybeSingle("pureice_deposits", "select=*&id=eq.$id", bearer) }.getOrNull()
            remote = withDeposit(sale, entry)
        }
    }
    LaunchedEffect(id, changeSupport) {
        if (!changeSupport) return@LaunchedEffect
        runCatching { changes = repo.api.select("pureice_invoice_changes", "select=*&sale_id=eq.$id&order=created_at.desc", repo.api.token()) }
    }

    val sale = local ?: remote
    if (sale == null) { Empty("جارٍ تحميل الفاتورة…"); return }
    val user = me
    val pendingChange = changes.firstOrNull { it.str("status") == "pending" }
    val cancelled = changes.firstOrNull { it.str("status") == "approved" }
    val lastRejected = if (pendingChange == null && cancelled == null) changes.firstOrNull { it.str("status") == "rejected" } else null
    val blocker = changeBlocker(sale, snap.trip, snap.closingSent, user?.id, user?.isManager == true)
    val canRequest = changeSupport && pendingChange == null && cancelled == null && blocker == null
    val canEdit = canRequest && canReplace(sale) && sale.str("created_by") == user?.id && snap.tripId == sale.str("trip_id")
    val customer = snap.customer(sale.str("customer_id")) ?: sale.obj("customers")
    val items = sale.rows("sale_items").ifEmpty { sale.rows("items") }
    fun variantName(variantId: String?) = snap.variant(variantId)?.str("name") ?: "صنف"
    val debt = snap.balanceOf(sale.str("customer_id") ?: "")
    val methodLabel = if (sale.str("payment_method") == "deposit") "إيداع" else if (sale.num("cash_paid") > 0) "نقد" else "آجل"
    val depositAmount = sale.numOrNull("deposit_amount")?.takeIf { it > 0 }
    val text = buildList {
        add("*${company.name}* — فاتورة ${sale.str("invoice_no")}")
        add(company.address)
        if (company.phone.isNotEmpty()) add("الهاتف: ${company.phone}")
        add("العميل: ${customer?.str("name") ?: ""}")
        add(formatDate(sale.str("created_at")))
        add("")
        items.forEach { add("${variantName(it.str("variant_id"))} × ${formatNum(it.num("qty"))} = ${formatMoney(it.num("line_total"))}") }
        add("")
        add("الإجمالي: ${formatMoney(sale.num("total"))}")
        add("المدفوع: ${formatMoney(sale.num("cash_paid"))}")
        add("طريقة الدفع: $methodLabel")
        depositAmount?.let { add("إيداع: ${formatMoney(it)}") }
        add("آجل: ${formatMoney(sale.num("credit_amount"))}")
        add("إجمالي الدين: ${formatMoney(debt)}")
        if (company.footerNote.isNotEmpty()) add(company.footerNote)
    }.joinToString("\n")

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cancelled?.let { change ->
            Banner(Ice.destructive.copy(alpha = 0.1f)) {
                Column {
                    Text("هذه الفاتورة ملغاة باعتماد المشرف.", color = Ice.destructive, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    change.obj("replacement")?.str("id")?.takeIf { change.str("kind") == "replace" }?.let { replacement ->
                        Text("فتح الفاتورة البديلة", color = Ice.primary, fontSize = 14.sp, modifier = Modifier.clickable { nav.go(Screen.Invoice(replacement)) })
                    }
                }
            }
        }
        pendingChange?.let { change ->
            Banner(Ice.warning.copy(alpha = 0.3f), Modifier.clickable { nav.go(Screen.InvoiceChanges) }) {
                Text("${changeKindLabel(change.str("kind"))} بانتظار اعتماد المشرف. الفاتورة سارية حتى الاعتماد.", fontSize = 14.sp)
            }
        }
        lastRejected?.let { change ->
            Banner(Ice.muted) { Text("رُفض ${changeKindLabel(change.str("kind"))}" + (change.str("review_note")?.let { ": $it" } ?: ""), fontSize = 14.sp) }
        }
        IceCard {
            Column {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(72.dp))
                    Text(company.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(company.address, fontSize = 12.sp, color = Ice.mutedText)
                    if (company.phone.isNotEmpty()) Text(company.phone, fontSize = 14.sp, style = TextStyle(textDirection = TextDirection.Ltr))
                }
                Text("رقم: ${sale.str("invoice_no")}", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp), style = TextStyle(textDirection = TextDirection.Content))
                Text(formatDate(sale.str("created_at")), fontSize = 13.sp)
                Text("العميل: ${customer?.str("name") ?: ""}", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                if (sale["lat"] == null || sale["lat"] is JsonNull || sale["lng"] == null || sale["lng"] is JsonNull) Text("بدون موقع", fontSize = 14.sp, color = Ice.onWarning)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("الصنف", Modifier.weight(2f), fontSize = 13.sp, color = Ice.mutedText)
                    Text("الكمية", Modifier.weight(1f), fontSize = 13.sp, color = Ice.mutedText, textAlign = TextAlign.Center)
                    Text("السعر", Modifier.weight(1f), fontSize = 13.sp, color = Ice.mutedText, textAlign = TextAlign.Center)
                    Text("المجموع", Modifier.weight(1.2f), fontSize = 13.sp, color = Ice.mutedText, textAlign = TextAlign.End)
                }
                items.forEach { item ->
                    HorizontalDivider(color = Ice.border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(variantName(item.str("variant_id")), Modifier.weight(2f), fontSize = 14.sp)
                        Text(formatNum(item.num("qty")), Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text(formatNum(item.num("unit_price")), Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text(formatNum(item.num("line_total")), Modifier.weight(1.2f), fontSize = 14.sp, textAlign = TextAlign.End)
                    }
                }
                HorizontalDivider(color = Ice.border)
                LineItem("الإجمالي", formatMoney(sale.num("total")))
                LineItem("المدفوع نقداً", formatMoney(sale.num("cash_paid")), bold = false)
                Text("طريقة الدفع: $methodLabel", fontSize = 14.sp)
                depositAmount?.let { LineItem("إيداع", formatMoney(it), bold = false) }
                LineItem("آجل", formatMoney(sale.num("credit_amount")), Ice.destructive, bold = false)
                HorizontalDivider(color = Ice.border)
                LineItem("إجمالي دين العميل", formatMoney(debt), bold = false)
                if (sale.bool("_pending") == true) Banner(Ice.warning.copy(alpha = 0.3f), Modifier.padding(top = 8.dp)) {
                    Text("محفوظة على الهاتف — سترفع عند توفر الإنترنت", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (company.footerNote.isNotEmpty()) Text(company.footerNote, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MidButton("واتساب", Modifier.weight(1f)) {
                val phone = yemenPhone(customer?.str("phone")) ?: ""
                if (!openUrl(context, "https://wa.me/$phone?text=${enc(text).replace("+", "%20")}")) toast("تعذّر فتح واتساب")
            }
            MidButton("طباعة", Modifier.weight(1f), outlined = true) { printText(context, "فاتورة ${sale.str("invoice_no")}", text) }
        }
        val slipPath = sale.str("deposit_slip_path")
        if (user?.isManager == true && slipPath != null) MidButton("عرض إيصال الإيداع", Modifier.fillMaxWidth(), outlined = true) {
            scope.launch {
                try { openUrl(context, repo.api.signedUrl(Backend.RECEIPT_BUCKET, slipPath, 300, repo.api.token())) } catch (e: Exception) { toast(e.userMessage("تعذّر فتح الصورة")) }
            }
        }
        if (canRequest) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canEdit) MidButton("طلب تعديل", Modifier.weight(1f), outlined = true) { nav.go(Screen.InvoiceChange(id, "replace")) }
            MidButton("طلب إلغاء", Modifier.weight(1f), outlined = true, danger = true) { nav.go(Screen.InvoiceChange(id, "cancel")) }
        }
        MidButton("فاتورة جديدة", Modifier.fillMaxWidth(), outlined = true) { nav.replace(Screen.Sale()) }
    }
}

fun changeKindLabel(kind: String?) = if (kind == "replace") "طلب تعديل" else "طلب إلغاء"

/** Why a cancel/edit request cannot be made right now, or null when it can. */
fun changeBlocker(sale: DataRow?, trip: DataRow?, closingSent: Boolean, userId: String?, manager: Boolean): String? {
    if (sale == null || userId == null) return "الفاتورة غير متاحة"
    if (sale.bool("_pending") == true) return "الفاتورة لم تُرفع بعد؛ زامن أولاً ثم اطلب الإلغاء أو التعديل"
    if (manager) return null // the server checks the campaign state for managers
    if (sale.str("created_by") != userId) return "الفاتورة تخص حساباً آخر"
    if (trip == null || trip.str("status") != "open" || trip.str("id") != sale.str("trip_id")) return "الحملة مغلقة؛ لا يمكن طلب إلغاء أو تعديل الفاتورة"
    if (closingSent) return "أُرسل إغلاق الحملة؛ لا يمكن طلب إلغاء أو تعديل الفاتورة"
    return null
}

/** Deposit invoices can be cancelled, not edited: the slip belongs to the original amount. */
fun canReplace(sale: DataRow?): Boolean = sale != null && sale.str("payment_method") != "deposit" && sale.num("deposit_amount") == 0.0

/** Prints (or saves as PDF) plain right-to-left text through Android's print dialog. */
fun printText(context: Context, title: String, text: String) {
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("*", "")
    val html = "<html dir=\"rtl\"><body style=\"font-size:16px;font-family:sans-serif;white-space:pre-wrap\">$escaped</body></html>"
    val view = WebView(context)
    view.webViewClient = object : WebViewClient() {
        override fun onPageFinished(webView: WebView, url: String?) {
            runCatching {
                (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(title, webView.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
            }
        }
    }
    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}
