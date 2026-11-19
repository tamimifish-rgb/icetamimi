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
import com.tamimi.naqiice.core.ServiceState
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.serviceState
import com.tamimi.naqiice.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The open campaign's truck against its service plan, from what the phone last loaded. */
fun truckServiceState(care: JsonObject?, today: String = adenDay()): ServiceState? {
    val plan = care?.get("plan") as? JsonObject ?: return null
    return serviceState(
        plan.numOrNull("interval_km")?.toInt(), plan.numOrNull("interval_days")?.toInt(),
        plan.numOrNull("last_service_km"), plan.str("last_service_date"), care.numOrNull("km"), today,
    )
}

/** Warns on the Today screen when the campaign's truck is due, or nearly due, for service. */
@Composable
fun TruckServiceBanner() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val supported by repo.truckCareSupport.collectAsState()
    val care by repo.truckCare.collectAsState()
    if (!supported) return
    when (truckServiceState(care)) {
        ServiceState.Due -> Banner(Ice.destructive.copy(alpha = 0.1f), Modifier.tap { nav.go(Screen.TruckIssue) }) {
            Text("الشاحنة تحتاج صيانة — أخبر المشرف قبل الخروج.", style = T.sm.bold.color(Ice.destructive))
        }
        ServiceState.Soon -> Banner(Ice.warning.copy(alpha = 0.3f)) { Text("موعد صيانة الشاحنة قريب.", style = T.sm) }
        else -> {}
    }
}

/**
 * The distributor reports a problem with the truck of his open campaign (title, details, urgent,
 * photo). The report is queued like any record and uploads when online; the truck's open reports
 * are listed below the form.
 */
@Composable
fun TruckIssueScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val care by repo.truckCare.collectAsState()
    val pending by repo.pending.collectAsState()
    val operation = rememberOperation()
    val toast = rememberToast()
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<String?>(null) }
    val truck = snap.trip?.str("truck_id")

    fun send() = operation.run { id ->
        val user = me ?: throw Notice("سجّل الدخول")
        if (truck == null || !snap.ownOpenTrip(user.id)) throw Notice("تحتاج حملة مفتوحة معينة لك")
        if (title.isBlank()) throw Notice("اكتب عنوان المشكلة")
        repo.enqueue("truck_issue", buildJsonObject {
            put("id", id); put("truck_id", truck); put("title", title.trim()); put("details", details.trim().ifEmpty { null })
            put("severity", if (urgent) "urgent" else "normal")
            if (photo != null) { put("photo_path", "${user.id}/issue-$id.jpg"); put("photo_blob", photo) } else put("photo_path", null as String?)
            put("created_by", user.id); put("created_at", isoNow())
        })
        toast("تم إرسال البلاغ للمشرف")
        nav.back()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("بلاغ عطل في الشاحنة")
        if (truck == null) { Empty("لا توجد حملة مفتوحة."); return@Column }
        care?.numOrNull("km")?.let { Text("آخر قراءة للعداد: ${formatNum(it)} كم", style = T.sm.muted) }
        Field(title, { title = it }, "عنوان المشكلة")
        Field(details, { details = it }, "التفاصيل (اختياري)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("عادي", { urgent = false }, Modifier.weight(1f), if (!urgent) Variant.Default else Variant.Outline, height = 44.dp)
            Btn("عاجل", { urgent = true }, Modifier.weight(1f), if (urgent) Variant.Destructive else Variant.Outline, height = 44.dp)
        }
        PhotoPick("صورة المشكلة (اختياري)", photo) { photo = it }
        BigButton("إرسال البلاغ", enabled = !operation.busy) { send() }

        // Open reports for this truck: those the server knows and those still on the phone.
        val waiting = pending.filter { it.kind == "truck_issue" && it.payload.str("truck_id") == truck }.map { it.payload }
        val known = (care?.takeIf { it.str("truck_id") == truck }?.rows("issues") ?: emptyList()).filter { row -> waiting.none { it.str("id") == row.str("id") } }
        val issues = waiting + known
        if (issues.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("بلاغات مفتوحة لهذه الشاحنة")
            ListCard {
                issues.forEachIndexed { index, issue ->
                    if (index > 0) Divider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(issue.str("title") ?: "", style = T.sm.medium)
                        val state = when (issue.str("status")) { "in_progress" -> "قيد الإصلاح"; null -> "بانتظار الرفع"; else -> "مفتوح" }
                        Text(
                            state + (if (issue.str("severity") == "urgent") " · عاجل" else "") + " · " + formatDate(issue.str("reported_at") ?: issue.str("created_at")),
                            style = T.xs.color(if (issue.str("severity") == "urgent") Ice.destructive else Ice.mutedText),
                        )
                    }
                }
            }
        }
    }
}
