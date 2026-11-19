package com.tamimi.naqiice

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tamimi.naqiice.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Test

/** An assistant distributor sells from his own phone on another distributor's campaign. */
class AssistantScreensTest : ScreenHarness() {
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { pump(); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun absent(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()

    @Test fun assistantCannotCloseOrApprove() {
        Fixture.assistant = "assistant"
        show(Screen.Today)
        waitFor("أنت مساعد في هذه الحملة")
        shot("today-assistant")
        // The campaign has a load waiting; approving it is the main distributor's.
        assertTrue(absent("اعتماد التحميل"))
        assertTrue(absent("إغلاق الحملة"))
        // He hands his cash to the main distributor: the banner shows his own share.
        waitFor("سلّم النقد للموزع الرئيسي")
        waitFor("خط السير يسجله جوال الموزع الرئيسي")
        assertTrue(absent("بانتظار الموقع"))
        open(Screen.Closing, "closing-assistant")
        waitFor("الإغلاق يرسله الموزع الرئيسي")
        assertTrue(absent("إرسال الإغلاق"))
        // Selling is open to him.
        open(Screen.Sale(), "sale-assistant")
        compose.waitForIdle()
        assertTrue(absent("تحتاج حملة معينة لك"))
    }

    @Test fun mainDistributorIsToldTheExpectedCashIncludesHisAssistant() {
        Fixture.assistant = "main"
        show(Screen.Today)
        waitFor("يشمل النقد المتوقع مبيعات المساعد")
        waitFor("اعتماد التحميل")
        assertTrue(absent("أنت مساعد في هذه الحملة"))
    }

    @Test fun supervisorAddsAssistant() {
        Fixture.assistant = "assistant"
        show(Screen.Trip("sup-trip-b"), "supervisor")
        waitFor("المساعدون")
        waitFor("فهد الكثيري") // sup-d7 already assists this campaign
        shot("trip-assistants")
        compose.onNodeWithText("اختر الموزع المساعد").performScrollTo().performClick()
        compose.onAllNodesWithText("عمر باحشوان").onLast().performClick() // sup-d6 leads another open campaign, but the server decides
        compose.onNodeWithText("إضافة مساعد").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_set_trip_assistant" in it } }
        val call = Fixture.writes.first { "pureice_set_trip_assistant" in it }
        assertTrue(call, "\"p_trip\":\"sup-trip-b\"" in call && "\"p_user\":\"sup-d6\"" in call && "\"p_on\":true" in call)
    }

    @Test fun supervisorRemovesAssistant() {
        Fixture.assistant = "assistant"
        show(Screen.Trip("sup-trip-b"), "supervisor")
        waitFor("فهد الكثيري")
        compose.onNodeWithText("إزالة").performScrollTo().performClick()
        compose.waitUntil(30_000) { pump(); Fixture.writes.any { "pureice_set_trip_assistant" in it } }
        val call = Fixture.writes.first { "pureice_set_trip_assistant" in it }
        assertTrue(call, "\"p_user\":\"sup-d7\"" in call && "\"p_on\":false" in call)
    }

    @Test fun noAssistantSectionWithoutTheFeature() {
        show(Screen.Trip("sup-trip-b"), "supervisor")
        waitFor("ملف الموزع وحسابه")
        assertTrue(absent("المساعدون"))
    }
}
