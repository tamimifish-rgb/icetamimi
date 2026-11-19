package com.tamimi.naqiice.ui

import com.tamimi.naqiice.core.handoverError
import com.tamimi.naqiice.core.handoverAmounts
import com.tamimi.naqiice.core.HandoverMethod
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.jstr
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.Me
import com.tamimi.naqiice.data.Snapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Field records need the distributor's own open campaign with no closing sent. */
private fun requireCampaign(user: Me?, snap: Snapshot): Me {
    if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
    if (snap.closingSent) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
    return user
}

// ---- expenses -------------------------------------------------------------------------

private val EXPENSE_CATEGORIES = listOf("وقود", "وجبات", "إصلاح الشاحنة", "مواقف/رسوم", "أخرى")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpensesScreen() {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var category by remember { mutableStateOf(EXPENSE_CATEGORIES.first()) }
    var amount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var odometer by remember { mutableStateOf("") }
    var odometerPhoto by remember { mutableStateOf<String?>(null) }
    val fuel = category == "وقود"
    val day = snap.day

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("المصروفات")
        // flex flex-wrap gap-2 of rounded-full chips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EXPENSE_CATEGORIES.forEach { c ->
                val on = category == c
                Box(Modifier.box(100.dp, if (on) Ice.primary else Ice.card, if (on) Ice.primary else Ice.border).tap { category = c }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(c, style = T.sm.color(if (on) Ice.onPrimary else Ice.foreground))
                }
            }
        }
        Field(amount, { amount = it }, "المبلغ", numeric = true, large = true)
        if (fuel) {
            Field(odometer, { odometer = it }, "قراءة عداد الشاحنة (كم)", numeric = true)
            PhotoPick("صورة العداد (مطلوبة)", odometerPhoto) { odometerPhoto = it }
        }
        PhotoPick("صورة الفاتورة (اختياري)", photo) { photo = it }
        Field(notes, { notes = it }, "ملاحظات")
        BigButton("حفظ المصروف", enabled = !operation.busy) {
            operation.run { id ->
                val user = requireCampaign(me, snap)
                val a = parseNumber(amount)
                if (!a.isFinite() || a <= 0) throw Notice("أدخل مبلغاً موجباً")
                if (fuel && odometer.isEmpty()) throw Notice("أدخل قراءة عداد الشاحنة")
                if (fuel && odometerPhoto == null) throw Notice("أرفق صورة العداد")
                val reading = parseNumber(odometer)
                if (fuel && (!reading.isFinite() || reading < 0)) throw Notice("قراءة العداد غير صحيحة")
                repo.enqueue("expense", buildJsonObject {
                    put("id", id); put("truck_id", jstr(snap.trip?.str("truck_id"))); put("trip_id", jstr(snap.tripId))
                    put("category", category); put("amount", jnum(a)); put("notes", jstr(notes.ifEmpty { null }))
                    put("receipt_path", jstr(photo?.let { "${user.id}/exp-$id.jpg" }))
                    photo?.let { put("receipt_blob", it) }
                    put("odometer", if (fuel) jnum(reading) else JsonNull)
                    put("odometer_photo_path", jstr(if (fuel && odometerPhoto != null) "${user.id}/odo-$id.jpg" else null))
                    if (fuel) odometerPhoto?.let { put("odometer_photo_blob", it) }
                    put("created_by", user.id); put("created_at", isoNow())
                })
                amount = ""; notes = ""; photo = null; odometer = ""; odometerPhoto = null
                toast("تم حفظ المصروف")
            }
        }
        Text("مصروفات اليوم — ${formatMoney(day.spent)}", style = T.base.bold)
        if (day.expenses.isEmpty()) Empty("لا توجد مصروفات") else day.expenses.forEach { e ->
            Row(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(e.str("category") ?: "", style = T.sm.bold)
                    Text("${formatDate(e.str("created_at"))} ${e.str("notes") ?: ""}", style = T.xs.muted)
                }
                Text(formatMoney(e.num("amount")), style = T.sm.bold)
            }
        }
    }
}

// ---- maintenance ----------------------------------------------------------------------

