package com.tamimi.naqiice.data

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.adenDay
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.isoNow
import com.tamimi.naqiice.core.jnum
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.numOrNull
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.orNull
import com.tamimi.naqiice.core.parseIso
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.strings
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.core.without
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The server calls the sync engine needs; `Api` on the phone, a fake in tests. */
interface Remote {
    fun currentUserId(): String?
    suspend fun token(): String
    suspend fun select(table: String, query: String, bearer: String): List<Row>
    suspend fun selectAll(table: String, query: String, bearer: String): List<Row>
    suspend fun insert(table: String, body: JsonElement, bearer: String)
    suspend fun upsertIgnore(table: String, body: JsonElement, bearer: String)
    suspend fun rpc(function: String, args: JsonObject, bearer: String): JsonElement
    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String, bearer: String)
}

class ApiRemote(private val api: Api) : Remote {
    override fun currentUserId() = api.currentUserId()
    override suspend fun token() = api.token()
    override suspend fun select(table: String, query: String, bearer: String) = api.select(table, query, bearer)
    override suspend fun selectAll(table: String, query: String, bearer: String) = api.selectAll(table, query, bearer)
    override suspend fun insert(table: String, body: JsonElement, bearer: String) = api.insert(table, body, bearer)
    override suspend fun upsertIgnore(table: String, body: JsonElement, bearer: String) = api.upsertIgnore(table, body, bearer)
    override suspend fun rpc(function: String, args: JsonObject, bearer: String) = api.rpc(function, args, bearer)
    override suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String, bearer: String) = api.upload(bucket, path, bytes, contentType, bearer)
}

class ClosedClosingReview : Exception("الحملة مغلقة؛ هذا الطلب لم يُرفع ولا يطابق إغلاقاً مقبولاً. راجع النقد والملاحظات مع المدير أو نحِّ هذا الطلب فقط؛ بياناته ستبقى على الهاتف.")

// ---- pure reconciliation rules --------------------------------------------------------

private fun validMoney(e: JsonElement?): Boolean {
    val p = e.orNull() as? JsonPrimitive ?: return false
    val n = p.doubleOrNull ?: p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return false
    return n.isFinite() && n >= 0
}

/** Only the RPC inputs identify a revision; expected cash/difference are server-derived. */
/**
 * `allowFlagged`: an upload already acknowledged that the supervisor has since sent back still
 * proves the submission landed. Never used before uploading, so a resubmission is always sent.
 */
fun compatibleClosing(op: OutboxOp, closing: Row?, trip: Row?, allowRedundant: Boolean = false, allowFlagged: Boolean = false): Boolean {
    val p = op.payload
    val tripId = p.str("trip_id")
    return op.kind == "closing" && p.str("id") == op.id && p.str("created_by") == op.ownerId &&
        tripId != null && trip?.str("id") == tripId && trip.str("distributor_id") == op.ownerId &&
        (trip.str("status") == "open" || trip.str("status") == "closed") &&
        closing?.str("id") != null && (allowRedundant || closing.str("id") == op.id) &&
        closing.str("trip_id") == tripId && closing.str("created_by") == op.ownerId &&
        (((closing.str("status") == "submitted" || (allowFlagged && closing.str("status") == "flagged")) && trip.str("status") == "open") || closing.str("status") == "approved") &&
        validMoney(p["counted_cash"]) && validMoney(closing["counted_cash"]) && closing.num("counted_cash") == p.num("counted_cash") &&
        closing.str("notes") == p.str("notes")
}

private val CUSTOMER_DEFAULTS: JsonObject = buildJsonObject {
    put("phone", JsonNull); put("region_id", JsonNull); put("location_notes", JsonNull); put("lat", JsonNull); put("lng", JsonNull)
    put("truck_id", JsonNull); put("credit_limit", 0); put("active", true)
}
private val CUSTOMER_FIELDS = setOf("id", "created_by", "name", "created_at", "updated_at") + CUSTOMER_DEFAULTS.keys

/** Requests omit active/credit_limit; the existing database defaults are used. */
fun customerSubmission(op: OutboxOp): JsonObject {
    val p = op.payload
    if (op.kind != "customer" || p.str("id") != op.id || p.str("created_by") != op.ownerId || p.str("name").isNullOrBlank() || p.keys.any { it !in CUSTOMER_FIELDS }) {
        throw IllegalStateException("تعارض بيانات أو مالك العميل؛ الطلب محفوظ على الهاتف للمراجعة")
    }
    return JsonObject(CUSTOMER_DEFAULTS + p)
}

