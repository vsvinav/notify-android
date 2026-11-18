package app.notify.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.notify.android.R
import app.notify.android.controller.ControllerState
import app.notify.android.controller.Session
import app.notify.android.controller.filterSessions
import kotlinx.coroutines.launch

internal enum class Destination { Chat, Requests, Settings }
internal enum class SettingsSection(val title: String) {
    Overview("Settings"), Connection("Connection"), Notifications("Notifications"),
    Pairing("Account & pairing"), Diagnostics("Connection diagnostics")
}
internal enum class ShellMark { Menu, Back, Close, More }

@Composable
internal fun ShellIcon(mark: ShellMark) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), stroke)
        when (mark) {
            ShellMark.Menu -> for (y in listOf(.25f, .5f, .75f)) line(.15f, y, .85f, y)
            ShellMark.Back -> { line(.18f, .5f, .85f, .5f); line(.18f, .5f, .48f, .2f); line(.18f, .5f, .48f, .8f) }
            ShellMark.Close -> { line(.25f, .25f, .75f, .75f); line(.25f, .75f, .75f, .25f) }
            ShellMark.More -> for (x in listOf(.2f, .5f, .8f)) drawCircle(color, stroke, Offset(size.width * x, size.height * .5f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatShell(
    state: ControllerState,
    destination: Destination,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    onSession: (String) -> Unit,
    onConnection: () -> Unit,
    content: @Composable (openSessions: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val close: () -> Unit = { scope.launch { drawer.close() } }
        val open: () -> Unit = { if (expanded) searchFocus.requestFocus() else scope.launch { drawer.open() }; Unit }
        val drawerContent: @Composable () -> Unit = {
            SessionDrawer(state, destination, searchFocus, if (expanded) null else close,
                { onNavigate(it); close() }, { onSession(it); close() })
        }
        val body: @Composable () -> Unit = {
            Scaffold(topBar = {
                TopAppBar(
                    expandedHeight = maxOf(64.dp, 64.dp * LocalDensity.current.fontScale),
                    navigationIcon = {
                        if (canGoBack || !expanded) IconButton(
                            onClick = if (canGoBack) onBack else open,
                            modifier = Modifier.semantics { contentDescription = if (canGoBack) "Back" else "Open sessions menu" },
                        ) { ShellIcon(if (canGoBack) ShellMark.Back else ShellMark.Menu) }
                    },
                    title = {
                        TextButton(onClick = onConnection, contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_notify_mark), contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                Text(connectionLabel(state), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                )
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    content(open)
                }
            }
        }
        if (expanded) PermanentNavigationDrawer(drawerContent = {
            PermanentDrawerSheet(Modifier.width(300.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) { drawerContent() }
        }, content = body)
        else ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(Modifier.width(minOf(340.dp, maxWidth * .88f)),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) { drawerContent() }
        }, content = body)
        BackHandler(drawer.isOpen && !expanded, close)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionDrawer(
    state: ControllerState, destination: Destination, searchFocus: FocusRequester, close: (() -> Unit)?,
    navigate: (Destination) -> Unit, select: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var needsAttention by rememberSaveable { mutableStateOf(false) }
    val sessions = state.workspace.snapshot?.sessions.orEmpty()
    val filtered = remember(sessions, search, provider, needsAttention, state.inbox.visibleCards) {
        filterSessions(sessions, search, provider, false)
            .filter { !needsAttention || sessionGroup(state, it) == SessionGroup.NeedsYou }
    }
    val groups = filtered.groupBy { sessionGroup(state, it) }
    val browseControls: @Composable () -> Unit = {
        NavigationDrawerItem(label = { Text("Needs you") }, badge = { Text("${state.inbox.visibleCards.size}") },
            selected = destination == Destination.Requests, onClick = { navigate(Destination.Requests) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(provider == null, { provider = null }, label = { Text("All providers") })
            (sessions.map { it.agent } + listOfNotNull(provider)).distinct().sorted().forEach { agent ->
                FilterChip(provider == agent, { provider = agent }, label = { Text(agent) })
            }
            FilterChip(needsAttention, { needsAttention = !needsAttention }, label = { Text("Needs you") })
        }
        if (!state.live) Supporting("Last reported sessions. Connect to refresh; actions stay disabled.")
        if (sessions.isEmpty()) Supporting("No sessions reported. Start an agent in Notify on your Mac.")
        else if (filtered.isEmpty()) {
            Supporting("No sessions match these filters.")
            TextButton(onClick = { search = ""; provider = null; needsAttention = false }) { Text("Clear filters") }
        }
    }
    val settings: @Composable () -> Unit = {
        HorizontalDivider()
        NavigationDrawerItem(label = { Text("Settings") }, selected = destination == Destination.Settings,
            onClick = { navigate(Destination.Settings) }, modifier = Modifier.padding(vertical = 8.dp))
    }
    val sessionRow: @Composable (Session) -> Unit = { session ->
        SessionDrawerRow(session, sessionPresentation(state, session), state.workspace.sessionID == session.id,
            state.inbox.visibleCards.count { it.request.sessionID == session.id }) { select(session.id) }
    }
    // The sheet consumes system bars; imePadding subtracts only the remaining keyboard inset.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val pinned = drawerKeepsPinnedChrome(maxHeight.value, LocalDensity.current.fontScale)
    Column(Modifier.fillMaxSize().then(if (pinned) Modifier else Modifier.verticalScroll(rememberScrollState()))
        .padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleLarge)
            close?.let { IconButton(onClick = it, modifier = Modifier.semantics { contentDescription = "Close sessions menu" }) {
                ShellIcon(ShellMark.Close)
            } }
        }
        OutlinedTextField(search, { if (it.length <= 200) search = it },
            label = { Text("Search sessions or projects") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(searchFocus))
        if (pinned) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { browseControls() }
                SessionGroup.entries.forEach { group ->
                    val rows = groups[group].orEmpty()
                    if (rows.isNotEmpty()) {
                        item(key = "group:${group.name}") { DrawerGroupHeading(group, rows.size) }
                        items(rows, key = { "session:${it.id}" }) { sessionRow(it) }
                    }
                }
                if (state.workspace.snapshot?.truncated == true) item { Supporting("Recent sessions only. The full list is on your Mac.") }
            }
            settings()
        } else {
            settings()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                browseControls()
                SessionGroup.entries.forEach { group ->
                    val rows = groups[group].orEmpty()
                    if (rows.isNotEmpty()) {
                        DrawerGroupHeading(group, rows.size)
                        rows.forEach { session -> key(session.id) { sessionRow(session) } }
                    }
                }
                if (state.workspace.snapshot?.truncated == true) Supporting("Recent sessions only. The full list is on your Mac.")
            }
        }
    }
    }
}

@Composable
private fun DrawerGroupHeading(group: SessionGroup, count: Int) {
    Text("${group.title} · $count", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SessionDrawerRow(session: Session, presentation: AgentPresentation, isSelected: Boolean,
    pendingCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).semantics { selected = isSelected },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                AgentIllustration(presentation.presence, Modifier.size(40.dp), isLead = true, portrait = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.name, style = MaterialTheme.typography.titleSmall)
                Text("${session.project} · ${session.agent}", style = MaterialTheme.typography.bodySmall)
                Text(presentation.label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pendingCount > 0) Text("$pendingCount need you", style = MaterialTheme.typography.labelMedium)
                if (session.runningCount != null && session.idleCount != null)
                    Text("Helpers · ${session.runningCount} running · ${session.idleCount} idle",
                        style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
