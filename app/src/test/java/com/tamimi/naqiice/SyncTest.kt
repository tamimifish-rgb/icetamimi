package com.tamimi.naqiice

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.boxMoveRequest
import com.tamimi.naqiice.core.boxStatusAfter
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.strings
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.MemoryStore
import com.tamimi.naqiice.data.Remote
import com.tamimi.naqiice.data.Snapshot
import com.tamimi.naqiice.data.SyncEngine
import com.tamimi.naqiice.data.cacheObject
import com.tamimi.naqiice.data.enqueue
import com.tamimi.naqiice.data.setAside
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWNER = "11111111-1111-1111-1111-111111111111"
private const val OTHER = "22222222-2222-2222-2222-222222222222"
private const val TRIP = "33333333-3333-3333-3333-333333333333"

/** A tiny in-memory server: tables, the invoice/closing RPCs and the snapshot. */
private class FakeServer : Remote {
    var user: String? = OWNER
    val tables = HashMap<String, MutableList<Row>>()
    val calls = ArrayList<String>()
    val uploads = ArrayList<String>()
    var failInvoice: String? = null
    var cancelInvoice = false
    var failCustomerInsert = false
    var included: List<String> = emptyList()
    var onSelect: (() -> Unit)? = null
    /** true: the box feature is live; false: the function is missing; null: the server cannot say. */
    var boxSupport: Boolean? = false
    var failBoxMove = false
    var failLocation = false
    /** true: the closing-handover feature is live on the server. */
    var handoverSupport = false
    /** true: truck service plans and problem reports are live on the server. */
    var truckCareSupport = false
    /** true: assistant distributors are live; [myOpenTrip] is what pureice_my_open_trip answers. */
    var assistantSupport = false
    var myOpenTrip: JsonElement = JsonNull
    var snapshotTrip: String? = null
    val uploadTypes = HashMap<String, String>()

    fun table(name: String) = tables.getOrPut(name) { ArrayList() }

    override fun currentUserId() = user
    override suspend fun token() = "token-$user"

    private fun filter(table: String, query: String): List<Row> {
        var rows: List<Row> = table(table)
        for (part in query.split('&')) {
            val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (value.startsWith("eq.")) rows = rows.filter { it.str(key) == value.removePrefix("eq.") }
            if (value.startsWith("neq.")) rows = rows.filter { it.str(key) != value.removePrefix("neq.") }
            if (value == "is.null") rows = rows.filter { it.str(key) == null }
            if (value.startsWith("in.(")) value.removePrefix("in.(").removeSuffix(")").split(',').let { set -> rows = rows.filter { it.str(key) in set } }
        }
        return rows
    }

    override suspend fun select(table: String, query: String, bearer: String): List<Row> { onSelect?.invoke(); return filter(table, query) }
    override suspend fun selectAll(table: String, query: String, bearer: String): List<Row> = filter(table, query)

    override suspend fun insert(table: String, body: JsonElement, bearer: String) {
        calls.add("insert:$table")
        if (table == "customers" && failCustomerInsert) throw ApiError(403, "42501", "denied")
        table(table).add(body as JsonObject)
    }

    override suspend fun upsertIgnore(table: String, body: JsonElement, bearer: String) {
        calls.add("upsert:$table")
        val row = body as JsonObject
        if (table(table).none { it.str("id") == row.str("id") }) table(table).add(row)
    }

