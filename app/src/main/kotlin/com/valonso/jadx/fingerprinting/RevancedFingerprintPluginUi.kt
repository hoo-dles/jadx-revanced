package com.valonso.jadx.fingerprinting

import app.revanced.patcher.Fingerprint
import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.JadxGuiContext
import jadx.gui.ui.MainWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ActionEvent
import java.io.ByteArrayInputStream
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object RevancedFingerprintPluginUi {

    private val LOG = KotlinLogging.logger("${RevancedFingerprintPlugin.ID}/ui")
    private lateinit var context: JadxPluginContext
    private lateinit var guiContext: JadxGuiContext

    val revancedSvg = """
        <svg role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" id="revanced" height="16" width="16">
            <path d="M5.1 0a0.28 0.28 0 0 0 -0.23 0.42l6.88 11.93a0.28 0.28 0 0 0 0.48 0L19.13 0.42A0.28 0.28 0 0 0 18.9 0ZM0.5 0a0.33 0.33 0 0 0 -0.3 0.46L10.43 23.8c0.05 0.12 0.17 0.2 0.3 0.2h2.54c0.13 0 0.25 -0.08 0.3 -0.2L23.8 0.46a0.33 0.33 0 0 0 -0.3 -0.46h-2.32a0.24 0.24 0 0 0 -0.21 0.14L12.2 20.08a0.23 0.23 0 0 1 -0.42 0L3.03 0.14A0.23 0.23 0 0 0 2.82 0Z" fill="#000000" stroke-width="1"></path>
        </svg>
    """.trimIndent()
    val playArrowSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><!-- Icon from Material Symbols by Google - https://github.com/google/material-design-icons/blob/master/LICENSE --><path fill="currentColor" d="M8 19V5l11 7z"/></svg>
    """.trimIndent()
    private val frameName = "Revanced Fingerprint Evaluator"
    private var fingerprintEvalFrame: JFrame? = null

    fun init(context: JadxPluginContext) {
        this.context = context
        this.guiContext = context.guiContext!!
        SwingUtilities.invokeLater {
            try {
                //Remove all frames with the title "Revanced Script Evaluator"
                JFrame.getFrames().filter { it.title == frameName }.forEach { it.dispose() }
                addToolbarButton()
            } catch (e: Exception) {
                LOG.error(e) { "Failed to initialize UI" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "Failed to initialize Revanced Fingerprint Plugin UI: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun addToolbarButton() {
        try {
            val mainFrame = guiContext.mainFrame ?: run {
                LOG.warn { "Could not get main frame" }
                return
            }
            val mainPanel = getMainPanelReflectively(mainFrame) ?: run {
                LOG.warn { "Could not get main panel via reflection" }
                return
            }

            // Find the toolbar (assuming it's the component at index 2 in mainPanel's NORTH region)
            // This is fragile and depends on JADX internal layout
            var northPanel = mainPanel.components.find { comp ->
                mainPanel.layout is BorderLayout && (mainPanel.layout as BorderLayout).getConstraints(comp) == BorderLayout.NORTH
            }

            if (northPanel !is JToolBar) {
                // Fallback: Try the example's direct index approach if BorderLayout failed
                if (mainPanel.componentCount > 2 && mainPanel.getComponent(2) is JToolBar) {
                    northPanel = mainPanel.getComponent(2) as JToolBar
                } else {
                    LOG.warn { "Could not find JToolBar in main panel's NORTH region or at index 2. Found: ${northPanel?.javaClass?.name}" }
                    return
                }
            }

            val toolbar = northPanel
            val scriptButtonName = "${RevancedFingerprintPlugin.ID}.button"
            // Re initialize the plugin button since if not there are classpath shenanigans
            toolbar.components.find { it.name == scriptButtonName }?.let {
                LOG.info { "Removing existing button from toolbar." }
                toolbar.remove(it)
            }

            val icon = inlineSvgIcon(revancedSvg)
            val button = JButton(null, icon)
            button.name = scriptButtonName
            button.toolTipText = "Open Revanced Fingerprint Evaluator"

            button.addActionListener {
                LOG.info { "Toolbar button clicked, showing UI." }
                if (fingerprintEvalFrame != null) {
                    fingerprintEvalFrame?.requestFocus()
                } else {
                    showScriptPanel()
                }
            }

            val preferencesIndex = toolbar.components.indexOfFirst { it.name?.contains("preferences") == true }
                .let { if (it == -1) toolbar.componentCount - 2 else it + 2 }
            toolbar.add(button, preferencesIndex) // Add after preferences button
            toolbar.revalidate()
            toolbar.repaint()
            LOG.info { "Added fingerprint evaluator button to toolbar." }

        } catch (e: Exception) {
            LOG.error(e) { "Failed to add button to toolbar" }
        }
    }

    // Helper function using reflection (similar to the Java example)
    private fun getMainPanelReflectively(frame: JFrame): JPanel? {
        return try {
            val field: Field = frame::class.java.getDeclaredField("mainPanel")
            field.isAccessible = true
            field.get(frame) as? JPanel
        } catch (e: Exception) {
            LOG.error(e) { "Failed to get mainPanel field via reflection" }
            null
        }
    }

    fun showScriptPanel() {

        SwingUtilities.invokeLater {
            val frame = JFrame(frameName)
            fingerprintEvalFrame = frame
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent?) {
                    fingerprintEvalFrame = null
                }
            })
            frame.setSize(800, 600)
            frame.setLocationRelativeTo(guiContext.mainFrame) // Center relative to main frame

            // Main panel with BorderLayout contains the CodePanel in the CENTER region.
            val mainPanel = JPanel(BorderLayout())
            mainPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0) // Add padding
            val codePanel = CodePanel().apply {
                preferredSize = Dimension(600, 400)
            }
            try {
                val editorTheme = (guiContext.mainFrame as MainWindow).editorTheme
                codePanel.setTheme(editorTheme)
            } catch (e: Exception) {
                LOG.error(e) { "Failed to set theme for CodePanel" }
            }
            mainPanel.add(codePanel, BorderLayout.WEST)

            val icon = inlineSvgIcon(playArrowSvg) as Icon
            val resultPanel = JPanel(BorderLayout())
            val resultHeaderPanel = JPanel(BorderLayout())
            resultHeaderPanel.border = BorderFactory.createEmptyBorder(0, 10, 10, 0) // Add padding
            val runButton = JButton(null, icon).apply {
                toolTipText = "Run the script"
                margin = Insets(0, 0, 0, 0)
                preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
                maximumSize = preferredSize
            }

            resultHeaderPanel.add(runButton, BorderLayout.WEST)
            //add a label to the right
            val resultLabel = JLabel("Fingerprint result")
            resultLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0) // Add padding
            resultHeaderPanel.add(resultLabel, BorderLayout.CENTER)
            resultPanel.add(resultHeaderPanel, BorderLayout.NORTH)

            val resultContentPanel = JPanel()
            resultContentPanel.layout = BorderLayout()

            val resultContentBox = Box.createVerticalBox()
            resultContentPanel.add(resultContentBox, BorderLayout.PAGE_START)


            val resultScrollPane = JScrollPane(
                resultContentPanel
            )
            resultPanel.add(resultScrollPane, BorderLayout.CENTER)
            mainPanel.add(resultPanel, BorderLayout.CENTER)

            runButton.addActionListener {
                runButton.isEnabled = false
                resultContentBox.removeAll()
                val statusLabel = JLabel("Evaluating...")
                statusLabel.alignmentX = Component.LEFT_ALIGNMENT
                resultContentBox.add(statusLabel)
                resultContentBox.revalidate()
                resultContentBox.repaint()

                // Get script text from CodePanel's CodeArea
                val script = codePanel.getText()

                // Launch evaluation in a background thread using Coroutines
                GlobalScope.launch(Dispatchers.IO) { // Use Dispatchers.IO for blocking tasks
                    LOG.info { "Evaluating script: $script" }
                    var result: ResultWithDiagnostics<EvaluationResult>? = null
                    var evaluationError: Throwable? = null
                    val executionTime = measureTime {
                        try {
                            result = ScriptEvaluation.rawEvaluate(script)
                        } catch (t: Throwable) {
                            evaluationError = t
                            LOG.error(t) { "Exception during script evaluation" }
                        }
                    }

                    // Prepare result components (JLabels) in the background
                    val resultComponents = mutableListOf<Component>()
                    val outputBuilder = StringBuilder() // For logging or alternative display

                    if (evaluationError != null) {
                        val errorMsg = "Evaluation failed: ${evaluationError.message}"
                        resultComponents.add(createWrappedTextArea(errorMsg))
                        outputBuilder.appendLine(errorMsg)
                        // Optionally add stack trace details
                    } else if (result != null) {
                        when (val evalResult = result!!) {
                            is ResultWithDiagnostics.Failure -> {
                                val failMsg = "Script evaluation failed:"
                                resultComponents.add(createWrappedTextArea(failMsg))
                                outputBuilder.appendLine(failMsg)
                                ScriptEvaluation.LOG.error { failMsg }
                                evalResult.reports.forEach { report ->
                                    val message = "  ${report.severity}: ${report.message}"
                                    resultComponents.add(createWrappedTextArea(message))
                                    outputBuilder.appendLine(message)
                                    ScriptEvaluation.LOG.error { message }
                                    report.exception?.let {
                                        ScriptEvaluation.LOG.error(it) { "  Exception details:" }
                                        // Optionally add exception details to outputBuilder/components
                                    }
                                }
                            }

                            is ResultWithDiagnostics.Success -> {
                                when (val returnValue = evalResult.value.returnValue) {
                                    ResultValue.NotEvaluated -> {
                                        val msg = "Script was not evaluated."
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                    }

                                    is ResultValue.Error -> {
                                        val msg = "Script execution error: ${returnValue.error} "
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.error(returnValue.error) { "Script execution error:" }
                                        // Optionally add stack trace
                                    }

                                    is ResultValue.Unit -> {
                                        val msg =
                                            "Script did not produce a value. Result type: ${returnValue::class.simpleName}"
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.warn { msg }
                                    }

                                    is ResultValue.Value -> {
                                        val actualValue = returnValue.value
                                        if (actualValue == null) {
                                            val msg = "Script returned null."
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.warn { msg }
                                        } else if (actualValue !is Fingerprint) {
                                            val msg = "Script returned unexpected type: ${returnValue.type}"
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.error { msg }
                                            ScriptEvaluation.LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
                                            ScriptEvaluation.LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
                                        } else {
                                            val resolvedFingerprint = actualValue
                                            val msg = "Fingerprint: $resolvedFingerprint"
                                            outputBuilder.appendLine(msg)
                                            val searchResult = RevancedResolver.searchFingerprint(resolvedFingerprint)
                                            if (searchResult != null) {
                                                outputBuilder.appendLine("Fingerprint found in APK: ${searchResult.definingClass}")
                                                context.decompiler.searchJavaClassByOrigFullName(
                                                    ReflectionUtils.dexToJavaName(
                                                        searchResult.definingClass
                                                    )
                                                )?.searchMethodByShortId(searchResult.getShortId())
                                                    ?.let { sourceMethod ->
                                                        val searchResultMsg =
                                                            "Fingerprint found at method: ${sourceMethod.fullName}"
                                                        outputBuilder.appendLine(searchResultMsg)

                                                        resultComponents.add(createWrappedTextArea(searchResultMsg))
                                                        ScriptEvaluation.LOG.info { searchResultMsg }
                                                        val jumpButton = JButton("Jump to method")
                                                        jumpButton.addActionListener {
                                                            ScriptEvaluation.LOG.info { "Jumping to method: ${sourceMethod.fullName}" }
                                                            guiContext.open(sourceMethod.codeNodeRef)
                                                        }
                                                        resultComponents.add(jumpButton)
                                                    }


                                            } else {
                                                val msg = "Fingerprint not found in the APK."
                                                resultComponents.add(createWrappedTextArea(msg))
                                                outputBuilder.appendLine(msg)
                                                ScriptEvaluation.LOG.warn { msg }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val msg = "Evaluation did not produce a result."
                        resultComponents.add(createWrappedTextArea(msg))
                        outputBuilder.appendLine(msg)
                    }

                    // Switch back to the Event Dispatch Thread (EDT) to update the UI
                    withContext(Dispatchers.Swing) {
                        resultContentBox.removeAll() // Remove "Evaluating..." label
                        if (resultComponents.isEmpty()) {
                            resultContentBox.add(createWrappedTextArea(("No output.")))
                        } else {
                            resultComponents.forEach {
                                resultContentBox.add(
                                    it
                                )
                            }
//                            resultContentBox.add(createWrappedTextArea(outputBuilder.toString()))
                        }


                        resultLabel.text = "Executed in ${executionTime.inWholeMilliseconds.milliseconds}"
                        runButton.isEnabled = true
                        // Ensure layout updates are processed
                        resultContentBox.revalidate()
                        resultContentBox.repaint()
                        // Scroll to top if needed
                        resultScrollPane.verticalScrollBar.value = resultScrollPane.verticalScrollBar.minimum
                        LOG.info { "Script evaluation UI updated." }
                    }
                }
            }
            frame.contentPane = mainPanel
            frame.isVisible = true
        }


    }

    fun createWrappedTextArea(text: String): JTextArea {
        val textArea = JTextArea(text)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.isEditable = false // Make read-only
        textArea.alignmentX = Component.LEFT_ALIGNMENT // Align left
        textArea.alignmentY = Component.TOP_ALIGNMENT // Align top
        textArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2)) // Add padding
        return textArea
    }

    fun oldStyle() {
        // Ensure UI updates happen on the Event Dispatch Thread
        SwingUtilities.invokeLater {
            // Create the main frame (window)
            val frame = JFrame("Revanced Script Evaluator")
            frame.setSize(800, 600) // Increased size slightly
            // Set location relative to the main JADX window if possible, otherwise center on screen

            // --- Output Panel Setup ---
            val outputPanel = JPanel()
            outputPanel.layout = BoxLayout(outputPanel, BoxLayout.Y_AXIS) // Vertical layout
            outputPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10) // Add padding

            val statusLabel = JLabel("Output will be shown here:")
            statusLabel.alignmentX = Component.LEFT_ALIGNMENT // Align left
            outputPanel.add(statusLabel)

            // Results Area (Bottom, Scrollable)
            val resultsTextArea = JTextArea()
            resultsTextArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            resultsTextArea.isEditable = false // Make read-only
            resultsTextArea.lineWrap = true // Enable line wrapping
            resultsTextArea.wrapStyleWord = true // Wrap at word boundaries
            val resultsScrollPane = JScrollPane(resultsTextArea)
            resultsScrollPane.alignmentX = Component.LEFT_ALIGNMENT // Align left
            // Make the results area take available vertical space (might need tweaking depending on container)
            // resultsScrollPane.preferredSize = Dimension(200, Int.MAX_VALUE) // Adjust width as needed
            // Set preferred width to 50%


            outputPanel.add(resultsScrollPane)
            // --- End Output Panel Setup ---


            // Create the text area for the script
            val scriptTextArea = JTextArea()
            val scriptScrollPane = JScrollPane(scriptTextArea) // Add scroll bars
            scriptScrollPane.setBorder(
                BorderFactory.createCompoundBorder(
                    scriptScrollPane.border,
                    BorderFactory.createEmptyBorder(5, 5, 5, 5)
                )
            );
            scriptScrollPane.minimumSize = Dimension(300, 0) // Set minimum width

            // Create the evaluate button
            val evaluateButton = JButton("Evaluate Script")
            evaluateButton.addActionListener { event: ActionEvent? ->
                // Disable UI elements
                evaluateButton.isEnabled = false
                scriptTextArea.isEnabled = false
                statusLabel.text = "Evaluating..."
                resultsTextArea.text = "" // Clear previous results

                val script = scriptTextArea.text
                LOG.info { "Evaluating script: $script" }
                var result: ResultWithDiagnostics<EvaluationResult>? = null
                var evaluationError: Throwable? = null
                val executionTime = measureTime {
                    result = ScriptEvaluation.rawEvaluate(script)
                }
                // Always update status and re-enable UI, regardless of success/failure/exception
                statusLabel.text = "Executed in ${executionTime.inWholeMilliseconds.milliseconds}"
                evaluateButton.isEnabled = true
                scriptTextArea.isEnabled = true


                val outputBuilder = StringBuilder()

                if (evaluationError != null) {
                    outputBuilder.appendLine("Evaluation failed with exception: ${evaluationError.message}")
                    // Optionally add stack trace or more details
                } else if (result != null) {
                    when (val evalResult = result!!) {
                        is ResultWithDiagnostics.Failure -> {
                            ScriptEvaluation.LOG.error { "Script evaluation failed:" }
                            outputBuilder.appendLine("Script evaluation failed:")
                            evalResult.reports.forEach { report ->
                                val message = "  ${report.severity}: ${report.message}"
                                ScriptEvaluation.LOG.error { message }
                                outputBuilder.appendLine(message)
                                report.exception?.let {
                                    ScriptEvaluation.LOG.error(it) { "  Exception details:" }
                                    // Optionally add exception details to outputBuilder
                                }
                            }
                        }

                        is ResultWithDiagnostics.Success -> {
                            when (val returnValue = evalResult.value.returnValue) {
                                ResultValue.NotEvaluated -> {
                                    outputBuilder.appendLine("Script was not evaluated.")
                                }

                                is ResultValue.Error -> {
                                    ScriptEvaluation.LOG.error(returnValue.error) { "Script execution error:" }
                                    outputBuilder.appendLine("Script execution error: ${returnValue.error}")
                                    // Optionally add stack trace
                                }

                                is ResultValue.Unit -> {
                                    ScriptEvaluation.LOG.warn { "Script did not produce a value result. Result type: ${returnValue::class.simpleName}" }
                                    outputBuilder.appendLine("Script did not produce a value result. Result type: ${returnValue::class.simpleName}")
                                }

                                is ResultValue.Value -> {
                                    ScriptEvaluation.LOG.info { "Script execution result: $returnValue" }
                                    val actualValue = returnValue.value
                                    if (actualValue == null) {
                                        ScriptEvaluation.LOG.warn { "Script returned null." }
                                        outputBuilder.appendLine("Script returned null.")
                                    } else if (actualValue !is Fingerprint) {
                                        ScriptEvaluation.LOG.error { "Script returned unexpected type: ${returnValue.type}" }
                                        ScriptEvaluation.LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
                                        ScriptEvaluation.LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
                                        outputBuilder.appendLine("Script returned unexpected type: ${returnValue.type}")
                                    } else {
                                        val resolvedFingerprint = actualValue
                                        outputBuilder.appendLine("Fingerprint: $resolvedFingerprint")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    outputBuilder.appendLine("Evaluation did not produce a result.")
                }


                resultsTextArea.text = outputBuilder.toString().trim()
                resultsTextArea.caretPosition = 0 // Scroll to top
                // No need to call revalidate/repaint explicitly here,
                // setting text on Swing components usually handles it.
                LOG.info { "Script evaluation UI updated." }
            }

            val buttonPanel = JPanel()
            buttonPanel.add(evaluateButton)

            // Add components to the frame's content pane
            // Use a SplitPane for resizable areas if desired, or adjust BorderLayout
            frame.contentPane.layout = BorderLayout() // Ensure main layout is BorderLayout
            frame.contentPane.add(scriptScrollPane, BorderLayout.CENTER) // Script input takes center
            frame.contentPane.add(buttonPanel, BorderLayout.SOUTH)      // Button at the bottom
            frame.contentPane.add(outputPanel, BorderLayout.EAST)       // Output on the right

            // Set default close operation (dispose frame, don't exit application)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

            // Make the window visible
            frame.isVisible = true
        }
    }

    fun inlineSvgIcon(svg: String): FlatSVGIcon {
        val svgInputStream = ByteArrayInputStream(svg.trimIndent().toByteArray(StandardCharsets.UTF_8))
        return FlatSVGIcon(svgInputStream)
    }
}