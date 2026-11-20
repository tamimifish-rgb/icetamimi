package com.tamimi.naqiice

import com.tamimi.naqiice.core.equalShares
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's preview of a split charge matches the server's rounding (pureice_split_charge). */
class SharedChargesTest {
    @Test fun equalSharesAddUpExactly() {
        assertEquals(listOf(100.0), equalShares(100.0, 1))
        assertEquals(listOf(150.5, 150.5), equalShares(301.0, 2))
        // The main distributor (first) takes the remainder.
        assertEquals(listOf(33.34, 33.33, 33.33), equalShares(100.0, 3))
        assertEquals(listOf(0.02, 0.01), equalShares(0.03, 2))
        assertEquals(emptyList<Double>(), equalShares(100.0, 0))
    }
}
