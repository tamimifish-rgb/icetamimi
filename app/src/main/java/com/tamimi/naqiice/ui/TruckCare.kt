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
import com.tamimi.naqiice.core.ServiceState
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatMoney
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.serviceState
import com.tamimi.naqiice.core.shiftDay
import com.tamimi.naqiice.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private class TruckRow(val truck: DataRow, val plan: DataRow?, val km: Double?, val state: ServiceState, val issues: List<DataRow>, val services: List<DataRow>)

private val STATE_LABEL = mapOf(
    ServiceState.Due to "تحتاج صيانة", ServiceState.Soon to "صيانة قريبة", ServiceState.Ok to "سليمة", ServiceState.NoPlan to "بلا خطة صيانة",
)
private val STATE_ORDER = listOf(ServiceState.Due, ServiceState.Soon, ServiceState.Ok, ServiceState.NoPlan)

/** The plan's next service as text: "عند 123,000 كم · بتاريخ 2026-12-30", or null without a plan. */
private fun nextDue(plan: DataRow?): String? {
    if (plan == null) return null
    val km = plan.numOrNull("interval_km")?.let { i -> plan.numOrNull("last_service_km")?.let { "عند ${formatNum(it + i)} كم" } }
    val day = plan.numOrNull("interval_days")?.let { d -> plan.str("last_service_date")?.let { "بتاريخ ${shiftDay(it, d.toInt())}" } }
    return listOfNotNull(km, day).joinToString(" · ").ifEmpty { null }
}

/**
 * Managers: every truck against its service plan (due ones first), its open problem reports with
 * their follow-up, and its recorded services. Plans are set and services recorded here.
 */
