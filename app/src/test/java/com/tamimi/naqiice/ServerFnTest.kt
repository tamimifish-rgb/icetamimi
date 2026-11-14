package com.tamimi.naqiice

import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.SESSION_EXPIRED
import com.tamimi.naqiice.data.ServerFn
import com.tamimi.naqiice.data.staffFormError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFnTest {
    /** The ids the published web bundle calls. */
    @Test fun functionIdsMatchPublishedSite() {
        assertTrue(ServerFn.functionId("setStaffPin").startsWith("16a82f3865de7574"))
        assertTrue(ServerFn.functionId("createEmployee").startsWith("137ba768ac75c976"))
        assertEquals(64, ServerFn.functionId("createEmployee").length)
    }

    @Test fun encodesCreateEmployeeWithoutTruck() {
        val input = linkedMapOf<String, Any?>("full_name" to "أحمد", "username" to "ahmed-01", "pin" to "123456", "role" to "distributor", "truck_id" to null)
        assertEquals(
            """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["full_name","username","pin","role","truck_id"],"v":[{"t":1,"s":"أحمد"},{"t":1,"s":"ahmed-01"},{"t":1,"s":"123456"},{"t":1,"s":"distributor"},{"t":2,"s":0}]},"o":0}]},"o":0}""",
            ServerFn.encodeRequest(input).toString(),
        )
    }

    @Test fun encodesPinResetAndNestedIndices() {
        assertEquals(
            """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["id","pin"],"v":[{"t":1,"s":"u-1"},{"t":1,"s":"654321"}]},"o":0}]},"o":0}""",
            ServerFn.encodeRequest(linkedMapOf("id" to "u-1", "pin" to "654321")).toString(),
        )
        assertEquals(
            """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["a","b","c"],"v":[{"t":10,"i":2,"p":{"k":["x"],"v":[{"t":2,"s":2}]},"o":0},{"t":10,"i":3,"p":{"k":[],"v":[]},"o":0},{"t":2,"s":3}]},"o":0}]},"o":0}""",
            ServerFn.encodeRequest(linkedMapOf("a" to mapOf("x" to true), "b" to emptyMap<String, Any?>(), "c" to false)).toString(),
        )
    }

    @Test fun readsResults() {
        val created = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":10,"i":1,"p":{"k":["id"],"v":[{"t":1,"s":"0b0e8a3e-0000-4000-8000-000000000001"}]},"o":0},{"t":2,"s":1},{"t":2,"s":1}]},"o":0}"""
        assertEquals("0b0e8a3e-0000-4000-8000-000000000001", ServerFn.parseResponse(200, created)["id"])
        val reset = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":10,"i":1,"p":{"k":["ok"],"v":[{"t":2,"s":2}]},"o":0},{"t":2,"s":1},{"t":2,"s":1}]},"o":0}"""
        assertEquals(true, ServerFn.parseResponse(200, reset)["ok"])
    }

    @Test fun surfacesServerMessage() {
        fun reply(message: String) = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":2,"s":1},{"t":13,"i":1,"s":0,"m":"$message","p":{"k":["stack"],"v":[{"t":1,"s":"Error"}]}},{"t":2,"s":1}]},"o":0}"""
        val taken = assertThrows(ApiError::class.java) { ServerFn.parseResponse(500, reply("اسم المستخدم غير متاح")) }
        assertEquals("اسم المستخدم غير متاح", taken.message)
        assertEquals(500, taken.status)
        val bare = assertThrows(ApiError::class.java) { ServerFn.parseResponse(500, """{"t":13,"i":0,"s":0,"m":"غير مصرح"}""") }
        assertEquals("غير مصرح", bare.message)
        // A revoked session is reported like an Auth rejection so the caller can ask for a new sign-in.
        assertEquals(401, assertThrows(ApiError::class.java) { ServerFn.parseResponse(500, reply(SESSION_EXPIRED)) }.status)
    }

    @Test fun neverAcceptsAnUnreadableReply() {
        assertThrows(ApiError::class.java) { ServerFn.parseResponse(403, "Forbidden") }
        assertThrows(ApiError::class.java) { ServerFn.parseResponse(200, "<html></html>") }
        assertThrows(ApiError::class.java) { ServerFn.parseResponse(200, "") }
        val noResult = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":2,"s":1},{"t":2,"s":1},{"t":2,"s":1}]},"o":0}"""
        assertThrows(ApiError::class.java) { ServerFn.parseResponse(200, noResult) }
    }

    @Test fun staffFormRules() {
        assertEquals("أدخل اسم الموظف", staffFormError(" ", "ali", "123456", false))
        assertTrue(staffFormError("علي", "علي", "123456", false)!!.startsWith("اسم المستخدم لا يقبل حروفاً عربية"))
        assertTrue(staffFormError("علي", "a b", "123456", false)!!.startsWith("اسم المستخدم غير صالح"))
        assertEquals("رمز الدخول يجب أن يكون ٦ أرقام بالضبط", staffFormError("علي", "ali", "12345", false))
        assertEquals("رمز الدخول يجب أن يكون ٦ أرقام بالضبط", staffFormError("علي", "ali", "12", true))
        assertNull(staffFormError("علي", " Ali-01 ", "١٢٣٤٥٦", false))
        assertNull(staffFormError("علي", "ali", "", true))
    }
}