/** A visible shared-directory UUID alone is not proof this owner submitted these facts. */
fun compatibleCustomer(op: OutboxOp, row: Row?): Boolean {
    if (row == null) return false
    return customerSubmission(op).all { (key, value) ->
        val actual = row[key].orNull()
        val submitted = value.orNull()
        when (key) {
            "credit_limit" ->
                if (submitted == null) actual == null
                else actual != null && (submitted as? JsonPrimitive)?.doubleOrNull != null && (submitted as JsonPrimitive).doubleOrNull == row.numOrNull(key)
            // The database prints doubles with 15 significant digits (extra_float_digits = 0), so a
            // GPS fix such as 14.485588333333332 comes back as 14.4855883333333. Within 1e-9 degrees
            // (about 0.1 mm) is the same position; an exact comparison never matched again.
            "lat", "lng" -> {
                val sent = (submitted as? JsonPrimitive)?.doubleOrNull
                val stored = row.numOrNull(key)
                if (submitted == null) actual == null
                else sent != null && stored != null && kotlin.math.abs(sent - stored) <= 1e-9
            }
            "created_at", "updated_at" -> parseIso((submitted as? JsonPrimitive)?.contentOrNull) != null &&
                parseIso((submitted as? JsonPrimitive)?.contentOrNull) == parseIso((actual as? JsonPrimitive)?.contentOrNull)
            else -> submitted == actual
        }
    }
}

/** Stored legacy cash_paid is tendered (cash + deposit); present actual cash only. */
fun withDeposit(row: Row, entry: Row?): Row {
    if (entry == null) return row
    var out = row.with("deposit_amount" to jnum(entry.num("amount")), "deposit_slip_path" to (entry["slip_path"] ?: JsonNull), "payment_method" to JsonPrimitive("deposit"))
    if (entry.str("kind") == "sale") out = out.with("cash_paid" to jnum(row.num("cash_paid") - entry.num("amount")))
    return out
}

/**
 * Box moves and customer-location confirmations ride along with the accounting records but
 * are never part of them: they cannot hold up a closing and are cleared on their own evidence.
 */
val SIDE_KINDS = setOf("box_move", "customer_location")

// ---- engine ---------------------------------------------------------------------------

