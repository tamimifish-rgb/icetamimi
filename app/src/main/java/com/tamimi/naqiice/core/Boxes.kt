package com.tamimi.naqiice.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

// Returnable boxes: every box carries a sticker with a serial (TM-ICE-000001) and a QR code
// holding exactly that serial. These rules mirror the database function pureice_box_move.

const val BOX_PREFIX = "TM-ICE-"

private val SERIAL_IN_TEXT = Regex("TM-?ICE-?(\\d{1,6})(?!\\d)")
private val BARE_NUMBER = Regex("^(\\d{1,6})$")

/** The serial for a scanned QR text or a typed number ("12", "٠١٢", "tm-ice-000012"); null when it is not a box. */
fun parseBoxSerial(text: String): String? {
    val clean = normalizeDigits(text).trim().uppercase(Locale.ROOT)
    val digits = SERIAL_IN_TEXT.find(clean)?.groupValues?.get(1) ?: BARE_NUMBER.find(clean)?.groupValues?.get(1) ?: return null
    val number = digits.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return BOX_PREFIX + String.format(Locale.US, "%06d", number)
}

/** "TM-ICE-000012" → 12, for short display next to the full serial. */
fun boxNumber(serial: String?): Int? = serial?.removePrefix(BOX_PREFIX)?.toIntOrNull()

val BOX_STATUSES = listOf("new", "factory", "truck", "customer", "elsewhere", "damaged", "disposed")

val BOX_STATUS_LABELS = mapOf(
    "new" to "لم يُمسح بعد", "factory" to "في المصنع", "truck" to "على شاحنة", "customer" to "عند عميل",
    "elsewhere" to "مكان آخر", "damaged" to "تالف", "disposed" to "مستبعد",
)

val BOX_ACTION_LABELS = mapOf(
    "load" to "تحميل على شاحنة", "unload" to "إعادة إلى المصنع", "deliver" to "تسليم لعميل", "collect" to "استلام من عميل",
    "damage" to "تسجيل تالف", "dispose" to "استبعاد", "elsewhere" to "نقل إلى مكان آخر", "restore" to "إعادة إلى المصنع",
)

/** Where a box ends up after an action. */
fun boxStatusAfter(action: String): String = when (action) {
    "load", "collect" -> "truck"
    "unload", "restore" -> "factory"
    "deliver" -> "customer"
    "damage" -> "damaged"
    "dispose" -> "disposed"
    else -> "elsewhere"
}

/** The action a manager uses to put a box into a status by hand. */
fun boxActionFor(status: String, current: String?): String = when (status) {
    "truck" -> "load"
    "customer" -> "deliver"
    "damaged" -> "damage"
    "disposed" -> "dispose"
    "elsewhere" -> "elsewhere"
    else -> if (current == "damaged" || current == "elsewhere" || current == "disposed") "restore" else "unload"
}

/** True when the recorded state does not fit the move; the scan is still accepted and flagged. */
fun unexpectedBoxMove(box: Row, action: String, truckId: String?, customerId: String?): Boolean {
    val status = box.str("status") ?: "new"
    if (status == "new") return false
    return when (action) {
        "load" -> status != "factory"
        "unload" -> status != "truck"
        "deliver" -> status != "truck" || (truckId != null && box.str("truck_id") != truckId)
        "collect" -> status != "customer" || (customerId != null && box.str("customer_id") != customerId)
        "restore" -> status != "damaged" && status != "elsewhere" && status != "disposed"
        else -> status == "damaged" || status == "disposed"
    }
}

/**
 * Boxes as the screens show them: the server rows with the moves still waiting on this
 * phone applied in the order they were made.
 */
fun overlayBoxes(boxes: List<Row>, moves: List<Row>): List<Row> {
    if (moves.isEmpty()) return boxes
    val bySerial = LinkedHashMap<String, Row>()
    for (box in boxes) bySerial[box.str("serial") ?: continue] = box
    for (move in moves) {
        val action = move.str("action") ?: continue
        val status = boxStatusAfter(action)
        for (entry in move.rows("boxes")) {
            val serial = entry.str("serial") ?: continue
            val box = bySerial[serial] ?: continue
            bySerial[serial] = box.with(
                "status" to JsonPrimitive(status),
                "truck_id" to (if (status == "truck") jstr(move.str("truck_id")) else JsonNull),
                "customer_id" to (if (status == "customer") jstr(move.str("customer_id")) else JsonNull),
                "notes" to (if (status == "damaged" || status == "disposed" || status == "elsewhere") jstr(move.str("notes")) else JsonNull),
                "moved_at" to jstr(move.str("created_at")),
                "_pending" to JsonPrimitive(true),
            )
        }
    }
    return bySerial.values.toList()
}

fun boxCounts(boxes: List<Row>): Map<String, Int> = boxes.groupingBy { it.str("status") ?: "new" }.eachCount()

// ---- customer location capture ------------------------------------------------------------

const val CUSTOMER_LOCATION_ACCURACY = 50f
const val CUSTOMER_LOCATION_MAX_AGE = 60_000L

/**
 * After an invoice: ask the distributor to confirm the customer's location only when the
 * customer has none, the fix is good and fresh, and the customer already exists on the server.
 */
fun offerCustomerLocation(
    customer: Row?, lat: Double?, lng: Double?, accuracy: Float?, fixTime: Long?, now: Long,
    customerUnsent: Boolean, supported: Boolean,
): Boolean {
    if (!supported || customer == null || customerUnsent) return false
    if (customer.numOrNull("lat") != null || customer.numOrNull("lng") != null) return false
    if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) return false
    if (accuracy == null || !(accuracy >= 0f && accuracy <= CUSTOMER_LOCATION_ACCURACY)) return false
    return fixTime != null && now - fixTime <= CUSTOMER_LOCATION_MAX_AGE
}

/**
 * One confirmed scan list as the server function takes it. [boxes] are (move id, serial);
 * the first move id is also the id of the whole request.
 */
fun boxMoveRequest(
    owner: String, action: String, boxes: List<Pair<String, String>>, createdAt: String,
    truckId: String? = null, customerId: String? = null, tripId: String? = null, saleId: String? = null,
    notes: String? = null, lat: Double? = null, lng: Double? = null,
): Row = kotlinx.serialization.json.buildJsonObject {
    require(boxes.isNotEmpty())
    put("id", JsonPrimitive(boxes.first().first)); put("created_by", JsonPrimitive(owner)); put("created_at", JsonPrimitive(createdAt))
    put("action", JsonPrimitive(action))
    put("truck_id", jstr(truckId)); put("customer_id", jstr(customerId)); put("trip_id", jstr(tripId)); put("sale_id", jstr(saleId))
    put("notes", jstr(notes?.trim()?.takeIf { it.isNotEmpty() }))
    put("lat", if (lat != null && lng != null) JsonPrimitive(lat) else JsonNull); put("lng", if (lat != null && lng != null) JsonPrimitive(lng) else JsonNull)
    put("boxes", kotlinx.serialization.json.JsonArray(boxes.map { (id, serial) ->
        kotlinx.serialization.json.buildJsonObject { put("id", JsonPrimitive(id)); put("serial", JsonPrimitive(serial)) }
    }))
}
