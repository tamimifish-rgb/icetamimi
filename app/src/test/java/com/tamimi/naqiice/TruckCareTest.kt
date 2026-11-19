package com.tamimi.naqiice

import com.tamimi.naqiice.core.ServiceState
import com.tamimi.naqiice.core.serviceState
import org.junit.Assert.assertEquals
import org.junit.Test

class TruckCareTest {
    private fun km(driven: Double?, interval: Int? = 5000) =
        serviceState(interval, null, 118000.0, "2026-10-01", driven?.let { 118000.0 + it }, "2026-10-02")

    @Test fun serviceStateRules() {
        assertEquals(ServiceState.Ok, km(1000.0))
        assertEquals(ServiceState.Soon, km(4600.0))   // within 10% of the 5,000 km interval
        assertEquals(ServiceState.Due, km(5000.0))
        assertEquals(ServiceState.Due, km(7000.0))
        // A 90-day plan: day 80 is fine, day 82 is within 10%, day 91 is due.
        assertEquals(ServiceState.Ok, serviceState(null, 90, null, "2026-10-01", null, "2026-12-20"))
        assertEquals(ServiceState.Soon, serviceState(null, 90, null, "2026-10-01", null, "2026-12-22"))
        assertEquals(ServiceState.Due, serviceState(null, 90, null, "2026-10-01", null, "2026-12-31"))
        // With both, whichever comes first.
        assertEquals(ServiceState.Due, serviceState(5000, 90, 118000.0, "2026-10-01", 119000.0, "2026-12-31"))
        assertEquals(ServiceState.Due, serviceState(5000, 90, 118000.0, "2026-10-01", 123500.0, "2026-10-05"))
        // No plan, or no interval at all.
        assertEquals(ServiceState.NoPlan, serviceState(null, null, 118000.0, "2026-10-01", 125000.0, "2026-12-31"))
    }

    @Test fun neverServicedYet() {
        // A plan exists but no service was ever recorded: nothing to count from, so it is due.
        assertEquals(ServiceState.Due, serviceState(5000, 90, null, null, 50000.0, "2026-11-19"))
        // A km plan without a current reading cannot be judged by km; days still count.
        assertEquals(ServiceState.Ok, serviceState(5000, 90, 118000.0, "2026-10-01", null, "2026-10-10"))
    }
}
