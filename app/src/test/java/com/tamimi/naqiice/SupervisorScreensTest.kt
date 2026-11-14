package com.tamimi.naqiice

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.tamimi.naqiice.ui.Screen
import org.junit.Test
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * The supervisor screens (trucks, a campaign, closings) and the invoice cancel/edit
 * screens with the rows of `fixture.supervisor.json`, saved as `sup-*.png`. The web side is
 * `tools/web-reference/steps.supervisor.js` (same names, same 2200dp-tall window).
 */
@Config(qualifiers = "+h2200dp")
class SupervisorScreensTest : ScreenHarness() {
    private fun await(text: String) {
        compose.waitUntil(20_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Leaves the field that was typed in, as the web steps do, so no cursor is in the picture. */
    private fun blur() {
        val decor = compose.activity.window.decorView as android.view.ViewGroup
        compose.runOnUiThread {
            // The window itself takes the focus, so it cannot fall back onto the first field.
            decor.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            decor.isFocusable = true
            decor.isFocusableInTouchMode = true
            decor.requestFocus()
        }
        compose.waitForIdle()
        compose.runOnUiThread { decor.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS }
    }

    /** Chooses a day in the system date picker that the trucks screen's date field opens. */
    private fun pickDate(year: Int, month: Int, day: Int) {
        compose.onNodeWithContentDescription("التاريخ").performClick()
        compose.waitForIdle()
        val picker = ShadowDialog.getLatestDialog() as DatePickerDialog
        picker.updateDate(year, month - 1, day)
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun go(screen: Screen, name: String, until: String) {
        compose.runOnUiThread { nav.home(screen) }
        await(until)
        shot(name)
    }

    @Test fun managerScreens() {
        show(Screen.Trips, "admin")
        await("شاحنة ٧")
        await("طلب إلغاء أو تعديل فاتورة بانتظار الاعتماد")
        // The fixture's campaigns are dated 2026-11-14; show that day whatever today is.
        pickDate(2026, 11, 14)
        await("حملة 2 · أحمد باوزير")
        compose.onNodeWithText("حملة مفتوحة بتاريخ 2026-11-12 · ياسر بن شملان").assertExists()
        compose.onNodeWithText("الموزع المختار لديه حملة مفتوحة بتاريخ 2026-11-14 على شاحنة أخرى").assertExists()
        shot("sup-trips")
        // Two days earlier: that day's campaign, and every later one still open.
        pickDate(2026, 11, 12)
        await("حملة 1 · ياسر بن شملان")
        compose.onNodeWithText("حملة مفتوحة بتاريخ 2026-11-14 · أحمد باوزير").assertExists()
        shot("sup-trips-other-day")

        go(Screen.Trip("sup-trip-b"), "sup-trip", "الفواتير (3)")
        compose.onNodeWithText("عرض خط السير").assertExists()
        compose.onNodeWithText("حفظ تحميل إضافي").assertExists()
        compose.onAllNodesWithText("مرتجع").onFirst().performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("3")
        blur()
        compose.onNodeWithText("حفظ مرتجع").assertExists()
        shot("sup-trip-return")

        go(Screen.Trip("sup-trip-a"), "sup-trip-closed", "الفواتير (2)")
        compose.onNodeWithText("الحملة مغلقة نهائياً — لا يمكن تعديلها أو فتحها مجدداً.").assertExists()
        go(Screen.Trip("sup-trip-d"), "sup-trip-pending", "يوجد 2 صنف بانتظار اعتماد الموزع.")
        go(Screen.Trip("sup-trip-c"), "sup-trip-flagged", "أُعيد الإغلاق للموزع للتصحيح")

        go(Screen.Closings, "sup-closings", "شاحنة ٥ — 2026-11-12")
        compose.onNodeWithText("ملاحظة المراجعة: أعد عدّ النقد وأرفق إيصال الإيداع").assertExists()

        go(Screen.InvoiceChanges, "sup-changes", "سوبرماركت الهدى")
        compose.onNodeWithText("عرض إيصال الإيداع").assertExists()
        compose.onNodeWithText("فتح الفاتورة البديلة", substring = true).assertExists()
    }

    /** The browser's confirm() and prompt() are system dialogs here; there is no web picture of them. */
    @Test fun reviewDialogs() {
        show(Screen.InvoiceChanges, "admin")
        await("سوبرماركت الهدى")
        compose.onAllNodesWithText("اعتماد").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("اعتماد الطلب يلغي الفاتورة الحالية نهائياً. متابعة؟").assertExists()
        shotDialog("sup-changes-approve-dialog")
        compose.onNodeWithText("إلغاء").performClick()
        compose.waitForIdle()
        val approve = ShadowDialog.getLatestDialog()
        shotPrompt("sup-changes-reject-dialog", approve) { compose.onAllNodesWithText("رفض").onFirst().performClick() }

        compose.runOnUiThread { nav.home(Screen.Closings) }
        await("شاحنة ٥ — 2026-11-12")
        val reject = ShadowDialog.getLatestDialog()
        shotPrompt("sup-closings-flag-dialog", reject) { compose.onAllNodesWithText("يحتاج مراجعة").onFirst().performClick() }
    }

    /**
     * A prompt's field takes the focus, and its blinking cursor never lets Compose settle
     * here: step the clock by hand, capture the dialog without waiting for idle, then leave
     * it with Back so the screen's own dismissal runs.
     */
    private fun shotPrompt(name: String, previous: android.app.Dialog?, open: () -> Unit) {
        compose.mainClock.autoAdvance = false
        open()
        repeat(6) { compose.mainClock.advanceTimeBy(32); pump(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }
        val dialog = ShadowDialog.getLatestDialog()
        check(dialog != null && dialog !== previous && dialog.isShowing) { "the prompt did not open" }
        val window = dialog.window!!
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, {}, Handler(Looper.getMainLooper()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnUiThread { (dialog as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        repeat(3) { compose.mainClock.advanceTimeBy(32); pump() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun supervisorSeesTheNoticeAndClosings() {
        show(Screen.Closings, "supervisor")
        await("شاحنة ٥ — 2026-11-12")
        await("طلب إلغاء أو تعديل فاتورة بانتظار الاعتماد")
        shot("sup-closings-supervisor")
    }

    @Test fun distributorRequestScreens() {
        show(Screen.InvoiceChanges)
        await("سوبرماركت الهدى")
        compose.onAllNodesWithText("اعتماد").assertCountEquals(0)
        shot("sup-changes-dist")

        go(Screen.InvoiceChange("s0", "cancel"), "sup-change-cancel", "إرسال طلب الإلغاء")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("الفاتورة سُجلت على عميل خطأ")
        blur()
        shot("sup-change-cancel-filled")

        go(Screen.InvoiceChange("s0", "replace"), "sup-change-replace", "إرسال طلب التعديل")
        val plus = compose.onAllNodesWithContentDescription("زيادة")
        plus[0].performClick(); plus[0].performClick(); plus[1].performClick()
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("3000")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("الكمية الصحيحة ١٢ وليست ١٠")
        blur()
        shot("sup-change-replace-filled")
    }
}
