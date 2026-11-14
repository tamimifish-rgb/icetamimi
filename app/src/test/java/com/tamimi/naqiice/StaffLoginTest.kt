package com.tamimi.naqiice

import com.tamimi.naqiice.data.ApiError
import com.tamimi.naqiice.data.StaffLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StaffLoginTest {
    /** The id the published web bundle calls for staffLogin. */
    @Test fun functionIdMatchesPublishedSite() {
        assertEquals("022a1df5d42d28cd61c85b21d129ca8c6424eba99733d6723f70c295b64c6d77", StaffLogin.FUNCTION_ID)
    }

    /** Byte-for-byte what the web client's serializer produces for the same input. */
    @Test fun requestMatchesWebSerializer() {
        assertEquals(
            """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["username","credential"],"v":[{"t":1,"s":"ali"},{"t":1,"s":"123456"}]},"o":0}]},"o":0}""",
            StaffLogin.encodeRequest("ali", "123456").toString(),
        )
    }

    /** The exact body the web client posts (seroval toJSONAsync), including the envelope. */
    @Test fun bodyMatchesWebClient() {
        assertEquals(
            """{"t":{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["username","credential"],"v":[{"t":1,"s":"ali"},{"t":1,"s":"12\\x3C3\\\"456"}]},"o":0}]},"o":0},"f":127,"m":[]}""",
            StaffLogin.envelope(StaffLogin.encodeRequest("ali", "12<3\"456")).toString(),
        )
    }

    @Test fun escapesLegacyPasswords() {
        assertEquals("a\\\"b\\\\c\\x3Cd", StaffLogin.escape("a\"b\\c<d"))
        assertEquals("a\"b\\c<d\n", StaffLogin.unescape("a\\\"b\\\\c\\x3Cd\\n"))
    }

    @Test fun readsTokens() {
        val body = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":10,"i":1,"p":{"k":["access_token","refresh_token"],"v":[{"t":1,"s":"A.B.C"},{"t":1,"s":"R"}]},"o":0},{"t":2,"s":1},{"t":2,"s":1}]},"o":0}"""
        val tokens = StaffLogin.parseResponse(200, body)
        assertEquals("A.B.C", tokens.accessToken)
        assertEquals("R", tokens.refreshToken)
    }

    @Test fun surfacesServerMessage() {
        val body = """{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":2,"s":1},{"t":13,"i":1,"s":0,"m":"بيانات الدخول غير صحيحة","p":{"k":["stack"],"v":[{"t":1,"s":"Error"}]}},{"t":2,"s":1}]},"o":0}"""
        val error = assertThrows(ApiError::class.java) { StaffLogin.parseResponse(500, body) }
        assertEquals("بيانات الدخول غير صحيحة", error.message)
    }

    @Test fun neverAcceptsAnUnreadableReply() {
        assertThrows(ApiError::class.java) { StaffLogin.parseResponse(403, "Forbidden") }
        assertThrows(ApiError::class.java) { StaffLogin.parseResponse(200, "<html></html>") }
        assertThrows(ApiError::class.java) { StaffLogin.parseResponse(200, "") }
    }
}
