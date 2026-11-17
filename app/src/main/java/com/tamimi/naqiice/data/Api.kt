package com.tamimi.naqiice.data

import kotlin.coroutines.cancellation.CancellationException
import android.util.Base64
import com.tamimi.naqiice.core.JSON
import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.asRows
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Nonsecret release settings — the same project and publishable key the web app ships. */
object Backend {
    const val URL = "https://monijxkifttaokcnyuoa.supabase.co"
    const val KEY = "sb_publishable_G9Lw2cAeP6q71d9_-Rg1OA_TFGVOWRP"
    const val RECEIPT_BUCKET = "pureice-receipts"
    const val MODE = "tamimi"
    /** Hosts the staff-login server function (PIN verification never happens on the phone). */
    const val SITE = "https://naqi-ice-tamimi.lovable.app"
    const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
}

class ApiError(val status: Int, val code: String?, message: String) : Exception(message)

const val CONNECTION_FAILED = "تعذّر الاتصال بالخادم؛ تحقق من الإنترنت وأعد المحاولة. لم يُحذف شيء"
const val ACCOUNT_CHANGED = "تغير الحساب؛ أُلغي تحديث النسخة المحلية"
const val SESSION_EXPIRED = "انتهت جلسة الدخول أو أُلغيت؛ سجّل الدخول من جديد ثم أعد المحاولة"

/** Shows connection failures in Arabic; server messages pass through unchanged. */
fun Throwable.userMessage(fallback: String = "تعذّر الحفظ؛ البيانات لم تُحذف"): String = when (this) {
    is IOException -> CONNECTION_FAILED
    else -> message?.takeIf { it.isNotBlank() } ?: fallback
}

data class Session(val userId: String, val email: String?, val accessToken: String, val refreshToken: String, val expiresAt: Long)

/** Durable session storage; implemented with private SharedPreferences on the phone. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * Thin client for the Supabase REST, RPC, Auth and Storage endpoints. Every data call
 * takes the bearer explicitly so a read started for one account can never continue
 * with another account's token.
 */
class Api(private val store: SessionStore, private val http: OkHttpClient = defaultClient()) {
    private val json = "application/json".toMediaType()
    private val refreshLock = Mutex()
    @Volatile private var session: Session? = store.load()
    /** Called when Auth confirms the saved session is revoked; local data is kept. */
    @Volatile var onSessionRevoked: (() -> Unit)? = null
    /** Called whenever the signed-in account changes (sign-in, sign-out). */
    @Volatile var onOwnerChanged: ((String?) -> Unit)? = null

    fun currentSession(): Session? = session
    fun currentUserId(): String? = session?.userId

    // ---- auth -------------------------------------------------------------------------

    /** Adopts tokens issued by the staff-login function after verifying them with Auth. */
    suspend fun setSession(accessToken: String, refreshToken: String): Session {
        val user = authGet("/auth/v1/user", accessToken)
        val id = user.str("id") ?: throw ApiError(401, null, "تعذّر تسجيل الدخول")
        val next = Session(id, user.str("email"), accessToken, refreshToken, jwtExpiry(accessToken))
        session = next
        store.save(next)
        onOwnerChanged?.invoke(id)
        return next
    }

    /** A valid bearer for the current account; refreshes ordinary expiry transparently. */
    suspend fun token(): String {
        val s = session ?: throw ApiError(401, null, SESSION_EXPIRED)
        if (fresh(s)) return s.accessToken
        return refreshLock.withLock {
            val latest = session ?: throw ApiError(401, null, SESSION_EXPIRED)
            if (fresh(latest)) return@withLock latest.accessToken
            val body = buildJsonObject { put("refresh_token", latest.refreshToken) }
            val result = try {
                call(Request.Builder().url("${Backend.URL}/auth/v1/token?grant_type=refresh_token").post(body.toString().toRequestBody(json)), null)
            } catch (e: ApiError) {
                // Only confirmed Auth rejections count as revoked; network/5xx are transient.
                if (e.status == 400 || e.status == 401 || e.status == 403) {
                    if (session?.refreshToken == latest.refreshToken) onSessionRevoked?.invoke()
                    throw ApiError(401, e.code, SESSION_EXPIRED)
                }
                throw e
            }
            val o = parseObject(result)
            val access = o.str("access_token") ?: throw ApiError(401, null, SESSION_EXPIRED)
            val user = o.obj("user")
            if (user?.str("id") != null && user.str("id") != latest.userId) throw ApiError(401, null, ACCOUNT_CHANGED)
            val next = latest.copy(accessToken = access, refreshToken = o.str("refresh_token") ?: latest.refreshToken, expiresAt = jwtExpiry(access))
            // A sign-out or account switch during the refresh must win.
            if (session?.userId != latest.userId) throw ApiError(401, null, ACCOUNT_CHANGED)
            session = next
            store.save(next)
            next.accessToken
        }
    }

