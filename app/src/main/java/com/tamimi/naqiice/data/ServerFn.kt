package com.tamimi.naqiice.data

import com.tamimi.naqiice.core.normalizeDigits
import com.tamimi.naqiice.core.normalizeUsername
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Caller for the hosted, signed-in server functions in `src/lib/staff.functions.ts`
 * (`createEmployee`, `setStaffPin`). Same wire protocol as [StaffLogin]; the server
 * revalidates the bearer and the admin role on every call.
 */
object ServerFn {
    fun functionId(export: String): String = StaffLogin.sha256("src/lib/staff.functions.ts--${export}_createServerFn_handler")

    suspend fun call(http: OkHttpClient, export: String, data: Map<String, Any?>, bearer: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        http.newCall(StaffLogin.request(functionId(export), encodeRequest(data), bearer)).execute().use { response ->
            parseResponse(response.code, response.body?.string() ?: "")
        }
    }

    /** `{ data: <input> }` as serialized nodes; object indices increase depth-first from 0. */
    fun encodeRequest(data: Map<String, Any?>): JsonObject {
        var next = 0
        fun node(value: Any?): JsonElement = when (value) {
            null -> constant(0)
            true -> constant(2)
            false -> constant(3)
            is String -> StaffLogin.stringNode(value)
            is Map<*, *> -> {
                val index = next++
                StaffLogin.objectNode(index, value.map { (key, inner) -> key as String to node(inner) })
            }
            else -> throw IllegalArgumentException("unsupported server function value")
        }
        return node(mapOf("data" to data)) as JsonObject
    }

    private fun constant(kind: Int) = buildJsonObject { put("t", 2); put("s", kind) }

    /** The function's result object; a thrown server error keeps its Arabic message. */
    fun parseResponse(status: Int, text: String): Map<String, Any?> {
        val decoded = StaffLogin.decodeReply(text)
        val error = (decoded as? Map<*, *>)?.get("error") ?: (decoded as? StaffLogin.ServerError)
        if (error is StaffLogin.ServerError) {
            // The auth middleware reports a revoked session only through this message.
            throw ApiError(if (error.message == SESSION_EXPIRED) 401 else status, null, error.message.ifBlank { failed(status) })
        }
        val result = (decoded as? Map<*, *>)?.get("result") as? Map<*, *>
        if (status !in 200..299 || result == null) throw ApiError(status, null, failed(status))
        return result.entries.associate { it.key.toString() to it.value }
    }

    private fun failed(status: Int) = "تعذّر تنفيذ العملية على الخادم ($status)؛ راجع قائمة الموظفين قبل إعادة المحاولة"
}

const val USERNAME_HINT = "أحرف إنجليزية صغيرة أو أرقام أو - و _ فقط، من ٣ إلى ٦٤ خانة (مثال: ahmed-01)"

/** Mirrors the server/database rules so the admin sees an Arabic reason before submitting. */
fun staffFormError(fullName: String, username: String, pin: String, editing: Boolean): String? {
    if (fullName.isBlank()) return "أدخل اسم الموظف"
    val name = normalizeUsername(username)
    if (Regex("[\\u0600-\\u06FF]").containsMatchIn(name)) return "اسم المستخدم لا يقبل حروفاً عربية؛ $USERNAME_HINT"
    if (!Regex("^[a-z0-9_-]{3,64}$").matches(name)) return "اسم المستخدم غير صالح؛ $USERNAME_HINT"
    val digits = normalizeDigits(pin)
    if ((!editing || digits.isNotEmpty()) && !Regex("^\\d{6}$").matches(digits)) return "رمز الدخول يجب أن يكون ٦ أرقام بالضبط"
    return null
}
