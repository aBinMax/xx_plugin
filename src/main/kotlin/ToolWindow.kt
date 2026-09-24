import com.example.MyMessageBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.JTextPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants


class ToolWindow(private val project: Project) {

    companion object {

        /**
         * 每页读取多少个 Unicode Code Point。
         */
        private const val PAGE_CHAR_COUNT = 5000

        /**
         * 默认字体大小。
         */
        private const val DEFAULT_FONT_SIZE = 12

        /**
         * 最大正文阅读宽度。
         *
         * Tool Window 很宽时，
         * 正文不会铺满整个窗口。
         */
        private const val MAX_TEXT_WIDTH = 760

        /**
         * 正文左右最小边距。
         */
        private const val MIN_HORIZONTAL_MARGIN = 40

        /**
         * 默认文字颜色。
         */
        private val DEFAULT_TEXT_COLOR = Color(0x222222)


    }

    private val state = service<TxtReaderState>()

    private var currentFilePath: String? = null

    /**
     * 当前页的 UTF-8 byte offset。
     */
    private var currentPageOffset = 0L

    /**
     * 当前页。
     *
     * 以当前章节 / 阅读起点为第一页。
     */
    private var currentPage = 1

    /**
     * 已经计算过的页面 offset。
     *
     * 例如：
     *
     * page 1 -> 0
     * page 2 -> 5321
     * page 3 -> 10482
     */
    private val pageOffsets = mutableListOf<Long>()

    /**
     * 是否已经到最后一页。
     */
    private var isLastPage = false

    //章节
    private data class Chapter(val title: String, val offset: Long)

    private val chapters = mutableListOf<Chapter>()

    /**
     * 防止扫描章节时 JComboBox 自动触发跳转。
     */
    private var suppressChapterEvent = false


    private val content = JBPanel<JBPanel<*>>(BorderLayout())

