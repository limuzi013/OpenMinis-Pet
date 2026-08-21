package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.ApprovalSeam
import com.openminis.app.tools.MessageFeedbackStore
import com.openminis.app.tools.PendingQuestion
import com.openminis.app.tools.QuestionAnswer
import com.openminis.app.tools.QuestionCenter
import com.openminis.app.ui.components.MinisTextButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Mobile counterparts of the Web Remote agent features, styled with the
 * same primitives as the rest of the app (MinisAlertDialog-style Surface,
 * MaterialTheme typography, MinisTextButton):
 *  - [AskUserQuestionDialog]: pending `ask_user_question` card.
 *  - [DangerousOperationApprovalDialog]: one-time approval for destructive tools.
 *  - [AgentStateBars]: goal / todo / plan / deliverables above the composer.
 *  - [MessageFeedbackRow]: 👍/👎 under assistant messages.
 */

@Composable
fun AskUserQuestionDialog(sessionId: String) {
    var question by remember { mutableStateOf<PendingQuestion?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var custom by remember { mutableStateOf("") }

    LaunchedEffect(sessionId) {
        while (isActive) {
            val q = QuestionCenter.pendingFor(sessionId).firstOrNull()
            if (q != null && question?.id != q.id) {
                question = q
                selected = emptySet()
                custom = ""
            } else if (q == null) {
                question = null
            }
            delay(500)
        }
    }

    val q = question ?: return
    fun submit(skip: Boolean) {
        QuestionCenter.answer(
            q.id,
            QuestionAnswer(
                selected = if (skip) emptyList() else selected.toList(),
                custom = if (skip) null else custom.trim().ifEmpty { null },
                skipped = skip,
            ),
        )
        question = null
    }

    Dialog(
        onDismissRequest = { submit(skip = true) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "模型在等你回答",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = q.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    q.options.forEach { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            if (q.multiple) {
                                Checkbox(
                                    checked = opt.value in selected,
                                    onCheckedChange = { on ->
                                        selected = if (on) selected + opt.value else selected - opt.value
                                    },
                                )
                            } else {
                                RadioButton(
                                    selected = opt.value in selected,
                                    onClick = {
                                        selected = setOf(opt.value)
                                        submit(skip = false)
                                    },
                                )
                            }
                            Text(
                                text = opt.label + if (opt.recommended) "（推荐）" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    if (q.allowCustom) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it.take(2000) },
                            label = { Text("自定义答案") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    MinisTextButton(onClick = { submit(skip = true) }) { Text("跳过") }
                    MinisTextButton(
                        onClick = { submit(skip = false) },
                        enabled = q.multiple || selected.isNotEmpty() || custom.isNotBlank(),
                    ) { Text("提交") }
                }
            }
        }
    }
}

/**
 * Native counterpart to Web Remote's `agent.approval.*` controls. A dangerous
 * tool call is suspended by [ApprovalSeam] while this dialog polls the shared
 * in-process request registry. Dismissal is deliberately a rejection: an
 * unintentional back press must never execute the pending command.
 */
@Composable
fun DangerousOperationApprovalDialog(sessionId: String) {
    var request by remember { mutableStateOf<ApprovalSeam.ApprovalRequest?>(null) }

    LaunchedEffect(sessionId) {
        while (isActive) {
            request = ApprovalSeam.pendingFor(sessionId).firstOrNull()
            delay(350)
        }
    }

    val pending = request ?: return
    fun decide(allowed: Boolean) {
        ApprovalSeam.answer(pending.id, allowed)
        request = null
    }

    Dialog(
        onDismissRequest = { decide(allowed = false) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = "需要你的批准",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Agent 想执行一项可能造成破坏的操作。请先核对以下内容：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = pending.toolName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = pending.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MinisTextButton(onClick = { decide(allowed = false) }) { Text("拒绝") }
                    MinisTextButton(onClick = { decide(allowed = true) }) { Text("仅此一次允许") }
                }
            }
        }
    }
}

@Composable
fun AgentStateBars(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var goal by remember { mutableStateOf(AgentStateStore.Goal()) }
    var todo by remember { mutableStateOf(AgentStateStore.TodoList()) }
    var plan by remember { mutableStateOf(AgentStateStore.Plan()) }
    var deliverables by remember { mutableStateOf<List<AgentStateStore.Deliverable>>(emptyList()) }
    var editingGoal by remember { mutableStateOf(false) }
    var goalDraft by remember { mutableStateOf("") }

    LaunchedEffect(sessionId) {
        while (isActive) {
            goal = AgentStateStore.goalGet(sessionId)
            todo = AgentStateStore.todoGet(sessionId)
            plan = AgentStateStore.planGet(sessionId)
            deliverables = AgentStateStore.deliverablesGet(sessionId)
            delay(1500)
        }
    }

    val hasContent = goal.text.isNotBlank() || todo.items.isNotEmpty() ||
        plan.mode == "plan" || deliverables.isNotEmpty()
    if (!hasContent) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (plan.mode == "plan") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Plan",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = plan.plan.ifBlank { "先计划，再执行。" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MinisTextButton(
                            onClick = { AgentStateStore.planSet(sessionId, "off") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("退出", style = MaterialTheme.typography.labelMedium) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (goal.text.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎯", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = goal.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MinisTextButton(
                            onClick = {
                                goalDraft = goal.text
                                editingGoal = true
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                        MinisTextButton(
                            onClick = { AgentStateStore.goalSetActive(sessionId, !goal.active) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text(if (goal.active) "暂停" else "恢复", style = MaterialTheme.typography.labelMedium) }
                        MinisTextButton(
                            onClick = { AgentStateStore.goalSet(sessionId, "") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("清除", style = MaterialTheme.typography.labelMedium) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (todo.items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        todo.items.take(4).forEach { item ->
                            val mark = when (item.status) {
                                "completed" -> "✅"
                                "in_progress" -> "🔄"
                                else -> "⬜"
                            }
                            Text(
                                text = "$mark ${item.title}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (deliverables.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📄", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            deliverables.take(4).forEach { d ->
                                Text(
                                    text = d.path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("deliverable", d.path))
                                            Toast.makeText(context, "已复制路径：${d.path}", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (deliverables.size > 4) {
                                Text(
                                    text = "…还有 ${deliverables.size - 4} 个",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        MinisTextButton(
                            onClick = {
                                AgentStateStore.deliverablesClear(sessionId)
                                deliverables = emptyList()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("清除", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }

    if (editingGoal) {
        Dialog(
            onDismissRequest = { editingGoal = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Text(
                        text = "编辑目标",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = goalDraft,
                        onValueChange = { goalDraft = it.take(500) },
                        label = { Text("目标") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MinisTextButton(onClick = { editingGoal = false }) { Text("取消") }
                        MinisTextButton(
                            onClick = {
                                AgentStateStore.goalSet(sessionId, goalDraft)
                                editingGoal = false
                            },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageFeedbackRow(messageId: String) {
    val context = LocalContext.current
    var kind by remember(messageId) { mutableStateOf<String?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Text(
            text = if (kind == null) "这条回答有用吗？" else (if (kind == "up") "已标记有用 👍" else "已标记不满意 👎"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MinisTextButton(
            onClick = {
                val next = if (kind == "up") "down" else "up"
                MessageFeedbackStore.put(context, messageId, next)
                kind = next
            },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("👍", fontSize = 14.sp) }
        MinisTextButton(
            onClick = {
                val next = if (kind == "down") "up" else "down"
                MessageFeedbackStore.put(context, messageId, next)
                kind = next
            },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("👎", fontSize = 14.sp) }
    }
}
