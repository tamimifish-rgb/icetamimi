package com.tamimi.naqiice

import com.tamimi.naqiice.core.customerPins
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerMapTest {
    private fun customer(id: String, lat: Double?, lng: Double?) = buildJsonObject {
        put("id", id); put("name", "عميل $id")
        if (lat == null) put("lat", JsonNull) else put("lat", lat)
        if (lng == null) put("lng", JsonNull) else put("lng", lng)
    }

    /** A sale as the server snapshot lists it. */
    private fun serverSale(customer: String) = buildJsonObject { put("id", "s-$customer"); put("customer_id", customer); put("total", 2500) }

    /** A sale still waiting on the phone: the outbox payload, with its items. */
    private fun pendingSale(customer: String) = buildJsonObject {
        put("id", "p-$customer"); put("customer_id", customer); put("total", 1000)
        put("items", buildJsonArray { add(buildJsonObject { put("variant_id", "v1"); put("qty", 2) }) })
    }

    private val customers = listOf(customer("a", 14.53, 49.12), customer("b", 14.54, 49.13), customer("c", null, null))

    @Test fun pinsOnlyForLocatedCustomers() {
        val result = customerPins(customers, emptyList())
        assertEquals(listOf("a", "b"), result.pins.map { it.id })
        assertEquals(14.53, result.pins[0].lat, 0.0)
        assertEquals(49.12, result.pins[0].lng, 0.0)
        assertEquals("عميل a", result.pins[0].name)
    }

    @Test fun customerWithCampaignSaleIsVisited() {
        val result = customerPins(customers, listOf(serverSale("b")))
        assertFalse(result.pins.first { it.id == "a" }.visited)
        assertTrue(result.pins.first { it.id == "b" }.visited)
        assertEquals(1, result.visited)
    }

    @Test fun pendingSaleCountsAsVisited() {
        val result = customerPins(customers, listOf(pendingSale("a")))
        assertTrue(result.pins.first { it.id == "a" }.visited)
        assertEquals(1, result.visited)
    }

    @Test fun customersWithoutLocationAreCounted() {
        // A sale for the unplaced customer counts nowhere on the map: it has no pin.
        val result = customerPins(customers, listOf(serverSale("c")))
        assertEquals(2, result.pins.size)
        assertEquals(1, result.unplaced)
        assertEquals(0, result.visited)
    }

    @Test fun halfALocationIsUnplaced() {
        val result = customerPins(listOf(customer("d", 14.5, null)), emptyList())
        assertEquals(0, result.pins.size)
        assertEquals(1, result.unplaced)
    }
}