    //顶部工具栏
    private val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))

    private val fileLabel = JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.select.label"))

    private val selectButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.select.button"))

    //章节选择
    private val chapterComboBox = ComboBox<String>()

    /**
     * 字体大小。
     */
    private val fontSizeComboBox = ComboBox(
        arrayOf(
            12,
            14,
            16,
            18,
            20,
            22,
            24,
            26,
            28,
            32
        )
    )

    /**
     * 颜色选择。
     */
    private val colorButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.file.text.color"))

    /**
     * 显示 / 隐藏顶部工具栏。
     */
    private val toggleTopButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.file.hide"))

    //阅读区域
    private val textArea = JTextPane().apply {
        isEditable = false
        isOpaque = false
        border = null
        font = Font(Font.SERIF, Font.PLAIN, DEFAULT_FONT_SIZE)
        foreground = DEFAULT_TEXT_COLOR
        margin = JBUI.insets(30, MIN_HORIZONTAL_MARGIN, 50, MIN_HORIZONTAL_MARGIN)
        caret.isVisible = false//不允许用户编辑。
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border = null
    }

    private val bottomPanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 3))

    private val previousButton = JButton("‹")
    private val nextButton = JButton("›")
    private val pageLabel = JBLabel("")


    /**
     * 章节识别规则。
     *
     * 支持：
     *
     * 第1章
     * 第 1 章
     * 第一章
     * 第一百章
     * 序章
     * 楔子
     * 番外
     * 番外篇
     */
    private val CHAPTER_REGEX = Regex(
        """^\s*(第\s*[0-9零一二三四五六七八九十百千万两]+\s*章.*|序章.*|楔子.*|引子.*|前言.*|后记.*|尾声.*|番外.*)\s*$"""
    )

    init {
        createTopPanel()
        createBottomPanel()
        content.add(topPanel, BorderLayout.NORTH)
        content.add(scrollPane, BorderLayout.CENTER)
        content.add(bottomPanel, BorderLayout.SOUTH)

        selectButton.addActionListener {
            chooseTxtFile()
        }

        chapterComboBox.addActionListener {
            if (suppressChapterEvent) {
                return@addActionListener
            }
            jumpToSelectedChapter()
        }

        fontSizeComboBox.addActionListener {
            changeFontSize()
        }

        colorButton.addActionListener {
            chooseTextColor()
        }

        //工具栏
        toggleTopButton.addActionListener {
            toggleTopPanel()
        }

        //翻页
        previousButton.addActionListener {
            previousPage()
        }

        nextButton.addActionListener {
            nextPage()
        }

        //恢复外观
        restoreAppearance()

        //页码 Hover
        installPageHover()

        //窗口大小变化时 动态调整正文左右边距
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateTextMargins()
            }
        }
        )
        updateTextMargins()
        updateButtonState()
    }

    //顶部工具栏
    private fun createTopPanel() {
        topPanel.add(selectButton)
        topPanel.add(fileLabel)
        topPanel.add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.file.zhang.jie")))
        chapterComboBox.preferredSize = Dimension(220, chapterComboBox.preferredSize.height)
        topPanel.add(chapterComboBox)
        topPanel.add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.file.font.size")))
        topPanel.add(fontSizeComboBox)
        topPanel.add(colorButton)
        topPanel.add(toggleTopButton)
    }

    /**
     * 隐藏 / 显示顶部工具栏。
     */
    private fun toggleTopPanel() {
        topPanel.isVisible = !topPanel.isVisible
        toggleTopButton.text = if (topPanel.isVisible) {
            MyMessageBundle.message("toolwindow.MyToolWindow.file.hide")
        } else {
            MyMessageBundle.message("toolwindow.MyToolWindow.file.show")
        }
        content.revalidate()
        content.repaint()
    }

    /**
     * 根据 Tool Window 当前宽度，
     * 动态计算正文左右边距。
     *
     * 例如窗口很宽：
     *
     * |       正文区域       |
     *
     * 而不是：
     *
     * |正文正文正文正文正文正文正文正文|
     */
    private fun updateTextMargins() {
        val width = scrollPane.viewport.width
        if (width <= 0) {
            return
        }

        val horizontalMargin = if (width > MAX_TEXT_WIDTH) {
            (width - MAX_TEXT_WIDTH) / 2
        } else {
            MIN_HORIZONTAL_MARGIN
        }

        val oldMargin = textArea.margin
        val newMargin = JBUI.insets(oldMargin.top, horizontalMargin, oldMargin.bottom, horizontalMargin)

        if (oldMargin.left != newMargin.left || oldMargin.right != newMargin.right) {
            textArea.margin = newMargin
            textArea.revalidate()
            textArea.repaint()
        }
    }

    /**
     * 设置当前页面为居中阅读。
     *
     * JTextPane 使用 StyledDocument，
     * 可以对整个文档设置段落属性。
     */
    private fun applyTextStyle() {
        val document = textArea.styledDocument
        if (document.length <= 0) {
            return
        }

        val attributes = SimpleAttributeSet()

        StyleConstants.setAlignment(attributes, StyleConstants.ALIGN_CENTER)
        StyleConstants.setFontFamily(attributes, textArea.font.family)
        StyleConstants.setFontSize(attributes, textArea.font.size)
        StyleConstants.setForeground(attributes, textArea.foreground)
        //设置整个文档的段落为居中
        document.setParagraphAttributes(0, document.length, attributes, false)
        //同时设置整个文档的字体和颜色
        document.setCharacterAttributes(0, document.length, attributes, false)
    }


    private fun changeFontSize() {
        val size = fontSizeComboBox.selectedItem as? Int ?: DEFAULT_FONT_SIZE
        textArea.font = textArea.font.deriveFont(Font.PLAIN, size.toFloat())
        state.fontSize = size
        applyTextStyle()
        textArea.revalidate()
        textArea.repaint()
    }

    private fun chooseTextColor() {
        val selectedColor = JColorChooser.showDialog(
            content,
            MyMessageBundle.message("toolwindow.MyToolWindow.file.text.color"),
            textArea.foreground
        )
        textArea.foreground = selectedColor
        state.textColor = selectedColor.rgb
        updateColorButton(selectedColor)
        applyTextStyle()
        textArea.repaint()
    }

    /**
     * 让按钮文字显示当前颜色。
     */
    private fun updateColorButton(color: Color) {
        colorButton.foreground = color
    }

    private fun restoreAppearance() {
        val fontSize = if (state.fontSize > 0) {
            state.fontSize
        } else {
            DEFAULT_FONT_SIZE
        }
        textArea.font = textArea.font.deriveFont(Font.PLAIN, fontSize.toFloat())
        fontSizeComboBox.selectedItem = fontSize

        val color = if (state.textColor != 0) {
            Color(state.textColor, true)
        } else {
            DEFAULT_TEXT_COLOR
        }

        textArea.foreground =
            color

        updateColorButton(
            color
        )
    }

    private fun createBottomPanel() {
        setupArrowButton(previousButton, MyMessageBundle.message("toolwindow.MyToolWindow.file.next"))
        setupArrowButton(nextButton, MyMessageBundle.message("toolwindow.MyToolWindow.file.previous"))
        pageLabel.isVisible = false
        bottomPanel.add(previousButton)
        bottomPanel.add(pageLabel)
        bottomPanel.add(nextButton)
    }

    /**
     * 箭头按钮设置。
     */
    private fun setupArrowButton(button: JButton, tooltip: String) {
        button.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        button.margin = JBUI.insets(0, 5)
        button.isFocusable = false
        button.toolTipText = tooltip
    }

    // 页码 Hover
    private fun installPageHover() {
        pageLabel.isVisible = false
        //鼠标进入底部区域
        bottomPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                pageLabel.isVisible = true
                bottomPanel.revalidate()
                bottomPanel.repaint()
            }

            override fun mouseExited(e: MouseEvent?) {
                pageLabel.isVisible = false
                bottomPanel.revalidate()
                bottomPanel.repaint()
            }
        }
        )

        /**
         * 防止鼠标移动到 pageLabel 本身
         * 时触发奇怪的状态变化。
         */
        pageLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                pageLabel.isVisible = true
            }
        }
        )
    }

    private fun chooseTxtFile() {
        val descriptor = FileChooserDescriptor(
            true,
            false,
            false,
            false,
            false,
            false
        ).apply {
            title = MyMessageBundle.message("toolwindow.MyToolWindow.file.chooser.title")
            description = MyMessageBundle.message("toolwindow.MyToolWindow.file.chooser.description")
            withExtensionFilter(MyMessageBundle.message("toolwindow.MyToolWindow.file.chooser.filter"), "txt")
        }

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = virtualFile.path
        currentFilePath = path
        scanChapters(path)
        currentPageOffset = state.getPosition(path)//恢复上次阅读位置

        /**
         * 如果保存的位置已经超过文件大小，
         * 回到文件开头。
         */
        val fileLength = try {
            RandomAccessFile(path, "r").use {
                it.length()
            }
        } catch (_: Exception) {
            0L
        }

        if (currentPageOffset !in 0..fileLength) {
            currentPageOffset = 0L
        }
        //建立第一页 offset。
        pageOffsets.clear()
        pageOffsets.add(currentPageOffset)
        currentPage = 1
        loadCurrentPage()

        /**
         * 根据当前阅读位置，
         * 自动选中当前章节。
         */
        selectChapterForOffset(currentPageOffset)
        fileLabel.text = MyMessageBundle.message("toolwindow.MyToolWindow.file.loaded", virtualFile.name)
    }

    /**
     * 扫描章节
     * 扫描 TXT 中的章节标题。
     *
     * 只建立：
     *
     * 章节名称
     * +
     * byte offset
     *
     * 不会把整个文件加载到内存。
     */
    private fun scanChapters(filePath: String) {
        chapters.clear()
        suppressChapterEvent = true
        try {
            chapterComboBox.removeAllItems()
            RandomAccessFile(filePath, "r").use { file ->
                while (true) {
                    //当前行开始位置
                    val offset = file.filePointer
                    val rawLine = file.readLine() ?: break
                    val line = decodeRandomAccessLine(rawLine).trim().removePrefix("\uFEFF")
                    if (line.isEmpty()) {
                        continue
                    }
                    if (CHAPTER_REGEX.matches(line)) {
                        chapters.add(Chapter(title = line, offset = offset))
                    }
                }
            }

            /**
             * 添加章节到 ComboBox。
             */
            for (chapter in chapters) {
                chapterComboBox.addItem(chapter.title)
            }
            if (chapters.isEmpty()) {
                chapterComboBox.addItem("未检测到章节")
            }

        } finally {
            suppressChapterEvent = false
        }
    }

    /**
     * RandomAccessFile.readLine()
     * 返回 ISO-8859-1 字符。
     *
     * 原始 TXT 是 UTF-8，
     * 所以重新转回 UTF-8。
     */
    private fun decodeRandomAccessLine(line: String): String {
        return String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
    }

    //根据 offset 选中章节
    private fun selectChapterForOffset(offset: Long) {
        if (chapters.isEmpty()) {
            return
        }
        var selectedIndex = 0
        for (index in chapters.indices) {
            if (chapters[index].offset <= offset) {
                selectedIndex = index
            } else {
                break
            }
        }
        suppressChapterEvent = true
        try {
            chapterComboBox.selectedIndex = selectedIndex
        } finally {
            suppressChapterEvent = false
        }
    }

    //跳转章节
    private fun jumpToSelectedChapter() {
        if (suppressChapterEvent) {
            return
        }
        if (chapters.isEmpty()) {
            return
        }
        val selectedIndex = chapterComboBox.selectedIndex

        if (selectedIndex < 0 || selectedIndex >= chapters.size) {
            return
        }

        val chapter = chapters[selectedIndex]
        //从章节起始位置开始阅读
        currentPageOffset = chapter.offset
        //重新建立分页缓存。
        pageOffsets.clear()
        pageOffsets.add(chapter.offset)
        currentPage = 1
        loadCurrentPage()
    }

    private fun loadCurrentPage() {
        val path = currentFilePath ?: return
        try {
            val result = readPage(path, currentPageOffset)
            textArea.text = result.text
            applyTextStyle()
            if (result.nextOffset != null) {
                val nextOffset = result.nextOffset
                if (pageOffsets.size <= currentPage) {
                    pageOffsets.add(nextOffset)
                } else {
                    pageOffsets[currentPage] = nextOffset
                }
                isLastPage = false
            } else {
                isLastPage = true
            }

            /**
             * 保存当前阅读位置。
             */
            state.savePosition(path, currentPageOffset)

            /**
             * 回到顶部。
             */
            textArea.caretPosition = 0
            SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = 0 }
            updatePageLabel()
            updateButtonState()
            updateTextMargins()

        } catch (e: Exception) {
            textArea.text = ""
            fileLabel.text = "读取文件失败：${e.message}"
        }
    }

    private fun nextPage() {
        if (isLastPage) {
            return
        }

        val nextOffset: Long

        /**
         * 已经计算过下一页。
         */
        if (pageOffsets.size > currentPage) {
            nextOffset = pageOffsets[currentPage]
        } else {
            val path = currentFilePath ?: return
            val result = readPage(path, currentPageOffset)
            nextOffset = result.nextOffset ?: return
            pageOffsets.add(nextOffset)
        }
        currentPageOffset = nextOffset
        currentPage++
        loadCurrentPage()
    }

    private fun previousPage() {
        if (currentPage <= 1) {
            return
        }
        currentPage--
        currentPageOffset = pageOffsets[currentPage - 1]
        loadCurrentPage()
    }

    //更新 UI
    private fun updatePageLabel() {
        pageLabel.text = "第 $currentPage 页"
    }

    private fun updateButtonState() {
        previousButton.isEnabled = currentPage > 1
        nextButton.isEnabled = !isLastPage
    }

    /**
     * 一次只读取当前页。
     *
     * 不会：
     *
     * contentsToByteArray()
     *
     * 所以不会把整个 TXT 一次性放入内存。
     */
    private fun readPage(filePath: String, startOffset: Long): PageResult {
        RandomAccessFile(filePath, "r").use { file ->
            file.seek(startOffset)
            val builder = StringBuilder()
            var charCount = 0
            while (charCount < PAGE_CHAR_COUNT) {
                val codePoint = readUtf8CodePoint(file) ?: break
                builder.appendCodePoint(codePoint)
                charCount++
            }

            val nextOffset = if (file.filePointer < file.length()) {
                file.filePointer
            } else {
                null
            }
            return PageResult(text = builder.toString(), nextOffset = nextOffset)
        }
    }

    /**
     * 从 UTF-8 文件中读取一个 Unicode Code Point。
     *
     * 支持：
     *
     * 中文
     * 英文
     * 日文
     * Emoji
     * 大部分 Unicode 字符
     */
    private fun readUtf8CodePoint(file: RandomAccessFile): Int? {
        val first = file.read()
        if (first == -1) {
            return null
        }
        /**
         * ASCII
         */
        if (first and 0x80 == 0) {
            return first
        }

        /**
         * 2 字节 UTF-8
         */
        if (first and 0xE0 == 0xC0) {
            val b2 = file.read()
            if (b2 == -1) {
                return null
            }
            if (b2 and 0xC0 != 0x80) {
                return 0xFFFD
            }
            return ((first and 0x1F) shl 6) or (b2 and 0x3F)
        }

        /**
         * 3 字节 UTF-8
         */
        if (first and 0xF0 == 0xE0) {
            val b2 = file.read()
            val b3 = file.read()
            if (b2 == -1 || b3 == -1) {
                return null
            }
            if (b2 and 0xC0 != 0x80 || b3 and 0xC0 != 0x80) {
                return 0xFFFD
            }

            return ((first and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
        }

        /**
         * 4 字节 UTF-8
         */
        if (first and 0xF8 == 0xF0) {
            val b2 = file.read()

            val b3 = file.read()
            val b4 = file.read()

            if (b2 == -1 || b3 == -1 || b4 == -1) {
                return null
            }
            if (b2 and 0xC0 != 0x80 || b3 and 0xC0 != 0x80 || b4 and 0xC0 != 0x80) {
                return 0xFFFD
            }
            val codePoint =
                ((first and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)

            /**
             * UTF-8 最大 Unicode Code Point
             */
            if (codePoint > 0x10FFFF) {
                return 0xFFFD
            }
            return codePoint
        }
        return 0xFFFD
    }

    fun getContent(): JBPanel<JBPanel<*>> {
        return content
    }

    private data class PageResult(
        val text: String,
        val nextOffset: Long?
    )
}