package app.notify.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import app.notify.android.controller.AgentRow
import app.notify.android.controller.ControllerState

@Composable
internal fun AgentTeam(state: ControllerState, modifier: Modifier, onSelect: (String) -> Unit) {
    val workspace = state.workspace
    val detail = workspace.detail
    var showIdle by rememberSaveable(workspace.sessionID) { mutableStateOf(false) }
    var showInactive by rememberSaveable(workspace.sessionID) { mutableStateOf(false) }
    val team = remember(detail?.agents, showIdle) { teamPresentation(detail?.agents.orEmpty(), showIdle) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(20.dp)) {
        item {
            Heading("Your agents")
            Text("Select an agent to open its conversation.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            AgentCard(sessionPresentation(state, workspace.session), workspace.agentID.isEmpty(), isLead = true,
                detail = workspace.session?.agent.orEmpty(), onClick = { onSelect("") })
        }
        if (detail == null) item { Supporting("Waiting for this session’s team.") }
        if (detail?.teamError?.isNotEmpty() == true) item { Text(detail.teamError, color = MaterialTheme.colorScheme.error) }
        if (detail?.truncated == true) item { Supporting("Partial history or team. Earlier or omitted agents may be on your Mac.") }
        items(team.rows, key = { it.agent.id }) { row -> HelperCard(state, row, onSelect) }
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
            if (showInactive) items(team.inactiveRows, key = { it.agent.id }) { row -> HelperCard(state, row, onSelect) }
        }
        if (detail != null && detail.agents.isEmpty() && detail.teamError.isEmpty())
            item { Supporting("No helpers reported for this session.") }
        item {
            Text("Only reported relationships are connected. Hidden, missing or unknown ancestry stays unlinked.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelperCard(state: ControllerState, row: AgentRow, onSelect: (String) -> Unit) {
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