    override suspend fun rpc(function: String, args: JsonObject, bearer: String): JsonElement {
        calls.add("rpc:$function")
        when (function) {
            "save_invoice" -> {
                val invoice = args["p_invoice"] as JsonObject
                failInvoice?.let { throw ApiError(400, "P0001", it) }
                if (cancelInvoice) throw kotlin.coroutines.cancellation.CancellationException("Job was cancelled")
                if (table("sales").none { it.str("id") == invoice.str("id") }) table("sales").add(invoice)
                return invoice
            }
            "submit_closing" -> {
                table("closings").add(buildJsonObject {
                    put("id", args["p_id"]!!); put("trip_id", args["p_trip"]!!); put("created_by", OWNER); put("status", "submitted")
                    put("counted_cash", args["p_counted"]!!); put("notes", args["p_notes"] ?: JsonNull)
                })
                return JsonNull
            }
            "accounting_snapshot" -> return buildJsonObject {
                snapshotTrip = args.str("p_trip")
                put("includedIds", JsonArray(included.map { JsonPrimitive(it) }))
                put("balances", buildJsonArray { add(buildJsonObject { put("customer_id", "c1"); put("balance", 2000) }) })
                put("stock", buildJsonArray { add(buildJsonObject { put("variant_id", "v1"); put("loaded", 100); put("sold", 10); put("returned", 0); put("damaged", 0) }) })
                put("sales", JsonArray(table("sales"))); put("payments", JsonArray(emptyList())); put("expenses", JsonArray(emptyList()))
                put("closing", JsonNull)
            }
            "pureice_deposit_support" -> throw ApiError(404, "PGRST202", "missing")
            "pureice_box_support" -> return when (boxSupport) {
                true -> JsonPrimitive(1)
                false -> throw ApiError(404, "PGRST202", "missing")
                null -> throw ApiError(503, "PGRST000", "unavailable")
            }
            "pureice_box_move" -> {
                if (failBoxMove) throw ApiError(400, "P0001", "صندوق غير معروف")
                val request = args["p_request"] as JsonObject
                check(args["p_sent_at"] != null)
                for (entry in request.rows("boxes")) {
                    if (table("pureice_box_events").none { it.str("id") == entry.str("id") }) table("pureice_box_events").add(buildJsonObject { put("id", entry.str("id")); put("batch_id", request.str("id")) })
                    val status = boxStatusAfter(request.str("action")!!)
                    table("pureice_boxes").replaceAll { box ->
                        if (box.str("serial") != entry.str("serial")) box
                        else box.with("status" to JsonPrimitive(status), "customer_id" to (if (status == "customer") request["customer_id"]!! else JsonNull), "truck_id" to (if (status == "truck") request["truck_id"]!! else JsonNull))
                    }
                }
                return JsonNull
            }
            "pureice_trip_assistant_support" -> if (assistantSupport) return JsonPrimitive(1) else throw ApiError(404, "PGRST202", "missing")
            "pureice_my_open_trip" -> return myOpenTrip
            "pureice_truck_care_support" -> if (truckCareSupport) return JsonPrimitive(1) else throw ApiError(404, "PGRST202", "missing")
            "pureice_report_truck_issue" -> {
                val request = args["p_request"] as JsonObject
                if (table("pureice_truck_issues").none { it.str("id") == request.str("id") }) table("pureice_truck_issues").add(request)
                return request
            }
            "pureice_closing_handover_support" -> if (handoverSupport) return JsonPrimitive(1) else throw ApiError(404, "PGRST202", "missing")
            "pureice_save_closing_handover" -> {
                val request = args["p_request"] as JsonObject
                val closing = table("closings").firstOrNull { it.str("id") == request.str("closing_id") } ?: throw ApiError(400, "42501", "الإغلاق ليس لهذا الحساب")
                if (request.num("cash_amount") + request.num("transfer_amount") != closing.num("counted_cash")) throw ApiError(400, "22023", "mismatch")
                table("pureice_closing_handover").removeAll { it.str("closing_id") == request.str("closing_id") }
                table("pureice_closing_handover").add(request)
                return request
            }
            "pureice_set_customer_location" -> {
                if (failLocation) throw ApiError(400, "P0001", "العميل غير موجود")
                val request = args["p_request"] as JsonObject
                table("pureice_customer_locations").add(buildJsonObject { put("id", request.str("id")) })
                table("customers").replaceAll { if (it.str("id") == request.str("customer_id") && it.str("lat") == null) it.with("lat" to request["lat"]!!, "lng" to request["lng"]!!) else it }
                return JsonNull
            }
        }
        throw ApiError(404, "PGRST202", "missing")
    }

    override suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String, bearer: String) { uploads.add(path); uploadTypes[path] = contentType }
}

private fun sale(id: String, customer: String = "c1", qty: Int = 5, credit: Int = 1000) = buildJsonObject {
    put("id", id); put("invoice_no", "T1-$id"); put("trip_id", TRIP); put("truck_id", "t1"); put("customer_id", customer)
    put("total", 2500); put("cash_paid", 2500 - credit); put("credit_amount", credit); put("created_by", OWNER); put("created_at", "2026-11-14T08:00:00.000Z")
    put("items", buildJsonArray { add(buildJsonObject { put("id", "$id-i"); put("sale_id", id); put("variant_id", "v1"); put("qty", qty); put("unit_price", 500); put("line_total", 2500) }) })
}

private fun closing(id: String, counted: Int = 9000) = buildJsonObject {
    put("id", id); put("trip_id", TRIP); put("expected_cash", 9000); put("counted_cash", counted); put("difference", 0)
    put("notes", JsonNull); put("created_by", OWNER); put("created_at", "2026-11-14T15:00:00.000Z")
}

/** A closing handover as the phone queues it: [transfer] of [counted] by deposit, the rest in cash. */
private fun handover(id: String, closing: String, counted: Int = 9000, transfer: Int = 0, slip: String? = null) = buildJsonObject {
    put("id", id); put("closing_id", closing); put("trip_id", TRIP)
    put("cash_amount", counted - transfer); put("transfer_amount", transfer)
    if (slip != null) { put("slip_path", "$OWNER/$slip"); put("slip_blob", "/data/$slip"); put("slip_type", if (slip.endsWith(".pdf")) "application/pdf" else "image/jpeg") }
    else put("slip_path", JsonNull)
    put("created_by", OWNER); put("created_at", "2026-11-14T15:00:01.000Z")
}

