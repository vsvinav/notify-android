package app.notify.android.controller

import app.notify.android.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanonicalAttentionTest {
    private val first = RemoteRequest("t1", "s1", "Orchard", Prompt("r1", "permission", "Review first"))
    private val second = RemoteRequest("t2", "s2", "Garden", Prompt("r2", "permission", "Review second"))
    private fun item(r: RemoteRequest) = AttentionItem(r.sessionID, r.prompt.id, r.sessionName, r.prompt.title)
    private val epoch = "11111111-1111-4111-8111-111111111111"
    private fun projection(r: RemoteRequest? = first, revision: Long = 1, count: Int = 1, source: String = epoch) =
        AttentionProjection(source, revision, r?.let(::item), if (r == null) 0 else count)
    private fun initial() = AttentionState().receive(projection(), listOf(first, second))
    private val account = Account("issuer", "owner")
    private val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
    private fun controller() = ControllerState(account = account, relationship = pairing, foreground = true,
        status = RemoteStatus("connected", "Connected")).receiveRequests(
            RequestSnapshot(true, listOf(first, second), projection(second)), 100)

    @Test fun realSyntheticSwiftAndHelperStateParsesAndNavigatesWithoutReply() {
        val path = System.getenv("NOTIFY_ATTENTION_STATE_FIXTURE")
        org.junit.Assume.assumeTrue("Run with the generated synthetic Swift/helper fixture", path != null)
        val parsed = Snapshots.requests(JSONObject(java.io.File(path!!).readText()))
        assertEquals(2, parsed.attention!!.additionalCount)
        assertEquals("first", parsed.attention.current!!.requestID)
        assertEquals(3, parsed.requests.size)
        val state = ControllerState(account = account, relationship = pairing, foreground = true,
            status = RemoteStatus("connected", "Synthetic")).receiveRequests(parsed, 100)
        assertEquals("first", state.attentionCard!!.request.prompt.id)
        val opened = state.openAttention()
        assertEquals("orchard", opened.workspace.sessionID)
        assertEquals(state.attentionCard!!.request.ticket, opened.inbox.selectedTicket)
        assertTrue(opened.inbox.cards.all { it.delivery == Delivery.Ready })
    }

    @Test fun exactNavigationChoosesCorrectDecisionLocallyWithoutSendingOrLosingDrafts() {
        val question = second.copy(prompt = Prompt("r2", "question", "Choose", questions =
            listOf(Question("q", "Where?", false, true, emptyList()))))
        val state = controller().receiveRequests(RequestSnapshot(true, listOf(first, question), projection(question, 2)), 101)
        val edited = state.copy(inbox = state.inbox.edit("t2", "q", AnswerDraft(custom = "keep exactly")))
        val opened = edited.openAttention()
        assertEquals("t2", opened.inbox.selectedTicket)
        assertEquals("s2", opened.workspace.sessionID)
        assertEquals(edited.decisionFocus + 1, opened.decisionFocus)
        assertEquals("keep exactly", opened.inbox.cards.last().answers["q"]?.custom)
        assertTrue(opened.inbox.cards.all { it.delivery == Delivery.Ready })
        assertEquals(opened.decisionFocus + 1, opened.openAttention().decisionFocus)
    }

    @Test fun pendingAndAckNeverInventCanonicalAdvancement() {
        val state = controller()
        val prepared = state.inbox.prepare("t2", ReplyDecision.Deny, 101)
        val sending = state.copy(inbox = prepared.inbox)
        assertEquals(item(second), sending.visibleAttention.current)
        assertEquals(item(second), sending.copy(inbox =
            prepared.inbox.acknowledge(prepared.inbox.generation, prepared.command.id)).visibleAttention.current)
        val canonical = sending.receiveRequests(RequestSnapshot(true, listOf(first), projection(first, 2, 0)), 102)
        assertEquals(item(first), canonical.visibleAttention.current)
        assertEquals(Delivery.Ready, canonical.inbox.cards.single().delivery)
    }

    @Test fun missingObservationDisablesExactNavigationButRetainsIdentityUntilCanonicalEmpty() {
        val lost = controller().receiveRequests(RequestSnapshot(true, emptyList(), projection(second)), 101)
        assertEquals(item(second), lost.visibleAttention.current)
        assertNull(lost.attentionCard)
        assertSame(lost, lost.openAttention())
        val malformed = lost.receiveRequests(RequestSnapshot(true, emptyList()), 102)
        assertEquals(item(second), malformed.visibleAttention.current)
        assertNull(malformed.visibleAttention.additionalCount)
        assertNull(malformed.receiveRequests(RequestSnapshot(true, emptyList(), projection(null, 2)), 103).visibleAttention.current)
    }

    @Test fun visibilityDisconnectAndStalenessDoNotClearButCannotNavigate() {
        val state = controller()
        for (blocked in listOf(state.visibility(false, 101), state.retireConnection(), state.staleSnapshots(),
            state.copy(now = 10_100), state.copy(refreshingVisibleState = true))) {
            assertEquals(item(second), blocked.visibleAttention.current)
            assertNull(blocked.attentionCard)
            assertSame(blocked, blocked.openAttention())
        }
        val hidden = state.visibility(false, 101)
        assertFalse(hidden.pollVisibleState)
        assertFalse(hidden.visibleAttention.fresh)
    }

    @Test fun accountAndPairingRetirementNeverShowPreviousPrivateSelection() {
        val state = controller()
        for (retired in listOf(state.endAccountSession(), state.copy(account = null),
            state.copy(account = Account("issuer", "other")), state.copy(relationship = null),
            state.copy(relationship = pairing.copy(macID = "other")),
            state.copy(relationship = pairing.copy(enrolled = false)))) {
            assertNull(retired.visibleAttention.current)
            assertNull(retired.attentionCard)
        }
        val replacement = state.copy(relationship = pairing.copy(macID = "other"))
            .receiveRequests(RequestSnapshot(true, emptyList(), projection(null)), 101)
        assertNull(replacement.visibleAttention.current)
        assertFalse(replacement.attention.handover)
    }

    @Test fun explicitFreshDecisionSelectionReleasesRestartFallbackButStaleSelectionCannot() {
        val state = controller().receiveRequests(RequestSnapshot(true, listOf(first),
            projection(first, source = "22222222-2222-4222-8222-222222222222")), 101)
        assertEquals(item(second), state.visibleAttention.current)
        assertTrue(state.visibleAttention.handover)
        assertEquals(item(second), state.staleSnapshots().selectDecision("t1").visibleAttention.current)
        val selected = state.selectDecision("t1")
        assertEquals(item(first), selected.visibleAttention.current)
        assertFalse(selected.visibleAttention.handover)
    }

    @Test fun canonicalCurrentAndCountDoNotFollowLocalOrderOrSending() {
        val state = AttentionState().receive(projection(second, count = 2), listOf(first, second))
        assertEquals(item(second), state.current)
        assertEquals(2, state.additionalCount)
        assertEquals(item(second), state.receive(projection(second, count = 2), listOf(second.copy(sending = true))).current)
    }

    @Test fun absenceAndAutoDispatchNeverResolveButCanonicalAdvanceAndEmptyDo() {
        val state = initial().receive(projection(), emptyList())
        assertEquals(item(first), state.current)
        val next = state.receive(projection(second, 2, 0), listOf(second))
        assertEquals(item(second), next.current)
        assertEquals(0, next.additionalCount)
        assertNull(next.receive(projection(null, 3), emptyList()).current)
    }

    @Test fun missingMalformedAndStaleSnapshotsPreserveOneUnknownNotClear() {
        for (next in listOf<AttentionProjection?>(null, projection(null, 0))) {
            val state = initial().receive(next, emptyList())
            assertEquals(item(first), state.current)
            assertFalse(state.fresh)
            assertNull(state.additionalCount)
        }
        val state = initial().stale()
        assertEquals(item(first), state.current)
        assertFalse(state.fresh)
        assertTrue(state.receive(projection(), listOf(first)).fresh)
    }

    @Test fun oldRevisionAndConflictingSameRevisionCannotChangeCurrent() {
        val state = initial().receive(projection(second, 4), listOf(second))
        for (old in listOf(projection(null, 3), projection(null, 4))) {
            val ignored = state.receive(old, emptyList())
            assertEquals(item(second), ignored.current)
            assertFalse(ignored.fresh)
        }
        assertTrue(state.receive(projection(second, 4), listOf(second)).fresh)
    }

    @Test fun explicitMacSelectionIsCanonicalButPhoneSelectionDoesNotReorderQueue() {
        val firstState = initial()
        assertEquals(item(first), firstState.select(second).current)
        assertEquals(item(second), firstState.receive(projection(second, 2), listOf(first, second)).current)
    }

    @Test fun restartEmptyOrDifferentCurrentDoesNotResolveIncumbent() {
        val otherEpoch = "22222222-2222-4222-8222-222222222222"
        var state = initial().receive(projection(null, source = otherEpoch), emptyList())
        assertEquals(item(first), state.current)
        assertTrue(state.handover)
        assertFalse(state.fresh)
        state = state.receive(projection(second, 2, 0, otherEpoch), listOf(second))
        assertEquals(item(first), state.current)
        assertEquals(item(first), state.select(first).current)
        val selected = state.select(second)
        assertEquals(item(second), selected.current)
        assertFalse(selected.handover)
        assertTrue(selected.fresh)
    }

    @Test fun restartCanRebindOnlyAfterSameCurrentIsActuallyObserved() {
        val next = projection(source = "22222222-2222-4222-8222-222222222222")
        val missing = initial().receive(next, emptyList())
        assertTrue(missing.handover)
        assertFalse(missing.fresh)
        val confirmed = missing.receive(next, listOf(first))
        assertFalse(confirmed.handover)
        assertTrue(confirmed.fresh)
        assertEquals(item(first), confirmed.current)
    }

    @Test fun parsingStrictOptionalProjectionDoesNotBreakLegacyRequests() {
        val raw = """{"version":1,"epoch":"$epoch","revision":1,"current":{"sessionID":"s1","requestID":"r1","sessionName":"Orchard","requestTitle":"Review first"},"additionalCount":1}"""
        val wire = JSONObject("""{"connected":true,"requests":[],"attention":$raw}""")
        assertEquals(projection(), Snapshots.requests(wire).attention)
        for ((key, invalid) in listOf("version" to 2, "revision" to "1", "revision" to 0,
            "revision" to 9_007_199_254_740_992L, "epoch" to "bad", "additionalCount" to 257,
            "current" to JSONObject.NULL)) {
            wire.put("attention", JSONObject(raw).put(key, invalid))
            assertNull(Snapshots.requests(wire).attention)
            assertTrue(Snapshots.requests(wire).connected)
        }
        for ((key, invalid) in listOf("sessionID" to "x".repeat(129), "requestID" to "bad\u0000id",
            "sessionName" to "x".repeat(257), "requestTitle" to "x".repeat(301))) {
            val a = JSONObject(raw)
            a.getJSONObject("current").put(key, invalid)
            assertNull(Snapshots.requests(wire.put("attention", a)).attention)
        }
        wire.remove("attention")
        assertNull(Snapshots.requests(wire).attention)
        wire.put("attention", JSONObject(raw).put("current", JSONObject.NULL).put("additionalCount", 0))
        assertNull(Snapshots.requests(wire).attention!!.current)
    }
}
