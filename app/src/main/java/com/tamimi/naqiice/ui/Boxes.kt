package com.tamimi.naqiice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.BOX_ACTION_LABELS
import com.tamimi.naqiice.core.BOX_STATUSES
import com.tamimi.naqiice.core.BOX_STATUS_LABELS
import com.tamimi.naqiice.core.Row as DataRow
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.boxActionFor
import com.tamimi.naqiice.core.boxCounts
import com.tamimi.naqiice.core.boxMoveRequest
import com.tamimi.naqiice.core.boxNumber
import com.tamimi.naqiice.core.formatDate
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.normalizeDigits
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.parseBoxSerial
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.Snapshot
import com.tamimi.naqiice.data.userMessage
import com.tamimi.naqiice.tracking.Locations
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Returnable boxes: every screen here reads the phone's snapshot (so it works offline) and
// saves moves through the outbox, like invoices.

private fun Snapshot.placeOf(box: DataRow?) = boxPlace(box, { truck(it)?.str("name") }, { customer(it)?.str("name") })

/** One line of a box's history: what was done and where the box went. */
private fun Snapshot.moveTitle(event: DataRow): String {
    val customer = customer(event.str("customer_id"))?.str("name") ?: ""
    return when (event.str("action")) {
        "load" -> "تحميل ${onTruck(truck(event.str("truck_id"))?.str("name"))}"
        "deliver" -> "تسليم للعميل $customer".trim()
        "collect" -> "استلام من العميل $customer".trim()
        else -> BOX_ACTION_LABELS[event.str("action")] ?: ""
    }
}

/** Saves one confirmed list of boxes as a move. */
class BoxMover(val busy: Boolean, val move: (action: String, boxes: List<ScannedBox>, truckId: String?, customerId: String?, notes: String?, done: () -> Unit) -> Unit)

@Composable
fun rememberBoxMover(): BoxMover {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val snapshot by repo.snapshot.collectAsState()
    val current by rememberUpdatedState(snapshot)
    var busy by remember { mutableStateOf(false) }
    return BoxMover(busy) { action, boxes, truckId, customerId, notes, done ->
        if (busy || boxes.isEmpty()) return@BoxMover
        busy = true
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                val user = repo.me.value ?: throw Notice("انتهت الجلسة")
                val snap = current
                val ownTrip = snap.ownOpenTrip(user.id)
                if (!user.isManager && !ownTrip) throw Notice("تحتاج حملة مفتوحة معينة لك")
                // Where the scan happened, when the phone already knows; never waited for.
                val fix = runCatching { Locations.current(context, 1_500, 120_000) }.getOrNull()
                repo.enqueue("box_move", boxMoveRequest(
                    user.id, action, boxes.map { it.id to it.serial }, isoNow(),
                    truckId = truckId ?: if (ownTrip) snap.trip?.str("truck_id") else null, customerId = customerId,
                    tripId = if (ownTrip) snap.tripId else null, notes = notes, lat = fix?.lat, lng = fix?.lng,
                ))
                toast(if (boxes.size == 1) "تم حفظ حركة الصندوق" else "تم حفظ حركة ${boxes.size} صناديق")
                done()
            } catch (e: Exception) {
                toast(e.userMessage())
            } finally { busy = false }
        }
    }
}

// ---- hub ------------------------------------------------------------------------------

@Composable
fun BoxesScreen() {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    if (snap.ref == null) { Empty("جارٍ تحميل البيانات…"); return }
    if (!snap.boxSupport) { Empty("تتبع الصناديق غير مفعّل بعد."); return }
    if (me?.isManager == true) ManagerBoxes(snap) else DistributorBoxes(snap)
}

@Composable
private fun BoxRow(snap: Snapshot, box: DataRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(box.str("serial") ?: "", Modifier.fillMaxWidth(), style = T.base.bold.ltr, textAlign = TextAlign.Right)
            Text(snap.placeOf(box) + if (box.bool("_pending") == true) " · بانتظار الرفع" else "", style = T.xs.muted)
        }
        Icon(Lucide.ChevronLeft, null, Modifier.size(16.dp), tint = Ice.mutedText)
    }
}

