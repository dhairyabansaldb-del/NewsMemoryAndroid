package com.dhairya.newsmemory.capture

import com.dhairya.newsmemory.capture.CapturePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePolicyTest {

    private val allowlist = setOf("com.news.a", "com.news.b")

    private fun decide(
        pkg: String = "com.news.a",
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
        title: String? = "Headline",
        body: String? = "Body"
    ) = CapturePolicy.decide(pkg, allowlist, isOngoing, isGroupSummary, title, body)

    @Test
    fun `allowlisted normal notification is captured`() {
        assertEquals(Decision.Capture, decide())
    }

    @Test
    fun `non-allowlisted package is rejected before anything else`() {
        // Even an otherwise-capturable notification from app C never passes the gate
        assertEquals(Decision.NotAllowlisted, decide(pkg = "com.bank.otp"))
        // Gate fires even when every other rule would also reject — allowlist is checked first
        assertEquals(
            Decision.NotAllowlisted,
            CapturePolicy.decide("com.bank.otp", allowlist, true, true, null, null)
        )
    }

    @Test
    fun `ongoing notifications are skipped`() {
        assertEquals(Decision.Ongoing, decide(isOngoing = true))
    }

    @Test
    fun `group summaries are skipped to avoid double-capture`() {
        assertEquals(Decision.GroupSummary, decide(isGroupSummary = true))
    }

    @Test
    fun `blank title and body is skipped`() {
        assertEquals(Decision.Blank, decide(title = null, body = null))
        assertEquals(Decision.Blank, decide(title = "  ", body = ""))
    }

    @Test
    fun `title-only and body-only both capture`() {
        assertEquals(Decision.Capture, decide(title = "Headline", body = null))
        assertEquals(Decision.Capture, decide(title = null, body = "Body text"))
    }
}
