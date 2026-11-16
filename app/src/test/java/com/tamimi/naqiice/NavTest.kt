package com.tamimi.naqiice

import com.tamimi.naqiice.ui.Nav
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {
    @Test fun replacingTheOnlyScreenWorks() {
        // "بيع" in the bottom bar opens the sale form as the only screen; saving replaces it.
        val nav = Nav(Screen.Today)
        nav.home(Screen.Sale())
        nav.replace(Screen.Invoice("s1"))
        assertEquals(listOf<Screen>(Screen.Invoice("s1")), nav.stack.toList())
        assertFalse(nav.back())
    }

    @Test fun replaceKeepsTheScreensBelowAndNeverDuplicates() {
        val nav = Nav(Screen.More)
        nav.go(Screen.NewCustomer)
        nav.replace(Screen.Sale("c1"))
        assertEquals(listOf(Screen.More, Screen.Sale("c1")), nav.stack.toList())
        nav.go(Screen.Sale("c1"))
        assertEquals(2, nav.stack.size)
        nav.go(Screen.Invoice("s1"))
        nav.go(Screen.InvoiceChange("s1", "cancel"))
        nav.replace(Screen.Invoice("s1"))
        assertEquals(listOf(Screen.More, Screen.Sale("c1"), Screen.Invoice("s1")), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(Screen.Sale("c1"), nav.current)
    }
}
