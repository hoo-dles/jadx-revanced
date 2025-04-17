package com.valonso.jadx.fingerprinting

import app.revanced.patcher.fingerprint
import jadx.gui.settings.LineNumbersMode
import jadx.gui.utils.ui.MousePressedHandler
import org.fife.ui.autocomplete.AutoCompletion
import org.fife.ui.autocomplete.CompletionProvider
import org.fife.ui.autocomplete.DefaultCompletionProvider
import org.fife.ui.autocomplete.TemplateCompletion
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.LineNumberFormatter
import org.fife.ui.rtextarea.LineNumberList
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.function.Consumer
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.log10

/**
 * A panel combining a [SearchBar] and a scollable [CodeArea]
 */
class CodePanel() : JPanel() {
    private val codeArea: RSyntaxTextArea
    private val codeScrollPane: RTextScrollPane

    private var useSourceLines = false

    fun loadSettings() {
//        codeArea.loadSettings()
        initLineNumbers()
    }

    fun load() {
//        codeArea.load()
        initLineNumbers()
    }

    @Synchronized
    private fun initLineNumbers() {
//        codeScrollPane.getGutter().setLineNumberFont(this.settings!!.getFont())
        val mode = this.lineNumbersMode
        if (mode == LineNumbersMode.DISABLE) {
            codeScrollPane.setLineNumbersEnabled(false)
            return
        }
        useSourceLines = mode == LineNumbersMode.DEBUG
        applyLineFormatter()
        codeScrollPane.setLineNumbersEnabled(true)
    }

    init {
        this.codeArea = RSyntaxTextArea()
        this.codeArea.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_KOTLIN)
        RSyntaxTextArea.setTemplatesEnabled(true);
        this.codeArea.setAntiAliasingEnabled(true)
        this.codeScrollPane = RTextScrollPane(codeArea)
        setLayout(BorderLayout())
        setBorder(EmptyBorder(0, 0, 0, 0))
        add(codeScrollPane, BorderLayout.CENTER)
        initLinesModeSwitch()
        val ac = AutoCompletion(createCompletionProvider())
        ac.isParameterAssistanceEnabled = true
        ac.install(codeArea)
    }

    fun getText(): String {
        return codeArea.text
    }

    fun setTheme(theme: Theme) {
        theme.apply(codeArea)
    }

    @Synchronized
    private fun applyLineFormatter() {
        val linesFormatter: LineNumberFormatter? =
            SIMPLE_LINE_FORMATTER
        codeScrollPane.gutter.lineNumberFormatter = linesFormatter
    }

    private val lineNumbersMode: LineNumbersMode?
        get() {
            return LineNumbersMode.NORMAL
        }


    private fun initLinesModeSwitch() {
        val lineModeSwitch = MousePressedHandler(Consumer { ev: MouseEvent? ->
            useSourceLines = !useSourceLines
            applyLineFormatter()
        })
        for (gutterComp in codeScrollPane.gutter.components) {
            if (gutterComp is LineNumberList) {
                gutterComp.addMouseListener(lineModeSwitch)
            }
        }
    }

    fun getCodeScrollPane(): JScrollPane {
        return codeScrollPane
    }

    fun createCompletionProvider(): CompletionProvider {
        val provider = DefaultCompletionProvider()

        provider.addCompletion(
            TemplateCompletion(
                provider,
                "fingerprint",
                "fingerprint",
                "fingerprint {\n\t\${cursor}\n}"
            )
        )
        provider.addCompletion(
            TemplateCompletion(
                provider,
                "accessFlags",
                "accessFlags",
                "accessFlags(AccessFlags.\${cursor})"
            )
        )
        provider.addCompletion(TemplateCompletion(provider, "returns", "returns", "returns(\"\${cursor}\")"))
        provider.addCompletion(TemplateCompletion(provider, "parameters", "parameters", "parameters(\"\${cursor}\")"))
        provider.addCompletion(
            TemplateCompletion(
                provider,
                "custom",
                "custom",
                "custom { method, classDef ->\n\t\${cursor}\n}"
            )
        )
        provider.addCompletion(
            TemplateCompletion(
                provider,
                "opcodes",
                "opcodes",
                "opcodes(Opcode.\${cursor})"
            )
        )
        provider.addCompletion(
            TemplateCompletion(
                provider,
                "strings",
                "strings",
                "strings(\"\${cursor}\")"
            )
        )
        return provider
    }


    companion object {
        private val SIMPLE_LINE_FORMATTER: LineNumberFormatter = object : LineNumberFormatter {
            override fun format(lineNumber: Int): String {
                return lineNumber.toString()
            }

            override fun getMaxLength(maxLineNumber: Int): Int {
                return if (maxLineNumber < 10) 1 else 1 + log10(maxLineNumber.toDouble()).toInt()
            }
        }
    }
}