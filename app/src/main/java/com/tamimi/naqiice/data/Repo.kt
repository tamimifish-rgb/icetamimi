package com.tamimi.naqiice.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.bool
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.parseObject
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class Me(val id: String, val email: String?, val fullName: String, val truckId: String?, val roles: List<String>) {
    val isAdmin get() = "admin" in roles
    val isSupervisor get() = "supervisor" in roles
    val isManager get() = isAdmin || isSupervisor
    val isDistributor get() = "distributor" in roles
    /** admin > supervisor > distributor, as in the web app's navigation. */
    val primaryRole: String? get() = when { isAdmin -> "admin"; isSupervisor -> "supervisor"; isDistributor -> "distributor"; else -> null }
    /** Location is mandatory for staff who are distributors only. */
    val locationRequired get() = isDistributor && !isManager
}

data class Company(val name: String, val address: String, val phone: String, val footerNote: String) {
    companion object { val DEFAULT = Company("مصنع التميمي للثلج المجروش والقوالب", "المنطقة الصناعية — المكلا", "", "") }
}

data class TrackingState(val owner: String, val trip: String, val status: String, val last: String?)

class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("pureice-tamimi-auth", Context.MODE_PRIVATE)
    override fun load(): Session? = prefs.getString("session", null)?.let { text ->
        runCatching {
            val o = parseObject(text)
            Session(o.str("userId")!!, o.str("email"), o.str("accessToken")!!, o.str("refreshToken")!!, o.num("expiresAt").toLong())
        }.getOrNull()
    }
    override fun save(session: Session?) {
        val editor = prefs.edit()
        if (session == null) editor.remove("session") else editor.putString("session", buildJsonObject {
            put("userId", session.userId); put("email", session.email); put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken); put("expiresAt", session.expiresAt)
        }.toString())
        editor.commit()
    }
}

/** App-wide state: the signed-in employee, the cached snapshot, the outbox and sync. */
class Repo(private val context: Context, private val httpClient: okhttp3.OkHttpClient = Api.defaultClient()) {
    val store: LocalStore = SqliteStore(context)
    val api = Api(PrefsSessionStore(context), httpClient)
    val http get() = httpClient
    private val engine = SyncEngine(store, ApiRemote(api), { path -> runCatching { File(path).readBytes() }.getOrNull() })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val me = MutableStateFlow<Me?>(null)
    val loading = MutableStateFlow(true)
    val ref = MutableStateFlow<Row?>(null)
    val pending = MutableStateFlow<List<OutboxOp>>(emptyList())
    val online = MutableStateFlow(true)
    val syncing = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val depositSupport = MutableStateFlow(false)
    val changeSupport = MutableStateFlow(false)
    val company = MutableStateFlow(Company.DEFAULT)
    val tracking = MutableStateFlow<TrackingState?>(null)
    /** "Keep the screen on" switch on the campaign screen (on by default, as on the web). */
    val keepAwake = MutableStateFlow(true)
    /** Shown on the sign-in screen, e.g. after the server revoked the session. */
    val notice = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<Snapshot> = combine(ref, pending, me) { r, p, m -> Snapshot(r, p, m?.id) }
        .stateIn(scope, SharingStarted.Eagerly, Snapshot.EMPTY)

    /** Starts or stops the trip-tracking service; set by the UI layer. */
    var trackingController: ((Repo) -> Unit)? = null

    @Volatile private var generation = 0
    private var busy = false
    private var loop: Job? = null

    init {
        api.onSessionRevoked = {
            scope.launch {
                notice.value = SESSION_EXPIRED
                api.forgetSession()
                applySession()
            }
        }
        watchNetwork()
    }

    fun start() { scope.launch { applySession() } }

    // ---- session ----------------------------------------------------------------------

    suspend fun signIn(username: String, credential: String) {
        val tokens = StaffLogin.login(httpClient, username, credential)
        api.setSession(tokens.accessToken, tokens.refreshToken)
        notice.value = null
        applySession()
    }

    /** Records waiting on the phone stay saved for this account across sign-out. */
    suspend fun signOut() {
        generation++
        me.value = null
        trackingController?.invoke(this)
        api.signOut()
        applySession()
    }

    fun pendingCount(): Int = me.value?.let { store.outboxFor(it.id).size } ?: 0

    private suspend fun applySession() {
        val version = ++generation
        loop?.cancel()
        val session = api.currentSession()
        ref.value = null; pending.value = emptyList(); lastError.value = null
        depositSupport.value = false; changeSupport.value = false; company.value = Company.DEFAULT
        if (session == null) {
            me.value = null; loading.value = false
            trackingController?.invoke(this)
            return
        }
        val next = loadMe(session)
        if (version != generation) return
        me.value = next
        loading.value = false
        withContext(Dispatchers.IO) {
            store.cacheObject(next.id, "ref")?.let { cached -> if (version == generation) ref.value = cached }
            depositSupport.value = store.getCache("${next.id}:deposit-support") == "true"
            changeSupport.value = store.getCache("${next.id}:invoice-change-support") == "true"
            store.cacheObject(next.id, "company")?.let { company.value = it.toCompany() }
        }
        reloadPending()
        trackingController?.invoke(this)
        loop = scope.launch {
            while (isActive) {
                if (online.value) syncNow()
                delay(60_000)
            }
        }
    }