private fun customer(id: String) = buildJsonObject {
    put("id", id); put("name", "بقالة النور"); put("phone", JsonNull); put("region_id", "r1"); put("location_notes", "")
    put("lat", JsonNull); put("lng", JsonNull); put("truck_id", "t1"); put("created_by", OWNER)
}

private fun boxMove(id: String, action: String, serial: String, customer: String? = "c1", saleId: String? = null) =
    boxMoveRequest(OWNER, action, listOf(id to serial), "2026-11-14T08:00:05.000Z", truckId = "t1", customerId = customer, tripId = TRIP, saleId = saleId)

private fun location(id: String, saleId: String, customer: String = "c1") = buildJsonObject {
    put("id", id); put("customer_id", customer); put("sale_id", saleId); put("lat", 14.53); put("lng", 49.12); put("accuracy", 8)
    put("created_by", OWNER); put("created_at", "2026-11-14T08:00:09.000Z")
}

private fun boxRow(n: Int, status: String = "truck") = buildJsonObject {
    put("id", "b$n"); put("serial", "TM-ICE-00000$n"); put("status", status); put("truck_id", if (status == "truck") JsonPrimitive("t1") else JsonNull); put("customer_id", JsonNull)
}

private fun openTrip(server: FakeServer, status: String = "open") {
    server.table("trips").add(buildJsonObject { put("id", TRIP); put("distributor_id", OWNER); put("status", status); put("truck_id", "t1") })
}

/** OWNER assists OTHER's campaign [TRIP]; the server answers with the assistant role. */
private fun assistTrip(server: FakeServer) {
    val trip = buildJsonObject { put("id", TRIP); put("distributor_id", OTHER); put("status", "open"); put("truck_id", "t1") }
    server.table("trips").add(trip)
    server.assistantSupport = true
    server.myOpenTrip = buildJsonObject { put("trip", trip); put("role", "assistant"); put("assistants", buildJsonArray { add(JsonPrimitive(OWNER)) }) }
}

class SyncTest {
    private val store = MemoryStore()
    private val server = FakeServer()
    private val engine = SyncEngine(store, server, { ByteArray(3) })

    @Test fun enqueueIsIdempotentAndOwnerBound() {
        store.enqueue(OWNER, "sale", sale("s1"))
        store.enqueue(OWNER, "sale", sale("s1")) // same content: harmless retry
        assertEquals(1, store.outboxFor(OWNER).size)
        assertThrows(IllegalStateException::class.java) { store.enqueue(OWNER, "sale", sale("s1", credit = 0)) }
        assertThrows(IllegalStateException::class.java) { store.enqueue(OTHER, "sale", sale("s2")) }
        assertEquals(1, store.outboxFor(OWNER).size)
    }

