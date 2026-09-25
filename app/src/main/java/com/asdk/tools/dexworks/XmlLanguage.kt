package com.asdk.tools.dexworks

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

class XmlColorScheme(isDark: Boolean) : EditorColorScheme(isDark) {
    override fun applyDefault() {
        super.applyDefault()
        if (isDark) {
            setColor(WHOLE_BACKGROUND, 0xFF1E1E1E.toInt())
            setColor(TEXT_NORMAL, 0xFFD4D4D4.toInt())
            setColor(LINE_NUMBER_BACKGROUND, 0xFF252526.toInt())
            setColor(LINE_NUMBER, 0xFF858585.toInt())
            setColor(LINE_NUMBER_CURRENT, 0xFFC6C6C6.toInt())
            setColor(LINE_DIVIDER, 0xFF333333.toInt())
            setColor(SELECTED_TEXT_BACKGROUND, 0xFF264F78.toInt())
            setColor(SELECTION_HANDLE, 0xFF007ACC.toInt())
            setColor(SELECTION_INSERT, 0xFF007ACC.toInt())
            setColor(CURRENT_LINE, 0xFF2A2D2E.toInt())
            setColor(HTML_TAG, 0xFF569CD6.toInt())          // Blue <manifest, <application
            setColor(ATTRIBUTE_NAME, 0xFF9CDCFE.toInt())    // Light Blue android:name
            setColor(ATTRIBUTE_VALUE, 0xFFCE9178.toInt())   // Orange/Brown string values
            setColor(COMMENT, 0xFF6A9955.toInt())           // Green comments
            setColor(OPERATOR, 0xFF808080.toInt())          // Gray <, >, </, =, />
            setColor(KEYWORD, 0xFFC586C0.toInt())           // Purple declarations <?xml
        } else {
            setColor(WHOLE_BACKGROUND, 0xFFFAFAFA.toInt())
            setColor(TEXT_NORMAL, 0xFF24292E.toInt())
            setColor(LINE_NUMBER_BACKGROUND, 0xFFF0F0F0.toInt())
            setColor(LINE_NUMBER, 0xFFA0A0A0.toInt())
            setColor(LINE_NUMBER_CURRENT, 0xFF333333.toInt())
            setColor(LINE_DIVIDER, 0xFFE1E4E8.toInt())
            setColor(SELECTED_TEXT_BACKGROUND, 0xFFB4D5FE.toInt())
            setColor(SELECTION_HANDLE, 0xFF0366D6.toInt())
            setColor(SELECTION_INSERT, 0xFF0366D6.toInt())
            setColor(CURRENT_LINE, 0xFFF0F0F0.toInt())
            setColor(HTML_TAG, 0xFF22863A.toInt())          // Green <manifest, <application
            setColor(ATTRIBUTE_NAME, 0xFF6F42C1.toInt())    // Purple android:name
            setColor(ATTRIBUTE_VALUE, 0xFF032F62.toInt())   // Dark Blue string values
            setColor(COMMENT, 0xFF6A737D.toInt())           // Gray comments
            setColor(OPERATOR, 0xFF666666.toInt())          // Gray <, >, </, =, />
            setColor(KEYWORD, 0xFFD73A49.toInt())           // Red declarations <?xml
        }
    }
}

class XmlLanguage : Language {

    private val analyzeManager = XmlAnalyzeManager()

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int = 0

    override fun useTab(): Boolean = false

    override fun getFormatter(): Formatter = EmptyLanguage.EmptyFormatter.INSTANCE

    override fun getSymbolPairs(): SymbolPairMatch = EmptyLanguage.EMPTY_SYMBOL_PAIRS

    override fun getNewlineHandlers(): Array<NewlineHandler> = emptyArray()

    override fun destroy() {
        analyzeManager.destroy()
    }

