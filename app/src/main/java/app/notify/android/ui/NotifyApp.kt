package app.notify.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.notify.android.controller.*
import app.notify.android.controller.Target
import app.notify.android.remote.*
import java.util.Base64
import kotlinx.coroutines.launch

private val light = lightColorScheme(
    primary = Color(0xFF765C3C), onPrimary = Color(0xFFFBF7F0),
    primaryContainer = Color(0xFFDCD0BE), onPrimaryContainer = Color(0xFF34302A),
    secondary = Color(0xFF60564B), onSecondary = Color(0xFFFBF7F0),
    secondaryContainer = Color(0xFFDCD0BE), onSecondaryContainer = Color(0xFF34302A),
    tertiary = Color(0xFF754718), onTertiary = Color(0xFFFBF7F0),
    tertiaryContainer = Color(0xFFDCD0BE), onTertiaryContainer = Color(0xFF754718),
    background = Color(0xFFEAE3D8), onBackground = Color(0xFF34302A),
    surface = Color(0xFFF3EEE5), onSurface = Color(0xFF34302A),
    surfaceVariant = Color(0xFFE7DFD2), onSurfaceVariant = Color(0xFF60564B),
    surfaceContainer = Color(0xFFEAE3D8), surfaceContainerLow = Color(0xFFE7DFD2),
    surfaceContainerHigh = Color(0xFFDCD0BE), surfaceContainerHighest = Color(0xFFDCD0BE),
    surfaceContainerLowest = Color(0xFFFBF7F0), surfaceTint = Color(0xFF765C3C),
    outline = Color(0xFF796B5A), outlineVariant = Color(0xFF796B5A).copy(alpha = .32f),
    error = Color(0xFF873B37), onError = Color(0xFFFBF7F0),
    errorContainer = Color(0xFFDCD0BE), onErrorContainer = Color(0xFF873B37),
    inverseSurface = Color(0xFF34302A), inverseOnSurface = Color(0xFFF3EEE5), inversePrimary = Color(0xFFCFAD7E),
)
private val dark = darkColorScheme(
    primary = Color(0xFFCFAD7E), onPrimary = Color(0xFF27221C),
    primaryContainer = Color(0xFF3C352C), onPrimaryContainer = Color(0xFFE9E0D2),
    secondary = Color(0xFFBFB2A0), onSecondary = Color(0xFF27221C),
    secondaryContainer = Color(0xFF3C352C), onSecondaryContainer = Color(0xFFE9E0D2),
    tertiary = Color(0xFFE1BB82), onTertiary = Color(0xFF27221C),
    tertiaryContainer = Color(0xFF3C352C), onTertiaryContainer = Color(0xFFE1BB82),
    background = Color(0xFF211F1C), onBackground = Color(0xFFE9E0D2),
    surface = Color(0xFF292622), onSurface = Color(0xFFE9E0D2),
    surfaceVariant = Color(0xFF24211E), onSurfaceVariant = Color(0xFFBFB2A0),
    surfaceContainer = Color(0xFF211F1C), surfaceContainerLow = Color(0xFF24211E),
    surfaceContainerHigh = Color(0xFF3C352C), surfaceContainerHighest = Color(0xFF3C352C),
    surfaceContainerLowest = Color(0xFF211F1C), surfaceTint = Color(0xFFCFAD7E),
    outline = Color(0xFFA18F78), outlineVariant = Color(0xFFA18F78).copy(alpha = .32f),
    error = Color(0xFFE4A294), onError = Color(0xFF27221C),
    errorContainer = Color(0xFF3C352C), onErrorContainer = Color(0xFFE4A294),
    inverseSurface = Color(0xFFE9E0D2), inverseOnSurface = Color(0xFF292622), inversePrimary = Color(0xFF765C3C),
)
internal class FeedPosition {
    val list = LazyListState()
    var following by mutableStateOf(true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyApp(controller: ControllerViewModel, activity: Activity) {
    val state by controller.state.collectAsState()
    val enableAlerts = rememberEnableAlerts(controller, activity)
    var destination by rememberSaveable { mutableStateOf(Destination.Chat) }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.Overview) }
    var settingsParent by rememberSaveable { mutableStateOf(SettingsSection.Overview) }
    val positions = remember(state.account, state.relationship) { mutableMapOf<Target, FeedPosition>() }
    val feedback = remember(state.account, state.relationship) { mutableStateMapOf<RemoteRequest, String>() }
    LaunchedEffect(state.inbox.cards) {
        feedback.keys.retainAll(state.inbox.cards.map { it.request }.toSet())
    }
    val route = controllerRoute(state)
    val back: () -> Unit = {
        when {
            state.notices != null -> controller.closeNotices()
            state.review != null || state.controlReview != null -> controller.cancelReview()
            route == ControllerRoute.Notifications -> controller.finishNotificationSetup()
            destination == Destination.Settings && settingsSection != SettingsSection.Overview -> {
                settingsSection = settingsParent
                settingsParent = SettingsSection.Overview
            }
            else -> destination = Destination.Chat
        }
    }
    val canGoBack = state.notices != null || route == ControllerRoute.Notifications || destination != Destination.Chat
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
        BackHandler(canGoBack || state.review != null || state.controlReview != null, back)
        val title = when {
            state.notices != null -> "Third-party notices"
            route == ControllerRoute.Notifications -> "Notifications"
            destination == Destination.Settings -> settingsSection.title
            route == ControllerRoute.Pairing -> "Connect your Mac"
            route == ControllerRoute.SignIn -> "Sign in"
            route == ControllerRoute.Trust -> "Mac approval"
            destination == Destination.Requests -> "Needs you"
            else -> state.workspace.session?.name ?: if (state.workspace.sessionID == null) "Notify" else "Session unavailable"
        }
        ChatShell(state, destination, title, canGoBack, back,
            onNavigate = { destination = it; settingsSection = SettingsSection.Overview; settingsParent = SettingsSection.Overview },
            onSession = { controller.selectSession(it); destination = Destination.Chat },
            onConnection = {
                if (route == ControllerRoute.Notifications) controller.finishNotificationSetup()
                destination = Destination.Settings
                settingsSection = SettingsSection.Connection
                settingsParent = SettingsSection.Overview
            },
        ) { openSessions ->
            Column(Modifier.fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 128.dp)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            Text(error, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = controller::clearError) { Text("Dismiss message") }
                        }
                    }
                }
                when {
                    state.notices != null -> Notices(state.notices!!, controller::closeNotices)
                    route == ControllerRoute.Notifications -> NotificationSetup(state, controller, enableAlerts)
                    destination == Destination.Settings -> Settings(state, controller, activity, enableAlerts,
                        settingsSection, { settingsParent = settingsSection; settingsSection = it })
                    route == ControllerRoute.Pairing -> PairingSetup(state, controller, activity)
                    route == ControllerRoute.SignIn -> SignIn(state, controller, activity)
                    route == ControllerRoute.Trust -> ConnectionSetup(state, controller)
                    destination == Destination.Requests -> Inbox(state, controller, feedback)
                    else -> Sessions(state, controller, positions, feedback, openSessions)
                }
            }
        }
        state.review?.let { review ->
            Confirmation("Confirm this exact reply", controller::cancelReview, controller::confirmReply, state.live && !state.busy) {
                Text(review.card.request.prompt.title, style = MaterialTheme.typography.titleMedium)
                Evidence("Session", review.preview.sessionID)
                Evidence("Request", review.preview.requestID)
                Evidence("Ticket", review.preview.ticket)
                Text(when (val decision = review.decision) {
                    ReplyDecision.AllowOnce -> "Allow once"
                    ReplyDecision.Deny -> "Deny"
                    ReplyDecision.Answer -> "Send answer"
                    is ReplyDecision.Permission -> review.card.request.prompt.permissionOptions.single { it.id == decision.id }.let { "${it.label}\n${it.detail}" }
                }, style = MaterialTheme.typography.titleMedium)
                review.preview.answers.forEach { (id, answers) ->
                    Evidence(review.card.request.prompt.questions.single { it.id == id }.text,
                        answers.joinToString("\n").ifEmpty { "No answer (optional)" })
                }
                review.preview.feedback?.let { Evidence("Denial feedback", it) }
            }
        }
        state.controlReview?.let { command ->
            Confirmation(if (command.isPolicy) "Enable Dangerous Auto-yes?" else if (command.action == "stop") "Stop this exact target?" else "Interrupt the main agent?",
                controller::cancelReview, controller::confirmControl, state.live && !state.busy,
                confirmLabel = if (command.isPolicy) "Enable dangerous mode" else "Confirm") {
                Evidence("Session", command.sessionID)
                Evidence("Ticket", command.ticket)
                Evidence("Target", if (command.agentID.isEmpty()) "Main agent" else command.agentID)
                Text(if (command.isPolicy) "Currently waiting eligible tools may immediately be approved. Ongoing eligible tools may also be approved, including destructive work. Questions stay manual.\n\nThis applies only to this session. It stays enabled after your PHONE disconnects until manually disabled or the session stops. Provider disconnects and errors still turn it off.\n\nThis replaces normal session Auto-yes; the two modes cannot be enabled together."
                    else if (command.action == "interrupt") "Stop the current main-agent turn and send the instruction below instead."
                    else if (command.agentID.isEmpty()) "Stop the current main-agent turn. This does not delete the session."
                    else "Stop only this selected subagent. Its descendants may also stop. Other agents are not selected.")
                if (command.text.isNotEmpty()) Evidence("Exact instruction", command.text)
            }
        }
    }
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable internal fun Heading(text: String) { Text(text, style = MaterialTheme.typography.headlineSmall) }
@Composable internal fun Supporting(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(label) }
}
@Composable internal fun Evidence(label: String, value: String, code: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (code) FontFamily.Monospace else FontFamily.Default) }
    }
}

