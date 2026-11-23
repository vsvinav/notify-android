package app.notify.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.notify.android.controller.*

private val boardNavy = Color(0xFF0E1B3D)
private val boardLime = Color(0xFFC6F432)
private val boardPink = Color(0xFFFFB1D3)

@Composable
private fun DashboardTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8EBEFF), onPrimary = boardNavy,
        surface = Color(0xFF111B2D), onSurface = Color(0xFFE7EDF9),
        surfaceContainerLow = Color(0xFF1B2942), surfaceContainerHigh = Color(0xFF344563),
        onSurfaceVariant = Color(0xFFA9B8D2), outline = Color(0xFF889AB9),
        outlineVariant = Color(0xFF344563), secondary = Color(0xFFFF9AC6),
        secondaryContainer = Color(0xFF45263D), onSecondaryContainer = Color(0xFFFF9AC6),
    ) else lightColorScheme(
        primary = Color(0xFF155FBD), onPrimary = Color.White,
        surface = Color(0xFFF3F6FC), onSurface = Color(0xFF152442),
        surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFE3EBFA),
        onSurfaceVariant = Color(0xFF4B5C78), outline = Color(0xFF63728B),
        outlineVariant = Color(0xFFD5DFED), secondary = Color(0xFFA91F62),
        secondaryContainer = Color(0xFFFFE8F2), onSecondaryContainer = Color(0xFFA91F62),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun Dashboard(state: ControllerState, controller: ControllerViewModel) {
    PanelScreen(state, controller::previewPanelAction, controller::editPanelDraft, controller::previewPanelForm,
        controller::cancelPanelAction, controller::sendPanelAction, controller::panelMessageShown)
}