@Composable
private fun BoxList(snap: Snapshot, boxes: List<DataRow>, empty: String) {
    val nav = LocalNav.current
    var shown by remember(boxes.size) { mutableStateOf(60) }
    if (boxes.isEmpty()) { Empty(empty); return }
    ListCard {
        boxes.take(shown).forEachIndexed { index, box ->
            if (index > 0) Divider()
            BoxRow(snap, box) { box.str("id")?.let { nav.go(Screen.Box(it)) } }
        }
    }
    if (boxes.size > shown) Btn("عرض المزيد (${boxes.size - shown})", { shown += 60 }, Modifier.fillMaxWidth(), Variant.Outline, height = 44.dp)
}

@Composable
private fun ManagerBoxes(snap: Snapshot) {
    val nav = LocalNav.current
    val mover = rememberBoxMover()
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf<String?>(null) }
    var truck by remember { mutableStateOf<String?>(null) }
    val counts = boxCounts(snap.boxes)
    fun count(vararg statuses: String) = formatNum(statuses.sumOf { counts[it] ?: 0 }.toDouble())
    val trucks = snap.trucks.filter { it.bool("active") != false }

    when (scan) {
        "lookup" -> BoxLookupDialog({ scan = null }) { box -> scan = null; box.str("id")?.let { nav.go(Screen.Box(it)) } }
        "truck" -> WebDialog("اختر الشاحنة", { scan = null }) {
            if (trucks.isEmpty()) Empty("لا توجد شاحنات")
            trucks.forEach { t -> Btn(t.str("name") ?: "", { truck = t.str("id"); scan = "load" }, Modifier.fillMaxWidth(), Variant.Outline, height = 48.dp, icon = Lucide.Truck) }
        }
        "load" -> BoxScanDialog("تحميل ${onTruck(snap.truck(truck)?.str("name"))}", "load", truck, null, emptyList(), confirmLabel = "تحميل", onClose = { scan = null }) { list ->
            mover.move("load", list, truck, null, null) { scan = null }
        }
        "unload" -> BoxScanDialog("إعادة إلى المصنع", "unload", null, null, emptyList(), confirmLabel = "إعادة إلى المصنع", onClose = { scan = null }) { list ->
            mover.move("unload", list, null, null, null) { scan = null }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("الصناديق") { Btn("مسح صندوق", { scan = "lookup" }, variant = Variant.Outline, icon = Lucide.QrCode) }
        StatGrid(listOf(
            Triple("في المصنع", count("factory"), Ice.foreground), Triple("على الشاحنات", count("truck"), Ice.primary),
            Triple("عند العملاء", count("customer"), Ice.success), Triple("لم تُمسح بعد", count("new"), Ice.mutedText),
            Triple("تالف", count("damaged"), Ice.destructive), Triple("مكان آخر / مستبعد", count("elsewhere", "disposed"), Ice.mutedText),
        ))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("تحميل على شاحنة", { scan = if (trucks.size == 1) { truck = trucks[0].str("id"); "load" } else "truck" }, Modifier.weight(1f), height = 48.dp, icon = Lucide.Truck, enabled = !mover.busy)
            Btn("إعادة إلى المصنع", { scan = "unload" }, Modifier.weight(1f), Variant.Secondary, height = 48.dp, icon = Lucide.Factory, enabled = !mover.busy)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Select(listOf("all" to "كل الصناديق") + BOX_STATUSES.map { it to (BOX_STATUS_LABELS[it] ?: it) }, filter) { filter = it }
            Input(query, { query = it }, placeholder = "بحث برقم الصندوق أو اسم العميل أو الشاحنة", height = 44.dp, leading = {
                Icon(Lucide.Search, null, Modifier.padding(end = 8.dp).size(16.dp), tint = Ice.mutedText)
            })
        }
        val q = normalizeDigits(query).trim()
        val wanted = parseBoxSerial(q)
        val list = snap.boxes.filter { box ->
            (filter == "all" || (box.str("status") ?: "new") == filter) &&
                (q.isEmpty() || box.str("serial") == wanted || snap.placeOf(box).contains(q, ignoreCase = true))
        }
        BoxList(snap, list, "لا توجد صناديق مطابقة")
    }
}