@Composable
fun MaintenanceScreen() {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var title by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(adenDay()) }
    var photo by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("صيانة الشاحنة")
        Field(title, { title = it }, "وصف العمل (مثال: تغيير زيت)")
        Column {
            Text("تاريخ الصيانة", style = T.sm.muted)
            DateField(date) { date = it }
        }
        Field(cost, { cost = it }, "التكلفة", numeric = true)
        PhotoPick("صورة الفاتورة (اختياري)", photo) { photo = it }
        Field(notes, { notes = it }, "ملاحظات")
        BigButton("حفظ", enabled = !operation.busy) {
            operation.run { id ->
                val user = requireCampaign(me, snap)
                if (title.isBlank()) throw Notice("اكتب وصف العمل")
                val amount = if (cost.isEmpty()) 0.0 else parseNumber(cost)
                if (!amount.isFinite() || amount < 0) throw Notice("التكلفة يجب أن تكون رقماً غير سالب")
                val today = adenDay()
                val jobDate = date.ifEmpty { today }
                if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(jobDate) || jobDate > today) throw Notice("تاريخ الصيانة غير صحيح")
                repo.enqueue("maintenance", buildJsonObject {
                    put("id", id); put("truck_id", jstr(snap.trip?.str("truck_id"))); put("trip_id", jstr(snap.tripId)); put("freezer_id", JsonNull)
                    put("title", title); put("job_date", jobDate); put("cost", jnum(amount)); put("notes", jstr(notes.ifEmpty { null }))
                    put("receipt_path", jstr(photo?.let { "${user.id}/$id.jpg" }))
                    photo?.let { put("receipt_blob", it) }
                    put("created_by", user.id); put("created_at", isoNow())
                })
                title = ""; cost = ""; notes = ""; photo = null; date = adenDay()
                toast("تم تسجيل الصيانة")
            }
        }
    }
}

// ---- freezers -------------------------------------------------------------------------

private val FREEZER_ACTIONS = listOf(
    "place" to "تسليم لعميل", "move" to "نقل لعميل آخر", "remove" to "سحب للمستودع", "condition" to "ملاحظة حالة", "maintenance" to "إرسال للصيانة",
)

@Composable
fun FreezerActionScreen(forCustomer: String?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var type by remember { mutableStateOf(if (forCustomer != null) "condition" else "place") }
    var freezerId by remember { mutableStateOf<String?>(null) }
    var customerId by remember { mutableStateOf(forCustomer) }
    var picking by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    if (snap.ref == null) { Empty("جارٍ التحميل…"); return }
    if (picking) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { PageTitle("اختر العميل"); CustomerPicker { customerId = it; picking = false } }
        return
    }
    val needsCustomer = type == "place" || type == "move"
    val freezers = snap.freezers.filter { f ->
        when (type) { "place" -> f.str("status") == "warehouse"; "remove", "move" -> f.str("status") == "placed"; else -> true }
    }.filter { forCustomer == null || type == "place" || it.str("customer_id") == forCustomer }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("حركة ثلاجة")
        // grid grid-cols-2 gap-2 of rounded-xl bordered choices
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FREEZER_ACTIONS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) ->
                        val on = type == value
                        Box(Modifier.weight(1f).box(Radius.xl, if (on) Ice.primary else Ice.card, if (on) Ice.primary else Ice.border).tap { type = value; freezerId = null }.padding(12.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = T.sm.medium.color(if (on) Ice.onPrimary else Ice.foreground))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Picker("الثلاجة", freezers.map { f ->
            f.str("id") to "${f.str("serial") ?: ""} · ${f.obj("freezer_models")?.str("name") ?: ""} " + (f.str("customer_id")?.let { "· ${snap.customer(it)?.str("name") ?: ""}" } ?: "")
        }, freezerId) { freezerId = it }
        if (needsCustomer) Btn({ picking = true }, Modifier.fillMaxWidth(), Variant.Outline, height = 48.dp) {
            Text(snap.customer(customerId)?.str("name")?.let { "العميل: $it" } ?: "اختر العميل", Modifier.weight(1f))
        }
        Field(notes, { notes = it }, "ملاحظات / الحالة")
        BigButton("حفظ", enabled = !operation.busy) {
            operation.run { id ->
                val user = requireCampaign(me, snap)
                val freezer = freezerId ?: throw Notice("اختر الثلاجة")
                if (needsCustomer && customerId == null) throw Notice("اختر العميل")
                repo.enqueue("freezer_event", buildJsonObject {
                    put("id", id); put("trip_id", snap.tripId); put("freezer_id", freezer); put("event_type", type)
                    put("customer_id", jstr(if (needsCustomer) customerId else null)); put("notes", jstr(notes.ifEmpty { null }))
                    put("created_by", user.id); put("created_at", isoNow())
                })
                toast("تم تسجيل الحركة")
                nav.back()
            }
        }
    }
}

