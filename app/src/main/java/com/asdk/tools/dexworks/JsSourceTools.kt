package com.asdk.tools.dexworks

object JsSourceTools {

    private const val INDENT = "    "

    private val KEYWORDS_BEFORE_REGEX = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw",
        "case", "do", "else", "yield", "await"
    )

    // Compiled once: unobfuscateOnce runs up to four times over the whole file,
    // and these were previously rebuilt on every pass.
    private val FROM_CHAR_CODE = Regex("String\\s*\\.\\s*fromCharCode\\s*\\(([^()]*)\\)")
    private val ATOB_CALL = Regex("atob\\s*\\(\\s*[\"']([A-Za-z0-9+/=]*)[\"']\\s*\\)")
    private val STRING_CONCAT = Regex(
        "\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"\\s*(?:\\+\\s*\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\")+"
    )
    private val BANG_ZERO = Regex("(?<![\\w$.])!0(?![\\w$])")
    private val BANG_ONE = Regex("(?<![\\w$.])!1(?![\\w$])")
    private val VOID_ZERO = Regex("(?<![\\w$.])void\\s+0(?![\\w$])")

    fun prettify(source: String): String {
        return try {
            prettifyInternal(source)
        } catch (e: Throwable) {
            source
        }
    }

    private fun prettifyInternal(source: String): String {
        val out = StringBuilder(source.length + source.length / 2)
        var indent = 0
        var i = 0
        val len = source.length
        var atLineStart = true

        fun emit(text: String) {
            if (atLineStart) {
                var level = indent
                if (text.startsWith("}")) level = (level - 1).coerceAtLeast(0)
                repeat(level) { out.append(INDENT) }
                atLineStart = false
            }
            out.append(text)
        }

        fun newLine() {
            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                out.setLength(out.length - 1)
            }
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            atLineStart = true
        }

        var lastMeaningful = '\u0000'

        while (i < len) {
            val c = source[i]

            if (c == '\n' || c == '\r') {
                newLine()
                i++
                continue
            }

            if (c == ' ' || c == '\t') {
                if (!atLineStart) out.append(c)
                i++
                continue
            }

            if (c == '/' && i + 1 < len && source[i + 1] == '/') {
                val end = source.indexOf('\n', i).let { if (it < 0) len else it }
                emit(source.substring(i, end))
                i = end
                continue
            }

            if (c == '/' && i + 1 < len && source[i + 1] == '*') {
                val end = source.indexOf("*/", i + 2).let {
                    if (it < 0) len else it + 2
                }
                emit(source.substring(i, end))
                i = end
                continue
            }

            if (c == '"' || c == '\'' || c == '`') {
                val end = scanStringEnd(source, i, c)
                emit(source.substring(i, end))
                lastMeaningful = c
                i = end
                continue
            }

            if (c == '/' && regexAllowed(lastMeaningful, source, i)) {
                val end = scanRegexEnd(source, i)
                emit(source.substring(i, end))
                lastMeaningful = '/'
                i = end
                continue
            }

            if (c == '{') {
                emit("{")
                indent++
                newLine()
                lastMeaningful = '{'
                i++
                continue
            }

            if (c == '}') {
                indent = (indent - 1).coerceAtLeast(0)
                newLine()
                emit("}")
                lastMeaningful = '}'
                i++
                // keep a trailing comment on the same line as its closing brace
                var j = i
                while (j < len && (source[j] == ' ' || source[j] == '\t')) j++
                if (j < len && source[j] == '/' && j + 1 < len &&
                    (source[j + 1] == '/' || source[j + 1] == '*')
                ) {
                    i = j
                } else {
                    newLine()
                }
                continue
            }

            if (c == ';') {
                emit(";")
                newLine()
                lastMeaningful = ';'
                i++
                continue
            }

            if (c == ',' && isInsideBraceCall(source, i)) {
                emit(",")
                newLine()
                lastMeaningful = ','
                i++
                continue
            }

            emit(c.toString())
            if (!c.isWhitespace()) lastMeaningful = c
            i++
        }

        newLine()
        return out.toString().trimEnd('\n') + "\n"
    }

    fun unobfuscate(source: String): String {
        var current = source
        repeat(MAX_UNOBFUSCATE_PASSES) {
            val next = try {
                unobfuscateOnce(current)
            } catch (e: Throwable) {
                return current
            }
            if (next == current) return current
            current = next
        }
        return current
    }

    fun unobfuscateWithReport(source: String): Pair<String, JsDeobfuscator.Report> {
        var current = source
        var report = JsDeobfuscator.Report()

        repeat(MAX_UNOBFUSCATE_PASSES) {
            val simple = try {
                unobfuscateOnce(current)
            } catch (e: Throwable) {
                current
            }
            val (decoded, passReport) = JsDeobfuscator.deobfuscate(simple)
            report = JsDeobfuscator.Report(
                arraysFound = maxOf(report.arraysFound, passReport.arraysFound),
                arraysResolved = maxOf(report.arraysResolved, passReport.arraysResolved),
                referencesReplaced = report.referencesReplaced + passReport.referencesReplaced,
                constantFolds = report.constantFolds + passReport.constantFolds,
                notes = (report.notes + passReport.notes).distinct()
            )
            if (decoded == current) return decoded to report
            current = decoded
        }
        return current to report
    }

    private fun unobfuscateOnce(source: String): String {
        var text = decodeStringEscapes(source)
        text = foldFromCharCode(text)
        text = foldAtob(text)
        text = foldConcatenation(text)
        text = foldTernaryConstants(text)
        return text
    }

    // Replaces String.fromCharCode(72, 101) with "He" when every argument is a literal number.
    private fun foldFromCharCode(source: String): String {
        return FROM_CHAR_CODE.replace(source) { match ->
            val args = match.groupValues[1]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (args.isEmpty() || args.any { it.toIntOrNull() == null }) {
                match.value
            } else {
                val decoded = args.mapNotNull { it.toIntOrNull() }.map { it.toChar() }.joinToString("")
                quoteForSource(decoded)
            }
        }
    }

    // Replaces atob("...") with the decoded text when the argument is a plain literal.
    private fun foldAtob(source: String): String {
        val pattern = ATOB_CALL
        return pattern.replace(source) { match ->
            val decoded = runCatching { String(decodeBase64(match.groupValues[1])) }.getOrNull()
            if (decoded == null || decoded.any { it.code < 9 || it.code > 126 }) {
                match.value
            } else {
                quoteForSource(decoded)
            }
        }
    }

    // Merges runs of literals joined by +, e.g. "a" + "b" + "c" becomes "abc".
    private fun foldConcatenation(source: String): String {
        return STRING_CONCAT.replace(source) { match ->
            val raw = match.value
            if (raw.contains("//") || raw.contains("/*")) return@replace raw
            val builder = StringBuilder()
            var index = 0
            while (index < raw.length) {
                when (raw[index]) {
                    '"' -> {
                        index++
                        while (index < raw.length && raw[index] != '"') {
                            if (raw[index] == '\\') index++
                            index++
                        }
                        index++
                    }
                    '+' -> index++
                    else -> {
                        builder.append(raw[index])
                        index++
                    }
                }
            }
            quoteForSource(decodeEscapes(builder.toString()))
        }
    }

    // !0 -> true, !1 -> false, void 0 -> undefined
    private fun foldTernaryConstants(source: String): String {
        var text = source
        text = BANG_ZERO.replace(text, "true")
        text = BANG_ONE.replace(text, "false")
        text = VOID_ZERO.replace(text, "undefined")
        return text
    }

    // Walks string literals and rewrites \xNN, \uNNNN and standard escapes into real characters.
    private fun decodeStringEscapes(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length &&
                (source[i + 1] == '/' || source[i + 1] == '*')
            ) {
                if (c == '/' && source[i + 1] == '/') {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    out.append(source, i, end)
                    i = end
                } else {
                    val end = source.indexOf("*/", i + 2).let {
                        if (it < 0) source.length else it + 2
                    }
                    out.append(source, i, end)
                    i = end
                }
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                val end = scanStringEnd(source, i, c)
                if (end <= i) {
                    out.append(c)
                    i++
                    continue
                }
                val raw = source.substring(i, end)
                val inner = if (raw.length >= 2) raw.substring(1, raw.length - 1) else ""
                out.append(c)
                out.append(decodeEscapes(inner))
                if (raw.length >= 2) out.append(raw.last())
                i = end
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun decodeEscapes(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            val next = value[i + 1]
            when (next) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'v' -> { out.append('\u000B'); i += 2 }
                '0' -> { out.append('\u0000'); i += 2 }
                'x' -> {
                    val hex = value.substring((i + 2).coerceAtMost(value.length), (i + 4).coerceAtMost(value.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 2 && code != null) {
                        out.append(code.toChar())
                        i += 4
                    } else {
                        out.append(c)
                        i++
                    }
                }
                'u' -> {
                    if (i + 2 < value.length && value[i + 2] == '{') {
                        val close = value.indexOf('}', i + 3)
                        val code = if (close < 0) null else value.substring(i + 3, close).toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i = close + 1
                        } else {
                            out.append(c)
                            i++
                        }
                    } else {
                        val hex = value.substring((i + 2).coerceAtMost(value.length), (i + 6).coerceAtMost(value.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            out.append(code.toChar())
                            i += 6
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                }
                else -> {
                    out.append(c).append(next)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    private fun quoteForSource(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 32) {
                    out.append(String.format("\\u%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun decodeBase64(value: String): ByteArray {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = value.trimEnd('=')
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val index = table.indexOf(c)
            if (index < 0) continue
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun scanStringEnd(source: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (quote != '`' && c == '\n') return i
            i++
        }
        return source.length
    }

    private fun scanRegexEnd(source: String, start: Int): Int {
        var i = start + 1
        var inClass = false
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '\n') return i
            if (c == '[') inClass = true
            if (c == ']') inClass = false
            if (c == '/' && !inClass) {
                i++
                while (i < source.length && source[i].isLetter()) i++
                return i
            }
            i++
        }
        return source.length
    }

    private fun regexAllowed(prev: Char, source: String, index: Int): Boolean {
        if (prev == '\u0000') return true
        if (prev in "(,=:[!&|?{};+-*%<>") return true
        if (index > 0) {
            val before = source[index - 1]
            if (!before.isWhitespace()) return false
        }
        var j = index - 1
        while (j >= 0 && source[j].isWhitespace()) j--
        if (j < 0) return true
        val word = readWordBackwards(source, j)
        return word in KEYWORDS_BEFORE_REGEX
    }

    private fun readWordBackwards(source: String, endExclusive: Int): String {
        var j = endExclusive
        while (j > 0 && (source[j - 1].isLetterOrDigit() || source[j - 1] == '_' || source[j - 1] == '$')) {
            j--
        }
        return source.substring(j, endExclusive)
    }

    private fun isInsideBraceCall(source: String, index: Int): Boolean {
        var depth = 0
        var j = index - 1
        while (j >= 0) {
            val c = source[j]
            if (c == ')' || c == ']') depth++
            if (c == '(' || c == '[') {
                if (depth == 0) return false
                depth--
            }
            if (depth == 0 && (c == ';' || c == '{' || c == '}')) return false
            j--
        }
        return false
    }

    private const val MAX_UNOBFUSCATE_PASSES = 4
}
