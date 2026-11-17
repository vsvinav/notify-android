package app.notify.android.remote

data class QuestionOption(val label: String, val description: String = "")
data class PermissionOption(val id: String, val label: String, val detail: String = "")
data class Question(
    val id: String,
    val text: String,
    val multiple: Boolean,
    val allowFreeform: Boolean,
    val options: List<QuestionOption>,
    val required: Boolean = true,
    val minimumSelections: Int = 1,
)
data class Prompt(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val questions: List<Question> = emptyList(),
    val permissionOptions: List<PermissionOption> = emptyList(),
    val supportsFeedback: Boolean = false,
)
data class ContextField(
    val label: String,
    val value: String,
    val presentation: String = "text",
    val target: String = "",
)
data class RequestIdentity(val sessionID: String, val requestID: String, val ticket: String)
data class RemoteRequest(
    val ticket: String,
    val sessionID: String,
    val sessionName: String,
    val prompt: Prompt,
    val agent: String = "",
    val project: String = "",
    val fields: List<ContextField> = emptyList(),
    val sending: Boolean = false,
) {
    val identity get() = RequestIdentity(sessionID, prompt.id, ticket)
}
data class AnswerDraft(val selected: Set<String> = emptySet(), val custom: String = "")

sealed interface ReplyDecision {
    data object AllowOnce : ReplyDecision
    data object Deny : ReplyDecision
    data object Answer : ReplyDecision
    data class Permission(val id: String) : ReplyDecision
}
sealed interface Delivery {
    data object Ready : Delivery
    data class Pending(val commandID: String) : Delivery
    data object Unknown : Delivery
    data object Acknowledged : Delivery
}
data class RequestCard(
    val request: RemoteRequest,
    val answers: Map<String, AnswerDraft> = emptyMap(),
    val delivery: Delivery = Delivery.Ready,
)
data class ReplyCommand(
    val id: String,
    val sessionID: String,
    val requestID: String,
    val ticket: String,
    val decision: String,
    val answers: Map<String, List<String>>,
    val permissionOptionID: String? = null,
    val feedback: String? = null,
)
data class PreparedReply(val inbox: RequestInbox, val command: ReplyCommand)
