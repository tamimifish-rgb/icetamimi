package com.tamimi.naqiice

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.tamimi.naqiice.core.REPORT_TABS
import com.tamimi.naqiice.ui.Screen
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The reports page (every tab) and the route history, rendered with the shared fixture plus
 * `tools/web-reference/fixture.reports.json`. The web side of each screenshot comes from
 * `steps.reports.js` (the commands are at its top); names are the same on both sides.
 */
class ReportsScreensTest : ScreenHarness() {
    /** Map tiles are not part of the fixture: every tile request fails at once, as in the web reference. */
    @Before fun noMapTiles() {
        org.osmdroid.config.Configuration.getInstance().httpProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))
    }

    /** The pages read online; waits until [text] has arrived on screen. */
    private fun waitFor(text: String) = compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun campaign(title: String, heading: String) {
        tap(title)
        waitFor(heading)
        // Three failed street tiles switch the map to the alternate layer and raise the notice.
        waitFor("تعذّر تحميل تفاصيل الخريطة")
        waitFor("Tiles © Esri")
    }

    @Test fun everyReportTab() {
        show(Screen.Reports, "admin")
        waitFor("القيمة (ر.ي)")
        shot("reports-day")
        // The value of a bar, as a tap on it shows (the same point the web reference hovers).
        compose.onNodeWithContentDescription("الرسم البياني").performTouchInput { click(Offset((133 - 16).dp.toPx(), (450 - 332).dp.toPx())) }
        compose.waitForIdle()
        shot("reports-tip")
        for ((value, label) in REPORT_TABS.drop(1)) {
            tap(label)
            if (value == "visits") {
                shot("reports-visits-none")
                tap("اختر الشاحنة")
                tap("شاحنة ٨")
            }
            shot("reports-$value")
            if (value == "inactive") {
                tap("14 يوم")
                tap("7 يوم")
                shot("reports-inactive-7")
            }
        }
        compose.onNodeWithText("لم تتم زيارتهم اليوم — شاحنة ٨").assertExists()
        compose.onNodeWithText("مخبز الريان").assertExists()
    }

    @Test fun routeHistory() {
        show(Screen.RouteMap(), "admin")
        waitFor("الحملات (")
        shot("map-list")
        campaign("شاحنة ٨ · 2026-11-10", "شاحنة ٨ · 2026-11-10 · مغلقة")
        shot("map-trip")
        compose.onNodeWithContentDescription("طبقات الخريطة").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("صور الأقمار الصناعية").assertExists()
        shot("map-trip-layers")
    }

    /** The same on a tall screen, so the summary under the map is in the picture. */
    @Config(qualifiers = "ar-rYE-w411dp-h1700dp-xhdpi")
    @Test fun routeHistoryTall() {
        show(Screen.RouteMap(), "admin")
        waitFor("الحملات (")
        campaign("شاحنة ٨ · 2026-11-10", "شاحنة ٨ · 2026-11-10 · مغلقة")
        shot("map-trip-tall")
        // The open campaign of truck 1 has an invoice but no saved route points.
        campaign("شاحنة ١ · 2026-11-14", "لا توجد نقاط تتبع محفوظة لهذه الحملة")
        shot("map-open-tall")
    }
}
