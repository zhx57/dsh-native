package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.PendingQuestion
import dev.dsh.nativeapp.data.QuestionItem
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * `ask_user_question` batch, rendered as selectable options.
 *
 * The answers live in the host's `userQuestions` projection rather than the event
 * stream, which is why this tool previously appeared to do nothing. Selection is
 * local until submitted so a multi-question batch can be filled in before the
 * model is resumed.
 */
@Composable
fun QuestionCard(
    batch: PendingQuestion,
    onSubmit: (Map<String, List<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    val chosen = remember(batch.callId) {
        mutableStateOf<Map<String, List<String>>>(emptyMap())
    }

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, colors.buttonInfoFill.copy(alpha = 0.45f), RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.inputMajor, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.buttonInfoFill.copy(alpha = 0.10f))
                .padding(horizontal = DshSpacing.md.dp, vertical = 6.dp),
        ) {
            Text(
                text = "需要你选择",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.buttonInfoFill,
            )
        }

        Column(
            Modifier.padding(horizontal = DshSpacing.md.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            batch.questions.forEach { question ->
                QuestionBlock(
                    question = question,
                    selected = chosen.value[question.id].orEmpty(),
                    multiSelect = question.multiSelect,
                    onToggle = { label ->
                        val current = chosen.value[question.id].orEmpty()
                        val next = when {
                            question.multiSelect ->
                                if (label in current) current - label else current + label
                            label in current -> emptyList()
                            else -> listOf(label)
                        }
                        chosen.value = chosen.value + (question.id to next)
                    },
                )
            }

            val complete = batch.questions.all { chosen.value[it.id].orEmpty().isNotEmpty() }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "提交",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (complete) colors.buttonPrimaryForeground else colors.labelCaption,
                    modifier = Modifier
                        .clickable(enabled = complete) { onSubmit(chosen.value) }
                        .background(
                            if (complete) colors.buttonPrimaryFill else colors.bgSkeleton,
                            RoundedCornerShape(DshRadius.full.dp),
                        )
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: QuestionItem,
    selected: List<String>,
    multiSelect: Boolean,
    onToggle: (String) -> Unit,
) {
    val colors = LocalDshColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (question.header != null) {
            Text(
                text = question.header,
                fontSize = 11.sp,
                color = colors.labelCaption,
            )
        }
        Text(
            text = question.question,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = colors.labelPrimary,
        )
        if (question.detail != null) {
            Text(
                text = question.detail,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = colors.labelTertiary,
            )
        }
        question.options.forEach { option ->
            val isOn = option.label in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isOn) colors.sidebarNavItemActive else Color.Transparent,
                        RoundedCornerShape(DshRadius.md.dp),
                    )
                    .clickable { onToggle(option.label) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(15.dp)
                        .border(
                            width = if (isOn) 0.dp else 1.dp,
                            color = colors.borderL4,
                            shape = RoundedCornerShape(if (multiSelect) 4.dp else DshRadius.full.dp),
                        )
                        .background(
                            if (isOn) colors.buttonPrimaryFill else Color.Transparent,
                            RoundedCornerShape(if (multiSelect) 4.dp else DshRadius.full.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isOn) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = colors.buttonPrimaryForeground,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(
                        text = option.label,
                        fontSize = 13.sp,
                        color = if (isOn) colors.labelPrimary else colors.labelSecondary,
                    )
                    if (option.description != null) {
                        Text(
                            text = option.description,
                            fontSize = 11.sp,
                            color = colors.labelCaption,
                        )
                    }
                }
            }
        }
    }
}

