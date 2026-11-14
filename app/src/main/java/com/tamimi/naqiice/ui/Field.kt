package com.tamimi.naqiice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("المصروفات")
        EXPENSE_CATEGORIES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { c -> MidButton(c, outlined = category != c) { category = c } } }
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
        SectionTitle("مصروفات اليوم — ${formatMoney(day.spent)}")
        if (day.expenses.isEmpty()) Empty("لا توجد مصروفات") else day.expenses.forEach { e ->
            IceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(e.str("category") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${formatDate(e.str("created_at"))} ${e.str("notes") ?: ""}", fontSize = 12.sp, color = Ice.mutedText)
                    }
                    Text(formatMoney(e.num("amount")), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("صيانة الشاحنة")
        Field(title, { title = it }, "وصف العمل (مثال: تغيير زيت)")
        Field(date, { date = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) }, "تاريخ الصيانة (سنة-شهر-يوم)", ltr = true)
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
        Column { PageTitle("اختر العميل"); CustomerPicker { customerId = it; picking = false } }
        return
    }
    val needsCustomer = type == "place" || type == "move"
    val freezers = snap.freezers.filter { f ->
        when (type) { "place" -> f.str("status") == "warehouse"; "remove", "move" -> f.str("status") == "placed"; else -> true }
    }.filter { forCustomer == null || type == "place" || it.str("customer_id") == forCustomer }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("حركة ثلاجة")
        FREEZER_ACTIONS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) -> MidButton(label, Modifier.weight(1f), outlined = type != value) { type = value; freezerId = null } }
            }
        }
        Picker("الثلاجة", freezers.map { f ->
            f.str("id") to listOfNotNull(f.str("serial"), f.obj("freezer_models")?.str("name"), f.str("customer_id")?.let { snap.customer(it)?.str("name") }).joinToString(" · ")
        }, freezerId) { freezerId = it }
        if (needsCustomer) MidButton(snap.customer(customerId)?.str("name")?.let { "العميل: $it" } ?: "اختر العميل", Modifier.fillMaxWidth(), outlined = true) { picking = true }
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
    val operation = rememberOperation()
    val toast = rememberToast()
    var counted by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var waitingChanges by remember { mutableStateOf(0) }
    val tripId = snap.tripId

    LaunchedEffect(tripId, changeSupport) {
        if (!changeSupport || tripId == null) return@LaunchedEffect
        runCatching { waitingChanges = repo.api.count("pureice_invoice_changes", "select=id&status=eq.pending&trip_id=eq.$tripId", repo.api.token()) }
    }

    if (snap.trip == null) { Empty("لا توجد حملة مفتوحة."); return }
    val flagged = snap.closing?.str("status") == "flagged"
    if (!flagged && snap.closingSent) { Empty("تم إرسال إغلاق الحملة بالفعل."); return }
    val day = snap.day
    val c = parseNumber(counted)
    val difference = if (counted.isEmpty()) 0.0 else c - day.expected

    fun send() = operation.run { operationId ->
        val user = me
        if (user == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
        if ((snap.closing != null && !flagged) || snap.closingQueued) throw Notice("تم إرسال إغلاق الحملة؛ الحفظ متوقف")
        if (counted.isEmpty()) throw Notice("أدخل النقد المعدود")
        if (!c.isFinite() || c < 0) throw Notice("النقد المعدود غير صحيح")
        // A returned closing is corrected under its original id.
        val id = if (flagged) snap.closing?.str("id") ?: operationId else operationId
        repo.enqueue("closing", buildJsonObject {
            put("id", id); put("trip_id", tripId); put("expected_cash", jnum(day.expected)); put("counted_cash", jnum(c))
            put("difference", jnum(difference)); put("notes", jstr(notes.ifEmpty { null })); put("created_by", user.id); put("created_at", isoNow())
        })
        toast("تم إرسال الإغلاق للمشرف")
        nav.back()
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("إغلاق الحملة")
        if (waitingChanges > 0) Banner(Ice.warning.copy(alpha = 0.3f)) {
            Text("لديك $waitingChanges طلب إلغاء أو تعديل فاتورة بانتظار المشرف. لن يُقبل الإغلاق قبل مراجعته.", fontSize = 14.sp)
        }
        if (flagged) Banner(Ice.destructive.copy(alpha = 0.1f)) {
            Text("أعاد المشرف الإغلاق للمراجعة" + (snap.closing?.str("review_note")?.let { ": $it" } ?: ""), fontSize = 14.sp)
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
            IceCard {
                Column {
                    snap.variants.forEachIndexed { index, v ->
                        if (index > 0) HorizontalDivider(color = Ice.border)
                        LineItem(v.str("name") ?: "", formatNum(snap.stockOf(v.str("id") ?: "")))
                    }
                }
            }
            Text("يسجّل المشرف المرتجع والتالف عند وصولك للمصنع.", fontSize = 12.sp, color = Ice.mutedText, modifier = Modifier.padding(top = 4.dp))
        }
        Field(counted, { counted = it }, "النقد المعدود فعلياً", numeric = true, large = true)
        if (counted.isNotEmpty()) Banner(if (difference == 0.0) Ice.success.copy(alpha = 0.15f) else Ice.destructive.copy(alpha = 0.1f)) {
            Text(
                "الفرق: ${formatMoney(difference)} " + if (difference < 0) "(عجز)" else if (difference > 0) "(زيادة)" else "✓",
                Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (difference == 0.0) Ice.success else Ice.destructive,
            )
        }
        Field(notes, { notes = it }, "ملاحظات")
        BigButton("إرسال الإغلاق", enabled = !operation.busy) { if (flagged || counted.isEmpty()) send() else confirm = true }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text("بعد إرسال الإغلاق يتوقف البيع في هذه الحملة. متابعة؟") },
        confirmButton = { TextButton({ confirm = false; send() }) { Text("إرسال") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
    )
}
