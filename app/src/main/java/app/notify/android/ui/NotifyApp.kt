package app.notify.android.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.notify.android.controller.*
import app.notify.android.controller.Target
import app.notify.android.remote.*
import java.util.Base64
import kotlinx.coroutines.launch

private val light = lightColorScheme(
    primary = Color(0xFF96472E), onPrimary = Color.White,
    primaryContainer = Color(0xFFF5D5C7), onPrimaryContainer = Color(0xFF3A190D),
    secondary = Color(0xFF69564A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE0D2), onSecondaryContainer = Color(0xFF29221C),
    background = Color(0xFFF8F4EC), onBackground = Color(0xFF292521),
    surface = Color(0xFFF8F4EC), onSurface = Color(0xFF292521),
    surfaceVariant = Color(0xFFECE5DA), onSurfaceVariant = Color(0xFF60574F),
    surfaceContainer = Color(0xFFF0E9DE), surfaceContainerLow = Color(0xFFF5EFE5),
    surfaceContainerHigh = Color(0xFFEAE2D7), surfaceContainerHighest = Color(0xFFE4DCD1),
    surfaceContainerLowest = Color(0xFFFFFBF5),
    outline = Color(0xFF80746B), error = Color(0xFFA32C24),
)
private val dark = darkColorScheme(
    primary = Color(0xFFF1AC8F), onPrimary = Color(0xFF522512),
    primaryContainer = Color(0xFF713A26), onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFD5C0AF), onSecondary = Color(0xFF392E26),
    secondaryContainer = Color(0xFF4D4036), onSecondaryContainer = Color(0xFFF0DFCE),
    background = Color(0xFF211E1B), onBackground = Color(0xFFEDE4D9),
    surface = Color(0xFF211E1B), onSurface = Color(0xFFEDE4D9),
    surfaceVariant = Color(0xFF494139), onSurfaceVariant = Color(0xFFD2C4B6),
    surfaceContainer = Color(0xFF292520), surfaceContainerLow = Color(0xFF25211D),
    surfaceContainerHigh = Color(0xFF332D27), surfaceContainerHighest = Color(0xFF3C352E),
    surfaceContainerLowest = Color(0xFF191613),
    outline = Color(0xFFA49587), error = Color(0xFFFFB4A9),
)
private enum class Destination(val title: String) { Inbox("Inbox"), Sessions("Sessions"), Settings("Settings") }
internal class FeedPosition {
    val list = LazyListState()
    var following by mutableStateOf(true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyApp(controller: ControllerViewModel, activity: Activity) {
    val state by controller.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(Destination.Inbox) }
    val positions = remember { mutableMapOf<Target, FeedPosition>() }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
        BackHandler(enabled = state.notices != null || state.review != null || state.controlReview != null ||
            destination != Destination.Inbox || state.workspace.sessionID != null) {
            when {
                state.notices != null -> controller.closeNotices()
                state.review != null || state.controlReview != null -> controller.cancelReview()
                destination == Destination.Sessions && state.workspace.sessionID != null -> controller.selectSession(null)
                else -> destination = Destination.Inbox
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 700.dp
            Scaffold(
                topBar = { TopAppBar(title = { Text("Notify", style = MaterialTheme.typography.titleLarge) },
                    actions = { Text(if (state.live) "Connected" else when (state.status.state) {
                        "pending" -> "Awaiting Mac"
                        "connecting" -> "Connecting"
                        else -> "Not connected"
                    }, Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge) }) },
                bottomBar = { if (!expanded) NavigationBar {
                    Destination.entries.forEach { tab -> NavigationBarItem(selected = destination == tab,
                        onClick = { destination = tab }, icon = { NavigationMark(tab) }, label = { Text(tab.title) }) }
                } },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if (expanded) NavigationRail {
                        Destination.entries.forEach { tab -> NavigationRailItem(selected = destination == tab,
                            onClick = { destination = tab }, icon = { NavigationMark(tab) }, label = { Text(tab.title) }) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().widthIn(max = 1000.dp)) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { error ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                    Text(error, Modifier.heightIn(max = 128.dp).verticalScroll(rememberScrollState())
                                        .padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = controller::clearError) { Text("Dismiss message") }
                                }
                            }
                        }
                        when {
                            state.notices != null -> Notices(state.notices!!, controller::closeNotices)
                            destination == Destination.Settings -> Settings(state, controller, activity)
                            state.account == null -> SignIn(state, controller, activity)
                            state.relationship == null || state.status.state == "pending" || state.status.state == "connecting" ->
                                ConnectionSetup(state, controller)
                            destination == Destination.Sessions -> Sessions(state, controller, positions)
                            else -> Inbox(state, controller)
                        }
                    }
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
            Confirmation(if (command.action == "stop") "Stop this exact target?" else "Interrupt the main agent?",
                controller::cancelReview, controller::confirmControl, state.live && !state.busy) {
                Evidence("Session", command.sessionID)
                Evidence("Ticket", command.ticket)
                Evidence("Target", if (command.agentID.isEmpty()) "Main agent" else command.agentID)
                Text(if (command.action == "interrupt") "Stop the current main-agent turn and send the instruction below instead."
                    else if (command.agentID.isEmpty()) "Stop the current main-agent turn. This does not delete the session."
                    else "Stop only this selected subagent. Its descendants may also stop. Other agents are not selected.")
                if (command.text.isNotEmpty()) Evidence("Exact instruction", command.text)
            }
        }
    }
}