    private class XmlAnalyzeManager : SimpleAnalyzeManager<Any?>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Any?>): Styles {
            val spans = MappedSpans.Builder()
            val len = text.length
            var line = 0
            var col = 0
            var i = 0

            val normalStyle = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
            val tagStyle = TextStyle.makeStyle(EditorColorScheme.HTML_TAG, 0, true, false, false, false)
            val attrNameStyle = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)
            val attrValStyle = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_VALUE)
            val commentStyle = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false, false)
            val operatorStyle = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
            val declStyle = TextStyle.makeStyle(EditorColorScheme.KEYWORD)

            spans.addIfNeeded(0, 0, normalStyle)

            while (i < len) {
                if (delegate.isCancelled) {
                    return Styles(spans.build())
                }

                val c = text[i]

                if (c == '\n') {
                    line++
                    col = 0
                    i++
                    continue
                }

                // 1. Comments: <!-- ... -->
                if (c == '<' && i + 3 < len && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                    spans.addIfNeeded(line, col, commentStyle)
                    i += 4
                    col += 4
                    while (i < len) {
                        if (text[i] == '\n') {
                            line++
                            col = 0
                            i++
                            continue
                        }
                        if (i + 2 < len && text[i] == '-' && text[i + 1] == '-' && text[i + 2] == '>') {
                            i += 3
                            col += 3
                            break
                        }
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // 2. XML Declaration / Processing: <?xml ... ?>
                if (c == '<' && i + 1 < len && text[i + 1] == '?') {
                    spans.addIfNeeded(line, col, declStyle)
                    i += 2
                    col += 2
                    while (i < len) {
                        if (text[i] == '\n') {
                            line++
                            col = 0
                            i++
                            continue
                        }
                        if (i + 1 < len && text[i] == '?' && text[i + 1] == '>') {
                            i += 2
                            col += 2
                            break
                        }
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // 3. Tags: <tag, </tag>, />, >
                if (c == '<') {
                    spans.addIfNeeded(line, col, operatorStyle)
                    i++
                    col++
                    if (i < len && text[i] == '/') {
                        i++
                        col++
                    }
                    val tagStartCol = col
                    val tagStartIdx = i
                    while (i < len && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/' && text[i] != '\n') {
                        i++
                        col++
                    }
                    if (i > tagStartIdx) {
                        spans.addIfNeeded(line, tagStartCol, tagStyle)
                        spans.addIfNeeded(line, col, normalStyle)
                    }
                    continue
                }

                if (c == '>' || (c == '/' && i + 1 < len && text[i + 1] == '>')) {
                    spans.addIfNeeded(line, col, operatorStyle)
                    if (c == '/') {
                        i += 2
                        col += 2
                    } else {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                if (c == '=') {
                    spans.addIfNeeded(line, col, operatorStyle)
                    i++
                    col++
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // 4. Attribute String Values: "value" or 'value'
                if (c == '"' || c == '\'') {
                    val quote = c
                    spans.addIfNeeded(line, col, attrValStyle)
                    i++
                    col++
                    while (i < len && text[i] != quote) {
                        if (text[i] == '\n') {
                            line++
                            col = 0
                            i++
                            continue
                        }
                        i++
                        col++
                    }
                    if (i < len && text[i] == quote) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // 5. Attribute Names (e.g. android:name= or package=)
                if (c.isLetter() || c == '_' || c == ':') {
                    val attrStartCol = col
                    val attrStartIdx = i
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) {
                        i++
                        col++
                    }
                    var lookAhead = i
                    while (lookAhead < len && text[lookAhead].isWhitespace() && text[lookAhead] != '\n') {
                        lookAhead++
                    }
                    if (lookAhead < len && text[lookAhead] == '=') {
                        spans.addIfNeeded(line, attrStartCol, attrNameStyle)
                        spans.addIfNeeded(line, col, normalStyle)
                    }
                    continue
                }

                i++
                col++
            }

            spans.determine(line)
            spans.addNormalIfNull()
            return Styles(spans.build())
        }
    }
}
