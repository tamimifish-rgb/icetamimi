package com.tamimi.naqiice

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tamimi.naqiice.core.num
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.ui.Photos
import com.tamimi.naqiice.ui.Screen
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Closing with the handover choice: cash, deposit or transfer, or both. */
class ClosingHandoverScreenTest : ScreenHarness() {
    @Before fun handoverOn() { Fixture.handover = true }

    private fun ops(kind: String) = repo.store.outboxFor(FX_OWNER).filter { it.kind == kind }

    private fun send() {
        compose.onNodeWithText("إرسال الإغلاق").performScrollTo().performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTextExists("موافق")) { compose.onNodeWithText("موافق").performClick(); compose.waitForIdle() }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun closingTransferNeedsSlip() {
        show(Screen.Closing)
        compose.onNodeWithText("النقد المعدود فعلياً").performScrollTo().performTextInput("9000")
        compose.onNodeWithText("إيداع/حوالة").performScrollTo().performClick()
        compose.waitForIdle()
        shot("closing-transfer")
        send()
        assertEquals("أرفق صورة أو ملف إشعار الإيداع", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue(ops("closing").isEmpty())
        assertTrue(ops("closing_handover").isEmpty())
        assertTrue(Fixture.writes.none { "rpc/submit_closing" in it || "pureice_save_closing_handover" in it })
    }

    @Test fun closingCashQueuesHandover() {
        show(Screen.Closing)
        compose.onNodeWithText("النقد المعدود فعلياً").performScrollTo().performTextInput("9000")
        send()
        compose.waitUntil(30_000) { pump(); ops("closing_handover").isNotEmpty() }
        val closing = ops("closing").single()
        val handover = ops("closing_handover").single()
        assertEquals(closing.id, handover.payload.str("closing_id"))
        assertEquals(9000.0, handover.payload.num("cash_amount"), 0.0)
        assertEquals(0.0, handover.payload.num("transfer_amount"), 0.0)
        // The fixture has no submit_closing, so the closing cannot upload, and its handover waits for it.
        assertTrue(Fixture.writes.none { "pureice_save_closing_handover" in it })
    }

    private fun pdfOfSize(bytes: Int): android.net.Uri {
        val file = File(app.cacheDir, "slip-${bytes}.pdf")
        file.writeBytes(ByteArray(bytes) { if (it < 5) "%PDF-"[it].code.toByte() else 0 })
        return android.net.Uri.fromFile(file)
    }

    @Test fun pdfIsCopiedAsIs() {
        val source = pdfOfSize(2048)
        val result = runBlocking { Photos.importPdf(app, source) }
        val saved = (result as Photos.Imported.Saved).path
        assertTrue(saved.endsWith(".pdf"))
        assertTrue(File(saved).readBytes().contentEquals(File(source.path!!).readBytes()))
    }

    @Test fun pdfOver10MbIsRefused() {
        val result = runBlocking { Photos.importPdf(app, pdfOfSize(10 * 1024 * 1024 + 1)) }
        assertEquals(Photos.Imported.TooLarge, result)
    }
}
