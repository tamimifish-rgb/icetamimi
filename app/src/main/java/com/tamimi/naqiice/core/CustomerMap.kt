package com.tamimi.naqiice.core

/** One customer on the distributor's map. */
data class CustomerPin(val id: String, val name: String, val lat: Double, val lng: Double, val visited: Boolean)

/** The map's pins, how many customers have no saved location, and how many pins were visited. */
data class CustomerPins(val pins: List<CustomerPin>, val unplaced: Int, val visited: Int)

/**
 * Pins for every customer with a saved location. A customer counts as visited when the current
 * campaign has a sale for them; [campaignSales] is `Snapshot.day.sales`, which already includes
 * sales still waiting on the phone.
 *
 * Filters, combined: [region] keeps the customers of that area; [variant] keeps those who bought
 * that product, in the last year's history ([bought]: customer id → variant ids) or in this campaign.
 */
fun customerPins(
    customers: List<Row>, campaignSales: List<Row>,
    region: String? = null, variant: String? = null, bought: Map<String, Set<String>> = emptyMap(),
): CustomerPins {
    val invoiced = campaignSales.mapNotNullTo(HashSet()) { it.str("customer_id") }
    // This campaign's products per customer, from server sales (sale_items) and waiting ones (items).
    val boughtNow = HashMap<String, MutableSet<String>>()
    for (sale in campaignSales) {
        val customer = sale.str("customer_id") ?: continue
        for (item in sale.rows("sale_items") + sale.rows("items")) item.str("variant_id")?.let { boughtNow.getOrPut(customer) { HashSet() }.add(it) }
    }
    val pins = ArrayList<CustomerPin>()
    var unplaced = 0
    for (c in customers) {
        val id = c.str("id") ?: continue
        if (region != null && c.str("region_id") != region) continue
        if (variant != null && variant !in bought[id].orEmpty() && variant !in boughtNow[id].orEmpty()) continue
        val lat = c.numOrNull("lat")
        val lng = c.numOrNull("lng")
        if (lat == null || lng == null) { unplaced++; continue }
        pins.add(CustomerPin(id, c.str("name") ?: "", lat, lng, id in invoiced))
    }
    return CustomerPins(pins, unplaced, pins.count { it.visited })
}