    /**
     * The server rejected [bearer] (for example "JWT expired"): if it is still the current
     * token, the next [token] call refreshes it instead of sending it again.
     */
    private fun expire(bearer: String) { rejectedToken = bearer }

    /** Kept apart from [session] so this never races a refresh that is replacing the tokens. */
    @Volatile private var rejectedToken: String? = null

    private fun fresh(s: Session) = s.accessToken != rejectedToken && s.expiresAt - System.currentTimeMillis() > 60_000

    /** Server-validated check that the saved session is still accepted. */
    suspend fun verifyUser(): String? = authGet("/auth/v1/user", token()).str("id")

    suspend fun signOut() { signOutLocally()?.let { revoke(it) } }

    /** Forgets the session on this phone at once; returns it so [revoke] can end it on the server. */
    fun signOutLocally(): Session? {
        val s = session
        session = null
        store.save(null)
        onOwnerChanged?.invoke(null)
        return s
    }

    /**
     * Ends [ended] on the server too. Auth only accepts a logout with a valid access token, so
     * an expired or rejected one is first exchanged with the refresh token; otherwise a phone
     * idle for an hour would sign out locally while its refresh token stayed usable. Best
     * effort: offline or refused, the phone is signed out regardless.
     */
    suspend fun revoke(ended: Session) {
        suspend fun logout(bearer: String) = call(Request.Builder().url("${Backend.URL}/auth/v1/logout").post("{}".toRequestBody(json)), bearer)
        try {
            if (fresh(ended)) {
                try { logout(ended.accessToken); return } catch (e: ApiError) { if (e.status != 401 && e.status != 403) return }
            }
            val body = buildJsonObject { put("refresh_token", ended.refreshToken) }
            val refreshed = parseObject(call(Request.Builder().url("${Backend.URL}/auth/v1/token?grant_type=refresh_token").post(body.toString().toRequestBody(json)), null))
            logout(refreshed.str("access_token") ?: return)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Drops the saved session without contacting Auth (already revoked). */
    fun forgetSession() {
        session = null
        store.save(null)
        onOwnerChanged?.invoke(null)
    }

    private suspend fun authGet(path: String, bearer: String): JsonObject =
        parseObject(call(Request.Builder().url(Backend.URL + path).get(), bearer))

    // ---- REST -------------------------------------------------------------------------

    suspend fun select(table: String, query: String, bearer: String): List<Row> =
        JSON.parseToJsonElement(call(Request.Builder().url("${Backend.URL}/rest/v1/$table?$query").get(), bearer)).asRows()

    suspend fun maybeSingle(table: String, query: String, bearer: String): Row? =
        select(table, "$query&limit=1", bearer).firstOrNull()

    /** Fresh ordered query for each page; never treats API errors as empty data. */
    suspend fun selectAll(table: String, query: String, bearer: String): List<Row> {
        val rows = ArrayList<Row>()
        var offset = 0
        while (true) {
            val page = select(table, "$query&offset=$offset&limit=500", bearer)
            rows.addAll(page)
            if (page.size < 500) return rows
            offset += 500
        }
    }

    suspend fun count(table: String, query: String, bearer: String): Int {
        val request = Request.Builder().url("${Backend.URL}/rest/v1/$table?$query").head().header("Prefer", "count=exact")
        val range = execute(request, bearer) { it.header("Content-Range") }
        return range?.substringAfter('/', "")?.toIntOrNull() ?: 0
    }

    suspend fun insert(table: String, body: JsonElement, bearer: String) {
        call(Request.Builder().url("${Backend.URL}/rest/v1/$table").post(body.toString().toRequestBody(json)).header("Prefer", "return=minimal"), bearer)
    }

    suspend fun insertReturning(table: String, body: JsonElement, bearer: String): Row? =
        JSON.parseToJsonElement(call(Request.Builder().url("${Backend.URL}/rest/v1/$table").post(body.toString().toRequestBody(json)).header("Prefer", "return=representation"), bearer)).asRows().firstOrNull()

    /** UUID-idempotent insert: an existing id is left untouched and reported as success. */
    suspend fun upsertIgnore(table: String, body: JsonElement, bearer: String) {
        call(
            Request.Builder().url("${Backend.URL}/rest/v1/$table?on_conflict=id").post(body.toString().toRequestBody(json))
                .header("Prefer", "resolution=ignore-duplicates,return=minimal"),
            bearer,
        )
    }

    suspend fun upsert(table: String, body: JsonElement, onConflict: String, bearer: String) {
        call(
            Request.Builder().url("${Backend.URL}/rest/v1/$table?on_conflict=$onConflict").post(body.toString().toRequestBody(json))
                .header("Prefer", "resolution=merge-duplicates,return=minimal"),
            bearer,
        )
    }

    suspend fun update(table: String, filter: String, body: JsonElement, bearer: String) {
        call(Request.Builder().url("${Backend.URL}/rest/v1/$table?$filter").patch(body.toString().toRequestBody(json)).header("Prefer", "return=minimal"), bearer)
    }

    suspend fun delete(table: String, filter: String, bearer: String) {
        call(Request.Builder().url("${Backend.URL}/rest/v1/$table?$filter").delete().header("Prefer", "return=minimal"), bearer)
    }

    suspend fun rpc(function: String, args: JsonObject, bearer: String): JsonElement {
        val text = call(Request.Builder().url("${Backend.URL}/rest/v1/rpc/$function").post(args.toString().toRequestBody(json)), bearer)
        return if (text.isBlank()) JsonNull else JSON.parseToJsonElement(text)
    }

    // ---- storage ----------------------------------------------------------------------

    /** Uploads once; an object that already exists counts as uploaded (retry-safe). */
    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String, bearer: String) {
        val body: RequestBody = bytes.toRequestBody(contentType.toMediaType())
        try {
            call(Request.Builder().url("${Backend.URL}/storage/v1/object/$bucket/${encodePath(path)}").post(body).header("x-upsert", "false"), bearer)
        } catch (e: ApiError) {
            if (!Regex("exists|Duplicate", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "") && e.status != 409) throw e
        }
    }

    suspend fun signedUrl(bucket: String, path: String, seconds: Int, bearer: String): String {
        val body = buildJsonObject { put("expiresIn", seconds) }
        val o = parseObject(call(Request.Builder().url("${Backend.URL}/storage/v1/object/sign/$bucket/${encodePath(path)}").post(body.toString().toRequestBody(json)), bearer))
        val signed = o.str("signedURL") ?: o.str("signedUrl") ?: throw ApiError(500, null, "تعذّر فتح الصورة")
        return "${Backend.URL}/storage/v1$signed"
    }

    private fun encodePath(path: String) = path.split('/').joinToString("/") { enc(it).replace("+", "%20") }

    // ---- transport --------------------------------------------------------------------

    private suspend fun call(request: Request.Builder, bearer: String?): String = execute(request, bearer) { it.body?.string() ?: "" }

    private suspend fun <T> execute(request: Request.Builder, bearer: String?, read: (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
        request.header("apikey", Backend.KEY)
        if (bearer != null) request.header("Authorization", "Bearer $bearer")
        http.newCall(request.build()).execute().use { response ->
            if (response.code == 401 && bearer != null) expire(bearer)
            if (!response.isSuccessful) throw toError(response.code, response.body?.string() ?: "")
            read(response)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()

        fun toError(status: Int, body: String): ApiError {
            val o = runCatching { parseObject(body) }.getOrNull()
            val code = o?.str("code") ?: o?.str("error_code") ?: o?.str("error")
            val message = o?.str("message") ?: o?.str("msg") ?: o?.str("error_description") ?: body.take(300).ifBlank { "HTTP $status" }
            return ApiError(status, code, message)
        }

        /**
         * When to refresh (epoch millis on this phone's clock), measured from the moment the
         * token was received. The token's lifetime (exp - iat) is used rather than exp itself,
         * so a phone clock that is off does not make every call fail or every call refresh.
         * An unreadable token is treated as expired.
         */
        fun jwtExpiry(token: String, now: Long = System.currentTimeMillis()): Long = runCatching {
            val payload = token.split('.')[1]
            val claims = parseObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            localExpiry((claims["exp"] as JsonPrimitive).content.toLong(), (claims["iat"] as? JsonPrimitive)?.content?.toLongOrNull(), now)
        }.getOrDefault(0L)

        /** [exp] and [iat] are JWT seconds; without a usable iat the server's exp is used as is. */
        fun localExpiry(exp: Long, iat: Long?, now: Long): Long =
            if (iat != null && exp > iat) now + (exp - iat) * 1000 else exp * 1000
    }
}
