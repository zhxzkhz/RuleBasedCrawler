package com.zhhz.spider.ui.widget.novel

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val lineChar = "\n"

class KmpReadProvider(
    private val textMeasurer: TextMeasurer,
    private val textStyle: TextStyle,
    private val density: Density
) {
    // 缓存字符宽度，大幅提升测量性能
    private val charWidthCache = mutableMapOf<Char, Float>()

    private fun getCharWidth(char: Char): Float {
        return charWidthCache.getOrPut(char) {
            textMeasurer.measure(char.toString(), textStyle).size.width.toFloat()
        }
    }

    private val cnCharWidth by lazy { getCharWidth('我') }

    suspend fun measureTextInBackground(
        text: String,
        constraints: Constraints,
        config: ReadBookConfig
    ): List<TextPage> {
        return withContext(Dispatchers.Default) {
            val time = System.currentTimeMillis()
            val maxWidth = (constraints.maxWidth - config.margin * 2 * density.density).toInt()
            
            // 将所有 px 配置基于 density 转换
            val textHeight = textMeasurer.measure("我", textStyle).size.height.toFloat()
            val marginPx = config.margin * density.density
            val lineSpacingPx = config.lineSpacing * density.density
            val paragraphSpacingPx = config.paragraphSpacing * density.density

            val lines = mutableListOf<TextLine>()
            
            // 简单处理换行符
            var textStr = text.replace("\r\n", lineChar).replace("\r", lineChar)
            
            val contents = arrayListOf<String>()
            if (config.firstLineIndentation) {
                textStr.split(lineChar).forEach { str ->
                    val paragraph = str.trim { it.code <= 0x20 || it == '　' }
                    if (paragraph.isNotEmpty()) {
                        contents.add("　　${paragraph}")
                    } else if (str.isEmpty()) {
                        contents.add(lineChar)
                    }
                }
            } else {
                contents.addAll(textStr.split(lineChar))
            }

            for (str in contents) {
                if (str == lineChar) {
                    if (config.preserveEmptyLine) {
                        val textLine = TextLine(lineChar)
                        textLine.addElement(NovelElement.TextRun(lineChar))
                        textLine.height = textHeight
                        lines.add(textLine)
                    }
                } else {
                    textParse(str, maxWidth, lines, textHeight, config, paragraphSpacingPx)
                }
            }

            println("测量耗时 》》》》 ms  行数: ")
            generateAllTextPage(lines, constraints, config, textHeight, marginPx, lineSpacingPx, paragraphSpacingPx)
        }
    }

    private fun textParse(
        text: String,
        maxWidth: Int,
        lines: MutableList<TextLine>,
        textHeight: Float,
        config: ReadBookConfig,
        paragraphSpacingPx: Float
    ) {
        val widthsArray = FloatArray(text.length)
        for (i in text.indices) {
            widthsArray[i] = getCharWidth(text[i])
        }

        val (words, widths) = measureTextSplit(text, widthsArray, 0)
        
        val layout = KmpZhLayout(
            text = text,
            width = maxWidth,
            words = words,
            widths = widths,
            indentSize = 2,
            cnCharWidth = cnCharWidth
        )

        var curX: Float
        var textLine: TextLine

        for (lineIndex in 0 until layout.getLineCount()) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            if (lineStart >= lineEnd) continue
            
            val lineText = text.substring(lineStart, lineEnd)
            val (lineWords, lineWidths) = measureTextSplit(lineText, widthsArray, lineStart)

            textLine = TextLine(lineText)
            textLine.height = textHeight
            lines.add(textLine)

            curX = config.margin * density.density

            for (index in lineWords.indices) {
                val charStr = lineWords[index]
                val charWidth = lineWidths[index]
                val tmpX = curX + charWidth

                val element = NovelElement.TextRun(text = charStr)
                element.rect.left = curX
                element.rect.right = tmpX
                curX = tmpX
                textLine.addElement(element)
            }

            if (config.isJustify) {
                when {
                    lineIndex == 0 && layout.getLineCount() > 1 -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat(), true)
                    }
                    lineIndex == layout.getLineCount() - 1 -> {
                        textLine.isParagraphEnd = true
                    }
                    else -> {
                        justifyTextLine(textLine.elements, maxWidth.toFloat(), false)
                    }
                }
            }
        }
    }

    private fun justifyTextLine(
        elements: List<NovelElement>,
        visibleWidth: Float,
        hasIndent: Boolean
    ) {
        val num = if (hasIndent) 2 else 0
        if (elements.size <= num + 1) return

        val residualWidth = visibleWidth - elements.sumOf { it.rect.width.toDouble() }.toFloat()
        val increment = residualWidth / (elements.size - 1 - num)

        for ((index, element) in elements.withIndex()) {
            if (num > index) continue
            if (index < elements.size) {
                element.rect.right += increment * (index - num)
            }
            element.rect.left += increment * (index - num)
        }
    }

    private fun generateAllTextPage(
        textLines: MutableList<TextLine>,
        constraints: Constraints,
        config: ReadBookConfig,
        textHeight: Float,
        marginPx: Float,
        lineSpacingPx: Float,
        paragraphSpacingPx: Float
    ): List<TextPage> {
        val textPageList = mutableListOf<TextPage>()
        
        val header = textHeight * 0.85f
        val footer = textHeight * 0.85f
        val maxHeight = constraints.maxHeight - marginPx * 2 - header - footer

        val height = textLines.firstOrNull()?.height ?: textHeight
        val actualHeight = height * config.lineSpacingRatio + lineSpacingPx
        val extraHeight = actualHeight - height

        var fontIndex = 0
        var textPage = TextPage(fontIndex, text = "")
        textPageList.add(textPage)
        var curY = 0f

        for (textLine in textLines) {
            if (curY + height > maxHeight) {
                if (config.ignoreLastEmptyLine) {
                    while (textPage.textLines.isNotEmpty() && textPage.textLines.last().text == lineChar) {
                        textPage.textLines.removeAt(textPage.textLines.size - 1)
                    }
                }
                fontIndex += textPage.text.length
                textPage = TextPage(fontIndex, text = "")
                textPageList.add(textPage)
                curY = 0f
            }

            if (config.ignoreFirstEmptyLine && textPage.textLines.isEmpty() && textLine.text == lineChar) {
                continue
            }

            textPage.addTextLine(textLine)
            curY += height

            val spacingHeight = extraHeight + if (textLine.isParagraphEnd) paragraphSpacingPx else 0f

            if (curY + spacingHeight > maxHeight) {
                if (config.ignoreLastEmptyLine) {
                    while (textPage.textLines.isNotEmpty() && textPage.textLines.last().text == lineChar) {
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

        verticallyJustifyPages(textPageList, maxHeight, height, extraHeight, config, paragraphSpacingPx)

        return textPageList
    }

    private fun verticallyJustifyPages(
        textPageList: List<TextPage>,
        maxHeight: Float,
        height: Float,
        baseExtraHeight: Float,
        config: ReadBookConfig,
        paragraphSpacingPx: Float
    ) {
        for (pageIndex in 0 until textPageList.size) {
            val page = textPageList[pageIndex]
            val lines = page.textLines

            if (lines.size <= 1) continue

            val totalTextInkHeight = height * lines.size
            var totalBaseSpacingHeight = 0f
            for (lineIndex in 0 until lines.size - 1) {
                if (lines[lineIndex].isParagraphEnd) {
                    totalBaseSpacingHeight += paragraphSpacingPx
                } else {
                    totalBaseSpacingHeight += baseExtraHeight
                }
            }

            val totalUsedHeightBeforeJustify = totalTextInkHeight + totalBaseSpacingHeight
            val stretchableWhitespace = maxHeight - totalUsedHeightBeforeJustify

            if (stretchableWhitespace <= 0) {
                for (lineIndex in 0 until lines.size - 1) {
                    val line = lines[lineIndex]
                    line.extraHeight = if (line.isParagraphEnd) paragraphSpacingPx else baseExtraHeight
                }
                if (lines.isNotEmpty()) lines.last().extraHeight = 0f
                continue
            }

            val gapCount = lines.size - 1
            val justificationIncrement = if (pageIndex == textPageList.size - 1) 0f else stretchableWhitespace / gapCount

            for (lineIndex in 0 until lines.size - 1) {
                val line = lines[lineIndex]
                if (line.isParagraphEnd) {
                    line.extraHeight = paragraphSpacingPx + justificationIncrement
                } else {
                    line.extraHeight = baseExtraHeight + justificationIncrement
                }
            }
            if (lines.isNotEmpty()) lines.last().extraHeight = 0f
        }
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        val widths = ArrayList<Float>(length)
        val stringList = ArrayList<String>(length)
        for (i in 0 until length) {
            stringList.add(text[i].toString())
            widths.add(widthsArray[start + i])
        }
        return stringList to widths
    }
}
