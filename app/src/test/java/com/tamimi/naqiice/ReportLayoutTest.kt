package com.tamimi.naqiice

import com.tamimi.naqiice.core.niceTicks
import com.tamimi.naqiice.core.tableColumnWidths
import com.tamimi.naqiice.core.tickLabel
import com.tamimi.naqiice.core.tickPlaces
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chart axis and table column rules behind the reports page; the expected values are what the web page draws. */
class ReportLayoutTest {
    @Test fun valueAxisTicksAreTheWebCharts() {
        assertEquals(listOf(0.0, 6000.0, 12000.0, 18000.0, 24000.0), niceTicks(0.0, 22500.0))
        assertEquals(listOf(0.0, 9500.0, 19000.0, 28500.0, 38000.0), niceTicks(0.0, 37500.0))
        assertEquals(listOf(0.0, 7500.0, 15000.0, 22500.0, 30000.0), niceTicks(0.0, 28500.0))
        assertEquals(listOf(0.0, 1500.0, 3000.0, 4500.0, 6000.0), niceTicks(0.0, 4500.0))
        assertEquals(listOf(0.0, 15.0, 30.0, 45.0, 60.0), niceTicks(0.0, 58.0))
        assertEquals(listOf(0.0, 0.75, 1.5, 2.25, 3.0), niceTicks(0.0, 3.0))
        // Nothing sold at all: recharts still draws a 0–4 axis.
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), niceTicks(0.0, 0.0))
    }

    @Test fun tickLabelsPrintLikeJavaScriptNumbers() {
        assertEquals("24000", tickLabel(24000.0))
        assertEquals("0", tickLabel(0.0))
        assertEquals("2.25", tickLabel(2.25))
        assertEquals("1.5", tickLabel(1.5))
    }

    @Test fun topValueLabelMovesInsideTheChart() {
        // Five ticks up a 224px chart; a label counts as 15px tall, so the top one drops 2.5px.
        assertEquals(listOf(189f, 143f, 97f, 51f, 7.5f), tickPlaces(listOf(189f, 143f, 97f, 51f, 5f), List(5) { 15f }, 224f, 0f))
    }

    @Test fun crowdedCategoryLabelsAreDroppedFromTheEnd() {
        assertEquals(listOf(132.5f, 235.5f, 338.5f), tickPlaces(listOf(132.5f, 235.5f, 338.5f), List(3) { 53f }, 0f, 379f))
        // The last label always stays; a neighbour closer than 5px to it goes.
        assertEquals(listOf(10f, null, 30f), tickPlaces(listOf(10f, 20f, 30f), List(3) { 15f }, 0f, 100f))
        // A last label that would stick out of the chart is drawn further in.
        assertEquals(listOf(30f, 80f), tickPlaces(listOf(30f, 95f), listOf(20f, 40f), 0f, 100f))
    }

    @Test fun tableColumnsFollowBrowserAutomaticLayout() {
        // Room to spare: every column grows in proportion to its widest content.
        val wide = tableColumnWidths(listOf(98.5f, 98.5f, 70f), listOf(98.5f, 98.5f, 70f), 379f)
        assertEquals(379f, wide.sum(), 0.01f)
        assertEquals(139.8f, wide[0], 0.1f)
        assertEquals(99.4f, wide[2], 0.1f)
        // In between: the narrowest width plus an equal share of what each column still wants.
        assertEquals(listOf(75f, 125f), tableColumnWidths(listOf(50f, 50f), listOf(100f, 200f), 200f))
        // Too little room: the table keeps its narrowest widths and scrolls.
        assertEquals(listOf(50f, 60f), tableColumnWidths(listOf(50f, 60f), listOf(100f, 200f), 80f))
    }
}