    private suspend fun loadMe(session: Session): Me {
        val key = "${session.userId}:me"
        return try {
            val bearer = api.token()
            val profile = api.maybeSingle("profiles", "select=*&id=eq.${session.userId}", bearer)
            val roles = api.select("user_roles", "select=role&user_id=eq.${session.userId}", bearer).mapNotNull { it.str("role") }
            val loaded = Me(
                session.userId, session.email, profile?.str("full_name")?.takeIf { it.isNotBlank() } ?: session.email ?: "",
                profile?.str("truck_id"), if (profile?.bool("active") == true) roles else emptyList(),
            )
            withContext(Dispatchers.IO) {
                store.putCache(key, buildJsonObject {
                    put("id", loaded.id); put("email", loaded.email); put("full_name", loaded.fullName); put("truck_id", loaded.truckId)
                    put("roles", JsonArray(loaded.roles.map { JsonPrimitive(it) }))
                }.toString())
            }
            loaded
        } catch (e: Exception) {
            // Offline start: the last known profile keeps the distributor working.
            val cached = withContext(Dispatchers.IO) { store.getCache(key) }?.let { runCatching { parseObject(it) }.getOrNull() }
            if (cached != null) Me(session.userId, cached.str("email"), cached.str("full_name") ?: "", cached.str("truck_id"), cached.strings("roles"))
            else Me(session.userId, session.email, "", null, emptyList())
        }
    }

    suspend fun reloadMe() = applySession()

    // ---- outbox + sync ----------------------------------------------------------------

    suspend fun reloadPending() {
        val owner = me.value?.id
        val rows = if (owner != null) withContext(Dispatchers.IO) { store.outboxFor(owner) } else emptyList()
        if (me.value?.id == owner) pending.value = rows
    }

    /** Saves a record on the phone first, then starts an upload attempt. */
    suspend fun enqueue(kind: String, payload: JsonObject) {
        val owner = me.value?.id ?: throw IllegalStateException(SESSION_EXPIRED)
        withContext(Dispatchers.IO) { store.enqueue(owner, kind, payload) }
        reloadPending()
        trackingController?.invoke(this)
        if (kind != "gps") scope.launch { syncNow() }
    }

    suspend fun retry(op: OutboxOp) {
        val owner = me.value?.id ?: return
        if (op.ownerId != owner) return
        withContext(Dispatchers.IO) { store.getOp(op.id)?.let { store.putOp(it.copy(state = "queued", lastError = null)) } }
        syncNow()
    }

    suspend fun setAside(op: OutboxOp) {
        val owner = me.value?.id ?: return
        withContext(Dispatchers.IO) { store.setClosingAside(owner, op.id) }
        reloadPending()
    }

    suspend fun syncNow() {
        val user = me.value ?: return
        if (busy) return
        busy = true
        syncing.value = true
        val version = generation
        try {
            if (online.value) {
                val result = withContext(Dispatchers.IO) { engine.flushOutbox(user.id) }
                val fresh = withContext(Dispatchers.IO) { engine.fetchRef(user.id) { generation == version } }
                if (generation != version) return
                ref.value = fresh
                lastError.value = if (result.failed > 0) "تعذّر رفع بعض السجلات؛ افتح حالة المزامنة للمراجعة" else null
                refreshExtras(user, version)
            }
        } catch (e: Exception) {
            if (generation == version) lastError.value = e.userMessage("تعذّرت المزامنة")
        } finally {
            busy = false
            syncing.value = false
            reloadPending()
            trackingController?.invoke(this)
        }
    }

    /** Optional server features and invoice branding; failures keep the cached values. */
    private suspend fun refreshExtras(user: Me, version: Int) = withContext(Dispatchers.IO) {
        for ((function, key, flow) in listOf(
            Triple("pureice_deposit_support", "deposit-support", depositSupport),
            Triple("pureice_invoice_change_support", "invoice-change-support", changeSupport),
        )) runCatching {
            val supported = engine.featureSupport(user.id, function) ?: return@runCatching
            if (generation != version) return@runCatching
            store.putCache("${user.id}:$key", supported.toString())
            flow.value = supported
        }
        runCatching {
            val row = api.maybeSingle("pureice_company_settings", "select=name,address,phone,footer_note&id=eq.true", api.token())
            if (generation != version) return@runCatching
            val value = row ?: return@runCatching
            store.setCacheObject(user.id, "company", value)
            company.value = value.toCompany()
        }
    }

    private fun Row.toCompany() = Company(
        str("name") ?: Company.DEFAULT.name, str("address") ?: Company.DEFAULT.address, str("phone") ?: "", str("footer_note") ?: "",
    )

    /** A previously uploaded record kept on the phone (e.g. an old campaign's invoice). */
    suspend fun archivedOperation(id: String): Row? {
        val owner = me.value?.id ?: return null
        return withContext(Dispatchers.IO) { store.cacheObject(owner, "operation:$id") }
    }

    // ---- tracking ---------------------------------------------------------------------

    /** Saves one route point for the open campaign; called by the tracking service. */
    fun recordPoint(owner: String, trip: String, truck: String?, lat: Double, lng: Double, recordedAt: String) {
        val payload = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("created_by", owner); put("trip_id", trip)
            put("truck_id", truck?.let { JsonPrimitive(it) } ?: JsonNull)
            put("lat", JsonPrimitive(lat)); put("lng", JsonPrimitive(lng)); put("recorded_at", recordedAt)
        }
        store.enqueue(owner, "gps", payload)
        scope.launch { reloadPending() }
    }

    // ---- connectivity -----------------------------------------------------------------

    private fun watchNetwork() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        fun connected(): Boolean {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        online.value = runCatching { connected() }.getOrDefault(true)
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online.value = true
                    scope.launch { syncNow() }
                }
                override fun onLost(network: Network) { online.value = runCatching { connected() }.getOrDefault(false) }
            })
        }
    }
}
