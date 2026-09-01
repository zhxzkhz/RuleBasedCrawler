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
private val imgPattern = Regex("""<img[^>]*\bsrc=['"]([^'"]*)['"][^>]*>""", RegexOption.IGNORE_CASE)
private val inlineImageMarkerPattern = Regex("""▩([^▩]+)▩""")

object AndroidReadProvider {

    val textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    suspend fun measureTextInBackground(
        text: String,
        constraints: Constraints,
        config: ReadBookConfig,
        density: Density
    ): List<TextPage> {
        return withContext(Dispatchers.Default) {
            val layoutPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.LEFT
                textSize = config.textSize * density.density
                letterSpacing = config.letterSpacing
            }

            val marginPx = config.margin * density.density
            val maxWidth = (constraints.maxWidth - marginPx * 2).toInt()

            val lines = mutableListOf<TextLine>()

            // 同时兼容旧缓存中的 <img> 和新正文缓存中的 ▩图片地址▩ 标记。
            val imageList = mutableListOf<String>()
            val textStr = text.replace(imgPattern) { matchResult ->
                imageList.add(matchResult.groupValues[1].trim())
                SRC_REPLACE_CHAR
            }.replace(inlineImageMarkerPattern) { matchResult ->
                imageList.add(matchResult.groupValues[1].trim())
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
                        textLine.height = layoutPaint.textHeight
                        lines.add(textLine)
                    }
                } else if (str == SRC_REPLACE_CHAR && imageList.isNotEmpty()) {
                    // 整行都是插图
                    val line = TextLine(SRC_REPLACE_CHAR)
                    line.addElement(NovelElement.Illustration(imageList.removeAt(0)))
                    line.height = layoutPaint.textHeight * 4
                    lines.add(line)
                } else {
                    textParse(str, maxWidth, lines, config, marginPx, layoutPaint, imageList)
                }
            }

            generateAllTextPage(lines, constraints, config, density, layoutPaint)
        }
    }

    private fun textParse(
        text: String,
        maxWidth: Int,
        lines: MutableList<TextLine>,
        config: ReadBookConfig,
        marginPx: Float,
        paint: TextPaint,
        imageList: MutableList<String>
    ) {
        val widthsArray = FloatArray(text.length)
        paint.getTextWidths(text, widthsArray)

        val (words, widths) = measureTextSplit(text, widthsArray)
        val layout = AndroidZhLayout(text, paint, maxWidth, words, widths, 2)

        val textHeight = paint.textHeight

        for (lineIndex in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (lineWords, lineWidths) = measureTextSplit(lineText, widthsArray, lineStart)

            val textLine = TextLine(lineText)
            textLine.height = textHeight
            lines.add(textLine)

            var curX = marginPx

            for (index in lineWords.indices) {
                val charWidth = lineWidths[index]
                val tmpX = curX + charWidth

                val element = if (lineWords[index] == SRC_REPLACE_CHAR && imageList.isNotEmpty()) {
                    NovelElement.Illustration(imageList.removeAt(0))
                } else {
                    NovelElement.TextRun(text = lineWords[index])
                }
                element.rect.left = curX
                element.rect.right = tmpX
                curX = tmpX
                textLine.addElement(element)
            }

            // 段落最后一行必须标记，段间距才生效（与是否两端对齐无关）
            if (lineIndex == layout.lineCount - 1) {
                textLine.isParagraphEnd = true
            }

            if (config.isJustify) {
                when {
                    lineIndex == 0 && layout.lineCount > 1 -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat(), true)
                    }
                    lineIndex != layout.lineCount - 1 -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat())
                    }
                }
            }
        }
    }

    private fun generateAllTextPage(
        textLines: MutableList<TextLine>,
        constraints: Constraints,
        config: ReadBookConfig,
        density: Density,
        paint: TextPaint
    ): List<TextPage> {
        val textPageList = mutableListOf<TextPage>()

        val header = paint.textHeight * 0.65f
        val footer = paint.textHeight * 0.65f
        val marginPx = config.margin * density.density
        val maxHeight = constraints.maxHeight - marginPx * 2 - header - footer

        val height = textLines.firstOrNull()?.height ?: paint.textHeight
        val actualHeight = height * config.lineSpacingRatio + config.lineSpacing * density.density
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

            val spacingHeight = extraHeight + if (textLine.isParagraphEnd) config.paragraphSpacing * density.density else 0f

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

        verticallyJustifyPages(
            textPageList,
            maxHeight - paint.fontMetrics.descent,
            height,
            extraHeight,
            config,
            density
        )

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
        config: ReadBookConfig,
        density: Density
    ) {
        for (pageIndex in 0 until textPageList.size) {
            val page = textPageList[pageIndex]
            val lines = page.textLines
            if (lines.size <= 1) continue

            val totalTextInkHeight = height * lines.size
            var totalBaseSpacingHeight = 0f
            for (lineIndex in 0 until lines.size - 1) {
                if (lines[lineIndex].isParagraphEnd) {
                    totalBaseSpacingHeight += baseExtraHeight + config.paragraphSpacing * density.density
                } else {
                    totalBaseSpacingHeight += baseExtraHeight
                }
            }

            val totalUsedHeightBeforeJustify = totalTextInkHeight + totalBaseSpacingHeight
            val stretchableWhitespace = maxHeight - totalUsedHeightBeforeJustify

            if (stretchableWhitespace <= 0) {
                for (lineIndex in 0 until lines.size - 1) {
                    val line = lines[lineIndex]
                    // 段落末行 = 行距 + 段距（叠加），否则设置行距时会"挤掉"段间距
                    line.extraHeight = if (line.isParagraphEnd) baseExtraHeight + config.paragraphSpacing * density.density else baseExtraHeight
                }
                if (lines.isNotEmpty()) lines.last().extraHeight = 0f
                continue
            }

            val gapCount = lines.size - 1
            val justificationIncrement = if (pageIndex == textPageList.size - 1) 0f else stretchableWhitespace / gapCount

            for (lineIndex in 0 until lines.size - 1) {
                val line = lines[lineIndex]
                if (line.isParagraphEnd) {
                    line.extraHeight = baseExtraHeight + config.paragraphSpacing * density.density + justificationIncrement
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