@Composable
internal fun PanelScreen(
    state: ControllerState, onAction: (PanelAction) -> Unit,
    onDraft: (String, String, String) -> Unit, onForm: (String) -> Unit,
    onCancel: () -> Unit, onSend: () -> Unit, onMessageShown: (String) -> Unit,
) = DashboardTheme {
    val panel = state.panel
    val feed = panel.feed
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.connectionAccount, state.relationship, state.foreground, panel.message) {
        if (state.foreground) panel.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown(it)
        }
    }
    // Reuse the controller's foreground freshness clock instead of adding a dashboard timer.
    val wallNow = remember(feed, state.now / 60_000) { System.currentTimeMillis() }
    var selectedTab by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember(state.connectionAccount, state.relationship, selectedTab) { mutableStateOf<PanelItem?>(null) }
    val tab = feed?.tabs?.firstOrNull { it.id == selectedTab } ?: feed?.tabs?.firstOrNull()
    val actionsEnabled = state.panelActionsEnabled
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().background(boardNavy)) {
                    if (panel.pending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(feed?.title ?: "Dashboard", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        BridgeStatus(feed)
                        Text(panelAge(feed?.updatedAt, wallNow), color = Color(0xFFBECDE6), style = MaterialTheme.typography.bodySmall)
                    }
                    if (feed != null && tab != null) {
                        ScrollableTabRow(selectedTabIndex = feed.tabs.indexOf(tab), edgePadding = 8.dp,
                            containerColor = boardNavy, contentColor = boardLime, indicator = { positions ->
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(positions[feed.tabs.indexOf(tab)]), color = boardLime)
                            }) {
                            feed.tabs.forEach { t ->
                                Tab(selected = t == tab, onClick = { selectedTab = t.id },
                                    selectedContentColor = Color.White, unselectedContentColor = Color(0xFFBECDE6),
                                    modifier = Modifier.heightIn(min = 48.dp), text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(t.label)
                                            t.badge?.takeIf { it > 0 }?.let {
                                                Badge(containerColor = boardPink, contentColor = boardNavy) { Text("$it") }
                                            }
                                        }
                                    })
                            }
                        }
                    }
                }
            }
            if (!panel.fresh || !state.live) item(key = "stale") {
                Supporting("Last reported dashboard. Waiting for fresh Mac updates; actions stay disabled.", Modifier.padding(horizontal = 16.dp))
            }
            panel.error?.let { error -> item(key = "error") {
                Text(error, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error)
            } }
            tab?.form?.let { form -> item(key = "form:${tab.id}:${form.actionId}") {
                val key = state.panelDraftKey(tab.id, form.actionId)
                PanelTextForm(form, panel.drafts[key]?.text.orEmpty(), actionsEnabled && key != null,
                    { onDraft(tab.id, form.actionId, it) }, { onForm(tab.id) })
            } }
            when {
                feed == null -> item { Supporting(panel.reason.ifBlank { "Loading dashboard…" }, Modifier.padding(16.dp)) }
                tab == null -> item { Supporting("Nothing to show yet.", Modifier.padding(16.dp)) }
                tab.sections.isEmpty() && tab.track == null -> item { Supporting("Nothing here right now.", Modifier.padding(16.dp)) }
            }
            val placed = tab?.track?.placements?.map { it.itemId }?.toSet().orEmpty()
            tab?.sections?.filter { it.attention }?.forEachIndexed { index, section ->
                item(key = "attention:$index") { PanelHeading(section.title, attention = true) }
                itemsIndexed(section.items, key = { i, _ -> "attention:$index:$i" }) { _, item ->
                    PanelCard(item, actionsEnabled, panel.pending?.actionID, onAction, { detail = item }, attention = true)
                }
            }
            tab?.track?.let { track ->
                item(key = "track") { PanelHeading(track.title) }
                track.stages.forEachIndexed { index, stage ->
                    item(key = "stage:${stage.id}") {
                        StageHeading(stage, index, track.stages.size)
                    }
                    val stageItems = track.placements.filter { it.stageId == stage.id }.mapNotNull { placement ->
                        tab.sections.flatMap { it.items }.singleOrNull { it.id == placement.itemId }
                    }
                    itemsIndexed(stageItems, key = { _, item -> "stage:${stage.id}:${item.id}" }) { _, item ->
                        val line = MaterialTheme.colorScheme.outlineVariant
                        Box(Modifier.padding(horizontal = 16.dp).drawBehind {
                            if (index < track.stages.lastIndex)
                                drawLine(line, Offset(16.dp.toPx(), 0f), Offset(16.dp.toPx(), size.height), 2.dp.toPx())
                        }.padding(start = 42.dp)) {
                            PanelCard(item, actionsEnabled, panel.pending?.actionID, onAction, { detail = item },
                                inset = false, compact = true)
                        }
                    }
                }
            }
            tab?.sections?.filterNot { it.attention }?.forEachIndexed { index, section ->
                val unplaced = section.items.filterNot { it.id in placed }
                if (unplaced.isNotEmpty() || section.items.isEmpty()) {
                    item(key = "section:$index") { PanelHeading(section.title) }
                    if (unplaced.isEmpty()) item(key = "empty:$index") { Supporting("Nothing here.", Modifier.padding(horizontal = 16.dp)) }
                    itemsIndexed(unplaced, key = { i, _ -> "item:$index:$i" }) { _, item ->
                        PanelCard(item, actionsEnabled, panel.pending?.actionID, onAction, { detail = item })
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (panel.confirm == null) detail?.let { selected ->
        val current = tab?.sections?.flatMap { it.items }?.singleOrNull { it.id == selected.id }
        PanelDetail(current ?: selected, actionsEnabled && current != null, panel.pending?.actionID, onAction) { detail = null }
    }
    panel.confirm?.let { confirm ->
        Confirmation(confirm.label, onCancel, onSend, actionsEnabled, confirmLabel = "Send") {
            confirm.submission?.let {
                Text("Your exact text", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider()
            }
            Text("Provider instruction", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(confirm.prompt, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PanelTextForm(form: PanelForm, text: String, enabled: Boolean, onText: (String) -> Unit, onPreview: () -> Unit) {
    val error = form.error(text)
    var overLimit by remember(form.actionId) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(form.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        OutlinedTextField(text, {
            overLimit = it.length > 300
            if (!overLimit) onText(it)
        }, Modifier.fillMaxWidth(), label = { Text(form.title) },
            placeholder = form.placeholder?.let { { Text(it) } }, minLines = 2, maxLines = 5,
            isError = overLimit || (text.isNotEmpty() && error != null),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            supportingText = {
                Column {
                    Text("${text.length}/${form.maxLength} UTF-16 units · minimum ${form.minLength} · input limit 300")
                    if (overLimit) Text("Text was not changed. The input limit is 300 UTF-16 units.")
                    if (text.isNotEmpty() && error != null) Text(error)
                }
            })
        Button(onClick = onPreview, enabled = enabled && error == null && !overLimit, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(form.submitLabel)
        }
        Supporting("Review the exact instruction before sending. Drafts are held only in memory on this phone.")
    }
}

@Composable
private fun PanelHeading(title: String, attention: Boolean = false) {
    if (title.isEmpty()) return
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = if (attention) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun StageHeading(stage: PanelStage, index: Int, count: Int) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val color = when (stage.kind) {
        PanelStageKind.Normal -> boardNavy
        PanelStageKind.Gate -> boardLime
        PanelStageKind.Decision -> boardPink
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).drawBehind {
        if (index < count - 1) drawLine(line, Offset(16.dp.toPx(), 16.dp.toPx()),
            Offset(16.dp.toPx(), size.height + 16.dp.toPx()), 2.dp.toPx())
    },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Surface(color = color, contentColor = if (stage.kind == PanelStageKind.Normal) Color.White else boardNavy,
            shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(min = 32.dp).heightIn(min = 32.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(5.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { contentDescription = "Stage ${index + 1} of $count, ${stage.kind.name.lowercase()}" })
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stage.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            stage.hint?.let { Supporting(it) }
        }
    }
}

@Composable
private fun BridgeStatus(feed: PanelFeed?) {
    val label = when {
        feed?.connected != true -> "Copilot · Not connected"
        feed.busy -> "Copilot · Busy"
        else -> "Copilot · Connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (feed?.connected == true) boardLime else Color(0xFFBCC6D8), CircleShape))
        Text(label, color = Color(0xFFD9E8FF), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun toneColor(tone: PanelTone): Color = when (tone) {
    PanelTone.Ok -> if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF8ED6A3) else Color(0xFF176837)
    PanelTone.Warn -> MaterialTheme.colorScheme.secondary
    PanelTone.Red -> MaterialTheme.colorScheme.error
    PanelTone.Info -> MaterialTheme.colorScheme.primary
    PanelTone.Idle -> MaterialTheme.colorScheme.outline
}

@Composable
private fun PanelCard(item: PanelItem, enabled: Boolean, pending: String?, onAction: (PanelAction) -> Unit,
    onDetail: () -> Unit, attention: Boolean = false, inset: Boolean = true, compact: Boolean = false) {
    Surface(onClick = onDetail, shape = MaterialTheme.shapes.medium,
        color = if (attention) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = if (inset) 16.dp else 0.dp).fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).background(toneColor(item.tone), CircleShape)
                .semantics { contentDescription = "Status ${item.tone.name.lowercase()}" })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (compact) Text("View details", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                else PanelActions(item.actions, enabled, pending, onAction)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelActions(actions: List<PanelAction>, enabled: Boolean, pending: String?, onAction: (PanelAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            val label = if (pending == action.id) "${action.label}…" else action.label
            val modifier = Modifier.heightIn(min = 48.dp)
            when (action.style) {
                PanelActionStyle.Primary -> Button(onClick = { onAction(action) }, enabled = enabled, modifier = modifier) { Text(label) }
                PanelActionStyle.Danger -> OutlinedButton(onClick = { onAction(action) }, enabled = enabled, modifier = modifier,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(label) }
                PanelActionStyle.Default -> OutlinedButton(onClick = { onAction(action) }, enabled = enabled, modifier = modifier) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelDetail(item: PanelItem, enabled: Boolean, pending: String?, onAction: (PanelAction) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var linkError by remember(item.url) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                item.subtitle?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
            item { PanelActions(item.actions, enabled, pending, onAction) }
            item.url?.let { url -> item {
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: ActivityNotFoundException) { linkError = true }
                }) { Text("Open provider link") }
                if (linkError) Supporting("No app can open this link.")
            } }
            item.log?.let { log ->
                item { Text("Recent activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                itemsIndexed(log.entries) { _, entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entry.at?.let { Supporting(it) }
                        SelectionContainer { Text(entry.text) }
                    }
                }
                item { Supporting(if (log.truncated) "Some recent activity was omitted. This is not a full transcript."
                    else "Provider-reported recent activity, not a full transcript.") }
            }
            item { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close details") } }
        }
    }
}
