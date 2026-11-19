package com.tamimi.naqiice.core

/** One customer on the distributor's map. */
data class CustomerPin(val id: String, val name: String, val lat: Double, val lng: Double, val visited: Boolean)

/** The map's pins, how many customers have no saved location, and how many pins were visited. */
data class CustomerPins(val pins: List<CustomerPin>, val unplaced: Int, val visited: Int)

/**
 * Pins for every customer with a saved location. A customer counts as visited when the current
 * campaign has a sale for them; [campaignSales] is `Snapshot.day.sales`, which already includes
 * sales still waiting on the phone.
 */
fun customerPins(customers: List<Row>, campaignSales: List<Row>): CustomerPins {
    val invoiced = campaignSales.mapNotNullTo(HashSet()) { it.str("customer_id") }
    val pins = ArrayList<CustomerPin>()
    var unplaced = 0
    for (c in customers) {
        val id = c.str("id") ?: continue
        val lat = c.numOrNull("lat")
        val lng = c.numOrNull("lng")
        if (lat == null || lng == null) { unplaced++; continue }
        pins.add(CustomerPin(id, c.str("name") ?: "", lat, lng, id in invoiced))
    }
    return CustomerPins(pins, unplaced, pins.count { it.visited })
}
