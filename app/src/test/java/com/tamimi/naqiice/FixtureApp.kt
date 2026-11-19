package com.tamimi.naqiice

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.asRows
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.with
import com.tamimi.naqiice.data.Api
import com.tamimi.naqiice.data.Repo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

/**
 * The app with its network replaced by `tools/web-reference/fixture.json` — the same sample
 * data the web reference screenshots use — and a signed-in session. Mirrors the routing in
 * `tools/web-reference/shoot.js`, so both sides answer every request identically.
 */
class FixtureApp : PureIceApp() {
    override fun createRepo(): Repo {
        val fixture = Fixture.load()
        getSharedPreferences("pureice-tamimi-auth", MODE_PRIVATE).edit().putString("session", buildJsonObject {
            put("userId", fixture.str("owner")); put("email", "x@staff.pureice.internal"); put("accessToken", "fixture-token")
            put("refreshToken", "r"); put("expiresAt", System.currentTimeMillis() + 86_400_000L)
        }.toString()).commit()
        return Repo(this, Api.defaultClient().newBuilder().addInterceptor(Fixture(fixture)).build())
    }
}

class Fixture(private val base: JsonObject) : Interceptor {
    companion object {
        /** distributor, supervisor or admin; set before the activity starts. */
        @Volatile var role = "distributor"
        /** Returnable boxes are a native-only feature: off unless a test turns the server feature on. */
        @Volatile var boxes = false
        /** Three more customers, all with a saved location, for the distributor's customer map. */
        @Volatile var customerMap = false
        /** The closing-handover server feature (cash / deposit / both at closing). */
        @Volatile var handover = false
        /** The staff account: shortage decisions and salary deductions. */
        @Volatile var staffLedger = false
        /** Every write the app sent: "POST /rest/v1/rpc/pureice_box_move {…}". */
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun merge(into: JsonObject, extra: JsonObject): JsonObject {
            val tables = into.obj("tables")!!.toMutableMap()
            extra.obj("tables")?.forEach { (name, rows) -> tables[name] = JsonArray((tables[name].asRows()) + rows.asRows()) }
            return into.with("tables" to JsonObject(tables), "rpc" to JsonObject((into.obj("rpc") ?: JsonObject(emptyMap())) + (extra.obj("rpc") ?: JsonObject(emptyMap()))))
        }
        /** fixture.json plus every fixture.<area>.json: table rows are appended, rpc results merged. */
        fun load(): JsonObject {
            val files = File("../tools/web-reference").listFiles { f -> f.name.startsWith("fixture") && f.name.endsWith(".json") }!!.sortedBy { it.name }
            var merged = parseObject(files.first { it.name == "fixture.json" }.readText())
            for (file in files.filter { it.name != "fixture.json" }) merged = merge(merged, parseObject(file.readText()))
            return merged
        }
    }

    private val withBoxes by lazy { merge(base, parseObject(File("src/test/fixtures/boxes.json").readText())) }
    private val withCustomerMap by lazy { merge(base, parseObject(File("src/test/fixtures/customer-map.json").readText())) }
    private val withHandover by lazy { merge(base, parseObject(File("src/test/fixtures/closing-handover.json").readText())) }
    private val withStaffLedger by lazy { merge(base, parseObject(File("src/test/fixtures/staff-ledger.json").readText())) }
    private val data: JsonObject get() = if (boxes) withBoxes else if (customerMap) withCustomerMap else if (handover) withHandover else if (staffLedger) withStaffLedger else base

    private fun tables(): Map<String, List<Row>> {
        val owner = data.str("owner")
        val all = data.obj("tables")!!.mapValues { it.value.asRows() }.toMutableMap()
        all["user_roles"] = listOf(buildJsonObject { put("user_id", owner); put("role", role) })
        all["profiles"] = (all["profiles"] ?: emptyList()).map { if (it.str("id") == owner && role != "distributor") it.with("full_name" to JsonPrimitive("المدير")) else it }
        return all
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        if (request.method != "GET" && request.method != "HEAD") {
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            writes.add("${request.method} $path $body")
        }
        fun reply(body: JsonElement, status: Int = 200, range: String? = null): Response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .apply { if (range != null) header("Content-Range", range) }
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        fun missing(code: String) = reply(buildJsonObject { put("code", code); put("message", "missing") }, 404)

        if (path == "/auth/v1/user") return reply(buildJsonObject { put("id", data.str("owner")); put("email", "x@staff.pureice.internal") })
        if (path.startsWith("/auth/")) return reply(JsonObject(emptyMap()))
        if (path.startsWith("/rest/v1/rpc/")) {
            val function = path.substringAfterLast('/')
            if (function == "accounting_snapshot") return reply(data.obj("snapshot")!!)
            if (function == "has_role" || function == "is_manager") return reply(JsonPrimitive(role != "distributor"))
            data.obj("rpc")?.get(function)?.let { return reply(it) }
            return missing("PGRST202")
        }
        if (path.startsWith("/rest/v1/")) {
            val table = path.substringAfterLast('/')
            val known = tables()
            if (table !in known && table.startsWith("pureice_")) return missing("PGRST205")
            var rows = known[table] ?: emptyList()
            for (name in url.queryParameterNames) {
                val value = url.queryParameter(name) ?: continue
                fun cell(row: Row) = (row[name] as? JsonPrimitive)?.content
                when {
                    value.startsWith("eq.") -> rows = rows.filter { name !in it || cell(it) == value.removePrefix("eq.") }
                    value.startsWith("neq.") -> rows = rows.filter { cell(it) != value.removePrefix("neq.") }
                    value == "is.null" -> rows = rows.filter { it[name] == null || it[name] is JsonNull }
                    value.startsWith("in.(") -> {
                        val set = value.removePrefix("in.(").removeSuffix(")").split(',').map { it.trim('"') }
                        rows = rows.filter { name !in it || cell(it) in set }
                    }
                }
            }
            if (request.method != "GET" && request.method != "HEAD") return reply(JsonArray(emptyList()), 201)
            val total = rows.size
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            rows = rows.drop(offset).let { page -> url.queryParameter("limit")?.toIntOrNull()?.let { page.take(it) } ?: page }
            return reply(JsonArray(rows), range = "0-${maxOf(0, rows.size - 1)}/$total")
        }
        return reply(JsonObject(emptyMap()))
    }
}
