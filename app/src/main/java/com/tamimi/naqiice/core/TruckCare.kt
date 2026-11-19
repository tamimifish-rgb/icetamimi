package com.tamimi.naqiice.core

/** Where a truck stands against its service plan. */
enum class ServiceState { Ok, Soon, Due, NoPlan }

/** Whole days from [from] to [to], both "yyyy-MM-dd". */
fun daysBetween(from: String, to: String): Long? {
    val a = parseIso("${from}T12:00:00Z") ?: return null
    val b = parseIso("${to}T12:00:00Z") ?: return null
    return Math.round((b - a) / 86_400_000.0)
}

/**
 * A truck is due once the km or the days since its last service reach the plan's interval, and
 * soon within the last 10% of it; whichever interval comes first decides. A plan with no service
 * recorded yet is due. Days are "yyyy-MM-dd" ([today] in Aden).
 */
fun serviceState(intervalKm: Int?, intervalDays: Int?, lastKm: Double?, lastDate: String?, currentKm: Double?, today: String): ServiceState {
    val km = intervalKm?.takeIf { it > 0 }
    val days = intervalDays?.takeIf { it > 0 }
    if (km == null && days == null) return ServiceState.NoPlan
    if (lastKm == null && lastDate == null) return ServiceState.Due
    // Share of each interval used up; null when it cannot be measured.
    val usedKm = if (km != null && lastKm != null && currentKm != null) (currentKm - lastKm) / km else null
    val usedDays = if (days != null && lastDate != null) daysBetween(lastDate, today)?.let { it.toDouble() / days } else null
    val used = listOfNotNull(usedKm, usedDays).maxOrNull() ?: return ServiceState.Ok
    return when {
        used >= 1.0 -> ServiceState.Due
        used >= 0.9 -> ServiceState.Soon
        else -> ServiceState.Ok
    }
}
