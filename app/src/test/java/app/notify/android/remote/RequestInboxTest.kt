package app.notify.android.remote

import org.junit.Assert.*
import org.junit.Test

class RequestInboxTest {
    private val question = Question(
        id = "choice", text = "Choose a destination", multiple = false,
        allowFreeform = true, options = listOf(QuestionOption("First"), QuestionOption("Second")),
    )
    private val request = RemoteRequest(
        ticket = "ticket-1", sessionID = "session-1", sessionName = "Notify",
        prompt = Prompt(id = "request-1", kind = "question", title = "Destination", questions = listOf(question)),
    )

    private fun inbox(vararg requests: RemoteRequest): RequestInbox =
        RequestInbox().beginConnection().receive(1, true, requests.toList(), 100)

    @Test fun equivalentRefreshPreservesSelectionAndCustomAnswer() {
        val other = request.copy(ticket = "ticket-2")
        val edited = inbox(request, other).select("ticket-2")
            .edit("ticket-2", "choice", AnswerDraft(custom = "my destination"))
        val refreshed = edited.receive(1, true, listOf(request.copy(), other.copy()), 200)
        assertEquals("ticket-2", refreshed.selectedTicket)
        assertEquals("my destination", refreshed.cards[1].answers["choice"]?.custom)
        assertSame(edited.cards[1], refreshed.cards[1])
    }

    @Test fun changedChoicesInvalidateTheOldAnswer() {
        val edited = inbox(request).edit("ticket-1", "choice", AnswerDraft(selected = setOf("First")))
        val changed = request.copy(prompt = request.prompt.copy(
            questions = listOf(question.copy(options = listOf(QuestionOption("Different")))),
        ))
        assertTrue(edited.receive(1, true, listOf(changed), 200).cards.single().answers.isEmpty())
    }

    @Test fun oldConnectionSnapshotCannotRestorePrivateData() {
        val reconnected = inbox(request).beginConnection()
        val stale = reconnected.receive(1, true, listOf(request), 200)
        assertSame(reconnected, stale)
        assertTrue(stale.cards.isEmpty())
        assertFalse(stale.connected)
    }

    @Test fun staleAndBackwardClockReadsCannotAuthorizeAReply() {
        val current = inbox(request)
        assertTrue(current.isLive(10_099))
        assertFalse(current.isLive(10_100))
        assertFalse(current.isLive(99))
        assertThrows(IllegalStateException::class.java) {
            current.prepare("ticket-1", ReplyDecision.Deny, 10_100)
        }
    }

    @Test fun submittedReplyRequiresExactAcknowledgmentAndNeverAutoRetriesAfterReconnect() {
        val prepared = inbox(request).prepare("ticket-1", ReplyDecision.Deny, 200)
        assertEquals(Delivery.Pending(prepared.command.id), prepared.inbox.cards.single().delivery)
        assertThrows(IllegalStateException::class.java) {
            prepared.inbox.prepare("ticket-1", ReplyDecision.Deny, 200)
        }
        assertSame(prepared.inbox, prepared.inbox.acknowledge(1, "wrong-id"))
        val reconnected = prepared.inbox.beginConnection().receive(2, true, listOf(request), 300)
        assertEquals(Delivery.Unknown, reconnected.cards.single().delivery)
        assertThrows(IllegalStateException::class.java) {
            reconnected.prepare("ticket-1", ReplyDecision.Deny, 300)
        }
        assertSame(reconnected, reconnected.acknowledge(1, prepared.command.id))
    }

    @Test fun acknowledgedRequestStaysDismissedUntilTheMacWithdrawsIt() {
        val prepared = inbox(request).prepare("ticket-1", ReplyDecision.Deny, 200)
        val acknowledged = prepared.inbox.acknowledge(1, prepared.command.id)
        assertTrue(acknowledged.visibleCards.isEmpty())
        assertTrue(acknowledged.receive(1, true, listOf(request.copy()), 300).visibleCards.isEmpty())
        assertTrue(acknowledged.receive(1, true, emptyList(), 300).cards.isEmpty())
    }

    @Test fun answerPreservesExactCustomTextAndAcceptsAnOmittedOptionalQuestion() {
        val optional = question.copy(id = "optional", required = false)
        val custom = "  Custom destination\nsecond line  "
        val current = inbox(request.copy(prompt = request.prompt.copy(questions = listOf(question, optional))))
            .edit("ticket-1", "choice", AnswerDraft(custom = custom))
        val prepared = current.prepare("ticket-1", ReplyDecision.Answer, 200)
        assertEquals(listOf(custom), prepared.command.answers["choice"])
        assertEquals(emptyList<String>(), prepared.command.answers["optional"])
        assertTrue(prepared.inbox.cards.single().answers.isEmpty())
    }

    @Test fun unofferedPermissionScopeAndCustomAnswersAreRejected() {
        val permission = request.copy(prompt = Prompt(id = "permission-1", kind = "permission", title = "Read a file"))
        assertThrows(IllegalArgumentException::class.java) {
            inbox(permission).prepare("ticket-1", ReplyDecision.Permission("invented"), 200)
        }
        val fixed = request.copy(prompt = request.prompt.copy(questions = listOf(question.copy(allowFreeform = false))))
        assertThrows(IllegalArgumentException::class.java) {
            inbox(fixed).edit("ticket-1", "choice", AnswerDraft(custom = "unoffered"))
        }
    }
}