@Composable
fun TruckCareScreen() {
    val repo = LocalRepo.current
    val operation = rememberOperation()
    val toast = rememberToast()
    val today = remember { adenDay() }
    var planFor by remember { mutableStateOf<DataRow?>(null) }
    var serviceFor by remember { mutableStateOf<DataRow?>(null) }
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf("") }
    var f3 by remember { mutableStateOf("") }

    val load = rememberLoaded("truck-care") { bearer ->
        val trucks = repo.api.selectAll("trucks", "select=id,name,code,active&active=eq.true&order=code,id", bearer)
        val plans = repo.api.selectAll("pureice_truck_service_plan", "select=*&order=truck_id", bearer)
        val issues = repo.api.selectAll("pureice_truck_issues", "select=*&status=neq.fixed&order=reported_at.desc,id", bearer)
        val services = repo.api.selectAll("pureice_truck_services", "select=*&order=service_date.desc,id", bearer)
        trucks.mapNotNull { t ->
            val id = t.str("id") ?: return@mapNotNull null
            val plan = plans.firstOrNull { it.str("truck_id") == id }
            val km = (repo.api.rpc("pureice_truck_km", buildJsonObject { put("p_truck", id) }, bearer) as? JsonPrimitive)?.content?.toDoubleOrNull()
            val state = serviceState(
                plan?.numOrNull("interval_km")?.toInt(), plan?.numOrNull("interval_days")?.toInt(),
                plan?.numOrNull("last_service_km"), plan?.str("last_service_date"), km, today,
            )
            TruckRow(t, plan, km, state, issues.filter { it.str("truck_id") == id }, services.filter { it.str("truck_id") == id })
        }.sortedBy { STATE_ORDER.indexOf(it.state) }
    }

    fun call(function: String, done: String, args: kotlinx.serialization.json.JsonObject) = operation.run {
        repo.api.rpc(function, args, repo.api.token())
        toast(done)
        planFor = null; serviceFor = null
        load.reload()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("صيانة الشاحنات")
        val rows = load.data
        if (load.error != null) { LoadError(load.error, load.reload); return@Column }
        if (rows == null) { Empty("جارٍ التحميل…"); return@Column }
        if (rows.isEmpty()) { Empty("لا توجد شاحنات"); return@Column }
        rows.forEach { row ->
            val truckId = row.truck.str("id") ?: return@forEach
            IceCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.truck.str("name") ?: "", style = T.base.bold)
                        val (bg, fg) = when (row.state) {
                            ServiceState.Due -> Ice.destructive.copy(alpha = 0.1f) to Ice.destructive
                            ServiceState.Soon -> Ice.warning.copy(alpha = 0.3f) to Ice.foreground
                            ServiceState.Ok -> Ice.success.copy(alpha = 0.15f) to Ice.success
                            ServiceState.NoPlan -> Ice.muted to Ice.mutedText
                        }
                        Chip(STATE_LABEL[row.state] ?: "", bg, fg)
                    }
                    Text("العداد: " + (row.km?.let { "${formatNum(it)} كم" } ?: "لا توجد قراءة"), style = T.sm)
                    nextDue(row.plan)?.let { Text("الصيانة القادمة: $it", style = T.sm) }
                    row.plan?.let { plan ->
                        val every = listOfNotNull(plan.numOrNull("interval_km")?.let { "${formatNum(it)} كم" }, plan.numOrNull("interval_days")?.let { "${it.toInt()} يوم" })
                        if (every.isNotEmpty()) Text("الخطة: كل " + every.joinToString(" أو "), style = T.xs.muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallBtn("تعديل خطة الصيانة", {
                            f1 = row.plan?.numOrNull("interval_km")?.toInt()?.toString() ?: ""; f2 = row.plan?.numOrNull("interval_days")?.toInt()?.toString() ?: ""
                            planFor = row.truck
                        }, Modifier.weight(1f), Variant.Outline, enabled = !operation.busy)
                        SmallBtn("تسجيل صيانة", { f1 = row.km?.toLong()?.toString() ?: ""; f2 = ""; f3 = ""; serviceFor = row.truck }, Modifier.weight(1f), enabled = !operation.busy)
                    }
                    if (row.issues.isNotEmpty()) {
                        Text("بلاغات مفتوحة", style = T.sm.bold)
                        row.issues.forEach { issue ->
                            val issueId = issue.str("id") ?: return@forEach
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(issue.str("title") ?: "", style = T.sm.medium.color(if (issue.str("severity") == "urgent") Ice.destructive else Ice.foreground))
                                issue.str("details")?.let { Text(it, style = T.xs.muted) }
                                Text((if (issue.str("status") == "in_progress") "قيد الإصلاح" else "مفتوح") + " · " + formatDate(issue.str("reported_at")), style = T.xs.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (issue.str("status") == "open") SmallBtn("قيد الإصلاح", {
                                        call("pureice_update_truck_issue", "تم التحديث", buildJsonObject { put("p_id", issueId); put("p_status", "in_progress"); put("p_note", JsonNull) })
                                    }, Modifier.weight(1f), Variant.Outline, enabled = !operation.busy)
                                    SmallBtn("تم الإصلاح", {
                                        call("pureice_update_truck_issue", "سُجّل الإصلاح", buildJsonObject { put("p_id", issueId); put("p_status", "fixed"); put("p_note", JsonNull) })
                                    }, Modifier.weight(1f), enabled = !operation.busy)
                                    issue.str("photo_path")?.let { DepositSlipButton(it, "الصورة") }
                                }
                            }
                        }
                    }
                    row.services.take(3).forEach { s ->
                        Text("صيانة ${s.str("service_date") ?: ""} · ${formatNum(s.num("km"))} كم · ${formatMoney(s.num("cost"))}" + (s.str("note")?.let { " · $it" } ?: ""), style = T.xs.muted)
                    }
                }
            }
        }
    }

    planFor?.let { truck ->
        FormDialog("خطة صيانة ${truck.str("name") ?: ""}", { if (!operation.busy) planFor = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(f1, { f1 = it }, "كل كم كيلومتر", numeric = true)
                Field(f2, { f2 = it }, "أو كل كم يوم", numeric = true)
                Btn("حفظ", {
                    val km = f1.trim().toIntOrNull(); val days = f2.trim().toIntOrNull()
                    if ((km ?: 0) <= 0 && (days ?: 0) <= 0) { toast("حدد عدد الكيلومترات أو الأيام"); return@Btn }
                    call("pureice_set_service_plan", "حُفظت الخطة", buildJsonObject {
                        put("p_truck", truck.str("id")); put("p_interval_km", km); put("p_interval_days", days)
                    })
                }, Modifier.fillMaxWidth(), enabled = !operation.busy)
            }
        }
    }
    serviceFor?.let { truck ->
        FormDialog("تسجيل صيانة ${truck.str("name") ?: ""}", { if (!operation.busy) serviceFor = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(f1, { f1 = it }, "قراءة العداد (كم)", numeric = true)
                Field(f2, { f2 = it }, "التكلفة", numeric = true)
                Field(f3, { f3 = it }, "ملاحظة")
                Btn("حفظ", {
                    val km = parseNumber(f1); val cost = if (f2.isBlank()) 0.0 else parseNumber(f2)
                    if (!km.isFinite() || km < 0 || !cost.isFinite() || cost < 0) { toast("أدخل قراءة العداد والتكلفة"); return@Btn }
                    call("pureice_record_service", "سُجّلت الصيانة", buildJsonObject {
                        put("p_id", UUID.randomUUID().toString()); put("p_truck", truck.str("id")); put("p_km", km); put("p_date", today)
                        put("p_cost", cost); put("p_note", f3.trim().ifEmpty { null })
                    })
                }, Modifier.fillMaxWidth(), enabled = !operation.busy)
            }
        }
    }
}
