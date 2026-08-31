package com.zhhz.spider.ui.widget.novel.android

import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.zhhz.spider.ui.widget.novel.NovelElement
import com.zhhz.spider.ui.widget.novel.ReadBookConfig
import com.zhhz.spider.ui.widget.novel.TextLine
import com.zhhz.spider.ui.widget.novel.TextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LINE_CHAR = "\n"

private const val SRC_REPLACE_CHAR = "▩"

// 匹配 img 标签，提取 src 参数
private val imgPattern = Regex("""<img[^>]*src=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>""", RegexOption.IGNORE_CASE)

object AndroidReadProvider {

    val textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textAlign = Paint.Align.LEFT
    }

    suspend fun measureTextInBackground(
        text: String,
        constraints: Constraints,
        config: ReadBookConfig,
        density: Density
    ): List<TextPage> {
        return withContext(Dispatchers.Default) {
            textPaint.textSize = config.textSize * density.density
            textPaint.letterSpacing = config.letterSpacing

            val maxWidth = (constraints.maxWidth - config.margin * 2 * density.density).toInt()

            val lines = mutableListOf<TextLine>()

            // 插图替换：提取 <img> 标签，记录地址，替换为占位符
            val imageList = mutableListOf<String>()
            val textStr = text.replace(imgPattern) { matchResult ->
                imageList.add(matchResult.value)
                SRC_REPLACE_CHAR
            }.replace("\r\n", LINE_CHAR).replace("\r", LINE_CHAR)

            val contents = arrayListOf<String>()
            if (config.firstLineIndentation) {
                textStr.split(LINE_CHAR).forEach { str ->
                    val paragraph = str.trim { it.code <= 0x20 || it == '　' }
                    if (paragraph.isNotEmpty()) {
                        contents.add("　　$paragraph")
                    } else if (str.isEmpty()) {
                        contents.add(LINE_CHAR)
                    }
                }
            } else {
                contents.addAll(textStr.split(LINE_CHAR))
            }

            for (str in contents) {
                if (str == LINE_CHAR){
                    if (config.preserveEmptyLine) {
                        val textLine = TextLine(LINE_CHAR)
                        textLine.addElement(NovelElement.TextRun(LINE_CHAR))
                        textLine.height = textPaint.textHeight
                        lines.add(textLine)
                    }
                } else if (str == SRC_REPLACE_CHAR && imageList.isNotEmpty()) {
                    // 整行都是插图
                    val line = TextLine(SRC_REPLACE_CHAR)
                    line.addElement(NovelElement.Illustration(imageList.removeAt(0)))
                    line.height = textPaint.textHeight * 4
                    lines.add(line)
                } else {
                    textParse(str, maxWidth, lines, config)
                }
            }

            generateAllTextPage(lines, constraints, config)
        }
    }

    private fun textParse(
        text: String,
        maxWidth: Int,
        lines: MutableList<TextLine>,
        config: ReadBookConfig
    ) {
        val widthsArray = FloatArray(text.length)
        textPaint.getTextWidths(text, widthsArray)

        val (words, widths) = measureTextSplit(text, widthsArray)
        val layout = AndroidZhLayout(text, textPaint, maxWidth, words, widths, 2)

        val textHeight = textPaint.textHeight

        for (lineIndex in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (lineWords, lineWidths) = measureTextSplit(lineText, widthsArray, lineStart)

            val textLine = TextLine(lineText)
            textLine.height = textHeight
            lines.add(textLine)

            var curX = config.margin * 1f

            for (index in lineWords.indices) {
                val charWidth = lineWidths[index]
                val tmpX = curX + charWidth

                val element = NovelElement.TextRun(text = lineWords[index])
                element.rect.left = curX
                element.rect.right = tmpX
                curX = tmpX
                textLine.addElement(element)
            }

            if (config.isJustify) {
                when {
                    lineIndex == 0 && layout.lineCount > 1 -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat(), true)
                    }
                    lineIndex == layout.lineCount - 1 -> {
                        textLine.isParagraphEnd = true
                    }
                    else -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat())
                    }
                }
            }
        }
    }

    private fun generateAllTextPage(
        textLines: MutableList<TextLine>,
        constraints: Constraints,
        config: ReadBookConfig
    ): List<TextPage> {
        val textPageList = mutableListOf<TextPage>()

        val header = textPaint.textHeight * 0.65f
        val footer = textPaint.textHeight * 0.65f
        val marginPx = config.margin * 1f
        val maxHeight = constraints.maxHeight - marginPx * 2 - header - footer

        val height = textLines.firstOrNull()?.height ?: textPaint.textHeight
        val actualHeight = height * config.lineSpacingRatio + config.lineSpacing
        val extraHeight = actualHeight - height

        var fontIndex = 0
        var textPage = TextPage(fontIndex, text = "")
        textPageList.add(textPage)
        var curY = 0f

        for (textLine in textLines) {
            if (curY + height > maxHeight) {
                if (config.ignoreLastEmptyLine) {
                    while (textPage.textLines.isNotEmpty() && textPage.textLines.last().text == LINE_CHAR) {
                        textPage.textLines.removeAt(textPage.textLines.size - 1)
                    }
                }
                fontIndex += textPage.text.length
                textPage = TextPage(fontIndex, text = "")
                textPageList.add(textPage)
                curY = 0f
            }

            if (config.ignoreFirstEmptyLine && textPage.textLines.isEmpty() && textLine.text == LINE_CHAR) {
                continue
            }

            textPage.addTextLine(textLine)
            curY += height

            val spacingHeight = extraHeight + if (textLine.isParagraphEnd) config.paragraphSpacing.toFloat() else 0f

            if (curY + spacingHeight > maxHeight) {
                if (config.ignoreLastEmptyLine) {
                    while (textPage.textLines.isNotEmpty() && textPage.textLines.last().text == LINE_CHAR) {
                        textPage.textLines.removeAt(textPage.textLines.size - 1)
                    }
                }
                fontIndex += textPage.text.length
                textPage = TextPage(fontIndex, text = "")
                textPageList.add(textPage)
                curY = 0f
            } else {
                curY += spacingHeight
            }
        }

        for (i in textPageList.size - 1 downTo 0) {
            if (textPageList[i].textLines.isNotEmpty()) break
            textPageList.removeAt(i)
        }

        verticallyJustifyPages(textPageList, maxHeight - textPaint.fontMetrics.descent, height, extraHeight, config)

        for (page in textPageList) {
            if (page.textLines.isNotEmpty()) {
                page.textLines.first().height += header
            }
        }

        return textPageList
    }

    private fun verticallyJustifyPages(
        textPageList: List<TextPage>,
        maxHeight: Float,
        height: Float,
        baseExtraHeight: Float,
        config: ReadBookConfig
    ) {
        for (pageIndex in 0 until textPageList.size) {
            val page = textPageList[pageIndex]
            val lines = page.textLines
            if (lines.size <= 1) continue

            val totalTextInkHeight = height * lines.size
            var totalBaseSpacingHeight = 0f
            for (lineIndex in 0 until lines.size - 1) {
                if (lines[lineIndex].isParagraphEnd) {
                    totalBaseSpacingHeight += config.paragraphSpacing
                } else {
                    totalBaseSpacingHeight += baseExtraHeight
                }
            }

            val totalUsedHeightBeforeJustify = totalTextInkHeight + totalBaseSpacingHeight
            val stretchableWhitespace = maxHeight - totalUsedHeightBeforeJustify

            if (stretchableWhitespace <= 0) {
                for (lineIndex in 0 until lines.size - 1) {
                    val line = lines[lineIndex]
                    line.extraHeight = if (line.isParagraphEnd) config.paragraphSpacing.toFloat() else baseExtraHeight
                }
                if (lines.isNotEmpty()) lines.last().extraHeight = 0f
                continue
            }

            val gapCount = lines.size - 1
            val justificationIncrement = if (pageIndex == textPageList.size - 1) 0f else stretchableWhitespace / gapCount

            for (lineIndex in 0 until lines.size - 1) {
                val line = lines[lineIndex]
                if (line.isParagraphEnd) {
                    line.extraHeight = config.paragraphSpacing + justificationIncrement
                } else {
                    line.extraHeight = baseExtraHeight + justificationIncrement
                }
            }
            if (lines.isNotEmpty()) lines.last().extraHeight = 0f
        }
    }

    private fun justifyTextLine(
        elements: List<NovelElement>,
        visibleWidth: Float,
        hasIndent: Boolean = false
    ) {
        val num = if (hasIndent) 2 else 0
        if (elements.size <= num + 1) return

        val residualWidth = visibleWidth - elements.sumOf { it.rect.width.toDouble() }.toFloat()
        val increment = residualWidth / (elements.size - 1 - num)

        for ((index, element) in elements.withIndex()) {
            if (num > index) continue
            element.rect.right += increment * (index - num)
            element.rect.left += increment * (index - num)
        }
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start until start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8288
    }
}