@Composable
private fun DistributorBoxes(snap: Snapshot) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val me by repo.me.collectAsState()
    var lookup by remember { mutableStateOf(false) }
    val own = snap.ownOpenTrip(me?.id)
    val onTruck = if (own) snap.boxesOn(snap.trip?.str("truck_id")) else emptyList()
    if (lookup) BoxLookupDialog({ lookup = false }) { box -> lookup = false; box.str("id")?.let { nav.go(Screen.Box(it)) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("الصناديق") { Btn("مسح صندوق", { lookup = true }, variant = Variant.Outline, icon = Lucide.QrCode) }
        Text("عند البيع امسح الصناديق التي تبقى عند العميل من شاشة الفاتورة، واستلم الصناديق الفارغة من صفحة العميل.", style = T.sm.muted)
        Column {
            SectionTitle("صناديق على شاحنتي (${formatNum(onTruck.size.toDouble())})")
            BoxList(snap, onTruck, if (own) "لا توجد صناديق مسجلة على شاحنتك" else "لا توجد حملة مفتوحة لك حالياً")
        }
    }
}

/** Scan or type one sticker to open that box. */
@Composable
fun BoxLookupDialog(onClose: () -> Unit, onFound: (DataRow) -> Unit) {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    fun find(text: String) {
        val serial = parseBoxSerial(text)
        val box = snap.box(serial)
        message = when {
            serial == null -> "ليس ملصق صندوق"
            box == null -> "الصندوق $serial غير مسجل في النظام"
            else -> null
        }
        if (box != null) onFound(box)
    }
    WebDialog("مسح صندوق", onClose) {
        QrScanner { find(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Input(typed, { typed = it }, Modifier.weight(1f), placeholder = "رقم الصندوق", height = 44.dp, numeric = true, ltr = true)
            Btn("بحث", { if (typed.isNotBlank()) find(typed) }, variant = Variant.Outline, height = 44.dp, icon = Lucide.Search)
        }
        message?.let { Text(it, style = T.sm.semibold.color(Ice.destructive)) }
    }
}

// ---- one box --------------------------------------------------------------------------

private class BoxAction(val label: String, val icon: ImageVector, val run: () -> Unit)

@Composable
fun BoxScreen(id: String) {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val online by repo.online.collectAsState()
    val mover = rememberBoxMover()
    var dialog by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val box = snap.boxes.firstOrNull { it.str("id") == id }
    if (box == null) { Empty("الصندوق غير موجود في البيانات المحفوظة."); return }
    val serial = box.str("serial") ?: ""
    val status = box.str("status") ?: "new"
    val manager = me?.isManager == true
    val own = snap.ownOpenTrip(me?.id)
    fun move(action: String, truckId: String? = null, customerId: String? = null, notes: String? = null) =
        mover.move(action, listOf(ScannedBox(UUID.randomUUID().toString(), serial)), truckId, customerId, notes) { dialog = null; note = "" }

    val history = rememberLoaded(id, box.str("moved_at"), online) { bearer ->
        val events = repo.api.select("pureice_box_events", "select=*&box_id=eq.$id&order=event_at.desc&limit=50", bearer)
        val people = if (manager) runCatching { repo.api.selectAll("profiles", "select=id,full_name&order=id", bearer) }.getOrDefault(emptyList()) else emptyList()
        events to people.associate { (it.str("id") ?: "") to (it.str("full_name") ?: "") }
    }

    val actions = buildList {
        if (manager) {
            if (status != "factory") add(BoxAction("إلى المصنع", Lucide.Factory) { move(boxActionFor("factory", status)) })
            add(BoxAction("على شاحنة", Lucide.Truck) { dialog = "truck" })
            add(BoxAction("عند عميل", Lucide.Store) { dialog = "customer" })
            add(BoxAction("مكان آخر", Lucide.MapPin) { dialog = "elsewhere" })
            if (status != "damaged") add(BoxAction("تالف", Lucide.AlertTriangle) { dialog = "damage" })
            if (status != "disposed") add(BoxAction("استبعاد", Lucide.Trash2) { dialog = "dispose" })
        } else if (own) {
            add(BoxAction("تسليم لعميل", Lucide.Store) { dialog = "customer" })
            add(BoxAction("استلام على شاحنتي", Lucide.Truck) { move("collect", customerId = box.str("customer_id")) })
            if (status != "damaged") add(BoxAction("تالف", Lucide.AlertTriangle) { dialog = "damage" })
        }
    }

    when (dialog) {
        "truck" -> WebDialog("اختر الشاحنة", { dialog = null }) {
            snap.trucks.filter { it.bool("active") != false }.forEach { t ->
                Btn(t.str("name") ?: "", { move("load", truckId = t.str("id")) }, Modifier.fillMaxWidth(), Variant.Outline, height = 48.dp, icon = Lucide.Truck, enabled = !mover.busy)
            }
        }
        "customer" -> WebDialog("اختر العميل", { dialog = null }) { CustomerPicker { move("deliver", customerId = it) } }
        "elsewhere", "damage" -> WebDialog(if (dialog == "damage") "تسجيل الصندوق تالفاً" else "نقل إلى مكان آخر", { dialog = null }) {
            Labeled(if (dialog == "damage") "ما الذي حدث للصندوق؟" else "أين الصندوق الآن؟") { Input(note, { note = it }, height = 48.dp) }
            val action = dialog ?: ""
            Btn("حفظ", { move(action, notes = note) }, Modifier.fillMaxWidth(), enabled = !mover.busy && (action == "damage" || note.isNotBlank()), height = 48.dp)
        }
        "dispose" -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text("استبعاد الصندوق $serial نهائياً من التداول؟") },
            confirmButton = { TextButton({ move("dispose") }, enabled = !mover.busy) { Text("استبعاد") } },
            dismissButton = { TextButton({ dialog = null }) { Text("إلغاء") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IceCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(serial, Modifier.fillMaxWidth(), style = T.xl.bold.ltr, textAlign = TextAlign.Right)
                Text(snap.placeOf(box), style = T.base.medium)
                box.str("moved_at")?.let { Text("آخر حركة: ${formatDate(it)}", style = T.sm.muted) }
                box.str("notes")?.takeIf { it.isNotBlank() }?.let { Text(it, style = T.sm) }
                if (box.bool("_pending") == true) Text("حركة محفوظة على الهاتف بانتظار الرفع", style = T.xs.color(Ice.onWarning))
            }
        }
        if (actions.isNotEmpty()) Column {
            SectionTitle("تغيير مكان الصندوق")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { a -> Btn(a.label, a.run, Modifier.weight(1f), Variant.Outline, height = 48.dp, icon = a.icon, enabled = !mover.busy) }
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        }
        Column {
            SectionTitle("سجل الصندوق")
            val data = history.data
            when {
                !online && data == null -> Text("السجل يظهر عند توفر الإنترنت.", style = T.xs.muted)
                history.error != null && data == null -> LoadError(history.error, history.reload)
                data == null -> Empty("جارٍ التحميل…")
                data.first.isEmpty() -> Empty(if (manager) "لا توجد حركات مسجلة" else "لا توجد حركات مسجلة منك لهذا الصندوق")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.first.forEach { e ->
                        Column(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(snap.moveTitle(e), style = T.sm.medium)
                            Text(
                                listOfNotNull(formatDate(e.str("event_at")), data.second[e.str("created_by")]?.takeIf { it.isNotEmpty() }, "كان: ${BOX_STATUS_LABELS[e.str("from_status")] ?: ""}").joinToString(" · "),
                                style = T.xs.muted,
                            )
                            e.str("notes")?.takeIf { it.isNotBlank() }?.let { Text(it, style = T.xs) }
                            if (e.bool("unexpected") == true) Text("حركة غير متوقعة: المكان المسجل قبلها لا يطابقها", style = T.xs.color(Ice.onWarning))
                            if (e.bool("applied") == false) Text("سُجلت متأخرة ولم تغيّر المكان الحالي", style = T.xs.color(Ice.onWarning))
                        }
                    }
                }
            }
        }
    }
}

