package com.tamimi.naqiice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.adenBounds
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.adenDayOf
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.shiftDay
import com.tamimi.naqiice.core.staffSummary
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.enc
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private class StaffData(
    val name: String,
    val sales: List<DataRow>,
    val payments: List<DataRow>,
    val closings: List<DataRow>,
    val ledger: List<DataRow>,
    /** closing id → charged (true) or absorbed (false). */
    val decisions: Map<String, Boolean>,
)

private val KIND = mapOf("shortage" to "عجز إغلاق", "deduction" to "خصم من الراتب", "adjustment" to "تسوية")

/**
 * One employee's profile: sales figures for a period, his closings and, with the staff-account
 * feature, what he owes for charged shortages less salary deductions. Managers open anyone's;
 * a distributor only his own, read-only. The admin records salary deductions here.
 */
@Composable
fun StaffProfileScreen(staffId: String) {
    val repo = LocalRepo.current
    val me by repo.me.collectAsState()
    val ledgerSupport by repo.ledgerSupport.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    val today = remember { adenDay() }
    var from by remember { mutableStateOf(shiftDay(today, -29)) }
    var to by remember { mutableStateOf(today) }
    var deducting by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val load = rememberLoaded(staffId, from, to, ledgerSupport) { bearer ->
        val (a, b) = adenBounds(from, to).let { enc(it.first) to enc(it.second) }
        val name = repo.api.select("profiles", "select=id,full_name&id=eq.$staffId", bearer).firstOrNull()?.str("full_name") ?: ""
        val sales = repo.api.selectAll("sales", "select=id,total,cash_paid,credit_amount,trip_id,created_at&created_by=eq.$staffId&created_at=gte.$a&created_at=lt.$b&order=created_at.desc,id", bearer)
        val payments = repo.api.selectAll("payments", "select=id,amount,created_at&created_by=eq.$staffId&created_at=gte.$a&created_at=lt.$b&order=id", bearer)
        val closings = repo.api.selectAll("closings", "select=*,trips(trip_date,trucks(name))&created_by=eq.$staffId&created_at=gte.$a&created_at=lt.$b&order=created_at.desc,id", bearer)
        // The account is all-time: what he owes does not depend on the period shown.
        val ledger = if (!ledgerSupport) emptyList() else repo.api.selectAll("pureice_staff_ledger", "select=*&staff_id=eq.$staffId&order=created_at.desc,id", bearer)
        val decisions = if (!ledgerSupport || closings.isEmpty()) emptyMap() else repo.api.selectAll(
            "pureice_shortage_decision", "select=closing_id,charged&closing_id=in.(${closings.mapNotNull { it.str("id") }.joinToString(",")})", bearer,
        ).mapNotNull { row -> row.str("closing_id")?.let { it to (row.bool("charged") == true) } }.toMap()
        StaffData(name, sales, payments, closings, ledger, decisions)
    }
    val data = load.data
    val isAdmin = me?.isAdmin == true

    fun deduct() = operation.run {
        val value = parseNumber(amount)
        if (!value.isFinite() || value <= 0) throw Notice("أدخل مبلغاً صحيحاً")
        repo.api.rpc("pureice_add_staff_entry", buildJsonObject {
            put("p_id", UUID.randomUUID().toString()); put("p_staff", staffId); put("p_kind", "deduction")
            put("p_amount", value); put("p_note", if (note.isBlank()) JsonNull else kotlinx.serialization.json.JsonPrimitive(note.trim()))
        }, repo.api.token())
        toast("سُجّل الخصم")
        deducting = false; amount = ""; note = ""
        load.reload()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle(if (staffId == me?.id) "حسابي" else data?.name?.ifEmpty { null } ?: "ملف الموزع")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text("من", style = T.sm); DateInput(from, Modifier.fillMaxWidth()) { from = it } }
            Column(Modifier.weight(1f)) { Text("إلى", style = T.sm); DateInput(to, Modifier.fillMaxWidth()) { to = it } }
        }
        if (load.error != null) { LoadError(load.error, load.reload); return@Column }
        if (data == null) { Empty("جارٍ التحميل…"); return@Column }
        val summary = staffSummary(data.sales, data.payments, data.closings, data.ledger)

        StatGrid(listOf(
            Triple("المبيعات", formatMoney(summary.sales), Ice.foreground),
            Triple("عدد الفواتير", summary.invoices.toString(), Ice.foreground),
            Triple("مبيعات نقدية", formatMoney(summary.cashSales), Ice.foreground),
            Triple("مبيعات آجلة", formatMoney(summary.creditSales), Ice.foreground),
            Triple("تحصيل ديون", formatMoney(summary.collected), Ice.success),
            Triple("الحملات", summary.campaigns.toString(), Ice.foreground),
            Triple("العجز في الفترة", formatMoney(summary.shortages), if (summary.shortages > 0) Ice.destructive else Ice.foreground),
        ))

        if (ledgerSupport) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("الحساب")
            Banner(if (summary.balance > 0) Ice.destructive.copy(alpha = 0.1f) else Ice.success.copy(alpha = 0.15f)) {
                Text("المستحق للخصم من الراتب: ${formatMoney(summary.balance)}", style = T.base.bold.color(if (summary.balance > 0) Ice.destructive else Ice.success))
            }
            if (isAdmin && staffId != me?.id) Btn("تسجيل خصم من الراتب", { deducting = true }, Modifier.fillMaxWidth(), Variant.Outline, height = 44.dp)
            if (data.ledger.isEmpty()) Text("لا توجد قيود في الحساب", style = T.sm.muted)
            else ListCard {
                data.ledger.forEachIndexed { index, e ->
                    if (index > 0) Divider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(KIND[e.str("kind")] ?: "", style = T.sm.medium)
                            Text(formatMoney(e.num("amount")), style = T.sm.bold.color(if (e.num("amount") > 0) Ice.destructive else Ice.success))
                        }
                        e.str("note")?.takeIf { it.isNotEmpty() }?.let { Text(it, style = T.xs.muted) }
                        Text(formatDate(e.str("created_at")), style = T.xs.muted)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("المبيعات حسب اليوم")
            val days = data.sales.groupBy { adenDayOf(it.str("created_at")) }.toSortedMap(reverseOrder())
            if (days.isEmpty()) Text("لا توجد مبيعات في هذه الفترة", style = T.sm.muted)
            else ListCard {
                days.entries.forEachIndexed { index, (day, rows) ->
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$day · ${rows.size} فاتورة", style = T.sm)
                        Text(formatMoney(rows.sumOf { it.num("total") }), style = T.sm.bold)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("الإغلاقات")
            if (data.closings.isEmpty()) Text("لا توجد إغلاقات في هذه الفترة", style = T.sm.muted)
            else ListCard {
                data.closings.forEachIndexed { index, c ->
                    if (index > 0) Divider()
                    val difference = c.num("difference")
                    val decision = when {
                        difference >= 0 || c.str("status") != "approved" || !ledgerSupport -> null
                        else -> when (data.decisions[c.str("id")]) { true -> "على حساب الموزع"; false -> "تتحمله الشركة"; null -> "بانتظار القرار" }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${c.obj("trips")?.obj("trucks")?.str("name") ?: ""} — ${c.obj("trips")?.str("trip_date") ?: ""}", style = T.sm)
                            Text(formatMoney(difference), style = T.sm.bold.color(if (difference < 0) Ice.destructive else Ice.foreground))
                        }
                        if (decision != null) Text("العجز: $decision", style = T.xs.muted)
                    }
                }
            }
        }
    }

    if (deducting) FormDialog("خصم من الراتب", { if (!operation.busy) deducting = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(amount, { amount = it }, "المبلغ", numeric = true)
            Field(note, { note = it }, "ملاحظة")
            Btn("حفظ", { deduct() }, Modifier.fillMaxWidth(), enabled = !operation.busy)
        }
    }
}

private fun DataRow.obj(key: String) = (this[key] as? kotlinx.serialization.json.JsonObject)
