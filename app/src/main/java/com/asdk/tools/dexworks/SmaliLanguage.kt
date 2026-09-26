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

class SmaliLanguage : Language {

    private val analyzeManager = SmaliAnalyzeManager()

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

    private class SmaliAnalyzeManager : SimpleAnalyzeManager<Any?>() {
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

                // Line comment starting with #
                if (c == '#') {
                    spans.addIfNeeded(line, col, commentStyle)
                    while (i < len && text[i] != '\n') {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // String literal
                if (c == '"') {
                    spans.addIfNeeded(line, col, literalStyle)
                    i++
                    col++
                    while (i < len && text[i] != '"') {
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
                    if (i < len && text[i] == '"') {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // Directive starting with . (e.g. .method, .end method, .class)
                if (c == '.') {
                    val startCol = col
                    val startIdx = i
                    i++
                    col++
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == '_')) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, startCol, keywordStyle)
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // Label starting with : (e.g. :cond_0, :goto_0)
                if (c == ':') {
                    val startCol = col
                    val startIdx = i
                    i++
                    col++
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) {
                        i++
                        col++
                    }
                    if (i > startIdx + 1) {
                        spans.addIfNeeded(line, startCol, identifierStyle)
                        spans.addIfNeeded(line, col, normalStyle)
                        continue
                    } else {
                        spans.addIfNeeded(line, startCol, operatorStyle)
                        spans.addIfNeeded(line, col, normalStyle)
                        continue
                    }
                }

                // Numbers (hex 0x..., negative, decimal)
                if (c.isDigit() || (c == '-' && i + 1 < len && (text[i + 1].isDigit() || text[i + 1] == '0'))) {
                    spans.addIfNeeded(line, col, literalStyle)
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == 'x' || text[i] == 'X')) {
                        i++
                        col++
                    }
                    spans.addIfNeeded(line, col, normalStyle)
                    continue
                }

                // Registers, instructions, identifiers
                if (c.isLetter() || c == '_' || c == '$' || c == '-') {
                    val startCol = col
                    val startIdx = i
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '-' || text[i] == '/')) {
                        i++
                        col++
                    }
                    val word = text.substring(startIdx, i)
                    val isRegister = (word.startsWith("v") || word.startsWith("p")) && word.length > 1 && word.substring(1).all { it.isDigit() }
                    when {
                        isRegister -> spans.addIfNeeded(line, startCol, literalStyle)
                        word in INSTRUCTIONS -> spans.addIfNeeded(line, startCol, keywordStyle)
                        word.contains("->") || word.startsWith("L") && word.endsWith(";") -> spans.addIfNeeded(line, startCol, identifierStyle)
                        word.startsWith("invoke-") -> spans.addIfNeeded(line, startCol, functionStyle)
                        else -> spans.addIfNeeded(line, startCol, normalStyle)
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
            private val INSTRUCTIONS = setOf(
                "nop", "move", "move/from16", "move/16", "move-wide", "move-wide/from16",
                "move-wide/16", "move-object", "move-object/from16", "move-object/16",
                "move-result", "move-result-wide", "move-result-object", "move-exception",
                "return-void", "return", "return-wide", "return-object", "const/4",
                "const/16", "const", "const/high16", "const-wide/16", "const-wide/32",
                "const-wide", "const-wide/high16", "const-string", "const-string/jumbo",
                "const-class", "monitor-enter", "monitor-exit", "check-cast", "instance-of",
                "array-length", "new-instance", "new-array", "filled-new-array",
                "filled-new-array/range", "fill-array-data", "throw", "goto", "goto/16",
                "goto/32", "packed-switch", "sparse-switch", "cmpl-float", "cmpg-float",
                "cmpl-double", "cmpg-double", "cmp-long", "if-eq", "if-ne", "if-lt",
                "if-ge", "if-gt", "if-le", "if-eqz", "if-nez", "if-ltz", "if-gez",
                "if-gtz", "if-lez", "aget", "aget-wide", "aget-object", "aget-boolean",
                "aget-byte", "aget-char", "aget-short", "aput", "aput-wide", "aput-object",
                "aput-boolean", "aput-byte", "aput-char", "aput-short", "iget", "iget-wide",
                "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
                "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char",
                "iput-short", "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte",
                "sget-char", "sget-short", "sput", "sput-wide", "sput-object", "sput-boolean",
                "sput-byte", "sput-char", "sput-short", "invoke-virtual", "invoke-super",
                "invoke-direct", "invoke-static", "invoke-interface", "invoke-virtual/range",
                "invoke-super/range", "invoke-direct/range", "invoke-static/range",
                "invoke-interface/range", "invoke-custom", "invoke-custom/range",
                "invoke-polymorphic", "invoke-polymorphic/range"
            )
        }
    }
}