// ---- customer page --------------------------------------------------------------------

/** Boxes recorded with a customer, with scan buttons for a distributor on a campaign. */
@Composable
fun CustomerBoxes(customerId: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val snap by repo.snapshot.collectAsState()
    val me by repo.me.collectAsState()
    val mover = rememberBoxMover()
    var scan by remember { mutableStateOf<String?>(null) }
    if (!snap.boxSupport) return
    val boxes = snap.boxesWith(customerId)
    val own = snap.ownOpenTrip(me?.id)
    val truckId = snap.trip?.str("truck_id")
    scan?.let { action ->
        BoxScanDialog(if (action == "collect") "استلام صناديق فارغة" else "تسليم صناديق للعميل", action, truckId, customerId, emptyList(), onClose = { scan = null }) { list ->
            mover.move(action, list, null, customerId, null) { scan = null }
        }
    }
    Column {
        SectionTitle("الصناديق لدى العميل")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (boxes.isEmpty()) Empty("لا توجد صناديق") else boxes.forEach { box ->
                Row(Modifier.fillMaxWidth().box(Radius.xl).tap { box.str("id")?.let { nav.go(Screen.Box(it)) } }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(box.str("serial") ?: "", Modifier.weight(1f), style = T.sm.bold.ltr, textAlign = TextAlign.Right)
                    if (box.bool("_pending") == true) Text("بانتظار الرفع", style = T.xs.muted)
                }
            }
            if (own) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("استلام فارغة", { scan = "collect" }, Modifier.weight(1f), Variant.Outline, height = 44.dp, icon = Lucide.QrCode, enabled = !mover.busy)
                Btn("تسليم صندوق", { scan = "deliver" }, Modifier.weight(1f), Variant.Outline, height = 44.dp, icon = Lucide.Package, enabled = !mover.busy)
            }
        }
    }
}