class SyncEngine(
    private val store: LocalStore,
    private val remote: Remote,
    /** Bytes of a photo saved on the phone, or null when the file is gone. */
    private val readFile: (String) -> ByteArray?,
    private val bucket: String = Backend.RECEIPT_BUCKET,
) {
    private val flushLock = Mutex()

    private val tables = mapOf(
        "payment" to "payments", "expense" to "expenses", "maintenance" to "maintenance_jobs",
        "freezer_event" to "freezer_events", "gps" to "gps_points", "closing" to "closings", "customer" to "customers",
    )

    /** Where an uploaded record's own row is, for records cleared by their UUID. */
    private val recordTables = tables - "closing" - "customer" + ("sale" to "sales")

    private fun assertOwner(ownerId: String, message: String = "تغير الحساب") {
        if (remote.currentUserId() != ownerId) throw IllegalStateException(message)
    }

    /** Uploads one record. Returns reconciliation evidence for a redundant closing. */
    private suspend fun push(op: OutboxOp, reconcileOnly: Boolean = false): JsonObject? {
        assertOwner(op.ownerId, "تم تغيير الحساب؛ توقف رفع السجلات")
        val bearer = remote.token()
        assertOwner(op.ownerId)
        val p = op.payload

        if (op.kind == "customer") {
            val payload = customerSubmission(op)
            suspend fun read(): Row? {
                val row = remote.select("customers", "select=*&id=eq.${op.id}&created_by=eq.${op.ownerId}&limit=1", bearer).firstOrNull()
                assertOwner(op.ownerId)
                if (row != null && !compatibleCustomer(op, row)) throw IllegalStateException("تعارض بيانات العميل مع السجل المحفوظ؛ راجع المسؤول، ولا تُعد إنشاء العميل أو الفاتورة")
                return row
            }
            if (read() != null) return null
            // Plain insert, no ON CONFLICT or RETURNING: directory policies cannot see a new UUID yet.
            val failure = try { remote.insert("customers", payload, bearer); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            assertOwner(op.ownerId)
            if (failure == null) return null
            // Lost responses / concurrent duplicates require independent, owner-bound evidence.
            if (read() != null) return null
            val code = (failure as? ApiError)?.code
            if (code == "23505") throw IllegalStateException("معرف العميل مستخدم دون تطابق موثق لهذا الحساب؛ الطلب والفاتورة محفوظان، راجع المسؤول")
            if (code == "42501") throw IllegalStateException("تعذّر رفع العميل: تحقق من الحساب والحملة المفتوحة ثم أعد المزامنة؛ لا تُعد إنشاء العميل أو الفاتورة")
            throw failure
        }

        if (op.kind in SIDE_KINDS) {
            if (p.str("id") != op.id || p.str("created_by") != op.ownerId) throw IllegalStateException("تعارض معرف أو مالك السجل")
            val box = op.kind == "box_move"
            // The phone's clock at upload lets the server place the scan on its own clock.
            remote.rpc(
                if (box) "pureice_box_move" else "pureice_set_customer_location",
                buildJsonObject { put("p_request", p); if (box) put("p_sent_at", isoNow()) }, bearer,
            )
            assertOwner(op.ownerId)
            return null
        }

        if (op.kind == "truck_issue") {
            if (p.str("id") != op.id || p.str("created_by") != op.ownerId) throw IllegalStateException("تعارض معرف أو مالك السجل")
            val path = p.str("photo_path")
            if (path != null && !path.startsWith("${op.ownerId}/")) throw IllegalStateException("الصورة تخص حساباً آخر")
            if (path != null) p.str("photo_blob")?.let { file -> readFile(file)?.let { remote.upload(bucket, path, it, "image/jpeg", bearer) } }
            assertOwner(op.ownerId)
            remote.rpc("pureice_report_truck_issue", buildJsonObject { put("p_request", p.without("photo_blob", "created_by", "created_at")) }, bearer)
            assertOwner(op.ownerId)
            return null
        }

        if (op.kind == "closing_handover") {
            val closingId = p.str("closing_id")
            if (p.str("id") != op.id || p.str("created_by") != op.ownerId || closingId == null) throw IllegalStateException("تعارض معرف أو مالك السجل")
            // The server only takes a handover for a closing it already holds.
            store.getOp(closingId)?.let { if (it.state != "acknowledged" && it.state != "reconciled") throw IllegalStateException("بانتظار رفع الإغلاق") }
            val path = p.str("slip_path")
            if (p.num("transfer_amount") > 0 && (path == null || !path.startsWith("${op.ownerId}/"))) throw IllegalStateException("إشعار الإيداع مطلوب ويخص هذا الحساب")
            val type = if (p.str("slip_type") == "application/pdf") "application/pdf" else "image/jpeg"
            if (path != null) p.str("slip_blob")?.let { file -> readFile(file)?.let { remote.upload(bucket, path, it, type, bearer) } }
            assertOwner(op.ownerId)
            val request = buildJsonObject {
                put("closing_id", closingId); put("cash_amount", p["cash_amount"] ?: JsonNull)
                put("transfer_amount", p["transfer_amount"] ?: JsonNull); put("slip_path", p["slip_path"] ?: JsonNull)
            }
            remote.rpc("pureice_save_closing_handover", buildJsonObject { put("p_request", request) }, bearer)
            assertOwner(op.ownerId)
            return null
        }

        if (p.str("payment_method") == "deposit") {
            val path = p.str("deposit_slip_path")
            if (path == null || !path.startsWith("${op.ownerId}/")) throw IllegalStateException("صورة إيصال الإيداع مطلوبة وتخص هذا الحساب")
            p.str("deposit_slip_blob")?.let { file -> readFile(file)?.let { remote.upload(bucket, path, it, "image/jpeg", bearer) } }
            assertOwner(op.ownerId)
            val request = p.without("deposit_slip_blob")
            remote.rpc(if (op.kind == "sale") "pureice_save_deposit_invoice" else "pureice_collect_deposit", buildJsonObject { put("p_request", request) }, bearer)
            return null
        }

        if (op.kind == "sale") {
            remote.rpc("save_invoice", buildJsonObject { put("p_invoice", p) }, bearer)
            return null
        }

        if (op.kind == "closing") {
            val tripId = p.str("trip_id")
            if (p.str("id") != op.id || p.str("created_by") != op.ownerId || tripId == null) throw IllegalStateException("تعارض معرف أو مالك الإغلاق")
            // Never infer success from an error message: read the committed rows first.
            val closing = remote.select("closings", "select=*&trip_id=eq.$tripId&limit=1", bearer).firstOrNull()
            val trip = remote.select("trips", "select=*&id=eq.$tripId&limit=1", bearer).firstOrNull()
            assertOwner(op.ownerId)
            if (compatibleClosing(op, closing, trip)) return null
            if (compatibleClosing(op, closing, trip, true)) return buildJsonObject { put("closing", closing ?: JsonNull); put("trip", trip ?: JsonNull) }
            if (trip != null && trip.str("id") == tripId && trip.str("distributor_id") == op.ownerId && trip.str("status") == "closed") throw ClosedClosingReview()
            if (reconcileOnly) throw IllegalStateException("توجد سجلات مرتبطة لم تُرفع بعد؛ راجع الأخطاء أولاً")
            remote.rpc(
                "submit_closing",
                buildJsonObject { put("p_id", op.id); put("p_trip", tripId); put("p_counted", p["counted_cash"] ?: JsonNull); put("p_notes", p["notes"] ?: JsonNull) },
                bearer,
            )
            assertOwner(op.ownerId)
            return null
        }

        var payload = p
        for (key in p.keys.filter { it.endsWith("_blob") }) {
            val file = p.str(key)
            val path = p.str(key.removeSuffix("_blob") + "_path")
            payload = payload.without(key)
            if (file == null || path == null) continue
            assertOwner(op.ownerId)
            if (!path.startsWith("${op.ownerId}/")) throw IllegalStateException("الصورة تخص حساباً آخر")
            readFile(file)?.let { remote.upload(bucket, path, it, "image/jpeg", bearer) }
        }
        assertOwner(op.ownerId)
        remote.upsertIgnore(tables[op.kind] ?: throw IllegalStateException("نوع سجل غير معروف"), payload, bearer)
        return null
    }

    data class FlushResult(val sent: Int, val failed: Int)

    /** Uploads everything waiting for this account: customers first, closings last. */
    suspend fun flushOutbox(ownerId: String): FlushResult {
        if (!flushLock.tryLock()) return FlushResult(0, 0)
        var sent = 0
        var failed = 0
        try {
            // A handover follows its closing.
            fun rank(op: OutboxOp) = when (op.kind) { "customer" -> -1; "closing" -> 1; "closing_handover" -> 2; else -> 0 }
            val ops = store.outboxFor(ownerId).filter { it.state == "queued" || it.state == "blocked" }
                .sortedWith(compareBy<OutboxOp> { rank(it) }.thenBy { it.createdAt })
            val blockedCustomers = HashSet<String>()
            val blockedTrips = HashSet<String>()
            val blockedSales = HashSet<String>()
            for (op in ops) {
                val dependsOnBlocked = (op.kind == "sale" && op.payload.str("customer_id") in blockedCustomers) ||
                    (op.kind == "closing" && op.payload.str("trip_id") in blockedTrips) ||
                    // A location is confirmed for an invoice; a box can only go to a customer the server knows.
                    (op.kind == "customer_location" && (op.payload.str("sale_id") in blockedSales || op.payload.str("customer_id") in blockedCustomers)) ||
                    (op.kind == "box_move" && op.payload.str("customer_id") in blockedCustomers)
                if (dependsOnBlocked) {
                    // Failed campaign writes stay queued, but must not prevent a read-only
                    // acknowledgement of a closing that was already committed.
                    if (op.kind == "closing") {
                        try {
                            val evidence = push(op, true)
                            store.putOp(op.copy(state = if (evidence != null) "reconciled" else "acknowledged", reconciliation = evidence, lastError = null))
                            if (evidence == null) sent++
                            continue
                        } catch (e: ClosedClosingReview) {
                            store.putOp(op.copy(state = "review", lastError = e.message))
                            failed++
                            continue
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { /* falls through to blocked */ }
                    }
                    store.putOp(op.copy(lastError = "توجد سجلات مرتبطة لم تُرفع بعد؛ راجع الأخطاء أولاً", state = "blocked"))
                    if (op.kind == "sale") blockedSales.add(op.id)
                    // A skipped campaign record holds its closing back exactly like a failed one.
                    if (op.kind !in SIDE_KINDS) op.payload.str("trip_id")?.let { blockedTrips.add(it) }
                    failed++
                    continue
                }
                try {
                    val evidence = push(op)
                    // Keep the sole local copy until the durable snapshot acknowledges the UUID.
                    store.putOp(op.copy(state = if (evidence != null) "reconciled" else "acknowledged", reconciliation = evidence, lastError = null))
                    if (evidence == null) sent++
                } catch (e: CancellationException) {
                    // A cancelled sync is not a failed upload: the record stays as it was and is retried.
                    throw e
                } catch (e: Exception) {
                    failed++
                    store.putOp(op.copy(state = if (e is ClosedClosingReview) "review" else "blocked", attempts = op.attempts + 1, lastError = e.userMessage("تعذّر الرفع")))
                    if (op.kind == "customer") blockedCustomers.add(op.id)
                    if (op.kind == "sale") blockedSales.add(op.id)
                    // Boxes and locations are not accounting: they never hold a closing back.
                    if (op.kind !in SIDE_KINDS) op.payload.str("trip_id")?.let { blockedTrips.add(it) }
                }
            }
        } finally { flushLock.unlock() }
        return FlushResult(sent, failed)
    }

    private suspend fun supported(function: String, bearer: String): Boolean? = try {
        (remote.rpc(function, JsonObject(emptyMap()), bearer) as? JsonPrimitive)?.contentOrNull == "1"
    } catch (e: ApiError) {
        if (e.code == "PGRST202" || e.code == "42883") false else null
    }

    /** True/false once the server answers; null when the answer is unknown (keep the cached value). */
    suspend fun featureSupport(ownerId: String, function: String): Boolean? {
        val bearer = remote.token()
        val result = supported(function, bearer)
        assertOwner(ownerId, ACCOUNT_CHANGED)
        return result
    }

    /**
     * Pulls reference data and the authoritative campaign snapshot, stores it, and archives
     * local records the snapshot now includes. `current` aborts a stale refresh.
     */
    suspend fun fetchRef(userId: String, current: () -> Boolean = { true }): JsonObject {
        fun guard() { if (remote.currentUserId() != userId || !current()) throw IllegalStateException(ACCOUNT_CHANGED) }
        guard()
        val bearer = remote.token()
        guard()
        // Listed before any read: whatever these uploads changed is in the rows fetched below.
        val sideSent = store.outboxFor(userId).filter { it.kind in SIDE_KINDS && it.state == "acknowledged" }
        val handoversSent = store.outboxFor(userId).filter { it.kind == "closing_handover" && it.state == "acknowledged" }
        val issuesSent = store.outboxFor(userId).filter { it.kind == "truck_issue" && it.state == "acknowledged" }
        val recordsSent = store.outboxFor(userId).filter { it.kind in recordTables && it.state == "acknowledged" }
        val variants = remote.selectAll("product_variants", "select=*,products(name,active,sort)&active=eq.true&order=sort,id", bearer)
        val regions = remote.selectAll("regions", "select=*&active=eq.true&order=sort,id", bearer)
        val customers = remote.selectAll("customers", "select=*&active=eq.true&order=id", bearer)
        val prices = remote.selectAll("customer_prices", "select=customer_id,variant_id,price&order=id", bearer)
        val freezers = remote.selectAll("freezers", "select=*,freezer_models(name)&order=serial,id", bearer)
        val models = remote.selectAll("freezer_models", "select=*&order=id", bearer)
        val trucks = remote.selectAll("trucks", "select=*&order=code,id", bearer)
        guard()

        // Assigned campaign only; no profile-truck fallback. With assistants on the server, it says
        // which campaign (one he leads, else one he assists) and his role on it.
        val assistantSupport = supported("pureice_trip_assistant_support", bearer)
            ?: throw IllegalStateException("تعذّر تحديث الحملة")
        val mine = if (assistantSupport) remote.rpc("pureice_my_open_trip", JsonObject(emptyMap()), bearer) as? JsonObject else null
        val trip = if (assistantSupport) mine?.obj("trip")
            else remote.select("trips", "select=*&distributor_id=eq.$userId&status=neq.closed&order=created_at.desc&limit=1", bearer).firstOrNull()
        val tripRole = when {
            trip == null -> null
            assistantSupport -> mine?.str("role")
            else -> "main"
        }
        guard()
        val tripId = trip?.str("id")
        val unapprovedLoads = if (tripId != null) remote.selectAll("trip_loads", "select=*&trip_id=eq.$tripId&approved_at=is.null&order=id", bearer) else emptyList()
        guard()
        val products = variants.filter { it.obj("products")?.get("active") != JsonPrimitive(false) }
        val accounting = remote.rpc("accounting_snapshot", buildJsonObject { put("p_trip", tripId ?: Backend.ZERO_UUID) }, bearer) as? JsonObject
            ?: throw IllegalStateException("تعذّر تحديث الحسابات")
        guard()
        val includedIds = accounting.strings("includedIds")
        val deposits = if (tripId != null && supported("pureice_deposit_support", bearer) == true) {
            remote.selectAll("pureice_deposits", "select=*&trip_id=eq.$tripId&order=id", bearer)
        } else emptyList()
        guard()

        // Returnable boxes (optional server feature). Unknown answer: keep what the phone has.
        val previous = store.cacheObject(userId, "ref")
        val boxSupport = supported("pureice_box_support", bearer)
        val boxes = when (boxSupport) {
            true -> remote.selectAll("pureice_boxes", "select=*&order=serial,id", bearer)
            false -> emptyList()
            null -> previous?.rows("boxes") ?: emptyList()
        }
        val boxVariants = when (boxSupport) {
            true -> remote.selectAll("pureice_box_variants", "select=variant_id&order=variant_id", bearer).mapNotNull { it.str("variant_id") }
            false -> emptyList()
            null -> previous?.strings("boxVariants") ?: emptyList()
        }
        guard()
        // A side record is cleared only when the server holds its UUID.
        val sideEvidence = HashSet<String>()
        if (boxSupport == true) for ((kind, table) in listOf("box_move" to "pureice_box_events", "customer_location" to "pureice_customer_locations")) {
            for (ids in sideSent.filter { it.kind == kind }.map { it.id }.chunked(40)) {
                remote.select(table, "select=id&id=in.(${ids.joinToString(",")})", bearer).mapNotNullTo(sideEvidence) { it.str("id") }
            }
        }
        // A truck problem report is cleared once the server holds its UUID.
        val issueEvidence = HashSet<String>()
        if (issuesSent.isNotEmpty() && supported("pureice_truck_care_support", bearer) == true) {
            for (ids in issuesSent.map { it.id }.chunked(40)) {
                remote.select("pureice_truck_issues", "select=id&id=in.(${ids.joinToString(",")})", bearer).mapNotNullTo(issueEvidence) { it.str("id") }
            }
        }
        // A handover is cleared when the server holds exactly what it sent: a later correction
        // of the same closing must not clear an earlier request, nor the other way round.
        val handoverEvidence = HashSet<String>()
        if (handoversSent.isNotEmpty() && supported("pureice_closing_handover_support", bearer) == true) {
            for (chunk in handoversSent.chunked(40)) {
                val rows = remote.select("pureice_closing_handover", "select=closing_id,cash_amount,transfer_amount,slip_path&closing_id=in.(${chunk.mapNotNull { it.payload.str("closing_id") }.joinToString(",")})", bearer)
                for (op in chunk) {
                    val row = rows.firstOrNull { it.str("closing_id") == op.payload.str("closing_id") } ?: continue
                    if (row.num("cash_amount") == op.payload.num("cash_amount") && row.num("transfer_amount") == op.payload.num("transfer_amount") &&
                        row.str("slip_path") == op.payload.str("slip_path")) handoverEvidence.add(op.id)
                }
            }
        }
        guard()

        // The snapshot only vouches for the current campaign. A record of another campaign
        // (closed before this phone saw it archived) or a route point is cleared when the
        // server holds its UUID, so it is never counted on top of the server's balances.
        val included = includedIds.toHashSet()
        val recordEvidence = HashSet<String>()
        val unvouched = recordsSent.filter { it.id !in included && (it.kind == "gps" || it.payload.str("trip_id") != tripId) }
        for ((kind, ops) in unvouched.groupBy { it.kind }) {
            for (ids in ops.map { it.id }.chunked(40)) {
                remote.select(recordTables.getValue(kind), "select=id&id=in.(${ids.joinToString(",")})", bearer).mapNotNullTo(recordEvidence) { it.str("id") }
            }
        }
        guard()

        // The current campaign may already be a different one (or absent). Fetch evidence
        // for every acknowledged closing by its original campaign.
        val closingEvidence = HashMap<String, JsonObject>()
        for (op in store.outboxFor(userId).filter { it.kind == "closing" && (it.state == "acknowledged" || it.state == "reconciled") }) {
            val t = op.payload.str("trip_id") ?: continue
            val c = remote.select("closings", "select=*&trip_id=eq.$t&limit=1", bearer).firstOrNull()
            val tr = remote.select("trips", "select=*&id=eq.$t&limit=1", bearer).firstOrNull()
            guard()
            if (compatibleClosing(op, c, tr, op.state == "reconciled", op.state == "acknowledged")) closingEvidence[op.id] = buildJsonObject { put("closing", c ?: JsonNull); put("trip", tr ?: JsonNull) }
        }

        fun deposit(kind: String, id: String?) = deposits.firstOrNull { it.str("kind") == kind && it.str("id") == id }
        val ref = buildJsonObject {
            put("ownerId", userId)
            put("includedIds", accounting["includedIds"] ?: JsonArray(emptyList()))
            put("variants", JsonArray(products))
            put("regions", JsonArray(regions))
            put("customers", JsonArray(customers))
            put("prices", JsonArray(prices))
            put("balances", accounting["balances"].orNull() ?: JsonArray(emptyList()))
            put("freezers", JsonArray(freezers))
            put("models", JsonArray(models))
            put("trucks", JsonArray(trucks))
            put("trip", trip ?: JsonNull)
            put("tripRole", tripRole)
            put("assistants", (mine?.get("assistants") as? JsonArray) ?: JsonArray(emptyList()))
            put("stock", accounting["stock"].orNull() ?: JsonArray(emptyList()))
            put("unapprovedLoads", JsonArray(unapprovedLoads))
            put("salesToday", JsonArray(accounting.rows("sales").map { withDeposit(it, deposit("sale", it.str("id"))) }))
            put("paymentsToday", JsonArray(accounting.rows("payments").map { withDeposit(it, deposit("payment", it.str("id"))) }))
            put("expensesToday", accounting["expenses"].orNull() ?: JsonArray(emptyList()))
            put("closing", accounting["closing"].orNull() ?: JsonNull)
            put("boxSupport", boxSupport ?: (previous?.bool("boxSupport") == true))
            put("boxes", JsonArray(boxes))
            put("boxVariants", JsonArray(boxVariants.map { JsonPrimitive(it) }))
            put("day", adenDay())
            put("pulledAt", System.currentTimeMillis())
        }
        guard()
        store.transaction {
            store.setCacheObject(userId, "ref", ref)
            for (op in store.outboxFor(userId)) {
                val uploaded = op.state == "acknowledged" && when (op.kind) {
                    "customer" -> true
                    in SIDE_KINDS -> op.id in sideEvidence
                    "closing_handover" -> op.id in handoverEvidence
                    "truck_issue" -> op.id in issueEvidence
                    else -> op.id in included || op.id in recordEvidence
                }
                val redundant = op.state == "reconciled" && closingEvidence.containsKey(op.id)
                if (!uploaded && !redundant) continue
                if (op.kind == "customer" && !compatibleCustomer(op, customers.firstOrNull { it.str("id") == op.id })) continue
                // Closings include revisions; only prune when the submitted values are visible.
                val evidence = closingEvidence[op.id]
                if (op.kind == "closing" && !compatibleClosing(op, evidence?.obj("closing"), evidence?.obj("trip"), op.state == "reconciled", op.state == "acknowledged")) continue
                // Archive the full local record in the same transaction before removing it.
                store.putCache("$userId:operation:${op.id}", archive(op, evidence).toString())
                store.deleteOp(op.id)
            }
            // An account switch during the transaction rolls the whole refresh back.
            guard()
        }
        return ref
    }

    private fun archive(op: OutboxOp, evidence: JsonObject?) = buildJsonObject {
        put("id", op.id); put("ownerId", op.ownerId); put("state", op.state); put("kind", op.kind)
        put("payload", op.payload); put("createdAt", op.createdAt); put("attempts", op.attempts)
        (evidence ?: op.reconciliation)?.let { put("reconciliation", it) }
    }
}
