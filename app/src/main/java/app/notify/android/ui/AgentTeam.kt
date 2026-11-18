package app.notify.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.notify.android.controller.AgentRow
import app.notify.android.controller.ControllerState
import app.notify.android.controller.SubagentCreation

@Composable
internal fun AgentTeam(state: ControllerState, modifier: Modifier, onSelect: (String) -> Unit,
    onManagement: (String, String) -> Unit = { _, _ -> }, onCreationEdit: (SubagentCreation) -> Unit = {}) {
    val workspace = state.workspace
    val detail = workspace.detail
    var showIdle by rememberSaveable(workspace.sessionID) { mutableStateOf(false) }
    var showInactive by rememberSaveable(workspace.sessionID) { mutableStateOf(false) }
    var addSubagent by rememberSaveable(workspace.sessionID) { mutableStateOf(false) }
    val team = remember(detail?.agents, showIdle) { teamPresentation(detail?.agents.orEmpty(), showIdle) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(20.dp)) {
        item {
            Heading("Your agents")
            Text("Select an agent to open its conversation.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val unavailable = workspace.managementUnavailable("create_subagent", state.now)
            Button(onClick = { addSubagent = true }, enabled = state.live && !state.busy && unavailable == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add subagent") }
            if (unavailable != null) Supporting(unavailable)
            else if (!state.live) Supporting("Wait for a fresh connection to your Mac.")
        }
        item {
            val unavailable = workspace.managementUnavailable("stop_idle_subagents", state.now)
            OutlinedButton(onClick = { onManagement("stop_idle_subagents", "") },
                enabled = state.live && !state.busy && unavailable == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop idle >10 min") }
            if (unavailable != null) Text(unavailable, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(workspace.managementDeliveries.entries.filter { it.key.sessionID == workspace.sessionID },
            key = { "management-${it.value.commandID}" }) { result ->
            Supporting("${if (result.key.agentID.isEmpty()) "This session" else "Helper ${result.key.agentID}"} · ${result.value.status}")
        }
        item {
            AgentCard(sessionPresentation(state, workspace.session), workspace.agentID.isEmpty(), isLead = true,
                detail = workspace.session?.agent.orEmpty(), onClick = { onSelect("") })
        }
        if (detail == null) item { Supporting("Waiting for this session’s team.") }
        if (detail?.teamError?.isNotEmpty() == true) item { Text(detail.teamError, color = MaterialTheme.colorScheme.error) }
        if (detail?.truncated == true) item { Supporting("Partial history or team. Earlier or omitted agents may be on your Mac.") }
        items(team.rows, key = { it.agent.id }) { row -> HelperCard(state, row, onSelect, onManagement) }
        if (team.idleCount > 0) item {
            OutlinedButton(onClick = { showIdle = !showIdle }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${if (showIdle) "Hide" else "Show"} ${team.idleCount} idle ${if (team.idleCount == 1) "helper" else "helpers"}")
            }
        }
        if (team.inactiveCount > 0) {
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 8.dp))
                TextButton(onClick = { showInactive = !showInactive },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (showInactive) "Hide" else "Show"} ${team.inactiveCount} inactive · completed, failed or stopped")
                }
            }
            if (showInactive) items(team.inactiveRows, key = { it.agent.id }) { row -> HelperCard(state, row, onSelect, onManagement) }
        }
        if (detail != null && detail.agents.isEmpty() && detail.teamError.isEmpty())
            item { Supporting("No helpers reported for this session.") }
        item {
            Text("Only reported relationships are connected. Hidden, missing or unknown ancestry stays unlinked.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (addSubagent) Dialog(onDismissRequest = { addSubagent = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            SubagentForm(state, onCreationEdit, { onManagement("create_subagent", "") }, { addSubagent = false })
        }
    }
}

@Composable
private fun HelperCard(state: ControllerState, row: AgentRow, onSelect: (String) -> Unit,
    onManagement: (String, String) -> Unit) {
    val agent = row.agent
    val parent = state.workspace.detail?.agents?.firstOrNull { it.id == agent.parentAgentID }?.name ?: agent.parentAgentID
    val relationship = when {
        !agent.parentKnown -> "Parent not reported"
        row.unlinked -> "Unlinked · parent: ${parent ?: "unavailable"}"
        agent.parentAgentID == null -> "Reports to main"
        else -> "Reports to $parent"
    }
    val lineColor = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(3) * 12).dp)) {
        if (!row.unlinked) Canvas(Modifier.width(18.dp).height(48.dp)) {
            drawLine(lineColor, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 1.dp.toPx())
            drawLine(lineColor, Offset(1.dp.toPx(), size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(relationship, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AgentCard(helperPresentation(state, agent), state.workspace.agentID == agent.id,
                detail = listOf(agent.task, agent.model.takeIf { it.isNotEmpty() }?.let { "Model · $it" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString("\n"), onClick = { onSelect(agent.id) })
            if (agent.status in setOf("completed", "failed", "cancelled")) {
                val unavailable = state.workspace.managementUnavailable("remove_subagent", state.now, agent.id)
                OutlinedButton(onClick = { onManagement("remove_subagent", agent.id) },
                    enabled = state.live && !state.busy && unavailable == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Remove ${agent.name} from list"
                    }) { Text("Remove from list") }
                if (unavailable != null) Text(unavailable, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SubagentForm(state: ControllerState, onEdit: (SubagentCreation) -> Unit,
    onCreate: () -> Unit, onClose: () -> Unit) {
    val workspace = state.workspace
    val value = workspace.creationDraft.value
    val unavailable = workspace.managementUnavailable("create_subagent", state.now)
    val invalid = workspace.creationUnavailable()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to agents") }
        Heading("Add subagent")
        Text("For ${workspace.session?.name ?: "unavailable session"}", style = MaterialTheme.typography.titleMedium)
        Text("Session · ${workspace.sessionID.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value.name, { if (it.length <= 160) onEdit(value.copy(name = it)) },
            label = { Text("Name") }, supportingText = { Text("${value.name.length}/160 UTF-16 units") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        LiveCatalogChoice("Agent type", value.agentType.ifEmpty { "Select a live agent type" },
            workspace.session?.subagentTypes.orEmpty(), unavailable == null) { onEdit(value.copy(agentType = it)) }
        LiveCatalogChoice("Model", value.model ?: "Configured default",
            workspace.session?.subagentModels.orEmpty(), unavailable == null,
            onDefault = { onEdit(value.copy(model = null)) }) { onEdit(value.copy(model = it)) }
        Text("Only this live session’s advertised types and models are listed. Configured default uses the runtime’s current configuration; no model is selected by Notify.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value.prompt, { if (it.length <= 16_000) onEdit(value.copy(prompt = it)) },
            label = { Text("Instructions") }, supportingText = { Text("${value.prompt.length}/16,000 UTF-16 units") },
            minLines = 4, modifier = Modifier.fillMaxWidth())
        Text("Model choice can affect credit usage. Creating a helper runs these instructions under the session’s permission rules. Notify does not bypass them.",
            style = MaterialTheme.typography.bodyMedium)
        (unavailable ?: invalid)?.let { Supporting(it) }
        workspace.managementDelivery?.let { Supporting(it.status) }
        Button(onClick = onCreate, enabled = state.live && !state.busy && unavailable == null && invalid == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create subagent") }
    }
}

@Composable
private fun LiveCatalogChoice(label: String, selected: String, ids: List<String>, enabled: Boolean,
    onDefault: (() -> Unit)? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "$label: $selected. Choose from live catalog"
                }) { Text(selected) }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                if (onDefault != null) DropdownMenuItem(text = { Text("Configured default") },
                    onClick = { expanded = false; onDefault() })
                ids.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}

@Composable
private fun AgentCard(presentation: AgentPresentation, isSelected: Boolean, isLead: Boolean = false,
    detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = presentation.canOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = isSelected },
        shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        BoxWithConstraints {
            val artWidth = if (isLead) minOf(139.dp, maxWidth * .42f) else minOf(90.dp, maxWidth * .32f)
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentIllustration(presentation.presence, Modifier.width(artWidth).aspectRatio(180f / 136f), isLead)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(presentation.name, style = MaterialTheme.typography.titleSmall)
                    Text(presentation.label, style = MaterialTheme.typography.bodySmall,
                        color = if (presentation.presence == AgentPresence.Waiting) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (presentation.presence in setOf(AgentPresence.Completed, AgentPresence.Failed))
                        Text("View history", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