/** Opens the customer's saved position in the phone's maps app. */
@Composable
fun CustomerLocationLink(customer: DataRow) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val snap by repo.snapshot.collectAsState()
    if (!snap.boxSupport) return
    val lat = customer.numOrNull("lat")
    val lng = customer.numOrNull("lng")
    if (lat == null || lng == null) {
        Text("لم يُحفظ موقع العميل بعد — يُحفظ عند أول فاتورة من موقعه.", style = T.xs.muted, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Row(
        Modifier.padding(top = 8.dp).heightIn(min = 44.dp).tap {
            val label = java.net.URLEncoder.encode(customer.str("name") ?: "", "UTF-8")
            if (!openUrl(context, "geo:$lat,$lng?q=$lat,$lng($label)")) openUrl(context, "https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Lucide.MapPin, null, Modifier.size(16.dp), tint = Ice.primary)
        Text("فتح موقع العميل في الخرائط", style = T.sm.color(Ice.primary))
    }
}

// ---- admin ----------------------------------------------------------------------------

@Composable
fun AdminBoxesScreen() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val snap by repo.snapshot.collectAsState()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    if (snap.ref == null) { Empty("جارٍ تحميل البيانات…"); return }
    if (!snap.boxSupport) { Empty("تتبع الصناديق غير مفعّل بعد."); return }
    fun online(work: suspend (String) -> String) {
        if (busy) return
        busy = true
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                val message = work(repo.api.token())
                repo.syncNow()
                toast(message)
            } catch (e: Exception) { toast(e.userMessage("تعذّر الحفظ")) } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("إعداد الصناديق")
        Text("عدد الصناديق المسجلة: ${formatNum(snap.boxes.size.toDouble())}", style = T.sm.muted)
        Column {
            SectionTitle("المنتجات المعبأة في صناديق")
            Text("عند بيع هذه المنتجات يظهر للموزع مسح الصناديق في الفاتورة.", style = T.xs.muted, modifier = Modifier.padding(bottom = 8.dp))
            ListCard {
                snap.variants.forEachIndexed { index, v ->
                    val id = v.str("id") ?: return@forEachIndexed
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(v.str("name") ?: "", Modifier.weight(1f), style = T.base)
                        Toggle(snap.isBoxVariant(id)) { on ->
                            online { bearer ->
                                if (on) repo.api.insert("pureice_box_variants", buildJsonObject { put("variant_id", id) }, bearer)
                                else repo.api.delete("pureice_box_variants", "variant_id=eq.$id", bearer)
                                "تم الحفظ"
                            }
                        }
                    }
                }
            }
        }
        Column {
            SectionTitle("إضافة أرقام صناديق جديدة")
            Text("أدخل أول وآخر رقم على الملصقات الجديدة. الأرقام المسجلة من قبل لا تتغير.", style = T.xs.muted, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Input(from, { from = it }, Modifier.weight(1f), placeholder = "من رقم", height = 48.dp, numeric = true, ltr = true)
                Input(to, { to = it }, Modifier.weight(1f), placeholder = "إلى رقم", height = 48.dp, numeric = true, ltr = true)
            }
            val first = from.toIntOrNull()
            val last = to.toIntOrNull()
            Btn("إضافة", {
                online { bearer ->
                    val added = repo.api.rpc("pureice_box_add", buildJsonObject { put("p_from", first); put("p_to", last) }, bearer)
                    from = ""; to = ""
                    "تمت إضافة ${(added as? JsonPrimitive)?.content ?: "0"} صندوق"
                }
            }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy && first != null && last != null && first >= 1 && last >= first, height = 48.dp, icon = Lucide.Plus)
        }
    }
}

