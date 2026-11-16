package com.tamimi.naqiice

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tamimi.naqiice.core.boxActionFor
import com.tamimi.naqiice.core.boxCounts
import com.tamimi.naqiice.core.boxMoveRequest
import com.tamimi.naqiice.core.boxNumber
import com.tamimi.naqiice.core.boxStatusAfter
import com.tamimi.naqiice.core.decodeQr
import com.tamimi.naqiice.core.offerCustomerLocation
import com.tamimi.naqiice.core.overlayBoxes
import com.tamimi.naqiice.core.packLuma
import com.tamimi.naqiice.core.parseBoxSerial
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.unexpectedBoxMove
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class BoxesTest {
    private fun box(n: Int, status: String, truck: String? = null, customer: String? = null) = buildJsonObject {
        put("id", "b$n"); put("serial", "TM-ICE-00000$n"); put("status", status)
        put("truck_id", truck?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("customer_id", customer?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    }

    @Test fun readsStickersAndTypedNumbers() {
        assertEquals("TM-ICE-000001", parseBoxSerial("TM-ICE-000001"))
        assertEquals("TM-ICE-000210", parseBoxSerial(" tm-ice-000210\n"))
        assertEquals("TM-ICE-000012", parseBoxSerial("12"))
        assertEquals("TM-ICE-000012", parseBoxSerial("٠١٢"))
        assertEquals("TM-ICE-000045", parseBoxSerial("https://example.com/box/TM-ICE-000045?x=1"))
        assertEquals("TM-ICE-123456", parseBoxSerial("123456"))
        assertNull(parseBoxSerial(""))
        assertNull(parseBoxSerial("0"))
        assertNull(parseBoxSerial("1234567"))
        assertNull(parseBoxSerial("TM-ICE-0000123"))
        assertNull(parseBoxSerial("https://example.com/12"))
        assertNull(parseBoxSerial("ثلج"))
        assertEquals(12, boxNumber("TM-ICE-000012"))
    }

    @Test fun movesLandWhereTheDatabasePutsThem() {
        assertEquals("truck", boxStatusAfter("load")); assertEquals("truck", boxStatusAfter("collect"))
        assertEquals("factory", boxStatusAfter("unload")); assertEquals("factory", boxStatusAfter("restore"))
        assertEquals("customer", boxStatusAfter("deliver")); assertEquals("damaged", boxStatusAfter("damage"))
        assertEquals("disposed", boxStatusAfter("dispose")); assertEquals("elsewhere", boxStatusAfter("elsewhere"))
        assertEquals("unload", boxActionFor("factory", "truck")); assertEquals("restore", boxActionFor("factory", "damaged"))
        assertEquals("load", boxActionFor("truck", "factory")); assertEquals("deliver", boxActionFor("customer", "truck"))
    }

    @Test fun oddMovesAreFlaggedNotRefused() {
        // A box nobody scanned yet can start anywhere.
        assertFalse(unexpectedBoxMove(box(1, "new"), "deliver", "t1", "c1"))
        assertFalse(unexpectedBoxMove(box(1, "factory"), "load", "t1", null))
        assertTrue(unexpectedBoxMove(box(1, "customer", customer = "c1"), "load", "t1", null))
        assertFalse(unexpectedBoxMove(box(1, "truck", truck = "t1"), "deliver", "t1", "c1"))
        assertTrue(unexpectedBoxMove(box(1, "truck", truck = "t2"), "deliver", "t1", "c1")) // on another truck
        assertTrue(unexpectedBoxMove(box(1, "factory"), "deliver", "t1", "c1"))             // never loaded
        assertFalse(unexpectedBoxMove(box(1, "customer", customer = "c1"), "collect", "t1", "c1"))
        assertTrue(unexpectedBoxMove(box(1, "customer", customer = "c2"), "collect", "t1", "c1"))
        assertTrue(unexpectedBoxMove(box(1, "disposed"), "damage", null, null))
        assertFalse(unexpectedBoxMove(box(1, "damaged"), "restore", null, null))
    }

    @Test fun waitingMovesApplyInOrder() {
        val boxes = listOf(box(1, "truck", truck = "t1"), box(2, "factory"), box(3, "new"))
        val moves = listOf(
            boxMoveRequest("u", "deliver", listOf("m1" to "TM-ICE-000001"), "2026-11-14T08:00:00.000Z", truckId = "t1", customerId = "c1"),
            boxMoveRequest("u", "collect", listOf("m2" to "TM-ICE-000001", "m3" to "TM-ICE-000003"), "2026-11-14T09:00:00.000Z", truckId = "t1", customerId = "c1"),
            boxMoveRequest("u", "damage", listOf("m4" to "TM-ICE-000009"), "2026-11-14T09:10:00.000Z", notes = "كسر"), // not a known box
        )
        val shown = overlayBoxes(boxes, moves)
        assertEquals(3, shown.size)
        assertEquals("truck", shown[0].str("status")); assertEquals("t1", shown[0].str("truck_id")); assertNull(shown[0].str("customer_id"))
        assertEquals("factory", shown[1].str("status"))
        assertEquals("truck", shown[2].str("status"))
        assertEquals(mapOf("truck" to 2, "factory" to 1), boxCounts(shown))
        assertEquals(boxes, overlayBoxes(boxes, emptyList()))
    }

    @Test fun requestCarriesTheBatchIdAndEveryBox() {
        val r = boxMoveRequest("u", "load", listOf("m1" to "TM-ICE-000001", "m2" to "TM-ICE-000002"), "2026-11-14T08:00:00.000Z", truckId = "t1", lat = 14.5, lng = null)
        assertEquals("m1", r.str("id")); assertEquals("u", r.str("created_by")); assertEquals("load", r.str("action"))
        assertEquals(listOf("TM-ICE-000001", "TM-ICE-000002"), r.rows("boxes").map { it.str("serial") })
        assertEquals(JsonNull, r["lat"]) // half a position is no position
    }

    @Test fun asksForACustomerLocationOnlyWhenItCanBeTrusted() {
        val now = 1_000_000L
        val none = buildJsonObject { put("id", "c1"); put("lat", JsonNull); put("lng", JsonNull) }
        val has = buildJsonObject { put("id", "c1"); put("lat", 14.5); put("lng", 49.1) }
        fun offer(customer: kotlinx.serialization.json.JsonObject? = none, lat: Double? = 14.53, lng: Double? = 49.12, accuracy: Float? = 12f, time: Long? = now - 5_000, unsent: Boolean = false, supported: Boolean = true) =
            offerCustomerLocation(customer, lat, lng, accuracy, time, now, unsent, supported)
        assertTrue(offer())
        assertFalse(offer(customer = has))                 // already has one
        assertFalse(offer(customer = null))
        assertFalse(offer(lat = null, lng = null))         // invoice saved without a position
        assertFalse(offer(accuracy = 80f))                 // too vague to call exact
        assertFalse(offer(accuracy = null))
        assertFalse(offer(time = now - 120_000))           // an old fix
        assertFalse(offer(unsent = true))                  // the customer is not on the server yet
        assertFalse(offer(supported = false))              // server without the feature
        assertTrue(offer(accuracy = 50f))
    }

    // ---- QR ---------------------------------------------------------------------------

    /** A camera-like greyscale frame: the code drawn at [scale] pixels per module on a white field. */
    private fun frame(modules: List<BooleanArray>, scale: Int = 8, margin: Int = 40, stride: Int = 0, inverted: Boolean = false): Triple<ByteArray, Int, Int> {
        val size = modules.size * scale + margin * 2
        val rowStride = size + stride
        val out = ByteArray(rowStride * size) { if (inverted) 20 else 235.toByte() }
        for (y in modules.indices) for (x in modules.indices) if (modules[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) out[(margin + y * scale + dy) * rowStride + margin + x * scale + dx] = if (inverted) 235.toByte() else 20
        }
        return Triple(out, size, rowStride)
    }

    private fun decode(modules: List<BooleanArray>, stride: Int = 0, inverted: Boolean = false): String? {
        val (bytes, size, rowStride) = frame(modules, stride = stride, inverted = inverted)
        return decodeQr(packLuma(ByteBuffer.wrap(bytes), size, size, rowStride), size, size)
    }

    @Test fun readsTheRealStickerCode() {
        // The module pattern printed on the sticker of box 1, taken from the owner's sticker sheet.
        val modules = javaClass.getResource("/sticker-000001.txt")!!.readText().trim().lines().map { line -> BooleanArray(line.length) { line[it] == '#' } }
        assertEquals(21, modules.size)
        assertEquals("TM-ICE-000001", decode(modules))
        assertEquals("TM-ICE-000001", parseBoxSerial(decode(modules)!!))
        assertEquals("TM-ICE-000001", decode(modules, stride = 24))      // padded camera rows
        assertEquals("TM-ICE-000001", decode(modules, inverted = true))  // light on dark
    }

    @Test fun readsGeneratedCodesAndIgnoresNoise() {
        for (serial in listOf("TM-ICE-000210", "TM-ICE-123456")) {
            val matrix = QRCodeWriter().encode(serial, BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
            val modules = List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
            assertEquals(serial, decode(modules))
        }
        assertNull(decodeQr(ByteArray(200 * 200) { (it * 31 % 251).toByte() }, 200, 200))
        assertNull(decodeQr(ByteArray(10), 200, 200))
    }
}
