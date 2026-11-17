package app.notify.android.remote

import java.util.UUID

data class RequestInbox(
    val generation: Long = 0,
    val connected: Boolean = false,
    val cards: List<RequestCard> = emptyList(),
    val selectedTicket: String? = null,
    private val receivedAt: Long? = null,
    private val deliveries: Map<RequestIdentity, Delivery> = emptyMap(),
) {
    val visibleCards get() = cards.filter { it.delivery != Delivery.Acknowledged }

    fun beginConnection() = copy(
        generation = generation + 1, connected = false, cards = emptyList(),
        selectedTicket = null, receivedAt = null,
        deliveries = disconnectedDeliveries(),
    )

    private fun disconnectedDeliveries() = deliveries.mapValues { (_, value) ->
        if (value is Delivery.Pending) Delivery.Unknown else value
    }

    fun receive(
        generation: Long, connected: Boolean, requests: List<RemoteRequest>, now: Long,
    ): RequestInbox {
        if (generation != this.generation) return this
        require(now >= 0 && (receivedAt == null || now >= receivedAt)) { "Invalid snapshot clock." }
        require(requests.size <= 100 && requests.map { it.ticket }.distinct().size == requests.size) {
            "Unsupported request snapshot."
        }
        if (!connected) return copy(
            connected = false, receivedAt = null, cards = emptyList(),
            selectedTicket = null, deliveries = disconnectedDeliveries(),
        )
        val present = requests.map { it.identity }.toSet()
        val retained = deliveries.filterKeys { it in present }
        val previous = cards.associateBy { it.request.identity }
        val updated = requests.map { request ->
            val old = previous[request.identity]
            if (old?.request == request) old
            else RequestCard(request, delivery = retained[request.identity] ?: Delivery.Ready)
        }
        val visible = updated.filter { it.delivery != Delivery.Acknowledged }
        return copy(
            connected = true, receivedAt = now, cards = updated, deliveries = retained,
            selectedTicket = selectedTicket?.takeIf { ticket -> visible.any { it.request.ticket == ticket } }
                ?: visible.firstOrNull()?.request?.ticket,
        )
    }

    fun isLive(now: Long): Boolean =
        connected && receivedAt != null && now >= receivedAt && now - receivedAt < 10_000

    fun select(ticket: String): RequestInbox {
        require(visibleCards.any { it.request.ticket == ticket }) { "This request is no longer available." }
        return copy(selectedTicket = ticket)
    }

    fun edit(ticket: String, questionID: String, draft: AnswerDraft): RequestInbox {
        val card = editable(ticket)
        val question = card.request.prompt.questions.singleOrNull { it.id == questionID }
            ?: throw IllegalArgumentException("This question is no longer available.")
        require(draft.custom.length <= 65_535) { "The answer is too long." }
        require(question.allowFreeform || draft.custom.isBlank()) { "This question only accepts its listed choices." }
        require(draft.selected.all { selected -> question.options.any { it.label == selected } }) {
            "The selected option is no longer offered."
        }
        require(question.multiple || (draft.selected.size <= 1 && (draft.selected.isEmpty() || draft.custom.isBlank()))) {
            "Choose one answer."
        }
        val updated = card.copy(answers = card.answers + (questionID to draft.copy(selected = draft.selected.toSet())))
        return copy(cards = cards.map { if (it === card) updated else it })
    }

    private fun editable(ticket: String): RequestCard {
        val card = cards.singleOrNull { it.request.ticket == ticket }
            ?: throw IllegalStateException("The request changed. Review the latest request.")
        check(card.delivery == Delivery.Ready && !card.request.sending) {
            "A reply is already pending or its result is unknown. Check your Mac; nothing was resent."
        }
        return card
    }

    fun prepare(ticket: String, decision: ReplyDecision, now: Long, feedback: String = ""): PreparedReply {
        check(isLive(now)) { "Reconnect and wait for a fresh Mac update before replying." }
        val card = editable(ticket)
        val request = card.request
        val prompt = request.prompt
        require(prompt.kind in setOf("permission", "question")) { "This request type is not supported." }
        require(feedback.length <= 4_000 && (feedback.isBlank() || (decision == ReplyDecision.Deny && prompt.supportsFeedback))) {
            "This request does not accept that denial feedback."
        }
        var permissionOptionID: String? = null
        val answers = linkedMapOf<String, List<String>>()
        val wireDecision = when (decision) {
            ReplyDecision.Deny -> "deny"
            ReplyDecision.AllowOnce -> {
                require(prompt.kind == "permission") { "This request needs an answer." }
                "allow"
            }
            is ReplyDecision.Permission -> {
                require(prompt.kind == "permission" && prompt.permissionOptions.any { it.id == decision.id }) {
                    "This permission scope is not offered."
                }
                permissionOptionID = decision.id
                "allow"
            }
            ReplyDecision.Answer -> {
                require(prompt.kind == "question" && prompt.questions.isNotEmpty()) { "This request does not accept answers." }
                for (question in prompt.questions) {
                    val draft = card.answers[question.id] ?: AnswerDraft()
                    val values = (question.options.map { it.label }.filter { it in draft.selected }
                        + listOfNotNull(draft.custom.takeIf { it.isNotBlank() })).distinct()
                    val minimum = if (question.multiple) question.minimumSelections else 1
                    require((!question.required && values.isEmpty()) ||
                        (values.size >= minimum && (question.multiple || values.size == 1))) {
                        "Review \"${question.text}\": ${if (question.multiple) "choose at least $minimum" else "choose one answer"}."
                    }
                    answers[question.id] = values
                }
                "answer"
            }
        }
        val command = ReplyCommand(
            UUID.randomUUID().toString(), request.sessionID, prompt.id, ticket, wireDecision,
            answers, permissionOptionID, feedback.takeIf { it.isNotBlank() },
        )
        val delivery = Delivery.Pending(command.id)
        return PreparedReply(
            copy(
                cards = cards.map { if (it === card) it.copy(answers = emptyMap(), delivery = delivery) else it },
                deliveries = deliveries + (request.identity to delivery),
            ),
            command,
        )
    }

    fun acknowledge(generation: Long, commandID: String): RequestInbox =
        finish(generation, commandID, Delivery.Acknowledged)

    fun uncertain(generation: Long, commandID: String): RequestInbox =
        finish(generation, commandID, Delivery.Unknown)

    private fun finish(generation: Long, commandID: String, delivery: Delivery): RequestInbox {
        if (generation != this.generation) return this
        val entry = deliveries.entries.singleOrNull { it.value == Delivery.Pending(commandID) } ?: return this
        val updated = cards.map { if (it.request.identity == entry.key) it.copy(delivery = delivery) else it }
        val visible = updated.filter { it.delivery != Delivery.Acknowledged }
        return copy(
            cards = updated, deliveries = deliveries + (entry.key to delivery),
            selectedTicket = selectedTicket?.takeIf { ticket -> visible.any { it.request.ticket == ticket } }
                ?: visible.firstOrNull()?.request?.ticket,
        )
    }
}
