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

class JavaLanguage : Language {

    private val analyzeManager = JavaAnalyzeManager()

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

    private class JavaAnalyzeManager : SimpleAnalyzeManager<Any?>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Any?>): Styles {
            val spans = MappedSpans.Builder()
            val len = text.length
            var line = 0
            var col = 0
            var i = 0

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
                    continue
                }

                // String literal or Char literal
                if (c == '"' || c == '\'') {
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
                    continue
                }

                // Annotation
                if (c == '@' && i + 1 < len && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                    val startCol = col
                    i++
                    col++
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, startCol, identifierStyle)
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // Number
                if (c.isDigit() || (c == '.' && i + 1 < len && text[i + 1].isDigit())) {
                    spans.addIfNeeded(line, col, literalStyle)
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
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
                    continue
                }

                // Operators
                if (c in operatorChars) {
                    spans.addIfNeeded(line, col, operatorStyle)
                    i++
                    col++
                    spans.addIfNeeded(line, col, normalStyle)
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
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                "import", "instanceof", "int", "interface", "long", "native", "new",
                "package", "private", "protected", "public", "return", "short", "static",
                "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "true", "false", "null",
                "record", "sealed", "permits", "non-sealed", "yield", "var"
            )
        }
    }
}