@Composable
private fun SignIn(state: ControllerState, controller: ControllerViewModel, activity: Activity) {
    var name by remember { mutableStateOf("") }
    Page {
        Heading("Sign in to Notify")
        Supporting("Use your account passkey. Signing in does not grant access to a Mac; pairing and local Mac approval are separate.")
        if (state.relationship != null) Supporting("Your Mac pairing is saved on this phone. Sign in to its account to reconnect.")
        if (state.account != null && state.account != state.relationship?.account) {
            Supporting("This account does not match your saved pairing. Sign out before signing in to the paired account.")
            OutlinedButton(onClick = controller::signOut, enabled = !state.busy) { Text("Sign out · keep pairing") }
        }
        Action("Sign in with a passkey", !state.busy) { controller.signIn(activity, false, "") }
        HorizontalDivider()
        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("New account name") },
            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { controller.signIn(activity, true, name) },
            enabled = !state.busy && name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Create account with a passkey") }
        Supporting("Native passkeys need this signed app to be trusted by the Notify service. If verification is unavailable, keep the saved pairing and retry after service setup.")
    }
}

@Composable
private fun ConnectionSetup(state: ControllerState, controller: ControllerViewModel) {
    var recover by remember { mutableStateOf(false) }
    Page {
        val record = state.relationship ?: return@Page
        Heading("Compare on your Mac")
        PublicKeys(record)
        Supporting("Approve on the Mac only when every character of the phone public key matches. A name or short prefix is not proof.")
        Supporting(state.status.message)
        OutlinedButton(onClick = controller::retry, enabled = !state.busy && record.enrolled) { Text("Retry connection") }
        Supporting("You can explicitly forget a pairing in Settings. A pending enrollment is not Mac approval.")
        if (!record.enrolled) OutlinedButton(onClick = { recover = true }, enabled = !state.busy) { Text("Recover with a fresh QR") }
    }
    if (recover) Confirmation("Replace this unconfirmed pairing?", { recover = false },
        { recover = false; controller.forget() }, !state.busy) {
        Text("The previous enrollment may have reached your Mac. Reject or revoke that phone there first. This deletes its saved phone key; it does not revoke access on the Mac.")
        Text("Then request a fresh QR on the Mac, scan it and compare the new complete public key. Nothing is automatically resent.")
    }
}
@Composable internal fun PublicKeys(record: Relationship) {
    Evidence("Phone · ${record.name}", Base64.getEncoder().encodeToString(decodeKey(record.publicKey)), true)
    Supporting("Complete phone key: 88 characters including the final =.")
    Evidence("Mac ID", record.macID)
    Evidence("Pinned Mac public key", record.macPublicKey, true)
}