/** In the admin customer form: the saved position, and a way to clear a wrong one. */
@Composable
fun CustomerLocationEditor(customer: DataRow) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val snap by repo.snapshot.collectAsState()
    var cleared by remember(customer.str("id")) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val id = customer.str("id") ?: return
    if (!snap.boxSupport) return
    val lat = customer.numOrNull("lat")
    val lng = customer.numOrNull("lng")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("موقع العميل", style = T.sm.medium)
        if (lat == null || lng == null || cleared) {
            Text(if (cleared) "تم مسح الموقع؛ سيُطلب من الموزع تأكيده عند الفاتورة القادمة." else "لا يوجد موقع محفوظ؛ يُحفظ عندما يؤكده الموزع عند إصدار فاتورة.", style = T.xs.muted)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("فتح في الخرائط", { if (!openUrl(context, "geo:$lat,$lng?q=$lat,$lng")) openUrl(context, "https://www.google.com/maps/search/?api=1&query=$lat,$lng") }, variant = Variant.Outline, icon = Lucide.MapPin)
            Btn("مسح الموقع", {
                if (!busy) {
                    busy = true
                    scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        try {
                            repo.api.update("customers", "id=eq.$id", buildJsonObject { put("lat", JsonNull); put("lng", JsonNull) }, repo.api.token())
                            cleared = true
                            repo.syncNow()
                        } catch (e: Exception) { toast(e.userMessage("تعذّر مسح الموقع")) } finally { busy = false }
                    }
                }
            }, variant = Variant.Outline, enabled = !busy)
        }
    }
}
