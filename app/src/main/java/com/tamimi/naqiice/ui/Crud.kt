package com.tamimi.naqiice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.customerPage
import com.tamimi.naqiice.core.filterCustomers
import com.tamimi.naqiice.core.formatNum
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnumOrNull
import com.tamimi.naqiice.core.normalizeCustomerSearch
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.tamimi.naqiice.core.Row as DataRow

// Shared pieces of the administrator screens: the session check that precedes every
// privileged write, online loading, and the generic list + edit dialog (the web app's
// components/app/crud.tsx).

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

@Composable
fun Crud(
    table: String, title: String, fields: List<CrudField>, select: String = "*", order: String = "name",
    defaults: Map<String, JsonElement> = emptyMap(), customerList: Boolean = false, onSaved: () -> Unit = {},
    extra: (@Composable (DataRow) -> Unit)? = null,
) {
    val repo = LocalRepo.current
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle(title) { MidButton("إضافة") { edit = JsonObject(defaults) } }
        if (customerList) {
            Field(search, { search = it; requested = 1 }, "ابحث بالاسم أو الهاتف")
            val regions = listOf("all" to "الكل") + (fields.firstOrNull { it.key == "region_id" }?.options ?: emptyList())
            regions.chunked(3).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunk.forEach { (id, name) -> MidButton(name, outlined = region != id) { region = id; requested = 1 } }
                }
            }
            Picker("إسناد الشاحنة", listOf("all" to "كل العملاء", "unassigned" to "غير مسند لشاحنة"), assignment) { assignment = it; requested = 1 }
            Text("${formatNum(filtered.size.toDouble())} مطابق من ${formatNum((data?.size ?: 0).toDouble())} عميل", fontSize = 14.sp, color = Ice.mutedText)
        } else if ((data?.size ?: 0) > 10) Field(search, { search = it }, "بحث")

        if (loaded.error != null) {
            Text(if (customerList) "تعذّر تحميل العملاء" else loaded.error, fontSize = 14.sp, color = Ice.destructive)
            MidButton("إعادة المحاولة", outlined = true) { version++ }
        }
        when {
            data == null -> if (loaded.error == null) Empty("جارٍ التحميل…")
            rows.isEmpty() -> Empty("لا توجد بيانات")
            else -> rows.forEach { r ->
                IceCard {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(fields.firstOrNull()?.let { show(it, r) } ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                val details = fields.drop(1).filter { !it.hideInList }.joinToString(" · ") { "${it.label}: ${show(it, r)}" }
                                if (details.isNotEmpty()) Text(details, fontSize = 12.sp, color = Ice.mutedText)
                            }
                            IconButton({ edit = r }) { Icon(Icons.Filled.Edit, if (customerList) "تعديل ${r.str("name") ?: ""}" else "تعديل", tint = Ice.primary) }
                        }
                        extra?.invoke(r)
                    }
                }
            }
        }
        if (page != null && data != null) {
            Text("${formatNum(page.start.toDouble())}–${formatNum(page.end.toDouble())} من ${formatNum(filtered.size.toDouble())}", Modifier.fillMaxWidth(), fontSize = 14.sp, color = Ice.mutedText, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                MidButton("السابق", outlined = true, enabled = page.page > 1) { requested = page.page - 1 }
                Text("${formatNum(page.page.toDouble())} / ${formatNum(page.pages.toDouble())}", fontSize = 14.sp)
                MidButton("التالي", outlined = true, enabled = page.page < page.pages) { requested = page.page + 1 }
            }
        }
    }

    edit?.let { draft ->
        AlertDialog(
            onDismissRequest = { if (!operation.busy) edit = null },
            title = { Text("${if (draft.str("id").isNullOrEmpty()) "إضافة" else "تعديل"} — $title", fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    fields.forEach { f -> CrudInput(f, draft) { edit = draft.with(f.key to it) } }
                }
            },
            confirmButton = { TextButton({ save(draft) }, enabled = !operation.busy) { Text("حفظ") } },
            dismissButton = { TextButton({ edit = null }, enabled = !operation.busy) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun CrudInput(f: CrudField, draft: DataRow, set: (JsonElement) -> Unit) {
    when (f.type) {
        CrudType.BOOL -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, Modifier.weight(1f))
            Switch(draft.bool(f.key) == true, { set(JsonPrimitive(it)) })
        }
        CrudType.SELECT -> OptionPicker(f.label, listOf("" to "—") + f.options, draft.str(f.key) ?: "") { set(JsonPrimitive(it)) }
        CrudType.NUMBER -> Field(draft.str(f.key) ?: "", { set(JsonPrimitive(it)) }, f.label, numeric = true, keyboard = KeyboardType.Decimal)
        CrudType.TEXT -> Field(draft.str(f.key) ?: "", { set(JsonPrimitive(it)) }, f.label)
    }
}

/** A dropdown for short lists; long ones (every customer) open a searchable list instead. */
@Composable
fun OptionPicker(label: String, options: Options, selected: String, onSelect: (String) -> Unit) {
    if (options.size <= 15) { Picker(label, options, selected, onSelect = onSelect); return }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = true },
        shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label, fontSize = 12.sp, color = Ice.mutedText)
            Text(options.firstOrNull { it.first == selected }?.second ?: "— اختر —", fontSize = 16.sp)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(query, { query = it }, "بحث")
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
