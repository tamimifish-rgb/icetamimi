package com.tamimi.naqiice.data

import com.tamimi.naqiice.core.JSON
import com.tamimi.naqiice.core.normalizeUsername
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * Username + PIN sign-in. The PIN is verified by the hosted `staffLogin` server function
 * (the keyed PIN derivation and login throttling live only on that server); this client
 * speaks the same wire protocol as the web app and receives Auth tokens on success.
 */
object StaffLogin {
    /** Build-stable id: sha256 of "<source file>--<export>_createServerFn_handler". */
    val FUNCTION_ID: String = sha256("src/lib/staff.functions.ts--staffLogin_createServerFn_handler")

    const val LOGIN_FAILED = "بيانات الدخول غير صحيحة أو المحاولات كثيرة؛ حاول لاحقاً"

    data class Tokens(val accessToken: String, val refreshToken: String)

    suspend fun login(http: OkHttpClient, username: String, credential: String): Tokens = withContext(Dispatchers.IO) {
        val request = request(FUNCTION_ID, encodeRequest(normalizeUsername(username), credential), null)
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            parseResponse(response.code, text)
        }
    }

    /** The server-function call shared by every hosted function (see ServerFn). */
    internal fun request(functionId: String, body: JsonObject, bearer: String?): Request = Request.Builder()
        .url("${Backend.SITE}/_serverFn/$functionId")
        .post(envelope(body).toString().toRequestBody("application/json".toMediaType()))
        .header("x-tsr-serverFn", "true")
        .header("accept", "application/x-tss-framed, application/x-ndjson, application/json")
        .header("x-pureice-backend", Backend.MODE)
        // The function only accepts same-origin callers.
        .header("Origin", Backend.SITE)
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .build()

    /**
     * Requests travel in the serializer's document form `{ t: <nodes>, f: <features>, m: [] }`;
     * replies come back as bare nodes. Sending bare nodes makes the server see no input.
     */
    internal fun envelope(nodes: JsonObject): JsonObject = buildJsonObject {
        put("t", nodes); put("f", 127); put("m", buildJsonArray { })
    }

    /** The decoded `{ result, error, context }` reply, or null when it is unreadable. */
    internal fun decodeReply(text: String): Any? {
        // A framed response carries one JSON document per line; the first is the result.
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val start = first.indexOf('{')
        val root = runCatching { JSON.parseToJsonElement(if (start > 0) first.substring(start) else first) as JsonObject }.getOrNull()
        return root?.let { decode(it) }
    }

    fun parseResponse(status: Int, text: String): Tokens {
        val decoded = decodeReply(text)
        val error = (decoded as? Map<*, *>)?.get("error") ?: (decoded as? ServerError)
        if (error is ServerError) throw ApiError(status, null, error.message.ifBlank { LOGIN_FAILED })
        val result = (decoded as? Map<*, *>)?.get("result") as? Map<*, *>
        val access = result?.get("access_token") as? String
        val refresh = result?.get("refresh_token") as? String
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
            throw ApiError(status, null, if (status in 200..299 || status == 500) LOGIN_FAILED else "تعذّر الاتصال بخدمة الدخول ($status)")
        }
        return Tokens(access, refresh)
    }

    // ---- wire format (seroval cross-JSON nodes) ---------------------------------------

    class ServerError(val message: String)

    /** `{ data: { username, credential } }` as serialized nodes. */
    fun encodeRequest(username: String, credential: String): JsonObject =
        objectNode(0, listOf("data" to objectNode(1, listOf("username" to stringNode(username), "credential" to stringNode(credential)))))

    internal fun stringNode(value: String) = buildJsonObject { put("t", 1); put("s", escape(value)) }

    internal fun objectNode(index: Int, entries: List<Pair<String, JsonElement>>) = buildJsonObject {
        put("t", 10); put("i", index)
        put("p", buildJsonObject {
            put("k", buildJsonArray { entries.forEach { add(JsonPrimitive(it.first)) } })
            put("v", buildJsonArray { entries.forEach { add(it.second) } })
        })
        put("o", 0)
    }

    /** Decodes the node kinds a login reply can contain; anything else becomes null. */
    fun decode(node: JsonObject): Any? = when ((node["t"] as? JsonPrimitive)?.intOrNull) {
        0 -> (node["s"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        1 -> unescape(node.str("s") ?: "")
        2 -> when ((node["s"] as? JsonPrimitive)?.intOrNull) { 2 -> true; 3 -> false; else -> null }
        9 -> node.rows("a").map { decode(it) }
        10, 11 -> {
            val p = node.obj("p")
            val keys = p?.strings("k") ?: emptyList()
            val values = p?.rows("v") ?: emptyList()
            keys.mapIndexed { i, k -> unescape(k) to values.getOrNull(i)?.let { decode(it) } }.toMap()
        }
        13, 14 -> ServerError(unescape(node.str("m") ?: ""))
        else -> null
    }

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\b' -> sb.append("\\b")
            '\t' -> sb.append("\\t")
            '\u000C' -> sb.append("\\f")
            '<' -> sb.append("\\x3C")
            ' ' -> sb.append("\\u2028")
            ' ' -> sb.append("\\u2029")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\' || i + 1 >= value.length) { sb.append(ch); i++; continue }
            when (val next = value[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'x' -> if (i + 3 < value.length) { sb.append(value.substring(i + 2, i + 4).toInt(16).toChar()); i += 4 } else { sb.append(ch); i++ }
                'u' -> if (i + 5 < value.length) { sb.append(value.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 } else { sb.append(ch); i++ }
                else -> { sb.append(next); i += 2 }
            }
        }
        return sb.toString()
    }

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
