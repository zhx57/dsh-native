package dev.dsh.nativeapp.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** Renders one markdown document with the DSH transcript's typography and colors. */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    codeFontSize: Int = 12,
) {
    val colors = LocalDshColors.current
    val blocks = remember(source) { Markdown.parse(source) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.spans.toAnnotated(),
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = colors.labelPrimary,
                )

                is MdBlock.Heading -> Text(
                    text = block.spans.toAnnotated(),
                    fontSize = when (block.level) {
                        1 -> 21.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.sp
                    },
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 28.sp,
                    color = colors.labelPrimary,
                )

                is MdBlock.Code -> CodeBlock(block, codeFontSize)

                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "•",
                                fontSize = 15.sp,
                                color = colors.labelTertiary,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(
                                text = item.toAnnotated(),
                                fontSize = 15.sp,
                                lineHeight = 24.sp,
                                color = colors.labelPrimary,
                            )
                        }
                    }
                }

                is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEachIndexed { offset, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${block.start + offset}.",
                                fontSize = 15.sp,
                                color = colors.labelTertiary,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = item.toAnnotated(),
                                fontSize = 15.sp,
                                lineHeight = 24.sp,
                                color = colors.labelPrimary,
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .padding(end = DshSpacing.md.dp)
                            .width(3.dp)
                            .background(colors.borderL4, RoundedCornerShape(DshRadius.full.dp))
                            .size(width = 3.dp, height = 20.dp),
                    )
                    Text(
                        text = block.spans.toAnnotated(),
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = colors.labelSecondary,
                    )
                }

                is MdBlock.Table -> TableBlock(block)

                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(colors.borderL2),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, codeFontSize: Int) {
    val colors = LocalDshColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.lg.dp))
            .background(colors.codeBlock, RoundedCornerShape(DshRadius.lg.dp)),
    ) {
        if (!block.language.isNullOrBlank()) {
            Text(
                text = block.language,
                fontSize = 11.sp,
                color = colors.labelTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.codeBlockBanner)
                    .padding(horizontal = DshSpacing.md.dp, vertical = 5.dp),
            )
        }
        Text(
            text = block.code,
            fontSize = codeFontSize.sp,
            lineHeight = (codeFontSize + 7).sp,
            fontFamily = FontFamily.Monospace,
            color = colors.labelPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(DshSpacing.md.dp),
        )
    }
}

/**
 * Table sized to the transcript column.
 *
 * The web build renders tables in a scrollable box with intrinsic column widths;
 * on a phone that pushes most columns off-screen. Here every column takes an equal
 * share of the available width and wraps, so a four-column comparison stays
 * readable without horizontal scrolling. Text that is still too wide (long paths,
 * code identifiers) breaks instead of clipping.
 */
@Composable
private fun TableBlock(block: MdBlock.Table) {
    val colors = LocalDshColors.current
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.md.dp))
            .clip(RoundedCornerShape(DshRadius.md.dp)),
    ) {
        Row(Modifier.background(colors.codeBlockBanner)) {
            repeat(columnCount) { column ->
                val cell = block.header.getOrNull(column).orEmpty()
                TableCell(
                    spans = cell,
                    weight = 1f,
                    bold = true,
                    color = colors.labelPrimary,
                    align = block.alignments.getOrNull(column) ?: MdAlign.Start,
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (rowIndex % 2 == 1) colors.codeBlock.copy(alpha = 0.5f) else colors.bgBase,
                    ),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(columnCount) { column ->
                        val cell = row.getOrNull(column).orEmpty()
                        TableCell(
                            spans = cell,
                            weight = 1f,
                            bold = false,
                            color = colors.labelSecondary,
                            align = block.alignments.getOrNull(column) ?: MdAlign.Start,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    spans: List<MdSpan>,
    weight: Float,
    bold: Boolean,
    color: androidx.compose.ui.graphics.Color,
    align: MdAlign,
) {
    val textAlign = when (align) {
        MdAlign.Start -> TextAlign.Start
        MdAlign.Center -> TextAlign.Center
        MdAlign.End -> TextAlign.End
    }
    Text(
        text = spans.toAnnotated(),
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        textAlign = textAlign,
        softWrap = true,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

/** Build the styled string for one inline span run. */
fun List<MdSpan>.toAnnotated(): AnnotatedString = buildAnnotatedString {
    this@toAnnotated.forEach { span ->
        val start = length
        append(span.text)
        val end = length
        if (end == start) return@forEach
        addStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                textDecoration = when {
                    span.link != null -> TextDecoration.Underline
                    span.strike -> TextDecoration.LineThrough
                    else -> null
                },
            ),
            start,
            end,
        )
    }
}
