package com.tamimi.naqiice

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.MemoryStore
import com.tamimi.naqiice.data.Remote
import com.tamimi.naqiice.data.Snapshot
import com.tamimi.naqiice.data.SyncEngine
import com.tamimi.naqiice.data.cacheObject
import com.tamimi.naqiice.data.enqueue
import com.tamimi.naqiice.data.setClosingAside
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
    var failCustomerInsert = false
    var included: List<String> = emptyList()
    var onSelect: (() -> Unit)? = null

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
                put("includedIds", JsonArray(included.map { JsonPrimitive(it) }))
                put("balances", buildJsonArray { add(buildJsonObject { put("customer_id", "c1"); put("balance", 2000) }) })
                put("stock", buildJsonArray { add(buildJsonObject { put("variant_id", "v1"); put("loaded", 100); put("sold", 10); put("returned", 0); put("damaged", 0) }) })
                put("sales", JsonArray(table("sales"))); put("payments", JsonArray(emptyList())); put("expenses", JsonArray(emptyList()))
                put("closing", JsonNull)
            }
            "pureice_deposit_support" -> throw ApiError(404, "PGRST202", "missing")
        }
        throw ApiError(404, "PGRST202", "missing")
    }

    override suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String, bearer: String) { uploads.add(path) }
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

private fun customer(id: String) = buildJsonObject {
    put("id", id); put("name", "بقالة النور"); put("phone", JsonNull); put("region_id", "r1"); put("location_notes", "")
    put("lat", JsonNull); put("lng", JsonNull); put("truck_id", "t1"); put("created_by", OWNER)
}

private fun openTrip(server: FakeServer, status: String = "open") {
    server.table("trips").add(buildJsonObject { put("id", TRIP); put("distributor_id", OWNER); put("status", status); put("truck_id", "t1") })
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

    @Test fun saleWaitsForItsNewCustomer() = runTest {
        openTrip(server)
        server.failCustomerInsert = true
        store.enqueue(OWNER, "customer", customer("new1"))
        store.enqueue(OWNER, "sale", sale("s1", customer = "new1"))
        engine.flushOutbox(OWNER)
        assertFalse(server.calls.contains("rpc:save_invoice"))
        assertTrue(store.outboxFor(OWNER).all { it.state == "blocked" })
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
        store.setClosingAside(OWNER, "k1")
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
}