// ---- closing --------------------------------------------------------------------------

@Composable
fun ClosingScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val changeSupport by repo.changeSupport.collectAsState()
    val handoverSupport by repo.handoverSupport.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var counted by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var waitingChanges by remember { mutableStateOf(0) }
    var method by remember { mutableStateOf(HandoverMethod.Cash) }
    var transferText by remember { mutableStateOf("") }
    var slip by remember { mutableStateOf<String?>(null) }
    val tripId = snap.tripId

    LaunchedEffect(tripId, changeSupport) {
        if (!changeSupport || tripId == null) return@LaunchedEffect
        runCatching { waitingChanges = repo.api.count("pureice_invoice_changes", "select=id&status=eq.pending&trip_id=eq.$tripId", repo.api.token()) }
    }

    if (snap.trip == null) { Empty("لا توجد حملة مفتوحة."); return }
    val flagged = snap.closing?.str("status") == "flagged"
    if (snap.isAssistant) { Empty("الإغلاق يرسله الموزع الرئيسي للحملة. سلّم له النقد الذي معك: ${formatMoney(snap.cashHeldBy(me?.id))}."); return }
    if (!flagged && snap.closingSent) { Empty("تم إرسال إغلاق الحملة بالفعل."); return }
    val day = snap.day
    val c = parseNumber(counted)
    val difference = if (counted.isEmpty()) 0.0 else c - day.expected

    fun send() = operation.run { operationId ->
        val user = me
        if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
        if (!snap.canCloseOrApprove) throw Notice("الإغلاق يرسله الموزع الرئيسي للحملة")
        if ((snap.closing != null && !flagged) || snap.closingQueued) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
        if (counted.isEmpty()) throw Notice("أدخل النقد المعدود")
        if (!c.isFinite() || c < 0) throw Notice("النقد المعدود غير صحيح")
        val transfer = if (transferText.isEmpty()) 0.0 else parseNumber(transferText)
        if (handoverSupport) handoverError(c, method, transfer, slip != null)?.let { throw Notice(it) }
        // A returned closing is corrected under its original id.
        val id = if (flagged) snap.closing?.str("id") ?: operationId else operationId
        repo.enqueue("closing", buildJsonObject {
            put("id", id); put("trip_id", tripId); put("expected_cash", jnum(day.expected)); put("counted_cash", jnum(c))
            put("difference", jnum(difference)); put("notes", jstr(notes.ifEmpty { null })); put("created_by", user.id); put("created_at", isoNow())
        })
        if (handoverSupport) {
            val (cash, deposit) = handoverAmounts(c, method, transfer)
            val handoverId = java.util.UUID.randomUUID().toString()
            val file = slip.takeIf { deposit > 0 }
            val pdf = file?.endsWith(".pdf") == true
            repo.enqueue("closing_handover", buildJsonObject {
                put("id", handoverId); put("closing_id", id); put("trip_id", tripId)
                put("cash_amount", jnum(cash)); put("transfer_amount", jnum(deposit))
                if (file != null) {
                    put("slip_path", "${user.id}/handover-$handoverId.${if (pdf) "pdf" else "jpg"}"); put("slip_blob", file)
                    put("slip_type", if (pdf) "application/pdf" else "image/jpeg")
                } else put("slip_path", JsonNull)
                put("created_by", user.id); put("created_at", isoNow())
            })
        }
        toast("تم إرسال الإغلاق للمشرف")
        nav.back()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("إغلاق الحملة")
        if (waitingChanges > 0) Banner(Ice.warning.copy(alpha = 0.3f), Modifier.tap { nav.go(Screen.InvoiceChanges) }) {
            Text("لديك $waitingChanges طلب إلغاء أو تعديل فاتورة بانتظار المشرف. لن يُقبل الإغلاق قبل مراجعته.")
        }
        if (flagged) Banner(Ice.destructive.copy(alpha = 0.1f)) {
            Text("أعاد المشرف الإغلاق للمراجعة" + (snap.closing?.str("review_note")?.let { ": $it" } ?: ""))
        }
        StatGrid(listOf(
            Triple("مبيعات نقدية", formatMoney(day.cashSales), Ice.foreground),
            Triple("إيداعات", formatMoney(day.deposits), Ice.foreground),
            Triple("تحصيل ديون", formatMoney(day.collected), Ice.foreground),
            Triple("مصروفات", formatMoney(day.spent), Ice.onWarning),
            Triple("النقد المتوقع", formatMoney(day.expected), Ice.success),
        ))
        Column {
            SectionTitle("المتبقي في الشاحنة (حسب النظام)")
            ListCard {
                snap.variants.forEachIndexed { index, v ->
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(v.str("name") ?: "", style = T.sm)
                        Text(formatNum(snap.stockOf(v.str("id") ?: "")), style = T.sm.bold)
                    }
                }
            }
            Text("يسجّل المشرف المرتجع والتالف عند وصولك للمصنع.", style = T.xs.muted, modifier = Modifier.padding(top = 4.dp))
        }
        Field(counted, { counted = it }, "النقد المعدود فعلياً", numeric = true, large = true)
        if (counted.isNotEmpty()) Banner(if (difference == 0.0) Ice.success.copy(alpha = 0.15f) else Ice.destructive.copy(alpha = 0.1f)) {
            Text(
                "الفرق: ${formatMoney(difference)} " + if (difference < 0) "(عجز)" else if (difference > 0) "(زيادة)" else "✓",
                Modifier.fillMaxWidth(), style = T.base.bold.color(if (difference == 0.0) Ice.success else Ice.destructive), textAlign = TextAlign.Center,
            )
        }
        if (handoverSupport) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("طريقة تسليم النقد", style = T.sm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HandoverMethod.Cash to "نقد", HandoverMethod.Transfer to "إيداع/حوالة", HandoverMethod.Both to "الاثنان").forEach { (m, label) ->
                    Btn(label, { method = m }, Modifier.weight(1f), if (method == m) Variant.Default else Variant.Outline, height = 44.dp, style = T.xs.medium)
                }
            }
            if (method == HandoverMethod.Both) {
                Field(transferText, { transferText = it }, "مبلغ الإيداع أو الحوالة", numeric = true)
                val (cash, _) = handoverAmounts(c, method, if (transferText.isEmpty()) 0.0 else parseNumber(transferText))
                if (counted.isNotEmpty() && transferText.isNotEmpty()) Text("الباقي نقداً: ${formatMoney(cash)}", style = T.sm.muted)
            }
            if (method != HandoverMethod.Cash) SlipPick(slip) { slip = it }
        }
        Field(notes, { notes = it }, "ملاحظات")
        BigButton("إرسال الإغلاق", enabled = !operation.busy) { if (flagged || counted.isEmpty()) send() else confirm = true }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text("بعد إرسال الإغلاق يتوقف البيع في هذه الحملة. متابعة؟") },
        confirmButton = { TextButton({ confirm = false; send() }) { Text("موافق") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
    )
}

/** The web's `<input type="date" class="h-12">`: shows the day and opens the system date picker. */
@Composable
fun DateField(value: String, max: String = adenDay(), onChange: (String) -> Unit) {
    val context = LocalContext.current
    val parts = value.split('-').mapNotNull { it.toIntOrNull() }
    Row(
        Modifier.fillMaxWidth().height(48.dp).smallShadow(Radius.md).box(Radius.md, null, Ice.input).tap {
            val (y, m, d) = if (parts.size == 3) parts else adenDay().split('-').map { it.toInt() }
            android.app.DatePickerDialog(context, { _, year, month, day -> onChange("%04d-%02d-%02d".format(java.util.Locale.US, year, month + 1, day)) }, y, m - 1, d).apply {
                com.tamimi.naqiice.core.parseIso("${max}T23:59:59Z")?.let { datePicker.maxDate = it }
            }.show()
        }.padding(horizontal = 12.dp),
        // A browser lays a date input out left-to-right: the date, then the calendar icon.
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
    ) {
        Icon(Lucide.Calendar, null, Modifier.size(16.dp))
        Text(if (parts.size == 3) "%02d/%02d/%04d".format(java.util.Locale.US, parts[2], parts[1], parts[0]) else "", style = T.base.ltr)
    }
}
