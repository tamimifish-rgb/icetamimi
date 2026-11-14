package com.tamimi.naqiice.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tamimi.naqiice.core.ExportTable
import com.tamimi.naqiice.core.Xlsx
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.customerPage
import com.tamimi.naqiice.core.filterCustomers
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnumOrNull
import com.tamimi.naqiice.core.normalizeCustomerSearch
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.orNull
import com.tamimi.naqiice.core.parseNumber
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.CONNECTION_FAILED
import com.tamimi.naqiice.data.Repo
import com.tamimi.naqiice.data.SESSION_EXPIRED
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import androidx.compose.material3.Text as M3Text
import com.tamimi.naqiice.core.Row as DataRow

// Shared pieces of the administrator screens: the session check that precedes every
// privileged write, online loading, the web form controls these screens use and the
// generic list + edit dialog (the web app's components/app/crud.tsx).

const val ACCOUNT_SWITCHED = "تغيّر الحساب المسجّل أثناء الحفظ؛ لم يتم الحفظ"

/**
 * Server-validated check before a privileged write (the web app's runPrivileged): a revoked
 * session or a different account never reaches the action. Returns the bearer to use.
 */
suspend fun Repo.privilegedToken(owner: String?): String {
    if (owner == null) throw Notice(SESSION_EXPIRED)
    val verified = try { api.verifyUser() } catch (e: ApiError) {
        throw Notice(if (e.status == 401 || e.status == 403) SESSION_EXPIRED else CONNECTION_FAILED)
    }
    if (verified != owner) throw Notice(ACCOUNT_SWITCHED)
    val bearer = api.token()
    if (api.currentUserId() != owner) throw Notice(ACCOUNT_SWITCHED)
    return bearer
}

/** Refreshes the cached snapshot after reference data changed, without holding the form. */
fun Repo.refreshSoon() { scope.launch { syncNow() } }

class LoadState<T>(val value: T?, val error: String?)

/** Online-only read; a reload keeps showing the previous rows until the new ones arrive. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend (bearer: String) -> T): LoadState<T> {
    val repo = LocalRepo.current
    var state by remember { mutableStateOf(LoadState<T>(null, null)) }
    LaunchedEffect(*keys) {
        runCatching { load(repo.api.token()) }
            .onSuccess { state = LoadState(it, null) }
            .onFailure { if (it !is CancellationException) state = LoadState(state.value, it.userMessage("تعذّر تحميل البيانات")) }
    }
    return state
}

typealias Options = List<Pair<String, String>>

/** id → label choices for a select field; empty until loaded (as in the web app). */
@Composable
fun rememberOptions(table: String, label: String = "name", version: Int = 0, all: Boolean = false): Options {
    val repo = LocalRepo.current
    return rememberLoad(table, label, version) { bearer ->
        val rows = if (all) repo.api.selectAll(table, "select=id,$label&order=$label,id", bearer)
        else repo.api.select(table, "select=id,$label&order=$label", bearer)
        rows.map { (it.str("id") ?: "") to (it.str(label) ?: "") }
    }.value ?: emptyList()
}

// ---- web form controls ----------------------------------------------------------------

/**
 * The web `Dialog` as the manager forms use it: a full-width sheet with a 1px border and
 * `p-6`, a centred `text-lg font-semibold leading-none` title, the close × at `right-4 top-4`
 * and `max-h-[90vh] overflow-y-auto`.
 */
@Composable
fun FormDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        val limit = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        CompositionLocalProvider(LocalContentColor provides Ice.foreground) {
            Box(Modifier.fillMaxWidth().heightIn(max = limit).largeShadow(0.dp).box(0.dp, Ice.background)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, Modifier.fillMaxWidth().offset(y = (-0.5).dp), style = T.lg.semibold.copy(lineHeight = 18.sp), textAlign = TextAlign.Center)
                    content()
                }
                // `right-4 top-4`: the physical right, i.e. the start edge in this RTL layout.
                Icon(Lucide.X, "إغلاق", Modifier.align(Alignment.TopStart).padding(16.dp).size(16.dp).alpha(0.7f).tap(onClick = onClose))
            }
        }
    }
}