@Composable
private fun NavigationMark(tab: Destination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        when (tab) {
            Destination.Inbox -> {
                drawRect(color, Offset(size.width * .12f, size.height * .2f), Size(size.width * .76f, size.height * .6f), style = Stroke(stroke))
                drawLine(color, Offset(size.width * .12f, size.height * .55f), Offset(size.width * .88f, size.height * .55f), stroke)
            }
            Destination.Sessions -> for (y in listOf(.25f, .5f, .75f)) {
                drawCircle(color, stroke, Offset(size.width * .18f, size.height * y))
                drawLine(color, Offset(size.width * .36f, size.height * y), Offset(size.width * .87f, size.height * y), stroke)
            }
            Destination.Settings -> {
                for (x in listOf(.25f, .5f, .75f)) drawLine(color, Offset(size.width * x, size.height * .13f), Offset(size.width * x, size.height * .87f), stroke)
                for ((x, y) in listOf(.25f to .35f, .5f to .65f, .75f to .4f)) drawCircle(color, stroke * 2, Offset(size.width * x, size.height * y))
            }
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun Heading(text: String) { Text(text, style = MaterialTheme.typography.headlineSmall) }
@Composable private fun Supporting(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(label) }
}
@Composable private fun Evidence(label: String, value: String, code: Boolean = false) {
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
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Android phone") }
    Page {
        val record = state.relationship
        Heading(if (record == null) "Pair with your Mac" else "Compare on your Mac")
        if (record == null) {
            Supporting("On your Mac, open Notify → Remote Access. Paste its complete pairing link here while you can compare both screens.")
            OutlinedTextField(link, { if (it.length <= 2048) link = it }, label = { Text("Full Mac pairing link") },
                supportingText = { Text("Must include #setup, mac and the complete public key.") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Phone name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Action("Pair with this Mac", !state.busy && link.isNotBlank() && name.isNotBlank()) { controller.pair(link, name) }
        } else {
            PublicKeys(record)
            Supporting("Approve on the Mac only when every character of the phone public key matches. A name or short prefix is not proof.")
            Supporting(state.status.message)
            OutlinedButton(onClick = controller::retry, enabled = !state.busy && record.enrolled) { Text("Retry connection") }
            Supporting("You can explicitly forget a pairing in Settings. A pending enrollment is not Mac approval.")
        }
    }
}
@Composable private fun PublicKeys(record: Relationship) {
    Evidence("Phone · ${record.name}", Base64.getEncoder().encodeToString(decodeKey(record.publicKey)), true)
    Supporting("Complete phone key: 88 characters including the final =.")
    Evidence("Mac ID", record.macID)
    Evidence("Pinned Mac public key", record.macPublicKey, true)
}

@Composable
private fun Inbox(state: ControllerState, controller: ControllerViewModel) {
    val cards = state.inbox.visibleCards
    val card = cards.singleOrNull { it.request.ticket == state.inbox.selectedTicket }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("Inbox")
            if (!state.live) {
                Supporting("${state.status.message} Replies are disabled until a fresh Mac update.")
                OutlinedButton(onClick = controller::retry, enabled = !state.busy) { Text("Retry connection") }
            }
            if (cards.size > 1) Picker("Review request", cards.map { it.request.ticket to "${it.request.sessionName} · ${it.request.prompt.title}" },
                card?.request?.ticket ?: "", controller::selectRequest)
        }
        if (card == null) {
            Text(if (state.live) "No waiting requests reported by your Mac." else "Waiting for a fresh request snapshot.",
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        } else key(card.request) {
            RequestReview(card, state.live && !state.busy && card.delivery == Delivery.Ready && !card.request.sending, controller)
        }
    }
}
@Composable
private fun RequestReview(card: RequestCard, enabled: Boolean, controller: ControllerViewModel) {
    var feedback by remember { mutableStateOf("") }
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
            OutlinedTextField(feedback, { if (it.length <= 4000) feedback = it },
                label = { Text("Optional feedback when denying") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (card.delivery) {
                    is Delivery.Pending -> Supporting("Waiting for the Mac to acknowledge this exact reply…")
                    Delivery.Unknown -> Supporting("Delivery unknown. Check your Mac. Nothing was resent; this request cannot be submitted again.")
                    else -> if (r.sending) Supporting("A reply is already being sent from another surface.")
                }
                if (r.prompt.kind == "permission") {
                    Action("Allow once", enabled) { controller.reviewReply(r.ticket, ReplyDecision.AllowOnce, "") }
                    r.prompt.permissionOptions.forEach { option ->
                        OutlinedButton(onClick = { controller.reviewReply(r.ticket, ReplyDecision.Permission(option.id), "") },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column { Text(option.label); if (option.detail.isNotEmpty()) Text(option.detail, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else Action("Review answer", enabled) { controller.reviewReply(r.ticket, ReplyDecision.Answer, "") }
                OutlinedButton(onClick = { controller.reviewReply(r.ticket, ReplyDecision.Deny, feedback) },
                    enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (r.prompt.kind == "permission") "Deny" else "Decline request") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Sessions(state: ControllerState, controller: ControllerViewModel, positions: MutableMap<Target, FeedPosition>) {
    val workspace = state.workspace
    if (workspace.sessionID == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Heading("Sessions") }
            if (!state.live) item {
                Supporting("Showing the last reported sessions. Connect to refresh and enable supported controls.")
                OutlinedButton(onClick = controller::retry, enabled = !state.busy) { Text("Retry connection") }
            }
            if (workspace.snapshot?.sessions.isNullOrEmpty()) item { Supporting("No sessions reported. Connect an agent in Notify on your Mac.") }
            items(workspace.snapshot?.sessions ?: emptyList(), key = { it.id }) { session ->
                OutlinedCard(onClick = { controller.selectSession(session.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(session.name, style = MaterialTheme.typography.titleMedium)
                        Supporting("${session.agent} · ${session.project}")
                        Supporting(if (session.connected) session.state else "Not connected · Set up on Mac")
                    }
                }
            }
            if (workspace.snapshot?.truncated == true) item { Supporting("Showing recent reported sessions. The full list is on your Mac.") }
        }
    } else {
        val target = workspace.target
        val position = target?.let { positions.getOrPut(it) { FeedPosition() } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 500.dp || LocalDensity.current.fontScale > 1.3f
            Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { controller.selectSession(null) }) { Text("All sessions") }
            Text(workspace.session?.name ?: "Session unavailable", style = MaterialTheme.typography.titleLarge)
            Supporting("${workspace.session?.agent ?: ""} · ${workspace.session?.state ?: "Waiting for session"}")
            Picker("Chat with", listOf("" to "Main agent") + (workspace.detail?.agents?.map { it.id to "${it.name} · ${it.status}" } ?: emptyList()),
                workspace.agentID) { controller.selectSession(workspace.sessionID, it) }
            if (position == null || workspace.detail == null) {
                Text("Waiting for this exact session and current ticket.", if (compact) Modifier.heightIn(min = 120.dp) else Modifier.weight(1f))
            } else key(target) {
                Conversation(workspace, position, if (compact) Modifier.height(320.dp) else Modifier.weight(1f))
            }
            val unavailable = if (!state.live) "Reconnect with a fresh Mac update before sending." else workspace.unavailable("queue", state.now)
            workspace.delivery?.let { Supporting(it.status) }
            if (unavailable != null) Supporting(unavailable)
            OutlinedTextField(workspace.draft, controller::draft, label = { Text("Message to ${if (workspace.agentID.isEmpty()) "main agent" else "selected subagent"}") },
                enabled = !state.busy && target != null && workspace.delivery?.blocked != true,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), maxLines = 5)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Send", !state.busy && unavailable == null && workspace.draft.isNotBlank()) { controller.prepareControl("queue") }
                if (workspace.agentID.isEmpty()) OutlinedButton(onClick = { controller.prepareControl("interrupt") },
                    enabled = !state.busy && unavailable == null && workspace.draft.isNotBlank()) { Text("Interrupt") }
                OutlinedButton(onClick = { controller.prepareControl("stop") }, enabled = state.live && !state.busy &&
                    workspace.unavailable("stop", state.now) == null) { Text("Stop") }
            }
            Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Conversation(workspace: WorkspaceState, position: FeedPosition, modifier: Modifier) {
    var tools by remember { mutableStateOf(false) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!tools, onClick = { tools = false }, label = { Text("Conversation") })
            FilterChip(tools, onClick = { tools = true }, label = { Text("Tools & thinking") })
        }
        if (detail.truncated) Supporting("Recent reported history only. Earlier entries are on your Mac.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = if (tools) androidx.compose.foundation.lazy.rememberLazyListState() else position.list,
            verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (feed.isEmpty()) item { Supporting(if (tools) "No tool or thinking updates reported." else "No conversation reported for this agent yet.") }
            items(feed, key = { it.id }) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (message.kind == "user") "You" else if (message.kind == "assistant") "Assistant" else message.kind} · ${message.title}",
                        style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(message.detail, fontFamily = if (tools) FontFamily.Monospace else FontFamily.Default) }
                    CopyButton(message.detail)
                }
            }
        }
        if (!tools) TextButton(onClick = {
            position.following = true
            scope.launch { if (feed.isNotEmpty()) position.list.scrollToItem(feed.lastIndex) }
        }) { Text("Jump to latest") }
    }
}
@Composable private fun CopyButton(value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    TextButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (copied) "Copied" else "Copy text")
    }
}

@Composable
private fun Settings(state: ControllerState, controller: ControllerViewModel, activity: Activity) {
    var forget by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.enableNotifications() else controller.notificationPermissionDenied()
    }
    Page {
        Heading("Connection")
        Supporting(state.status.message)
        Evidence("Service", SERVICE_ORIGIN)
        Supporting(if (state.account == null) "Account: signed out. Saved Mac trust is unchanged." else "Account: signed in for this app session.")
        if (state.account == null) Action("Sign in with a passkey", !state.busy) { controller.signIn(activity, false, "") }
        state.relationship?.let { record ->
            PublicKeys(record)
            Supporting(if (record.enrolled) "Enrollment recorded. Only an approved encrypted channel grants Mac access." else "Enrollment not confirmed. Do not assume the Mac approved this phone.")
            Action("Retry connection", !state.busy && state.account != null && record.enrolled, controller::retry)
            OutlinedButton(onClick = controller::disconnect) { Text("Disconnect") }
            OutlinedButton(onClick = { forget = true }, enabled = !state.busy) { Text("Forget this phone’s pairing") }
        }
        if (state.account != null) OutlinedButton(onClick = controller::signOut, enabled = !state.busy) { Text("Sign out · keep pairing") }
        HorizontalDivider()
        Heading("Notifications")
        Text(if (state.notifications.enabled) "Mac attention · on" else "Mac attention · not enabled",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Supporting(state.notifications.description)
        Supporting("Optional alerts use Google Firebase delivery. Only a generic attention signal is sent—never chat, commands or request text. Tapping opens Notify; it cannot approve anything.")
        Supporting("Notify includes a soft two-note sound and a short three-pulse vibration. Android’s channel, silent mode and Do Not Disturb settings remain in control. Your Mac must be awake and online.")
        Action(if (state.notifications.consent) "Refresh notification registration" else "Enable notifications",
            !state.busy && state.account != null && state.relationship?.enrolled == true &&
                !state.notifications.cleanupPending) {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else controller.enableNotifications()
        }
        if (state.notifications.consent || state.notifications.cleanupPending) OutlinedButton(
            onClick = controller::disableNotifications, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.notifications.cleanupPending) "Finish removing notification routing" else "Turn notifications off")
        }
        TextButton(onClick = controller::openNotificationSettings) { Text("Android sound, vibration & permission settings") }
        Supporting("Delivery is best effort. If a token rotates while you’re signed out, sign in and refresh registration here.")
        TextButton(onClick = controller::notices, enabled = !state.busy) { Text("Third-party notices") }
    }
    if (forget) Confirmation("Forget this pairing?", { forget = false }, { forget = false; controller.forget() }, !state.busy) {
        Text("Delete this phone’s saved relationship and AndroidKeyStore channel identity. This does not delete your account or revoke the phone on the Mac.")
        Text("Revoke the phone in Notify on your Mac too. Connecting again requires a new in-person pairing.")
        Text("If notifications were enabled, their routing must be removed while signed in before this key is deleted.")
    }
}
@Composable private fun Notices(text: String, close: () -> Unit) {
    Page { TextButton(onClick = close) { Text("Back to Settings") }; Heading("Third-party notices"); SelectionContainer { Text(text) } }
}
@Composable private fun Confirmation(title: String, cancel: () -> Unit, confirm: () -> Unit, enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content) },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = confirm, enabled = enabled) { Text("Confirm") } })
}