    @Test fun uploadsCustomersFirstAndClosingsLast() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "sale", sale("s1", customer = "new1"))
        store.enqueue(OWNER, "customer", customer("new1"))
        val result = engine.flushOutbox(OWNER)
        assertEquals(3, result.sent)
        assertEquals(0, result.failed)
        assertEquals(listOf("insert:customers", "rpc:save_invoice", "rpc:submit_closing"), server.calls)
        assertTrue(store.outboxFor(OWNER).all { it.state == "acknowledged" })
    }

    @Test fun rejectedInvoiceStaysOnThePhoneAndBlocksTheClosing() = runTest {
        openTrip(server)
        server.failInvoice = "المخزون غير كاف"
        store.enqueue(OWNER, "sale", sale("s1"))
        store.enqueue(OWNER, "closing", closing("k1"))
        val result = engine.flushOutbox(OWNER)
        assertEquals(2, result.failed)
        val ops = store.outboxFor(OWNER).associateBy { it.id }
        assertEquals("blocked", ops["s1"]!!.state)
        assertEquals("المخزون غير كاف", ops["s1"]!!.lastError)
        assertEquals("blocked", ops["k1"]!!.state)
        assertFalse(server.calls.contains("rpc:submit_closing"))
        // Once the cause is fixed both go through on the next attempt.
        server.failInvoice = null
        assertEquals(2, engine.flushOutbox(OWNER).sent)
    }

    /** The live database prints doubles with 15 significant digits (extra_float_digits = 0). */
    @Test fun newCustomerWithAPreciseLocationIsConfirmed() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "customer", customer("new1").with("lat" to JsonPrimitive(14.485588333333332), "lng" to JsonPrimitive(49.12148166666667)))
        engine.flushOutbox(OWNER)
        assertEquals("acknowledged", store.getOp("new1")!!.state)
        // What the server sends back: the same position, printed to 15 significant digits.
        server.table("customers").replaceAll { it.with("lat" to JsonPrimitive(14.4855883333333), "lng" to JsonPrimitive(49.1214816666667)) }
        engine.fetchRef(OWNER)
        assertNull(store.getOp("new1"))
        assertNotNull(store.cacheObject(OWNER, "operation:new1"))
    }

    @Test fun newCustomerAtAnotherLocationIsNotConfirmed() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "customer", customer("new1").with("lat" to JsonPrimitive(14.485588333333332), "lng" to JsonPrimitive(49.12148166666667)))
        engine.flushOutbox(OWNER)
        server.table("customers").replaceAll { it.with("lat" to JsonPrimitive(14.4855893333333)) } // 1e-6 degrees, about 11 cm away
        engine.fetchRef(OWNER)
        assertEquals("acknowledged", store.getOp("new1")!!.state)
    }

    @Test fun saleWaitsForItsNewCustomer() = runTest {
        openTrip(server)
        server.failCustomerInsert = true
        store.enqueue(OWNER, "customer", customer("new1"))
        store.enqueue(OWNER, "sale", sale("s1", customer = "new1"))
        engine.flushOutbox(OWNER)
        assertFalse(server.calls.contains("rpc:save_invoice"))
        assertTrue(store.outboxFor(OWNER).all { it.state == "blocked" })
    }

    @Test fun closingWaitsForASaleHeldBackByItsNewCustomer() = runTest {
        openTrip(server)
        server.failCustomerInsert = true
        store.enqueue(OWNER, "customer", customer("new1"))
        store.enqueue(OWNER, "sale", sale("s1", customer = "new1"))
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        assertFalse(server.calls.contains("rpc:save_invoice"))
        assertFalse(server.calls.contains("rpc:submit_closing"))
        assertEquals("blocked", store.getOp("k1")!!.state)
        // Once the customer goes through, the sale and then the closing follow in order.
        server.failCustomerInsert = false
        assertEquals(3, engine.flushOutbox(OWNER).sent)
        assertTrue(server.calls.indexOf("rpc:save_invoice") < server.calls.indexOf("rpc:submit_closing"))
    }

    @Test fun flaggedClosingIsArchivedSoTheCorrectionCanBeSent() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        assertEquals("acknowledged", store.getOp("k1")!!.state)
        // The refresh after the upload was lost; the supervisor sends the closing back meanwhile.
        server.table("closings").replaceAll { it.with("status" to JsonPrimitive("flagged")) }
        server.included = listOf("k1")
        engine.fetchRef(OWNER)
        assertNull(store.getOp("k1"))
        assertNotNull(store.cacheObject(OWNER, "operation:k1"))
        // The distributor's correction goes out under the same closing id.
        store.enqueue(OWNER, "closing", closing("k1", counted = 8500))
        assertEquals(1, engine.flushOutbox(OWNER).sent)
        assertEquals(2, server.calls.count { it == "rpc:submit_closing" })
    }

    @Test fun flaggedClosingIsResubmittedEvenWithTheSameValues() = runTest {
        openTrip(server)
        server.table("closings").add(buildJsonObject {
            put("id", "k1"); put("trip_id", TRIP); put("created_by", OWNER); put("status", "flagged"); put("counted_cash", 9000); put("notes", JsonNull)
        })
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        assertEquals(1, server.calls.count { it == "rpc:submit_closing" })
    }

    @Test fun cancelledSyncLeavesRecordsQueued() = runTest {
        openTrip(server)
        server.cancelInvoice = true
        store.enqueue(OWNER, "sale", sale("s1"))
        val before = store.getOp("s1")!!
        var cancelled = false
        try { engine.flushOutbox(OWNER) } catch (_: kotlin.coroutines.cancellation.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        val after = store.getOp("s1")!!
        assertEquals(before.state, after.state)
        assertEquals(0, after.attempts)
        assertNull(after.lastError)
        // The lock is released: the next sync uploads it.
        server.cancelInvoice = false
        assertEquals(1, engine.flushOutbox(OWNER).sent)
    }

    @Test fun recordOfAClosedCampaignIsArchivedOnItsServerCopy() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "sale", sale("s1"))
        engine.flushOutbox(OWNER)
        // The refresh after the upload was lost and the campaign closed meanwhile:
        // the next snapshot is for no campaign and never lists s1.
        server.table("trips").replaceAll { it.with("status" to JsonPrimitive("closed")) }
        engine.fetchRef(OWNER)
        assertNull(store.getOp("s1"))
        assertNotNull(store.cacheObject(OWNER, "operation:s1"))
    }

    @Test fun recordOfAClosedCampaignStaysUntilTheServerHasIt() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "sale", sale("s1"))
        engine.flushOutbox(OWNER)
        server.table("sales").clear() // acknowledged, yet the server shows no such row
        server.table("trips").replaceAll { it.with("status" to JsonPrimitive("closed")) }
        engine.fetchRef(OWNER)
        assertEquals("acknowledged", store.getOp("s1")!!.state)
    }

    @Test fun routePointsAreArchivedOnceTheServerHasThem() = runTest {
        openTrip(server)
        val point = buildJsonObject {
            put("id", "g1"); put("created_by", OWNER); put("trip_id", TRIP); put("truck_id", "t1")
            put("lat", 14.53); put("lng", 49.12); put("recorded_at", "2026-11-14T08:01:00.000Z")
        }
        store.enqueue(OWNER, "gps", point)
        engine.flushOutbox(OWNER)
        engine.fetchRef(OWNER) // the open campaign's snapshot does not list route points
        assertNull(store.getOp("g1"))
    }

    @Test fun handoverWaitsForItsClosing() = runTest {
        openTrip(server)
        server.failInvoice = "المخزون غير كاف"
        store.enqueue(OWNER, "sale", sale("s1"))
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "closing_handover", handover("h1", "k1", transfer = 4000, slip = "handover-k1.jpg"))
        engine.flushOutbox(OWNER)
        assertFalse(server.calls.contains("rpc:pureice_save_closing_handover"))
        assertEquals("blocked", store.getOp("h1")!!.state)
        assertTrue(server.uploads.isEmpty()) // the slip waits with it
        // Once the sale and the closing go through, the handover follows them.
        server.failInvoice = null
        engine.flushOutbox(OWNER)
        assertTrue(server.calls.indexOf("rpc:submit_closing") < server.calls.indexOf("rpc:pureice_save_closing_handover"))
        assertEquals("acknowledged", store.getOp("h1")!!.state)
    }

    @Test fun handoverArchivedOnServerCopy() = runTest {
        openTrip(server)
        server.handoverSupport = true
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "closing_handover", handover("h1", "k1", transfer = 9000, slip = "handover-k1.jpg"))
        engine.flushOutbox(OWNER)
        engine.fetchRef(OWNER)
        assertNull(store.getOp("h1"))
        assertNotNull(store.cacheObject(OWNER, "operation:h1"))
    }

    @Test fun handoverStaysWhenTheServerHoldsOtherValues() = runTest {
        openTrip(server)
        server.handoverSupport = true
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "closing_handover", handover("h1", "k1"))
        engine.flushOutbox(OWNER)
        // Another correction reached the server meanwhile.
        server.table("pureice_closing_handover").replaceAll { it.with("cash_amount" to JsonPrimitive(0), "transfer_amount" to JsonPrimitive(9000)) }
        engine.fetchRef(OWNER)
        assertEquals("acknowledged", store.getOp("h1")!!.state)
    }

    @Test fun pdfSlipUploadsAsPdf() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "closing_handover", handover("h1", "k1", transfer = 9000, slip = "handover-k1.pdf"))
        engine.flushOutbox(OWNER)
        assertEquals("application/pdf", server.uploadTypes["$OWNER/handover-k1.pdf"])
        val request = server.table("pureice_closing_handover").single()
        assertEquals("$OWNER/handover-k1.pdf", request.str("slip_path"))
        assertNull(request["slip_blob"]) // the phone's file path never leaves the phone
    }

    @Test fun handoverSlipMustBeTheOwners() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "closing", closing("k1"))
        store.enqueue(OWNER, "closing_handover", handover("h1", "k1", transfer = 9000, slip = "x.jpg").with("slip_path" to JsonPrimitive("$OTHER/x.jpg")))
        engine.flushOutbox(OWNER)
        assertEquals("blocked", store.getOp("h1")!!.state)
        assertTrue(server.uploads.isEmpty())
    }

    @Test fun assistantFindsHisTrip() = runTest {
        assistTrip(server)
        val ref = engine.fetchRef(OWNER)
        assertEquals(TRIP, ref.obj("trip")?.str("id"))
        assertEquals(TRIP, server.snapshotTrip) // stock and the campaign's sales come from his campaign
        val snap = Snapshot(ref, emptyList(), OWNER)
        assertEquals("assistant", snap.tripRole)
        assertTrue(snap.ownOpenTrip(OWNER))
        assertFalse(snap.canCloseOrApprove)
    }

    @Test fun mainDistributorKeepsHisRole() = runTest {
        openTrip(server)
        // A server without assistants: the campaign he leads, found as before.
        val before = Snapshot(engine.fetchRef(OWNER), emptyList(), OWNER)
        assertEquals("main", before.tripRole)
        assertTrue(before.canCloseOrApprove)
        // With assistants: the server says which campaign and which role.
        server.assistantSupport = true
        server.myOpenTrip = buildJsonObject { put("trip", server.table("trips").single()); put("role", "main"); put("assistants", buildJsonArray { add(JsonPrimitive(OTHER)) }) }
        val after = Snapshot(engine.fetchRef(OWNER), emptyList(), OWNER)
        assertEquals("main", after.tripRole)
        assertEquals(listOf(OTHER), after.assistants)
        assertTrue(after.ownOpenTrip(OWNER) && after.canCloseOrApprove)
    }

    @Test fun noTripWhenTheServerSaysNone() = runTest {
        server.assistantSupport = true
        // The old lookup would find this campaign; the server's answer (none) wins.
        openTrip(server)
        val snap = Snapshot(engine.fetchRef(OWNER), emptyList(), OWNER)
        assertNull(snap.trip)
        assertNull(snap.tripRole)
        assertFalse(snap.ownOpenTrip(OWNER))
    }

    @Test fun assistantSaleRejectedForStockStaysBlocked() = runTest {
        assistTrip(server)
        engine.fetchRef(OWNER)
        // The main distributor sold the last bags from his phone first.
        server.failInvoice = "المخزون المعتمد غير كاف"
        store.enqueue(OWNER, "sale", sale("s1"))
        assertEquals(1, engine.flushOutbox(OWNER).failed)
        val op = store.getOp("s1")!!
        assertEquals("blocked", op.state)
        assertEquals("المخزون المعتمد غير كاف", op.lastError)
        engine.fetchRef(OWNER)
        assertNotNull(store.getOp("s1")) // kept for review, never lost
    }

    @Test fun truckIssueQueuesOffline() = runTest {
        openTrip(server)
        server.truckCareSupport = true
        store.enqueue(OWNER, "truck_issue", buildJsonObject {
            put("id", "i1"); put("truck_id", "t1"); put("title", "الفرامل"); put("details", "صوت عند التوقف"); put("severity", "urgent")
            put("photo_path", "$OWNER/issue-i1.jpg"); put("photo_blob", "/data/issue.jpg"); put("created_by", OWNER); put("created_at", "2026-11-14T09:00:00.000Z")
        })
        // An issue never holds anything back, and nothing holds it back.
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        assertEquals(listOf("$OWNER/issue-i1.jpg"), server.uploads)
        val sent = server.table("pureice_truck_issues").single()
        assertNull(sent["photo_blob"]) // the phone's file path never leaves the phone
        assertEquals("الفرامل", sent.str("title"))
        assertEquals("acknowledged", store.getOp("i1")!!.state)
        engine.fetchRef(OWNER)
        assertNull(store.getOp("i1"))
    }

    @Test fun truckIssuePhotoMustBeTheOwners() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "truck_issue", buildJsonObject {
            put("id", "i2"); put("truck_id", "t1"); put("title", "الإطار"); put("severity", "normal")
            put("photo_path", "$OTHER/x.jpg"); put("photo_blob", "/data/x.jpg"); put("created_by", OWNER); put("created_at", "2026-11-14T09:00:00.000Z")
        })
        engine.flushOutbox(OWNER)
        assertEquals("blocked", store.getOp("i2")!!.state)
        assertTrue(server.uploads.isEmpty())
    }

    @Test fun localCopyIsKeptUntilTheSnapshotIncludesIt() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "sale", sale("s1"))
        engine.flushOutbox(OWNER)
        engine.fetchRef(OWNER)
        assertEquals("acknowledged", store.getOp("s1")!!.state) // uploaded, not yet in the snapshot
        server.included = listOf("s1")
        val ref = engine.fetchRef(OWNER)
        assertNull(store.getOp("s1"))
        assertNotNull(store.cacheObject(OWNER, "operation:s1")) // archived, not discarded
        assertEquals(OWNER, ref.str("ownerId"))
        assertEquals(1, ref.rows("salesToday").size)
    }

    @Test fun closingRetryIsAcknowledgedWithoutASecondSubmission() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        // The reply was lost: the phone queues the same closing again.
        store.putOp(store.getOp("k1")!!.copy(state = "queued"))
        engine.flushOutbox(OWNER)
        assertEquals(1, server.calls.count { it == "rpc:submit_closing" })
        assertEquals("acknowledged", store.getOp("k1")!!.state)
    }

    @Test fun closingForAClosedCampaignNeedsReviewAndCanBeSetAside() = runTest {
        openTrip(server, status = "closed")
        store.enqueue(OWNER, "closing", closing("k1"))
        val result = engine.flushOutbox(OWNER)
        assertEquals(1, result.failed)
        assertEquals("review", store.getOp("k1")!!.state)
        assertFalse(server.calls.contains("rpc:submit_closing"))
        store.setAside(OWNER, "k1")
        assertEquals("set_aside", store.getOp("k1")!!.state)
        engine.flushOutbox(OWNER)
        assertEquals("set_aside", store.getOp("k1")!!.state) // never uploaded, never deleted
    }

    @Test fun photosUploadUnderTheOwnersFolderOnly() = runTest {
        openTrip(server)
        val expense = buildJsonObject {
            put("id", "e1"); put("trip_id", TRIP); put("category", "وقود"); put("amount", 3000)
            put("receipt_path", "$OWNER/exp-e1.jpg"); put("receipt_blob", "/data/photo.jpg"); put("created_by", OWNER)
        }
        store.enqueue(OWNER, "expense", expense)
        engine.flushOutbox(OWNER)
        assertEquals(listOf("$OWNER/exp-e1.jpg"), server.uploads)
        assertNull(server.table("expenses").single()["receipt_blob"]) // the local path never leaves the phone
        val foreign = buildJsonObject {
            put("id", "e2"); put("trip_id", TRIP); put("category", "أخرى"); put("amount", 10)
            put("receipt_path", "$OTHER/exp-e2.jpg"); put("receipt_blob", "/data/photo.jpg"); put("created_by", OWNER)
        }
        store.enqueue(OWNER, "expense", foreign)
        engine.flushOutbox(OWNER)
        assertEquals("blocked", store.getOp("e2")!!.state)
        assertEquals(1, server.uploads.size)
    }

    @Test fun accountSwitchStopsUploadsAndRefresh() = runTest {
        openTrip(server)
        store.enqueue(OWNER, "sale", sale("s1"))
        server.user = OTHER
        engine.flushOutbox(OWNER)
        assertTrue(server.calls.isEmpty())
        assertEquals("blocked", store.getOp("s1")!!.state)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { engine.fetchRef(OWNER) } }
        assertNull(store.cacheObject(OWNER, "ref"))
    }

    @Test fun switchDuringRefreshNeverWritesTheCache() = runTest {
        openTrip(server)
        server.onSelect = { server.user = OTHER } // the account changes while reading
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { engine.fetchRef(OWNER) } }
        assertNull(store.cacheObject(OWNER, "ref"))
    }

    @Test fun screensCombineSnapshotAndWaitingRecords() = runTest {
        openTrip(server)
        val ref = engine.fetchRef(OWNER)
        store.enqueue(OWNER, "sale", sale("s1", qty = 5, credit = 1000))
        store.enqueue(OWNER, "payment", buildJsonObject { put("id", "p1"); put("customer_id", "c1"); put("trip_id", TRIP); put("amount", 700); put("created_by", OWNER) })
        val snap = Snapshot(ref, store.outboxFor(OWNER), OWNER)
        assertEquals(85.0, snap.stockOf("v1"), 0.0) // 100 loaded − 10 sold − 5 waiting
        assertEquals(2300.0, snap.balanceOf("c1"), 0.0) // 2000 + 1000 credit − 700 collected
        assertEquals(2500.0, snap.day.totalSales, 0.0)
        assertEquals(2200.0, snap.day.expected, 0.0) // 1500 cash + 700 collected
        assertTrue(snap.ownOpenTrip(OWNER))
        // Another account's snapshot or records are never shown.
        val foreign = Snapshot(ref, store.outboxFor(OWNER), OTHER)
        assertNull(foreign.ref)
        assertTrue(foreign.pending.isEmpty())
    }

    // ---- boxes and customer locations -------------------------------------------------

    private fun boxesLive() {
        server.boxSupport = true
        server.table("pureice_boxes").addAll(listOf(boxRow(1), boxRow(2)))
        server.table("pureice_box_variants").add(buildJsonObject { put("variant_id", "v1") })
        server.table("customers").add(customer("c1").with("active" to JsonPrimitive(true)))
        openTrip(server)
    }

    @Test fun boxesAndLocationUploadAfterTheirInvoice() = runTest {
        boxesLive()
        store.enqueue(OWNER, "sale", sale("s1"))
        store.enqueue(OWNER, "box_move", boxMove("m1", "deliver", "TM-ICE-000001", saleId = "s1"))
        store.enqueue(OWNER, "customer_location", location("l1", "s1"))
        assertEquals(SyncEngine.FlushResult(3, 0), engine.flushOutbox(OWNER))
        assertEquals(listOf("rpc:save_invoice", "rpc:pureice_box_move", "rpc:pureice_set_customer_location"), server.calls)
        server.included = listOf("s1")
        val ref = engine.fetchRef(OWNER)
        // Cleared only because the server holds their ids; the fetched rows show the effect.
        assertTrue(store.outboxFor(OWNER).isEmpty())
        assertEquals(true, ref.bool("boxSupport"))
        assertEquals("customer", ref.rows("boxes").first { it.str("serial") == "TM-ICE-000001" }.str("status"))
        assertEquals(listOf("v1"), ref.strings("boxVariants"))
        assertEquals(14.53, ref.rows("customers").first().num("lat"), 0.0)
    }

    @Test fun failedInvoiceHoldsItsLocationButNotItsBoxes() = runTest {
        boxesLive()
        server.failInvoice = "المخزون غير كاف"
        store.enqueue(OWNER, "sale", sale("s1"))
        store.enqueue(OWNER, "box_move", boxMove("m1", "deliver", "TM-ICE-000001", saleId = "s1"))
        store.enqueue(OWNER, "customer_location", location("l1", "s1"))
        engine.flushOutbox(OWNER)
        assertEquals("blocked", store.getOp("s1")!!.state)
        assertEquals("acknowledged", store.getOp("m1")!!.state) // the box is physically at the customer
        assertEquals("blocked", store.getOp("l1")!!.state)
        assertFalse("rpc:pureice_set_customer_location" in server.calls)
    }

    @Test fun boxesAndLocationsNeverHoldAClosingBack() = runTest {
        boxesLive()
        server.failBoxMove = true
        server.failLocation = true
        store.enqueue(OWNER, "box_move", boxMove("m1", "collect", "TM-ICE-000009"))
        store.enqueue(OWNER, "customer_location", location("l1", "s0"))
        store.enqueue(OWNER, "closing", closing("k1"))
        engine.flushOutbox(OWNER)
        assertEquals("blocked", store.getOp("m1")!!.state)
        assertEquals("blocked", store.getOp("l1")!!.state)
        assertEquals("acknowledged", store.getOp("k1")!!.state)
        assertTrue("rpc:submit_closing" in server.calls)
        // A record the server keeps refusing can be parked; it is kept, never uploaded again.
        store.setAside(OWNER, "m1")
        server.failBoxMove = false
        engine.flushOutbox(OWNER)
        assertEquals("set_aside", store.getOp("m1")!!.state)
    }

    @Test fun sideRecordsStayUntilTheServerHoldsTheirIds() = runTest {
        boxesLive()
        store.enqueue(OWNER, "box_move", boxMove("m1", "deliver", "TM-ICE-000001"))
        engine.flushOutbox(OWNER)
        server.table("pureice_box_events").clear() // e.g. a lost response followed by a rollback
        engine.fetchRef(OWNER)
        assertEquals("acknowledged", store.getOp("m1")!!.state)
        server.table("pureice_box_events").add(buildJsonObject { put("id", "m1") })
        engine.fetchRef(OWNER)
        assertNull(store.getOp("m1"))
    }

    @Test fun unknownBoxSupportKeepsWhatThePhoneHas() = runTest {
        boxesLive()
        assertEquals(2, engine.fetchRef(OWNER).rows("boxes").size)
        server.boxSupport = null
        val ref = engine.fetchRef(OWNER)
        assertEquals(true, ref.bool("boxSupport"))
        assertEquals(2, ref.rows("boxes").size)
        server.boxSupport = false
        assertEquals(false, engine.fetchRef(OWNER).bool("boxSupport"))
    }

    @Test fun waitingBoxMovesAndLocationsShowAtOnce() {
        val ref = buildJsonObject {
            put("ownerId", OWNER); put("boxSupport", true)
            put("boxes", buildJsonArray { add(boxRow(1)); add(boxRow(2)) })
            put("boxVariants", buildJsonArray { add(JsonPrimitive("v1")) })
            put("customers", buildJsonArray { add(customer("c1")) })
        }
        store.enqueue(OWNER, "box_move", boxMove("m1", "deliver", "TM-ICE-000001"))
        store.enqueue(OWNER, "customer_location", location("l1", "s1"))
        val snap = Snapshot(ref, store.outboxFor(OWNER), OWNER)
        assertEquals(listOf("TM-ICE-000001"), snap.boxesWith("c1").map { it.str("serial") })
        assertEquals(listOf("TM-ICE-000002"), snap.boxesOn("t1").map { it.str("serial") })
        assertEquals(14.53, snap.customer("c1")!!.num("lat"), 0.0)
        assertTrue(snap.isBoxVariant("v1"))
        assertFalse(snap.isBoxVariant("v2"))
        // Another account's snapshot never shows them.
        val foreign = Snapshot(ref, store.outboxFor(OWNER), OTHER)
        assertTrue(foreign.boxes.isEmpty())
        assertFalse(foreign.boxSupport)
        assertTrue(foreign.customers.isEmpty())
    }

    @Test fun customerCreatedOnThePhoneIsUsableBeforeItUploads() {
        val ref = buildJsonObject { put("ownerId", OWNER); put("customers", buildJsonArray { add(customer("c1")) }) }
        store.enqueue(OWNER, "customer", customer("c9"))
        val snap = Snapshot(ref, store.outboxFor(OWNER), OWNER)
        assertEquals(listOf("c1", "c9"), snap.customers.map { it.str("id") })
        assertNotNull(snap.customer("c9"))
    }
}
