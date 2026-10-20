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
import dev.dsh.nativeapp.data.LiveQuestion
import dev.dsh.nativeapp.data.QuestionPrompt
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * A question the Host is currently blocked on.
 *
 * This is the answer path that actually works: the request arrives as a waterfall
 * on the `$events` channel and the answer goes back through `$events/result`. The
 * `userQuestions` projection is only a mirror that this Host never fills, so the
 * card must not depend on it.
 */
@Composable
fun LiveQuestionCard(
    question: LiveQuestion,
    onSubmit: (Map<String, List<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshColors.current
    var chosen by remember(question.eventId) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    Column(
        modifier
            .fillMaxWidth()
            .border(1.5.dp, colors.buttonInfoFill.copy(alpha = 0.6f), RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.inputMajor, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.buttonInfoFill.copy(alpha = 0.14f))
                .padding(horizontal = DshSpacing.md.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DSH 在等你回答",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.buttonInfoFill,
            )
        }

        Column(
            Modifier.padding(horizontal = DshSpacing.md.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            question.questions.forEach { prompt ->
                LiveQuestionBlock(
                    prompt = prompt,
                    selected = chosen[prompt.id].orEmpty(),
                    onToggle = { label ->
                        val current = chosen[prompt.id].orEmpty()
                        val next = when {
                            prompt.multiSelect ->
                                if (label in current) current - label else current + label
                            label in current -> emptyList()
                            else -> listOf(label)
                        }
                        chosen = chosen + (prompt.id to next)
                    },
                )
            }

            val complete = question.questions.all { chosen[it.id].orEmpty().isNotEmpty() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "提交",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (complete) colors.buttonPrimaryForeground else colors.labelCaption,
                    modifier = Modifier
                        .clickable(enabled = complete) { onSubmit(chosen) }
                        .background(
                            if (complete) colors.buttonPrimaryFill else colors.bgSkeleton,
                            RoundedCornerShape(DshRadius.full.dp),
                        )
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveQuestionBlock(
    prompt: QuestionPrompt,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    val colors = LocalDshColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prompt.header != null) {
            Text(text = prompt.header, fontSize = 11.sp, color = colors.labelCaption)
        }
        Text(
            text = prompt.question,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            color = colors.labelPrimary,
        )
        prompt.options.forEach { option ->
            val isOn = option.label in selected
            val shape = RoundedCornerShape(if (prompt.multiSelect) 4.dp else DshRadius.full.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isOn) colors.sidebarNavItemActive else Color.Transparent,
                        RoundedCornerShape(DshRadius.md.dp),
                    )
                    .clickable { onToggle(option.label) }
                    .padding(horizontal = 9.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(15.dp)
                        .then(
                            if (isOn) Modifier.background(colors.buttonPrimaryFill, shape)
                            else Modifier.border(1.dp, colors.borderL4, shape),
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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = if (isOn) colors.labelPrimary else colors.labelSecondary,
                    )
                    if (option.description != null) {
                        Text(
                            text = option.description,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = colors.labelCaption,
                        )
                    }
                }
            }
        }
    }
}