/**
 * `Label` (text-sm font-medium) above its control. The inline label sits on the baseline of
 * the block's own 16px/24px line, a pixel lower than a 14px text centred in 24px would.
 */
@Composable
fun FieldLabel(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, Modifier.offset(y = 1.dp), style = T.sm.medium.copy(lineHeight = 24.sp))
        content()
    }
}

/**
 * Inline text in more than one style (`<b>name</b> <span class="text-xs">…</span>`), with the
 * web's line boxes: every line takes the block's line height, as the package's `Text` does.
 */
@Composable
fun RichText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle = T.base) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(0)
    val line = with(density) { style.lineHeight.roundToPx() }
    val extra = remember(style, density) { (measurer.measure("ا", style, density = density).size.height - line).coerceAtLeast(0) }
    val lines = remember { IntArray(1) { 1 } }
    M3Text(
        text, modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, lines[0] * line) { placeable.place(0, -(extra / 2)) }
        },
        onTextLayout = { lines[0] = it.lineCount }, style = style.copy(color = if (style.color == Color.Unspecified) LocalContentColor.current else style.color),
    )
}

/**
 * The browser's own `<select>` as the web forms style it (`rounded-md border border-input
 * bg-background`, [height] = `h-*`, [padding] = `px-*`): the chosen option's text and the
 * small drop-down arrow 3px inside the border. As in a browser the first option shows when
 * the value matches none. Long lists (every customer) open a searchable list.
 */
@Composable
fun <T> WebSelect(
    options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, height: Dp = 44.dp, padding: Dp = 12.dp,
    style: TextStyle = T.base, title: String = "", onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(Radius.md)
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height).box(Radius.md, Ice.background, Ice.input).clip(shape).clickable { open = true }) {
            Text(
                (options.firstOrNull { it.first == selected } ?: options.firstOrNull())?.second ?: "",
                // The browser keeps 4px of its own around a select's text, inside the `px-*`.
                Modifier.align(Alignment.CenterStart).padding(start = padding + 4.dp, end = maxOf(padding + 4.dp, 14.dp)), style = style, maxLines = 1,
            )
            val arrow = LocalContentColor.current
            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).size(10.dp, 6.dp)) {
                val path = Path().apply { moveTo(1.2.dp.toPx(), 0.9.dp.toPx()); lineTo(5.dp.toPx(), 5.dp.toPx()); lineTo(8.8.dp.toPx(), 0.9.dp.toPx()) }
                drawPath(path, arrow, style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Miter))
            }
        }
        if (options.size <= 15) DropdownMenu(open, { open = false }, containerColor = Ice.card) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text, style = T.base) }, onClick = { open = false; onSelect(value) }) }
        }
    }
    if (open && options.size > 15) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title, style = T.lg.semibold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Input(query, { query = it }, placeholder = "بحث", height = 44.dp)
                val shown = remember(options, query) {
                    val q = normalizeCustomerSearch(query)
                    options.filter { q.isEmpty() || normalizeCustomerSearch(it.second).contains(q) }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown) { (value, text) ->
                        Text(text, Modifier.fillMaxWidth().clickable { open = false; query = ""; onSelect(value) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("إلغاء") } },
    )
}

