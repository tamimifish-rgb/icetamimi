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
import com.tamimi.naqiice.core.customerPins

/**
 * The distributor's customers on a map. A green pin is a customer invoiced in this campaign,
 * a grey pin one not yet visited. Map tiles need the internet; the pins come from the phone.
 */
@Composable
fun CustomerMapScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val snap by LocalRepo.current.snapshot.collectAsState()
    val result = remember(snap.customers, snap.day.sales) { customerPins(snap.customers, snap.day.sales) }
    var selected by remember { mutableStateOf<String?>(null) }
    val pin = result.pins.firstOrNull { it.id == selected }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("خريطة العملاء")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تمت زيارتهم ${result.visited} من ${result.pins.size} في هذه الحملة · بلا موقع: ${result.unplaced}", style = T.sm.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Ice.mutedText, "لم تتم زيارته")
                LegendDot(Ice.success, "تمت زيارته")
            }
        }
        if (result.pins.isEmpty()) {
            Empty("لا يوجد عملاء بمواقع محفوظة. يُحفظ موقع العميل عند إضافته من موقعه أو عند تأكيد موقعه بعد الفاتورة.")
        } else {
            TripMap(
                points = emptyList(),
                sales = result.pins.map { MapStop(it.lat, it.lng, it.name, if (it.visited) StopStyle.Visited else StopStyle.Customer, it.id) },
                open = false,
                description = "خريطة العملاء",
            ) { selected = it.customerId }
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
