package com.tamimi.naqiice.data

import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.dedupeById
import com.tamimi.naqiice.core.expectedCash
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.obj
import com.tamimi.naqiice.core.remainingStock
import com.tamimi.naqiice.core.rows
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.strings
import com.tamimi.naqiice.core.with
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the screens show = the last server snapshot + records still waiting on this phone.
 * Everything here is pure so it can be unit-tested.
 */
class Snapshot(ref: Row?, pending: List<OutboxOp>, val ownerId: String?) {
    /** Only this account's records and this account's snapshot are ever combined. */
    val pending: List<OutboxOp> = pending.filter { it.ownerId == ownerId }
    private val baseRef: Row? = ref?.takeIf { it.str("ownerId") == ownerId }

    val ref: Row? = baseRef?.let { base ->
        val known = base.rows("customers").mapNotNull { it.str("id") }.toHashSet()
        val local = this.pending.filter { it.kind == "customer" && it.id !in known }
            .map { it.payload.with("credit_limit" to JsonPrimitive(0), "active" to JsonPrimitive(true)) }
        if (local.isEmpty()) base else base.with("customers" to JsonArray(base.rows("customers") + local))
    }

    val trip: Row? = ref?.obj("trip")
    val tripId: String? = trip?.str("id")
    val closing: Row? = ref?.obj("closing")
    val variants: List<Row> = ref?.rows("variants") ?: emptyList()
    val customers: List<Row> = ref?.rows("customers") ?: emptyList()
    val regions: List<Row> = ref?.rows("regions") ?: emptyList()
    val trucks: List<Row> = ref?.rows("trucks") ?: emptyList()
    val freezers: List<Row> = ref?.rows("freezers") ?: emptyList()
    val unapprovedLoads: List<Row> = ref?.rows("unapprovedLoads") ?: emptyList()

    private val included: Set<String> = ref?.strings("includedIds")?.toHashSet() ?: emptySet()
    private val waiting: List<OutboxOp> = this.pending.filter { it.id !in included }
    private val pendingSales: List<Row> = waiting.filter { it.kind == "sale" }.map { it.payload }

    fun customer(id: String?): Row? = customers.firstOrNull { it.str("id") == id }
    fun variant(id: String?): Row? = variants.firstOrNull { it.str("id") == id }
    fun regionName(id: String?): String = regions.firstOrNull { it.str("id") == id }?.str("name") ?: ""
    fun truck(id: String?): Row? = trucks.firstOrNull { it.str("id") == id }

    /** Customer-specific prices for one customer, by variant. */
    fun pricesFor(customerId: String?): Map<String, Double> =
        (ref?.rows("prices") ?: emptyList()).filter { it.str("customer_id") == customerId }.associate { (it.str("variant_id") ?: "") to it.num("price") }

    /** Stock per variant including sales still waiting on this phone. */
    fun stockOf(variantId: String): Double {
        val row = ref?.rows("stock")?.firstOrNull { it.str("variant_id") == variantId }
        val base = row?.let { remainingStock(it.num("loaded"), it.num("sold"), it.num("returned"), it.num("damaged")) } ?: 0.0
        val queued = pendingSales.filter { it.str("trip_id") != null && it.str("trip_id") == tripId }
            .flatMap { it.rows("items") }.filter { it.str("variant_id") == variantId }.sumOf { it.num("qty") }
        return base - queued
    }

    /** Customer debt including records still waiting on this phone. */
    fun balanceOf(customerId: String): Double {
        val base = ref?.rows("balances")?.firstOrNull { it.str("customer_id") == customerId }?.num("balance") ?: 0.0
        val credit = pendingSales.filter { it.str("customer_id") == customerId }.sumOf { it.num("credit_amount") }
        val paid = waiting.filter { it.kind == "payment" && it.payload.str("customer_id") == customerId }.sumOf { it.payload.num("amount") }
        return base + credit - paid
    }

    /** True once a closing was sent (or is waiting on this phone): field writes stop. */
    val closingSent: Boolean get() = closing != null || closingQueued
    val closingQueued: Boolean get() = tripId != null && this.pending.any { it.kind == "closing" && it.payload.str("trip_id") == tripId }

    /** The distributor's own open campaign, required for every field write. */
    fun ownOpenTrip(userId: String?): Boolean = trip != null && userId != null && trip.str("distributor_id") == userId && trip.str("status") == "open"

    val day: Day by lazy { computeDay() }

    private fun computeDay(): Day {
        val campaign = if (tripId != null) this.pending.filter { it.payload.str("trip_id") == tripId } else emptyList()
        val mark = "_pending" to JsonPrimitive(true)
        val pSales = campaign.filter { it.kind == "sale" }.map { it.payload.with("sale_items" to JsonArray(it.payload.rows("items")), mark) }
        val pPays = campaign.filter { it.kind == "payment" }.map { it.payload.with(mark) }
        val pExp = campaign.filter { it.kind == "expense" }.map { it.payload.with(mark) }
        val sales = dedupeById(pSales + (ref?.rows("salesToday") ?: emptyList())).sortedByDescending { it.str("created_at") ?: "" }
        val payments = dedupeById(pPays + (ref?.rows("paymentsToday") ?: emptyList()))
        val expenses = dedupeById(pExp + (ref?.rows("expensesToday") ?: emptyList()))
        val cashSales = sales.sumOf { it.num("cash_paid") }
        val collected = payments.sumOf { it.num("amount") - it.num("deposit_amount") }
        val spent = expenses.sumOf { it.num("amount") }
        return Day(
            sales, payments, expenses,
            totalSales = sales.sumOf { it.num("total") }, cashSales = cashSales, credit = sales.sumOf { it.num("credit_amount") },
            collected = collected, spent = spent, deposits = (sales + payments).sumOf { it.num("deposit_amount") },
            expected = expectedCash(cashSales, collected, spent),
        )
    }

    companion object {
        val EMPTY = Snapshot(null, emptyList(), null)
        fun emptyRow() = buildJsonObject { put("id", "") }
    }
}

data class Day(
    val sales: List<Row>, val payments: List<Row>, val expenses: List<Row>,
    val totalSales: Double, val cashSales: Double, val credit: Double, val collected: Double,
    val spent: Double, val deposits: Double, val expected: Double,
)
