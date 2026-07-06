package com.agenthub.app.ui.workflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.app.R
import com.agenthub.app.data.model.AgentType
import com.agenthub.app.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    onBack: () -> Unit = {}
) {
    var selectedWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val engine = remember { WorkflowEngine() }
    val executionState by engine.executionState.collectAsState()

    if (selectedWorkflow != null) {
        WorkflowDetailScreen(
            workflow = selectedWorkflow!!,
            engine = engine,
            executionState = executionState,
            onBack = { selectedWorkflow = null }
        )
    } else {
        WorkflowListScreen(
            onSelect = { selectedWorkflow = it },
            onCreate = { showCreateDialog = true },
            onBack = onBack
        )
    }

    if (showCreateDialog) {
        CreateWorkflowDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { workflow ->
                selectedWorkflow = workflow
                showCreateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowListScreen(
    onSelect: (Workflow) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit
) {
    val templates = remember { WorkflowTemplates.allTemplates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workflow_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.workflow_create))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Templates section
            Text(
                text = stringResource(R.string.workflow_templates),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates) { workflow ->
                    WorkflowCard(
                        workflow = workflow,
                        onClick = { onSelect(workflow) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    onClick: () -> Unit
) {
    val nodeCount = workflow.nodes.size
    val agentCount = workflow.nodes.count { it.type == NodeType.AGENT }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workflow.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = workflow.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WorkflowBadge(
                        icon = Icons.Default.Hub,
                        text = "$nodeCount nodes"
                    )
                    WorkflowBadge(
                        icon = Icons.Default.SmartToy,
                        text = "$agentCount agents"
                    )
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun WorkflowBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowDetailScreen(
    workflow: Workflow,
    engine: WorkflowEngine,
    executionState: WorkflowExecutionState,
    onBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showRunDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workflow.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showRunDialog = true },
                        enabled = !executionState.isRunning
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.workflow_run),
                            tint = if (executionState.isRunning)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Workflow canvas visualization
            WorkflowCanvas(
                workflow = workflow,
                executionState = executionState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Execution output
            if (executionState.output.isNotEmpty() || executionState.error != null || executionState.logs.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.workflow_output),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (executionState.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        if (executionState.error != null) {
                            Text(
                                text = executionState.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (executionState.output.isNotEmpty()) {
                            Text(
                                text = executionState.output,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        } else if (executionState.logs.isNotEmpty()) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                executionState.logs.forEach { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Run dialog
    if (showRunDialog) {
        AlertDialog(
            onDismissRequest = { showRunDialog = false },
            title = { Text(stringResource(R.string.workflow_run)) },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(stringResource(R.string.workflow_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRunDialog = false
                        coroutineScope.launch {
                            engine.execute(workflow, inputText)
                        }
                    },
                    enabled = inputText.isNotBlank() && !executionState.isRunning
                ) {
                    Text(stringResource(R.string.workflow_run))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRunDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun WorkflowCanvas(
    workflow: Workflow,
    executionState: WorkflowExecutionState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineMuted = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Draw edges
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodeMap = workflow.nodes.associateBy { it.id }
            workflow.edges.forEach { edge ->
                val from = nodeMap[edge.fromNodeId]
                val to = nodeMap[edge.toNodeId]
                if (from != null && to != null) {
                    val startX = from.positionX * density.density + 60 * density.density
                    val startY = from.positionY * density.density + 30 * density.density
                    val endX = to.positionX * density.density
                    val endY = to.positionY * density.density + 30 * density.density

                    val controlX1 = startX + (endX - startX) * 0.4f
                    val controlX2 = startX + (endX - startX) * 0.6f

                    val path = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(controlX1, startY, controlX2, endY, endX, endY)
                    }

                    val edgeColor = if (edge.fromNodeId in executionState.completedNodeIds &&
                        edge.toNodeId in executionState.completedNodeIds
                    ) {
                        primaryColor
                    } else {
                        outlineMuted
                    }

                    drawPath(
                        path = path,
                        color = edgeColor,
                        style = Stroke(width = 2f * density.density)
                    )
                }
            }
        }

        // Draw nodes
        workflow.nodes.forEach { node ->
            val isCurrent = node.id == executionState.currentNodeId
            val isCompleted = node.id in executionState.completedNodeIds

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (node.positionX * density.density).toDp() },
                        y = with(density) { (node.positionY * density.density).toDp() }
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isCurrent -> MaterialTheme.colorScheme.primaryContainer
                            isCompleted -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = when (node.type) {
                            NodeType.INPUT -> Icons.Default.Input
                            NodeType.AGENT -> Icons.Default.SmartToy
                            NodeType.TRANSFORM -> Icons.Default.Transform
                            NodeType.OUTPUT -> Icons.Default.Output
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = when {
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            isCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = node.label.ifEmpty { node.type.displayName },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            isCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.height(2.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp
                        )
                    } else if (isCompleted) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateWorkflowDialog(
    onDismiss: () -> Unit,
    onCreate: (Workflow) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(0) }

    val templates = listOf(
        "Blank" to "Start from scratch",
        "Translation Chain" to "Translate → Review",
        "Code Review" to "Analyze → Suggest",
        "Research Assistant" to "Search → Summarize"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_create)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.workflow_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.workflow_template),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                templates.forEachIndexed { index, (title, subtitle) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTemplate = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTemplate == index,
                            onClick = { selectedTemplate = index }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val workflow = when (selectedTemplate) {
                        0 -> Workflow(
                            name = name.ifEmpty { "Custom Workflow" },
                            description = description,
                            nodes = listOf(
                                WorkflowNode(id = "input", type = NodeType.INPUT, label = "Input", positionX = 50f, positionY = 200f),
                                WorkflowNode(id = "output", type = NodeType.OUTPUT, label = "Output", positionX = 400f, positionY = 200f)
                            ),
                            edges = listOf(WorkflowEdge(fromNodeId = "input", toNodeId = "output"))
                        )
                        1 -> WorkflowTemplates.translationChain().copy(
                            name = name.ifEmpty { "Translation Chain" },
                            description = description.ifEmpty { "Translate → Review & Polish" }
                        )
                        2 -> WorkflowTemplates.codeReview().copy(
                            name = name.ifEmpty { "Code Review" },
                            description = description.ifEmpty { "Analyze → Suggest Improvements" }
                        )
                        3 -> WorkflowTemplates.researchAssistant().copy(
                            name = name.ifEmpty { "Research Assistant" },
                            description = description.ifEmpty { "Search → Summarize" }
                        )
                        else -> WorkflowTemplates.translationChain()
                    }
                    onCreate(workflow)
                }
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