@Composable
private fun Inbox(state: ControllerState, controller: ControllerViewModel, feedback: MutableMap<RemoteRequest, String>,
    sessionID: String? = null) {
    val cards = decisionCards(state, sessionID)
    val card = cards.singleOrNull { it.request.ticket == state.inbox.selectedTicket } ?: cards.firstOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val headerHeight = maxHeight * .35f
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.heightIn(max = headerHeight).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.live) {
                Supporting("${state.status.message} Replies are disabled until a fresh Mac update.")
                OutlinedButton(onClick = controller::retry, enabled = !state.busy) { Text("Retry connection") }
            }
            if (cards.size > 1) Picker("Review request", cards.map { it.request.ticket to "${it.request.sessionName} · ${it.request.prompt.title}" },
                card?.request?.ticket ?: "", controller::selectRequest)
        }
        if (card == null) {
            Text(if (state.live) if (sessionID == null) "No waiting requests reported by your Mac."
                else "No waiting decisions for this session. Other sessions’ requests are in the sessions menu."
                else "Waiting for a fresh request snapshot.",
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        } else key(card.request) {
            RequestReview(card, state.live && !state.busy && card.delivery == Delivery.Ready && !card.request.sending, controller,
                feedback[card.request].orEmpty(), { feedback[card.request] = it })
        }
    }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestReview(card: RequestCard, enabled: Boolean, controller: ControllerViewModel,
    feedback: String, onFeedback: (String) -> Unit) {
    val r = card.request
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Supporting("${r.sessionName} · ${r.agent} · ${r.project}")
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(r.prompt.title, style = MaterialTheme.typography.titleLarge) }
            if (r.prompt.detail.isNotEmpty()) { Spacer(Modifier.height(12.dp)); SelectionContainer { Text(r.prompt.detail) } }
        }
        items(r.fields) { field -> Evidence(listOf(field.target, field.label).filter { it.isNotEmpty() }.joinToString(" · "),
            field.value, field.presentation in setOf("code", "diff") || field.target.isNotEmpty()) }
        items(r.prompt.questions, key = { it.id }) { question ->
            val draft = card.answers[question.id] ?: AnswerDraft()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question.text, style = MaterialTheme.typography.titleMedium)
                Supporting("${if (question.multiple) "Choose at least ${question.minimumSelections}" else "Choose one"} · ${if (question.required) "Required" else "Optional"}")
                question.options.forEach { option ->
                    val selected = option.label in draft.selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, enabled = enabled, role = if (question.multiple) Role.Checkbox else Role.RadioButton) {
                            val choices = if (question.multiple) {
                                if (selected) draft.selected - option.label else draft.selected + option.label
                            } else setOf(option.label)
                            controller.answer(r.ticket, question.id, draft.copy(selected = choices, custom = if (question.multiple) draft.custom else ""))
                        }, verticalAlignment = Alignment.CenterVertically) {
                        if (question.multiple) Checkbox(selected, null, enabled = enabled) else RadioButton(selected, null, enabled = enabled)
                        Column(Modifier.weight(1f).padding(8.dp)) {
                            Text(option.label)
                            if (option.description.isNotEmpty()) Supporting(option.description)
                        }
                    }
                }
                if (question.allowFreeform) OutlinedTextField(draft.custom, {
                    if (it.length <= 65_535) controller.answer(r.ticket, question.id,
                        draft.copy(custom = it, selected = if (!question.multiple && it.isNotBlank()) emptySet() else draft.selected))
                }, label = { Text("Your own answer") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
        if (r.prompt.supportsFeedback) item {
            OutlinedTextField(feedback, { if (it.length <= 4000) onFeedback(it) },
                label = { Text("Optional feedback when denying") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Supporting("Review all details above. You’ll confirm the exact reply before it is sent.")
                if (!enabled) Supporting("Replies are unavailable until this exact request is fresh and ready.")
                when (card.delivery) {
                    is Delivery.Pending -> Supporting("Waiting for the Mac to acknowledge this exact reply…")
                    Delivery.Unknown -> Supporting("Delivery unknown. Check your Mac. Nothing was resent; this request cannot be submitted again.")
                    else -> if (r.sending) Supporting("A reply is already being sent from another surface.")
                }
                if (r.prompt.kind == "permission") {
                    r.prompt.permissionOptions.forEach { option ->
                        OutlinedButton(onClick = { controller.reviewReply(r.ticket, ReplyDecision.Permission(option.id), "") },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column { Text(option.label); if (option.detail.isNotEmpty()) Text(option.detail, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { controller.reviewReply(r.ticket, ReplyDecision.Deny, feedback) },
                        enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (r.prompt.kind == "permission") "Deny" else "Decline request") }
                    if (r.prompt.kind == "permission")
                        Action("Allow once", enabled) { controller.reviewReply(r.ticket, ReplyDecision.AllowOnce, "") }
                    else Action("Review answer", enabled) { controller.reviewReply(r.ticket, ReplyDecision.Answer, "") }
                }
            }
        }
    }
}

@Composable private fun Picker(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Selected target unavailable")
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) },
                    onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}

@Composable
internal fun Sessions(state: ControllerState, controller: ControllerViewModel,
    positions: MutableMap<Target, FeedPosition>, feedback: MutableMap<RemoteRequest, String>,
    openSessions: () -> Unit = {}) {
    val workspace = state.workspace
    var pane by rememberSaveable(workspace.sessionID) { mutableStateOf(SessionPane.Chat) }
    BackHandler(pane != SessionPane.Chat) { pane = SessionPane.Chat }
    val select: (String) -> Unit = { agentID ->
        controller.selectSession(workspace.sessionID, agentID)
        pane = SessionPane.Chat
    }
    if (workspace.sessionID == null) {
        SessionChat(state, controller, positions, openSessions) { pane = SessionPane.Agents }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val sideTeam = teamFitsBeside(maxWidth.value, fontScale) && maxHeight >= 320.dp * fontScale
        val showHeading = maxHeight >= 420.dp * fontScale
        val scrollNavigation = !drawerKeepsPinnedChrome(maxHeight.value, fontScale)
        val shortBodyHeight = maxOf(240.dp * fontScale, maxHeight)
        Column(Modifier.fillMaxSize().then(if (scrollNavigation) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            if (showHeading) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(workspace.session?.name ?: "Session unavailable", style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(workspace.session?.project, workspace.session?.agent).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            DangerousPolicy(state, controller)
            val decisions = decisionCards(state, workspace.sessionID).size
            TabRow(selectedTabIndex = pane.ordinal) {
                SessionPane.entries.forEach { tab ->
                    Tab(selected = pane == tab, onClick = { pane = tab },
                        modifier = Modifier.heightIn(min = 48.dp),
                        text = {
                            Text(if (tab == SessionPane.Decision && decisions > 0) "Decision ($decisions)" else tab.name,
                                style = MaterialTheme.typography.labelLarge)
                        })
                }
            }
            Row(if (scrollNavigation) Modifier.height(shortBodyHeight) else Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (pane) {
                        SessionPane.Chat -> SessionChat(state, controller, positions, openSessions) { pane = SessionPane.Agents }
                        SessionPane.Decision -> Inbox(state, controller, feedback, workspace.sessionID)
                        SessionPane.Agents -> AgentTeam(state, Modifier.fillMaxSize(), select)
                    }
                }
                if (sideTeam && pane != SessionPane.Agents) {
                    VerticalDivider()
                    AgentTeam(state, Modifier.width(280.dp * fontScale).fillMaxHeight(), select)
                }
            }
        }
    }
}

private enum class SessionPane { Chat, Decision, Agents }

@Composable
private fun DangerousPolicy(state: ControllerState, controller: ControllerViewModel) {
    val workspace = state.workspace
    val policy = workspace.session?.dangerousAutoYes
    val active = policy?.enabled == true
    val fresh = state.live && workspace.receivedAt?.let { state.now >= it && state.now - it < 10_000 } == true &&
        workspace.snapshot?.connected == true && workspace.session?.connected == true
    val pending = workspace.policyDelivery?.blocked == true
    val label = when {
        policy == null -> "Dangerous Auto-yes · Unsupported"
        active && (!fresh || pending) -> "Dangerous Auto-yes · Last known On"
        active -> "Dangerous Auto-yes · On"
        !fresh || pending -> "Dangerous Auto-yes · Unknown (last known Off)"
        else -> "Dangerous Auto-yes · Off"
    }
    Surface(color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (active) Text("This session can approve eligible tools, even while your phone is disconnected.",
                style = MaterialTheme.typography.bodySmall)
            if (fresh) workspace.policyDelivery?.let { Text(it.status, style = MaterialTheme.typography.bodySmall) }
            val enabling = !active && !pending
            val unavailable = workspace.select(workspace.sessionID).policyUnavailable(state.now, enabling)
            if (!fresh) Text("Reconnect to verify the Mac policy. Off has not been confirmed.",
                style = MaterialTheme.typography.bodySmall)
            else if (unavailable != null) Text(unavailable, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { controller.preparePolicy(enabling) },
                enabled = fresh && (if (enabling) !state.busy && !state.policyOffPending else !state.policyOffPending) && unavailable == null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (enabling) "Enable Dangerous Auto-yes…" else "Turn off now")
            }
        }
    }
}

