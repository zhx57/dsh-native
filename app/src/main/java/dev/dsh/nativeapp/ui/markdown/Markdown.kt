package dev.dsh.nativeapp.ui.markdown

/** One block of parsed markdown. */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class BulletList(val items: List<List<MdSpan>>) : MdBlock
    data class OrderedList(val start: Int, val items: List<List<MdSpan>>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** Inline span with the emphasis flags the renderer needs. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/**
 * A compact markdown parser covering what assistant responses actually use.
 *
 * DSH renders through a full CommonMark pipeline in the browser; this covers the
 * block and inline constructs that appear in agent output — headings, fenced
 * code, lists, quotes, tables, rules and inline emphasis/link/code — so the
 * native transcript reads the same without pulling a renderer into the APK.
 */
object Markdown {

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            when {
                line.isBlank() -> index++

                line.trimStart().startsWith("```") -> {
                    val fence = line.trimStart().removePrefix("```")
                    val language = fence.trim().ifEmpty { null }
                    val body = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[index])
                        index++
                    }
                    if (index < lines.size) index++
                    blocks += MdBlock.Code(language, body.toString())
                }

                HEADING.matches(line) -> {
                    val match = HEADING.find(line)!!
                    val level = match.groupValues[1].length.coerceIn(1, 6)
                    blocks += MdBlock.Heading(level, inline(match.groupValues[2].trim()))
                    index++
                }

                RULE.matches(line.trim()) -> {
                    blocks += MdBlock.Rule
                    index++
                }

                line.trimStart().startsWith(">") -> {
                    val quoted = StringBuilder()
                    while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                        if (quoted.isNotEmpty()) quoted.append('\n')
                        quoted.append(lines[index].trimStart().removePrefix(">").trimStart())
                        index++
                    }
                    blocks += MdBlock.Quote(inline(quoted.toString()))
                }

                isTableHeader(lines, index) -> {
                    val header = splitTableRow(lines[index])
                    index += 2
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                        rows += splitTableRow(lines[index])
                        index++
                    }
                    blocks += MdBlock.Table(header, rows)
                }

                BULLET.matches(line) -> {
                    val items = mutableListOf<List<MdSpan>>()
                    while (index < lines.size && BULLET.matches(lines[index])) {
                        items += inline(lines[index].trimStart().removePrefix("- ").removePrefix("* ").trim())
                        index++
                    }
                    blocks += MdBlock.BulletList(items)
                }

                ORDERED.matches(line) -> {
                    val start = ORDERED.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val items = mutableListOf<List<MdSpan>>()
                    while (index < lines.size && ORDERED.matches(lines[index])) {
                        items += inline(ORDERED.find(lines[index])!!.groupValues[2].trim())
                        index++
                    }
                    blocks += MdBlock.OrderedList(start, items)
                }

                else -> {
                    val paragraph = StringBuilder()
                    while (index < lines.size &&
                        lines[index].isNotBlank() &&
                        !startsBlock(lines, index)
                    ) {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(lines[index].trim())
                        index++
                    }
                    if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(inline(paragraph.toString()))
                    else index++
                }
            }
        }
        return blocks
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return line.trimStart().startsWith("```") ||
            HEADING.matches(line) ||
            RULE.matches(line.trim()) ||
            line.trimStart().startsWith(">") ||
            BULLET.matches(line) ||
            ORDERED.matches(line) ||
            isTableHeader(lines, index)
    }

    private fun isTableHeader(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        if (!lines[index].contains('|')) return false
        val separator = lines[index + 1].trim()
        return separator.isNotEmpty() && separator.all { it == '|' || it == '-' || it == ':' || it == ' ' } &&
            separator.contains('-')
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    // ---------------------------------------------------------------- inline

    private val INLINE = Regex(
        "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(~~[^~]+~~)|(\\*[^*\\n]+\\*)|(_[^_\\n]+_)|(!?\\[[^\\]]*]\\([^)\\s]+\\))|(<https?://[^>]+>)",
    )

    fun inline(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<MdSpan>()
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) {
                spans += MdSpan(text.substring(cursor, match.range.first))
            }
            val raw = match.value
            val span = when {
                raw.startsWith("`") && raw.endsWith("`") -> MdSpan(raw.trim('`'), code = true)
                raw.startsWith("**") -> MdSpan(raw.trim('*'), bold = true)
                raw.startsWith("__") -> MdSpan(raw.trim('_'), bold = true)
                raw.startsWith("~~") -> MdSpan(raw.trim('~'), strike = true)
                raw.startsWith("*") -> MdSpan(raw.trim('*'), italic = true)
                raw.startsWith("_") -> MdSpan(raw.trim('_'), italic = true)
                raw.startsWith("<") -> MdSpan(raw.trim('<', '>'), link = raw.trim('<', '>'))
                else -> {
                    // [label](target) — image markers degrade to the label text.
                    val label = raw.substringAfter('[').substringBefore(']')
                    val target = raw.substringAfter("](").removeSuffix(")")
                    MdSpan(label, link = target)
                }
            }
            spans += span
            cursor = match.range.last + 1
        }
        if (cursor < text.length) spans += MdSpan(text.substring(cursor))
        return spans
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val BULLET = Regex("^\\s*[-*+]\\s+.*$")
    private val ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
}