/** The browser's 13px checkbox: blue with a white tick when on, a grey outline when off. */
@Composable
fun WebCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp)) {
        val r = CornerRadius(2.dp.toPx())
        if (checked) {
            drawRoundRect(Color(0xFF0075FF), cornerRadius = r)
            val tick = Path().apply { moveTo(3.1.dp.toPx(), 6.6.dp.toPx()); lineTo(5.1.dp.toPx(), 8.8.dp.toPx()); lineTo(9.6.dp.toPx(), 3.0.dp.toPx()) }
            drawPath(tick, Color.White, style = Stroke(1.9.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        } else {
            val w = 1.dp.toPx()
            drawRoundRect(Color.White, cornerRadius = r)
            drawRoundRect(Color(0xFF767676), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
        }
    }
}

// ---- generic list + edit dialog -------------------------------------------------------

enum class CrudType { TEXT, NUMBER, BOOL, SELECT }

class CrudField(
    val key: String, val label: String, val type: CrudType = CrudType.TEXT, val options: Options = emptyList(),
    val required: Boolean = false, val hideInList: Boolean = false, val format: ((DataRow) -> String)? = null,
)

private fun show(f: CrudField, r: DataRow): String = f.format?.invoke(r) ?: when (f.type) {
    CrudType.BOOL -> if (r.bool(f.key) == true) "نعم" else "لا"
    CrudType.SELECT -> f.options.firstOrNull { it.first == r.str(f.key) }?.second ?: "—"
    else -> r.str(f.key) ?: "—"
}

/**
 * The row sent to the server: only the listed fields. A key the form never touched stays
 * absent so the column keeps its database default, exactly like the web form.
 */
private fun crudRow(fields: List<CrudField>, draft: DataRow): JsonObject {
    val row = LinkedHashMap<String, JsonElement>()
    for (f in fields) {
        var value: JsonElement? = draft[f.key]
        val text = (value.orNull() as? JsonPrimitive)?.contentOrNull
        if (f.type == CrudType.NUMBER) value = jnumOrNull(text?.takeIf { it.isNotEmpty() }?.let { parseNumber(it) }?.takeIf { it.isFinite() })
        if (f.type == CrudType.SELECT && text == "") value = JsonNull
        if (f.required && (value.orNull() == null || text == "")) throw Notice("${f.label} مطلوب")
        if (value != null) row[f.key] = value
    }
    return JsonObject(row)
}

/** The customers list as an Excel sheet, handed to the phone's share sheet (the web's download). */
private suspend fun shareExcel(context: Context, table: ExportTable) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, table.title + ".xlsx").also { it.outputStream().use { out -> Xlsx.write(table, out) } }
    }
    val uri = Photos.uriFor(context, file)
    val send = Intent(Intent.ACTION_SEND).setType(Xlsx.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, "تصدير Excel").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Crud(
    table: String, title: String, fields: List<CrudField>, select: String = "*", order: String = "name",
    defaults: Map<String, JsonElement> = emptyMap(), customerList: Boolean = false, onSaved: () -> Unit = {},
    extra: (@Composable (DataRow) -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by repo.me.collectAsState()
    val toast = rememberToast()
    val operation = rememberOperation()
    var version by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<DataRow?>(null) }
    var search by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("all") }
    var assignment by remember { mutableStateOf("all") }
    var requested by remember { mutableStateOf(1) }

    val loaded = rememberLoad(table, select, version) { bearer ->
        if (customerList) repo.api.selectAll(table, "select=$select&order=$order,id", bearer)
        else repo.api.select(table, "select=$select&order=$order&limit=5000", bearer)
    }
    val data = loaded.value

    fun save(draft: DataRow) = operation.run {
        var row = crudRow(fields, draft)
        if (data.isNullOrEmpty() || "updated_at" in data.first()) row = row.with("updated_at" to JsonPrimitive(isoNow()))
        val bearer = repo.privilegedToken(me?.id)
        val id = draft.str("id")
        if (!id.isNullOrEmpty()) repo.api.update(table, "id=eq.$id", row, bearer) else repo.api.insert(table, row, bearer)
        toast("تم الحفظ")
        edit = null
        version++
        onSaved()
        repo.refreshSoon()
    }

    val filtered = remember(data, search, region, assignment) {
        val all = data ?: emptyList()
        if (customerList) filterCustomers(all, { it.toContact() }, search, region, assignment)
        else all.filter { search.isEmpty() || it.toString().contains(search) }
    }
    val page = if (customerList) customerPage(filtered, requested) else null
    val rows = page?.rows ?: filtered

    // div.space-y-3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // div.flex.items-center.gap-2 > h1.flex-1.text-xl.font-bold + Button.min-h-11
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = T.xl.bold)
            if (customerList) Btn("Excel", {
                scope.launch {
                    runCatching {
                        shareExcel(context, ExportTable("العملاء", fields.map { it.label }, filtered.map { r ->
                            fields.map { f -> if (f.type == CrudType.NUMBER) r.num(f.key) else show(f, r) }
                        }))
                    }.onFailure { if (it !is CancellationException) toast("تعذّر تصدير الملف") }
                }
            }, variant = Variant.Outline, enabled = data != null, height = 44.dp)
            Btn("إضافة", { edit = JsonObject(defaults) }, height = 44.dp, icon = Lucide.Plus)
        }
        if (customerList) {
            CustomerFilters(
                search, { search = it; requested = 1 }, fields.firstOrNull { it.key == "region_id" }?.options ?: emptyList(),
                region, { region = it; requested = 1 }, filtered.size, data?.size ?: 0,
            ) {
                // div.space-y-1 > label.text-sm.font-medium + select.h-11.px-3.text-sm
                FieldLabel("إسناد الشاحنة") {
                    WebSelect(listOf("all" to "كل العملاء", "unassigned" to "غير مسند لشاحنة"), assignment, style = T.sm) { assignment = it; requested = 1 }
                }
            }
        } else if ((data?.size ?: 0) > 10) Input(search, { search = it }, placeholder = "بحث", height = 44.dp)

        // div[role=alert].space-y-2.text-sm.text-destructive
        if (loaded.error != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (customerList) "تعذّر تحميل العملاء" else loaded.error, style = T.sm.color(Ice.destructive))
            Btn("إعادة المحاولة", { version++ }, variant = Variant.Outline, height = 44.dp)
        }
        when {
            data == null -> if (loaded.error == null) Empty("جارٍ التحميل…")
            rows.isEmpty() -> Empty("لا توجد بيانات")
            // div.space-y-2 > div.rounded-xl.border.bg-card.p-3
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { r ->
                    Column(Modifier.fillMaxWidth().box(Radius.xl).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(fields.firstOrNull()?.let { show(it, r) } ?: "", style = T.sm.bold)
                                // div.mt-0.5.flex.flex-wrap.gap-x-3.text-xs.text-muted-foreground
                                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    fields.drop(1).filter { !it.hideInList }.forEach { Text("${it.label}: ${show(it, r)}", style = T.xs.muted) }
                                }
                            }
                            val label = if (customerList) "تعديل ${r.str("name") ?: ""}" else "تعديل"
                            Btn({ edit = r }, Modifier.size(44.dp).semantics { contentDescription = label }, Variant.Ghost, height = 44.dp, padding = PaddingValues(0.dp)) {
                                Icon(Lucide.Pencil, null, Modifier.size(16.dp))
                            }
                        }
                        extra?.invoke(r)
                    }
                }
            }
        }
        if (page != null && data != null) CustomerPagination(page.page, page.pages, page.start, page.end, filtered.size) { requested = it }
    }

    edit?.let { draft ->
        FormDialog("${if (draft.str("id").isNullOrEmpty()) "إضافة" else "تعديل"} — $title", { if (!operation.busy) edit = null }) {
            // div.space-y-3 > div.space-y-1.5 (Label + control) … + Button.h-12.w-full
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.forEach { f -> FieldLabel(f.label) { CrudInput(f, draft) { edit = draft.with(f.key to it) } } }
                Btn("حفظ", { save(draft) }, Modifier.fillMaxWidth(), enabled = !operation.busy, height = 48.dp)
            }
        }
    }
}

@Composable
private fun CrudInput(f: CrudField, draft: DataRow, set: (JsonElement) -> Unit) {
    when (f.type) {
        // div > Switch: an inline switch at the top of the block's own 24px line
        CrudType.BOOL -> Box(Modifier.height(24.dp)) { Toggle(draft.bool(f.key) == true) { set(JsonPrimitive(it)) } }
        CrudType.SELECT -> WebSelect(listOf("" to "—") + f.options, draft.str(f.key) ?: "", title = f.label) { set(JsonPrimitive(it)) }
        CrudType.NUMBER -> Input(draft.str(f.key) ?: "", { set(JsonPrimitive(it)) }, height = 44.dp, numeric = true, keyboard = KeyboardType.Decimal)
        CrudType.TEXT -> Input(draft.str(f.key) ?: "", { set(JsonPrimitive(it)) }, height = 44.dp)
    }
}
