package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test

class AccountLifetimeTest {
    @Test fun expiredAccountClearsPrivateViewsButKeepsPairingAndDisablesAuthorization() {
        val account = Account("issuer", "subject")
        val record = Relationship(account, "mac", "pin", "device", "public", "Phone", true)
        val session = Session("s", "Build", "project", "copilot", "working", true, "t", Capabilities(true, true, true))
        val workspace = WorkspaceState(WorkspaceSnapshot(true, listOf(session),
            SessionDetail("s", "t", emptyList(), emptyList(), emptyList())), "s", receivedAt = 100).edit("private draft")
        val expired = ControllerState(account = account, relationship = record, workspace = workspace, foreground = true).endAccountSession()
        assertEquals(record, expired.relationship)
        assertNull(expired.account)
        assertTrue(expired.workspace.drafts.isEmpty())
        assertNull(expired.workspace.snapshot)
        assertFalse(expired.live)
    }
}
