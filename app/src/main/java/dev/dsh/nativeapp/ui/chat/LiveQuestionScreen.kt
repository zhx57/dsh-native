package dev.dsh.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.data.LiveQuestion
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/**
 * Full-screen answer surface for a pending question.
 *
 * The inline card could not be used on a watch-width screen: the options alone are
 * taller than the viewport, and the composer column it lived in does not scroll, so
 * the sheet could neither be read nor dismissed. Taking the whole screen and making
 * the body scrollable fixes both, and a free-text field is offered because a model
 * may ask something its suggested labels do not cover.
 */
@Composable
fun LiveQuestionScreen(
    question: LiveQuestion,
    onSubmit: (Map<String, List<String>>) -> Unit,
    onDismiss: () -> Unit,
    submitting: Boolean = false,
    error: String? = null,
) {
    val colors = LocalDshColors.current
    var chosen by remember(question.eventId) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var custom by remember(question.eventId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    // The answer value carries the text the operator typed, so a question without
    // usable options is still answerable.
    fun effective(): Map<String, List<String>> = question.questions.associate { prompt ->
        val picked = chosen[prompt.id].orEmpty()
        val typed = custom[prompt.id]?.trim().orEmpty()
        prompt.id to (picked + listOfNotNull(typed.takeIf { it.isNotEmpty() }))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.md.dp, vertical = DshSpacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DSH 在等你回答",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "收起",
                    fontSize = 12.sp,
                    color = colors.labelTertiary,
                    modifier = Modifier
                        .background(colors.selector, RoundedCornerShape(DshRadius.full.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // The body scrolls: option lists regularly exceed a watch viewport.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DshSpacing.md.dp),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.md.dp),
            ) {
                question.questions.forEach { prompt ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (prompt.header != null) {
                            Text(
                                text = prompt.header,
                                fontSize = 11.sp,
                                color = colors.labelCaption,
                            )
                        }
                        Text(
                            text = prompt.question,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.labelPrimary,
                        )
                        OptionList(
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
                        CustomAnswerField(
                            value = custom[prompt.id].orEmpty(),
                            onChange = { text -> custom = custom + (prompt.id to text) },
                        )
                    }
                }
                Spacer(Modifier.height(DshSpacing.sm.dp))
            }

            val complete = question.questions.all {
                chosen[it.id].orEmpty().isNotEmpty() || !custom[it.id].isNullOrBlank()
            }
            // A failed submit used to report into the chat screen behind this
            // full-screen surface, so it looked like nothing happened at all.
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = colors.stateError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpacing.md.dp, vertical = 4.dp),
                )
            }
            Text(
                text = if (submitting) "提交中…" else "提交",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (complete && !submitting) colors.buttonPrimaryForeground else colors.labelCaption,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.md.dp, vertical = DshSpacing.sm.dp)
                    .background(
                        if (complete && !submitting) colors.buttonPrimaryFill else colors.bgSkeleton,
                        RoundedCornerShape(DshRadius.full.dp),
                    )
                    // Repeat taps while a submit is in flight would send the same
                    // answer twice, which the Host rejects as a settled waterfall.
                    .clickable(enabled = complete && !submitting) { onSubmit(effective()) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** Option row list for one prompt. */
@Composable
private fun OptionList(
    prompt: dev.dsh.nativeapp.data.QuestionPrompt,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    val colors = LocalDshColors.current
    prompt.options.forEach { option ->
        val isOn = option.label in selected
        val shape = RoundedCornerShape(if (prompt.multiSelect) 4.dp else DshRadius.full.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (isOn) colors.sidebarNavItemActive else colors.sidebarFill,
                    RoundedCornerShape(DshRadius.md.dp),
                )
                .clickable { onToggle(option.label) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(15.dp)
                    .height(15.dp)
                    .background(
                        if (isOn) colors.buttonPrimaryFill else colors.bgBase,
                        shape,
                    ),
            )
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

/** Free-text answer, because a suggested label rarely covers every case. */
@Composable
private fun CustomAnswerField(value: String, onChange: (String) -> Unit) {
    val colors = LocalDshColors.current
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        textStyle = TextStyle(fontSize = 13.sp, color = colors.labelPrimary, lineHeight = 19.sp),
        cursorBrush = SolidColor(colors.labelPrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sidebarFill, RoundedCornerShape(DshRadius.md.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        text = "或者直接输入回答…",
                        fontSize = 13.sp,
                        color = colors.labelCaption,
                    )
                }
                inner()
            }
        },
    )
}
