package com.tamimi.naqiice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class AssistantsData(val assistants: List<String>, val names: Map<String, String>, val distributors: List<String>)

/**
 * Managers: the campaign's assistant distributors. An assistant sells and collects from his own
 * phone on the campaign's stock and hands his cash to the main distributor; the server decides who
 * may be added (an active distributor on no other open campaign) and refuses once the closing is sent.
 */
@Composable
fun TripAssistants(tripId: String, mainId: String?, open: Boolean, closingSent: Boolean) {
    val repo = LocalRepo.current
    val supported by repo.assistantSupport.collectAsState()
    if (!supported) return
    val operation = rememberOperation()
    val toast = rememberToast()
    var pick by remember { mutableStateOf<String?>(null) }

    val load = rememberLoaded("assistants", tripId) { bearer ->
        coroutineScope {
            val api = repo.api
            val here = async { api.select("pureice_trip_assistants", "select=user_id&trip_id=eq.$tripId&order=added_at", bearer) }
            val profiles = async { api.select("profiles", "select=id,full_name,active", bearer) }
            val roles = async { api.select("user_roles", "select=user_id&role=eq.distributor", bearer) }
            val ids = roles.await().mapNotNull { it.str("user_id") }.toHashSet()
            AssistantsData(
                here.await().mapNotNull { it.str("user_id") },
                profiles.await().mapNotNull { p -> p.str("id")?.let { it to (p.str("full_name") ?: "") } }.toMap(),
                profiles.await().filter { it.str("id") in ids && it.bool("active") != false }.mapNotNull { it.str("id") },
            )
        }
    }

    fun set(user: String, on: Boolean) = operation.run {
        repo.api.rpc("pureice_set_trip_assistant", buildJsonObject { put("p_trip", tripId); put("p_user", user); put("p_on", on) }, repo.api.token())
        toast(if (on) "أُضيف المساعد" else "أُزيل المساعد")
        pick = null
        load.reload()
    }

    IceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("المساعدون", style = T.base.bold)
            val data = load.data
            when {
                load.error != null -> LoadError(load.error, load.reload)
                data == null -> Text("جارٍ التحميل…", style = T.sm.muted)
                else -> {
                    if (data.assistants.isEmpty()) Text("لا يوجد مساعد في هذه الحملة.", style = T.sm.muted)
                    data.assistants.forEach { user ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(data.names[user] ?: "", style = T.sm.medium)
                            if (open) SmallBtn("إزالة", { set(user, false) }, variant = Variant.Outline, enabled = !operation.busy)
                        }
                    }
                    val options = data.distributors.filter { it != mainId && it !in data.assistants }.map { it to (data.names[it] ?: "") }
                    if (open && !closingSent && options.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallSelect(options, pick, Modifier.weight(1f), placeholder = "اختر الموزع المساعد") { pick = it }
                        SmallBtn("إضافة مساعد", { pick?.let { set(it, true) } }, enabled = pick != null && !operation.busy)
                    }
                    Text("يبيع المساعد ويحصّل من جواله على مخزون الحملة نفسه، ويسلّم نقده للموزع الرئيسي. اعتماد التحميل والإغلاق للموزع الرئيسي.", style = T.xs.muted)
                }
            }
        }
    }
}
