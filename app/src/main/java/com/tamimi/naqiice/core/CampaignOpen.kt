package com.tamimi.naqiice.core

// Ported from the web app's src/lib/campaign-open.ts (campaignConflictMessage lives in Calc.kt).

/** Open (not closed) campaigns across all dates for a truck. */
fun openForTruck(trips: List<Row>, truckId: String): List<Row> =
    trips.filter { it.str("status") != "closed" && it.str("truck_id") == truckId }

/** Open campaign held by a distributor on any truck/date. */
fun openForDistributor(trips: List<Row>, distributorId: String?): Row? =
    if (distributorId.isNullOrEmpty()) null else trips.firstOrNull { it.str("status") != "closed" && it.str("distributor_id") == distributorId }

/** Arabic reason why a new campaign cannot be opened, or null. */
fun openBlock(trips: List<Row>, truckId: String, distributorId: String?): String? {
    openForTruck(trips, truckId).firstOrNull()?.let { return "لدى هذه الشاحنة حملة مفتوحة بتاريخ ${it.str("trip_date")}؛ أغلقها أولاً" }
    openForDistributor(trips, distributorId)?.let { return "الموزع المختار لديه حملة مفتوحة بتاريخ ${it.str("trip_date")} على شاحنة أخرى" }
    return null
}
