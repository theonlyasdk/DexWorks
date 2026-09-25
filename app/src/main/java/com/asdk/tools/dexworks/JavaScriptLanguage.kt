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

class JavaScriptColorScheme(isDark: Boolean) : EditorColorScheme(isDark) {
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
            setColor(KEYWORD, 0xFF569CD6.toInt())        // Blue keywords
            setColor(LITERAL, 0xFFCE9178.toInt())        // Orange strings and numbers
            setColor(COMMENT, 0xFF6A9955.toInt())        // Green comments
            setColor(OPERATOR, 0xFFD4D4D4.toInt())       // Gray operators
            setColor(FUNCTION_NAME, 0xFFDCDCAA.toInt())  // Yellow call names
            setColor(IDENTIFIER_VAR, 0xFF9CDCFE.toInt()) // Light blue types and constants
        } else {
            setColor(WHOLE_BACKGROUND, 0xFFFFFFFF.toInt())
            setColor(TEXT_NORMAL, 0xFF1F1F1F.toInt())
            setColor(LINE_NUMBER_BACKGROUND, 0xFFF5F5F5.toInt())
            setColor(LINE_NUMBER, 0xFFA0A0A0.toInt())
            setColor(LINE_NUMBER_CURRENT, 0xFF333333.toInt())
            setColor(LINE_DIVIDER, 0xFFE1E4E8.toInt())
            setColor(SELECTED_TEXT_BACKGROUND, 0xFFB4D5FE.toInt())
            setColor(SELECTION_HANDLE, 0xFF0366D6.toInt())
            setColor(SELECTION_INSERT, 0xFF0366D6.toInt())
            setColor(CURRENT_LINE, 0xFFF0F0F0.toInt())
            setColor(KEYWORD, 0xFF0000FF.toInt())
            setColor(LITERAL, 0xFFA31515.toInt())
            setColor(COMMENT, 0xFF008000.toInt())
            setColor(OPERATOR, 0xFF1F1F1F.toInt())
            setColor(FUNCTION_NAME, 0xFF795E26.toInt())
            setColor(IDENTIFIER_VAR, 0xFF001080.toInt())
        }
    }
}

class JavaScriptLanguage : Language {

    private val analyzeManager = JavaScriptAnalyzeManager()

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

    private class JavaScriptAnalyzeManager : SimpleAnalyzeManager<Any?>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Any?>): Styles {
            val spans = MappedSpans.Builder()
            val len = text.length
            var line = 0
            var col = 0
            var i = 0
            var prevSignificant = '\u0000'

            val normalStyle = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
            val keywordStyle = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
            val literalStyle = TextStyle.makeStyle(EditorColorScheme.LITERAL)
            val commentStyle = TextStyle.makeStyle(
                EditorColorScheme.COMMENT,
                0,
                false,
                true,
                false,
                false
            )
            val operatorStyle = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
            val functionStyle = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
            val identifierStyle = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR)

            val regexAllowedAfter = "(,=:[!&|?{};+-*%<>"
            val operatorChars = "+-*/%=<>!&|^~?:;,.()[]{}"

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

                if (c.isWhitespace()) {
                    i++
                    col++
                    continue
                }

                // Line comment
                if (c == '/' && i + 1 < len && text[i + 1] == '/') {
                    spans.addIfNeeded(line, col, commentStyle)
                    while (i < len && text[i] != '\n') {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = ' '
                    continue
                }

                // Block comment
                if (c == '/' && i + 1 < len && text[i + 1] == '*') {
                    spans.addIfNeeded(line, col, commentStyle)
                    i += 2
                    col += 2
                    while (i < len) {
                        if (i + 1 < len && text[i] == '*' && text[i + 1] == '/') {
                            i += 2
                            col += 2
                            break
                        }
                        if (text[i] == '\n') {
                            line++
                            col = 0
                            i++
                            continue
                        }
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = ' '
                    continue
                }

                // Regex literal, only where a value is expected
                if (c == '/' && i + 1 < len && text[i + 1] != '/' && text[i + 1] != '*' &&
                    prevSignificant in regexAllowedAfter
                ) {
                    spans.addIfNeeded(line, col, literalStyle)
                    i++
                    col++
                    var inClass = false
                    while (i < len) {
                        val d = text[i]
                        if (d == '\\' && i + 1 < len) {
                            i += 2
                            col += 2
                            continue
                        }
                        if (d == '\n') break
                        if (d == '[') inClass = true
                        if (d == ']') inClass = false
                        if (d == '/' && !inClass) {
                            i++
                            col++
                            break
                        }
                        i++
                        col++
                    }
                    while (i < len && text[i].isLetter()) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = '/'
                    continue
                }

                // String, single quoted, double quoted or template literal
                if (c == '"' || c == '\'' || c == '`') {
                    val quote = c
                    spans.addIfNeeded(line, col, literalStyle)
                    i++
                    col++
                    while (i < len && text[i] != quote) {
                        if (text[i] == '\\' && i + 1 < len) {
                            i += 2
                            col += 2
                            continue
                        }
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
                    prevSignificant = quote
                    continue
                }

                // Number, including hex, binary, exponent and separators
                if (c.isDigit() || (c == '.' && i + 1 < len && text[i + 1].isDigit())) {
                    spans.addIfNeeded(line, col, literalStyle)
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = text[i - 1]
                    continue
                }

                // Identifier or keyword
                if (c.isLetter() || c == '_' || c == '$') {
                    val startCol = col
                    val startIdx = i
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) {
                        i++
                        col++
                    }
                    val word = text.substring(startIdx, i)
                    var lookAhead = i
                    while (lookAhead < len && text[lookAhead].isWhitespace() && text[lookAhead] != '\n') {
                        lookAhead++
                    }
                    val isCall = lookAhead < len && text[lookAhead] == '('
                    when {
                        word in KEYWORDS -> spans.addIfNeeded(line, startCol, keywordStyle)
                        isCall -> spans.addIfNeeded(line, startCol, functionStyle)
                        word[0].isUpperCase() -> spans.addIfNeeded(line, startCol, identifierStyle)
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = text[i - 1]
                    continue
                }

                // Operators and punctuation
                if (c in operatorChars) {
                    spans.addIfNeeded(line, col, operatorStyle)
                    i++
                    col++
                    spans.addIfNeeded(line, col, normalStyle)
                    prevSignificant = c
                    continue
                }

                i++
                col++
            }

            spans.determine(line)
            spans.addNormalIfNull()
            return Styles(spans.build())
        }

        companion object {
            private val KEYWORDS = setOf(
                "await", "break", "case", "catch", "class", "const", "continue", "debugger",
                "default", "delete", "do", "else", "export", "extends", "finally", "for",
                "function", "get", "if", "import", "in", "instanceof", "let", "new", "of",
                "return", "set", "static", "super", "switch", "this", "throw", "try",
                "typeof", "var", "void", "while", "with", "yield", "async", "true", "false",
                "null", "undefined", "NaN", "Infinity"
            )
        }
    }
}