@Composable
private fun SessionChat(state: ControllerState, controller: ControllerViewModel,
    positions: MutableMap<Target, FeedPosition>, openSessions: () -> Unit, openAgents: () -> Unit) {
    val workspace = state.workspace
    var tools by rememberSaveable(workspace.sessionID, workspace.agentID) { mutableStateOf(false) }
    var showHistory by rememberSaveable(workspace.target) { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    if (workspace.sessionID == null) {
        Page {
            Heading("Choose a session")
            Supporting("Open a reported session to continue its conversation. Sessions are started on your Mac.")
            Action("Open sessions", onClick = openSessions)
            if (!state.live) {
                Supporting("Your pairing is saved. Connect and wait for fresh Mac updates to use supported controls.")
                OutlinedButton(onClick = controller::retry, enabled = !state.busy) { Text("Reconnect") }
            }
        }
    } else {
        val target = workspace.target
        val position = target?.let { positions.getOrPut(it) { FeedPosition() } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale
            val compact = maxHeight < 420.dp || fontScale > 1.3f
            val composerHeight = composerHeightLimit(maxHeight.value, fontScale)?.dp
            val scrollLayout = composerHeight == null
            val presentation = targetPresentation(state)
            val targetName = presentation.name
            val unavailable = if (!state.live) "Waiting for fresh Mac updates. Your draft stays here; nothing will be sent."
                else workspace.unavailable("queue", state.now)
            Column(Modifier.fillMaxSize().then(if (scrollLayout) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        AgentIllustration(presentation.presence, Modifier.size(40.dp),
                            isLead = workspace.agentID.isEmpty(), portrait = true)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(presentation.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (tools) "Tools & thinking · ${presentation.label}" else presentation.label,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { actions = true },
                            modifier = Modifier.semantics { contentDescription = "Session actions" }) { ShellIcon(ShellMark.More) }
                        DropdownMenu(actions, { actions = false }) {
                            DropdownMenuItem(text = { Text(if (tools) "Show conversation" else "Tools & thinking") },
                                onClick = { tools = !tools; showHistory = true; actions = false })
                            DropdownMenuItem(text = { Text("Open sessions") }, onClick = { actions = false; openSessions() })
                            if (workspace.agentID.isEmpty()) DropdownMenuItem(text = {
                                Column {
                                    Text("Interrupt main with draft")
                                    Text("Stop the current turn and send this instruction.", style = MaterialTheme.typography.bodySmall)
                                }
                            }, onClick = { actions = false; controller.prepareControl("interrupt") },
                                enabled = state.live && !state.busy && workspace.draft.isNotBlank() &&
                                    workspace.unavailable("interrupt", state.now) == null)
                            DropdownMenuItem(text = {
                                Column {
                                    Text("Stop $targetName")
                                    Text(workspace.unavailable("stop", state.now) ?: if (!state.live) "Wait for fresh Mac updates."
                                        else "Requires confirmation for this exact target.", style = MaterialTheme.typography.bodySmall)
                                }
                            }, onClick = { actions = false; controller.prepareControl("stop") },
                                enabled = state.live && !state.busy && workspace.unavailable("stop", state.now) == null)
                        }
                    }
                }
                if (scrollLayout) TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(if (showHistory) "Hide history" else if (tools) "Show tools & thinking" else "Show conversation")
                }
                if (!scrollLayout || showHistory) {
                    if (position == null || workspace.detail == null) {
                        Column((if (scrollLayout) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())).padding(20.dp)) {
                            Supporting("Waiting for this exact session and current ticket. No other session has been selected.")
                            TextButton(onClick = openSessions) { Text("Open sessions") }
                        }
                    } else key(target) {
                        Conversation(workspace, position, tools,
                            (if (scrollLayout) Modifier.height(180.dp * fontScale) else Modifier.weight(1f)).padding(horizontal = 20.dp))
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().then(if (composerHeight != null) Modifier.heightIn(max = composerHeight) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(if (scrollLayout) Modifier else Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (workspace.agentID.isNotEmpty()) TextButton(onClick = openAgents) { Text("Change agent") }
                        workspace.delivery?.let { Supporting(it.status) }
                        if (unavailable != null) Text(unavailable, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        OutlinedTextField(workspace.draft, controller::draft,
                            label = { Text(if (workspace.agentID.isEmpty()) "Message main agent" else "Message selected helper") },
                            enabled = !state.busy && target != null && workspace.delivery?.blocked != true,
                            modifier = Modifier.fillMaxWidth(), maxLines = if (compact) 2 else 4)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Enter adds a new line", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Action("Send", !state.busy && unavailable == null && workspace.draft.isNotBlank()) { controller.prepareControl("queue") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Conversation(workspace: WorkspaceState, position: FeedPosition, tools: Boolean, modifier: Modifier) {
    val detail = workspace.detail!!
    val activity = detail.activity.filter { it.agentID == workspace.agentID }
    val messages = activity.filter { it.kind in setOf("user", "assistant") }.map { it.copy(id = "activity:${it.id}") }.toMutableList()
    for (receipt in detail.controls.filter { it.agentID == workspace.agentID && it.action in setOf("queue", "interrupt") }) {
        if (messages.none { it.clientTurnID == receipt.id }) messages.add(Activity("receipt:${receipt.id}", "user",
            "Your message · ${receipt.status}", receipt.message, receipt.timestamp, receipt.agentID))
    }
    val feed = if (tools) activity.filter { it.kind !in setOf("user", "assistant") } else messages.sortedBy { it.timestamp }
    val scope = rememberCoroutineScope()
    LaunchedEffect(position) {
        snapshotFlow { position.list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && position.list.layoutInfo.totalItemsCount > 0) position.following = !position.list.canScrollForward
        }
    }
    LaunchedEffect(feed, tools) {
        if (!tools && position.following && !position.list.isScrollInProgress && feed.isNotEmpty()) position.list.scrollToItem(feed.lastIndex)
    }
    Column(modifier) {
        if (detail.truncated) Supporting("Recent reported history only. Earlier entries are on your Mac.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = if (tools) androidx.compose.foundation.lazy.rememberLazyListState() else position.list,
            verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (feed.isEmpty()) item { Supporting(if (tools) "No tool or thinking updates reported." else "No conversation reported for this agent yet.") }
            items(feed, key = { it.id }) { message ->
                Surface(
                    color = if (message.kind == "user") MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(start = if (message.kind == "user") 32.dp else 0.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (message.kind == "user") "You" else if (message.kind == "assistant") workspace.session?.agent ?: "Assistant" else message.kind} · ${message.title}",
                            style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(message.detail, fontFamily = if (tools) FontFamily.Monospace else FontFamily.Default) }
                        CopyButton(message.detail)
                    }
                }
            }
        }
        if (!tools && !position.following) TextButton(onClick = {
            position.following = true
            scope.launch { if (feed.isNotEmpty()) position.list.scrollToItem(feed.lastIndex) }
        }) { Text("Jump to latest") }
    }
}
@Composable internal fun CopyButton(value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    TextButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (copied) "Copied" else "Copy text")
    }
}

@Composable private fun Notices(text: String, close: () -> Unit) {
    Page { TextButton(onClick = close) { Text("Back to Settings") }; Heading("Third-party notices"); SelectionContainer { Text(text) } }
}
@Composable internal fun Confirmation(title: String, cancel: () -> Unit, confirm: () -> Unit, enabled: Boolean,
    confirmLabel: String = "Confirm",
    content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content) },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = confirm, enabled = enabled) { Text(confirmLabel) } })
}
