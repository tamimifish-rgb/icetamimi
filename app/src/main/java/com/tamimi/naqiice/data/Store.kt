package com.tamimi.naqiice.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.str
import kotlinx.serialization.json.JsonObject

/**
 * queued       saved on the phone, not uploaded yet
 * blocked      an upload attempt failed; kept and retried
 * acknowledged uploaded; waiting for the server snapshot to include it
 * reconciled   a redundant closing that matches an accepted one; waiting to be archived
 * review       a closing for an already closed campaign that needs a person
 * set_aside    deliberately parked by the user; never uploaded, never deleted
 */
data class OutboxOp(
    val id: String,
    val ownerId: String,
    val state: String,
    val kind: String,
    val payload: JsonObject,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val reconciliation: JsonObject? = null,
)

/** Durable phone storage: the outbox of unsent work and an owner-keyed cache. */
interface LocalStore {
    fun outboxFor(ownerId: String): List<OutboxOp>
    fun getOp(id: String): OutboxOp?
    fun addOp(op: OutboxOp)
    fun putOp(op: OutboxOp)
    fun deleteOp(id: String)
    fun getCache(key: String): String?
    fun putCache(key: String, value: String)
    fun <T> transaction(block: () -> T): T
}

/** Saves a record for upload. The same id with the same content is a harmless retry. */
fun LocalStore.enqueue(ownerId: String, kind: String, payload: JsonObject, id: String = payload.str("id") ?: "") {
    if (ownerId.isEmpty() || id.isEmpty() || payload.str("created_by") != ownerId) throw IllegalStateException("مالك السجل غير صحيح")
    transaction {
        val existing = getOp(id)
        if (existing != null) {
            if (existing.ownerId != ownerId) throw IllegalStateException("السجل يخص حساباً آخر")
            if (existing.kind != kind || existing.payload != payload) throw IllegalStateException("تعارض معرف السجل؛ النسخة السابقة محفوظة")
        } else {
            addOp(OutboxOp(id, ownerId, "queued", kind, payload, System.currentTimeMillis()))
        }
    }
}

/** A closing under review, or a box/location record the server keeps refusing, can be parked. */
fun OutboxOp.canSetAside(): Boolean = (kind == "closing" && state == "review") ||
    ((kind in SIDE_KINDS || kind == "closing_handover" || kind == "truck_issue") && state == "blocked")

fun LocalStore.setAside(ownerId: String, id: String) = transaction {
    val op = getOp(id)
    if (op == null || op.ownerId != ownerId || !op.canSetAside()) throw IllegalStateException("الطلب غير متاح للتنحية")
    putOp(op.copy(state = "set_aside"))
}

fun LocalStore.cacheObject(ownerId: String, key: String): JsonObject? =
    getCache("$ownerId:$key")?.let { runCatching { parseObject(it) }.getOrNull() }

fun LocalStore.setCacheObject(ownerId: String, key: String, value: JsonObject) = putCache("$ownerId:$key", value.toString())

class MemoryStore : LocalStore {
    private val ops = LinkedHashMap<String, OutboxOp>()
    private val cache = HashMap<String, String>()
    @Synchronized override fun outboxFor(ownerId: String) = ops.values.filter { it.ownerId == ownerId }.sortedBy { it.createdAt }
    @Synchronized override fun getOp(id: String) = ops[id]
    @Synchronized override fun addOp(op: OutboxOp) { check(!ops.containsKey(op.id)); ops[op.id] = op }
    @Synchronized override fun putOp(op: OutboxOp) { ops[op.id] = op }
    @Synchronized override fun deleteOp(id: String) { ops.remove(id) }
    @Synchronized override fun getCache(key: String) = cache[key]
    @Synchronized override fun putCache(key: String, value: String) { cache[key] = value }
    @Synchronized override fun <T> transaction(block: () -> T): T {
        val opsBefore = LinkedHashMap(ops)
        val cacheBefore = HashMap(cache)
        try { return block() } catch (e: Throwable) {
            ops.clear(); ops.putAll(opsBefore); cache.clear(); cache.putAll(cacheBefore); throw e
        }
    }
}

class SqliteStore(context: Context) : SQLiteOpenHelper(context, "pure-ice.db", null, 1), LocalStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE outbox (id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, state TEXT NOT NULL, kind TEXT NOT NULL, " +
                "payload TEXT NOT NULL, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, reconciliation TEXT)",
        )
        db.execSQL("CREATE INDEX outbox_owner ON outbox (owner_id, created_at)")
        db.execSQL("CREATE TABLE cache (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun read(where: String, args: Array<String>): List<OutboxOp> {
        val out = ArrayList<OutboxOp>()
        readableDatabase.rawQuery(
            "SELECT id, owner_id, state, kind, payload, created_at, attempts, last_error, reconciliation FROM outbox WHERE $where ORDER BY created_at, rowid", args,
        ).use { c ->
            while (c.moveToNext()) out.add(
                OutboxOp(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), parseObject(c.getString(4)), c.getLong(5), c.getInt(6),
                    if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else parseObject(c.getString(8)),
                ),
            )
        }
        return out
    }

    private fun values(op: OutboxOp) = ContentValues().apply {
        put("id", op.id); put("owner_id", op.ownerId); put("state", op.state); put("kind", op.kind)
        put("payload", op.payload.toString()); put("created_at", op.createdAt); put("attempts", op.attempts)
        if (op.lastError == null) putNull("last_error") else put("last_error", op.lastError)
        if (op.reconciliation == null) putNull("reconciliation") else put("reconciliation", op.reconciliation.toString())
    }

    override fun outboxFor(ownerId: String) = read("owner_id = ?", arrayOf(ownerId))
    override fun getOp(id: String) = read("id = ?", arrayOf(id)).firstOrNull()
    override fun addOp(op: OutboxOp) { writableDatabase.insertOrThrow("outbox", null, values(op)) }
    override fun putOp(op: OutboxOp) { writableDatabase.insertWithOnConflict("outbox", null, values(op), SQLiteDatabase.CONFLICT_REPLACE) }
    override fun deleteOp(id: String) { writableDatabase.delete("outbox", "id = ?", arrayOf(id)) }

    override fun getCache(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM cache WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun putCache(key: String, value: String) {
        val v = ContentValues().apply { put("key", key); put("value", value); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("cache", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
}
