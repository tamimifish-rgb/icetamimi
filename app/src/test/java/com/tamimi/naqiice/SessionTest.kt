package com.tamimi.naqiice

import com.tamimi.naqiice.data.Api
import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.Session
import com.tamimi.naqiice.data.SessionStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/** Token handling against a fake Auth/REST server (Robolectric supplies android.util.Base64). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTest {
    private val nowSec = System.currentTimeMillis() / 1000

    /** A token issued [skewSec] away from this phone's clock, valid for [lifeSec]. */
    private fun jwt(name: String, skewSec: Long, lifeSec: Long = 3600): String {
        val iat = nowSec + skewSec
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"u1","iat":$iat,"exp":${iat + lifeSec},"n":"$name"}""".toByteArray())
        return "$header.$payload.sig"
    }

    private class Server(val nextAccess: () -> String) {
        val refreshes = ArrayList<String>()
        val rejected = HashSet<String>()
        val logouts = ArrayList<String>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val bearer = request.header("Authorization")?.removePrefix("Bearer ")
            val path = request.url.encodedPath
            when {
                path == "/auth/v1/user" -> reply(request, 200, """{"id":"u1","email":"u1@x"}""")
                path == "/auth/v1/token" -> {
                    val token = nextAccess()
                    refreshes.add(token)
                    reply(request, 200, """{"access_token":"$token","refresh_token":"r${refreshes.size}","user":{"id":"u1"}}""")
                }
                bearer in rejected -> reply(request, 401, """{"code":"PGRST301","message":"JWT expired"}""")
                path == "/auth/v1/logout" -> { logouts.add(bearer!!); reply(request, 200, "{}") }
                else -> reply(request, 200, "[]")
            }
        }.build()

        private fun reply(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private class Memory : SessionStore {
        var saved: Session? = null
        override fun load() = saved
        override fun save(session: Session?) { saved = session }
    }

    @Test fun lifetimeComesFromTheTokenNotThePhoneClock() {
        assertEquals(1_000L + 3_600_000L, Api.localExpiry(exp = 50_000 + 3_600, iat = 50_000, now = 1_000))
        assertEquals(7_000_000L, Api.localExpiry(exp = 7_000, iat = null, now = 1_000)) // no iat: server time as before
    }

    @Test fun phoneClockAheadDoesNotRefreshEveryCall() = runTest {
        var n = 0
        val server = Server { jwt("b${++n}", skewSec = -2 * 86_400) }
        val api = Api(Memory(), server.client)
        // Issued "two days ago" by the phone's clock: the old exp check saw it as long expired.
        val first = jwt("a", skewSec = -2 * 86_400)
        api.setSession(first, "r0")
        repeat(3) { assertEquals(first, api.token()) }
        assertEquals(0, server.refreshes.size)
    }

    @Test fun rejectedTokenIsRefreshedOnTheNextCall() = runTest {
        var n = 0
        val server = Server { jwt("b${++n}", skewSec = 0) }
        val api = Api(Memory(), server.client)
        val first = jwt("a", skewSec = 0)
        api.setSession(first, "r0")
        server.rejected.add(first)
        val error = assertThrows(ApiError::class.java) { kotlinx.coroutines.runBlocking { api.select("trips", "select=*", api.token()) } }
        assertEquals(401, error.status)
        val second = api.token()
        assertEquals(listOf(second), server.refreshes)
        assertEquals(second, api.token()) // refreshed once, then reused
        // A late 401 for the old token does not force another refresh.
        assertThrows(ApiError::class.java) { kotlinx.coroutines.runBlocking { api.select("trips", "select=*", first) } }
        assertEquals(second, api.token())
        assertEquals(1, server.refreshes.size)
    }

    @Test fun signOutAfterExpiryStillEndsTheServerSession() = runTest {
        var n = 0
        val server = Server { jwt("b${++n}", skewSec = 0) }
        val api = Api(Memory(), server.client)
        api.setSession(jwt("a", skewSec = 0, lifeSec = 30), "r0") // inside the refresh margin: treated as expired
        api.signOut()
        assertEquals(1, server.refreshes.size)
        assertEquals(server.refreshes, server.logouts) // logged out with the fresh token
        assertEquals(null, api.currentSession())
    }

    @Test fun signOutWithARejectedTokenRetriesWithAFreshOne() = runTest {
        var n = 0
        val server = Server { jwt("b${++n}", skewSec = 0) }
        val api = Api(Memory(), server.client)
        val first = jwt("a", skewSec = 0)
        api.setSession(first, "r0")
        server.rejected.add(first) // looks valid here, but the server already refuses it
        api.signOut()
        assertEquals(server.refreshes, server.logouts)
        assertEquals(1, server.logouts.size)
    }

    @Test fun signOutWithAValidTokenNeedsNoRefresh() = runTest {
        val server = Server { error("no refresh expected") }
        val api = Api(Memory(), server.client)
        val first = jwt("a", skewSec = 0)
        api.setSession(first, "r0")
        api.signOut()
        assertEquals(listOf(first), server.logouts)
        assertEquals(0, server.refreshes.size)
    }
}
