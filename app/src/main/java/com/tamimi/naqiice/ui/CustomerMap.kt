package com.tamimi.naqiice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.customerPins
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.tracking.Locations
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * The distributor's customers on a map. A green pin is a customer invoiced in this campaign,
 * a grey pin one not yet visited; the blue dot is the phone's position and the line the
 * campaign's route. Customers can be filtered by area and by product. Map tiles need the
 * internet; the pins, the route last loaded and the product history come from the phone.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomerMapScreen() {
    val nav = LocalNav.current
    val repo = LocalRepo.current
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    val bought by repo.customerProducts.collectAsState()
    val mapExtras by repo.mapExtrasSupport.collectAsState()
    var region by remember { mutableStateOf<String?>(null) }
    var variant by remember { mutableStateOf<String?>(null) }
    var showRoute by remember { mutableStateOf(true) }
    var me by remember { mutableStateOf<TrackPoint?>(null) }
    var serverRoute by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    val result = remember(snap.customers, snap.day.sales, region, variant, bought) { customerPins(snap.customers, snap.day.sales, region, variant, bought) }
    var selected by remember { mutableStateOf<String?>(null) }
    val pin = result.pins.firstOrNull { it.id == selected }
    val tripId = snap.tripId

    // The phone's position, every 20 seconds while the map is open.
    LaunchedEffect(Unit) {
        while (true) {
            if (Locations.granted(context)) Locations.current(context, 15_000, 60_000)?.let { me = TrackPoint(it.lat, it.lng, "") }
            delay(20_000)
        }
    }
    LaunchedEffect(tripId, mapExtras) { serverRoute = if (tripId == null) emptyList() else repo.tripRoute(tripId) }
    // Points recorded on this phone and not yet cleared come after what the server holds.
    val route = remember(serverRoute, snap.pending, tripId) {
        serverRoute + snap.pending.filter { it.kind == "gps" && tripId != null && it.payload.str("trip_id") == tripId }.mapNotNull { op ->
            val lat = op.payload.numOrNull("lat") ?: return@mapNotNull null
            val lng = op.payload.numOrNull("lng") ?: return@mapNotNull null
            TrackPoint(lat, lng, op.payload.str("recorded_at") ?: "")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("خريطة العملاء")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Pair<String?, String>>(null to "كل المناطق") + snap.regions.mapNotNull { r -> r.str("id")?.let { it to (r.str("name") ?: "") } }).forEach { (id, name) ->
                    Btn(name, { region = id }, variant = if (region == id) Variant.Default else Variant.Outline, height = 40.dp)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Pair<String?, String>>(null to "كل المنتجات") + snap.variants.mapNotNull { v -> v.str("id")?.let { it to (v.str("name") ?: "") } }).forEach { (id, name) ->
                    Btn(name, { variant = id }, variant = if (variant == id) Variant.Default else Variant.Outline, height = 40.dp)
                }
            }
            Text("تمت زيارتهم ${result.visited} من ${result.pins.size} في هذه الحملة · بلا موقع: ${result.unplaced}", style = T.sm.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendDot(Ice.mutedText, "لم تتم زيارته")
                LegendDot(Ice.success, "تمت زيارته")
                if (me != null) LegendDot(Color(0xFF2563EB), "موقعي")
                if (route.isNotEmpty()) Btn(
                    if (showRoute) "خط السير · ${route.size} نقطة" else "خط السير مخفي", { showRoute = !showRoute },
                    variant = if (showRoute) Variant.Default else Variant.Outline, height = 36.dp,
                )
            }
        }
        val noneLocated = snap.customers.none { it.numOrNull("lat") != null && it.numOrNull("lng") != null }
        if (noneLocated) Empty("لا يوجد عملاء بمواقع محفوظة. يُحفظ موقع العميل عند إضافته من موقعه أو عند تأكيد موقعه بعد الفاتورة.")
        // The map still shows the phone's position and the route without located customers.
        if (!noneLocated || route.isNotEmpty() || me != null) {
            if (!noneLocated && result.pins.isEmpty()) Text("لا يوجد عملاء مطابقون لهذا الاختيار.", style = T.sm.muted)
            TripMap(
                points = if (showRoute) route else emptyList(),
                sales = result.pins.map { MapStop(it.lat, it.lng, it.name, if (it.visited) StopStyle.Visited else StopStyle.Customer, it.id) },
                open = true,
                description = "خريطة العملاء",
                onStop = { selected = it.customerId },
                me = me,
            )
        }
        if (pin != null) IceCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pin.name, style = T.base.bold)
                Text(if (pin.visited) "تمت زيارته في هذه الحملة" else "لم تتم زيارته بعد في هذه الحملة", style = T.sm.color(if (pin.visited) Ice.success else Ice.mutedText))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("فتح العميل", { nav.go(Screen.Customer(pin.id)) }, Modifier.weight(1f))
                    OutlineBtn("الاتجاهات", Modifier.weight(1f)) {
                        val label = java.net.URLEncoder.encode(pin.name, "UTF-8")
                        if (!openUrl(context, "geo:${pin.lat},${pin.lng}?q=${pin.lat},${pin.lng}($label)")) openUrl(context, "https://www.google.com/maps/search/?api=1&query=${pin.lat},${pin.lng}")
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, style = T.xs)
    }
}
