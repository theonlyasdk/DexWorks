package com.asdk.tools.dexworks

/**
 * Static JavaScript deobfuscation for the obfuscator.io / javascript-obfuscator
 * string-array family, plus the cheap constant folds that usually accompany it.
 *
 * The source is split into code, string and comment segments first, so every
 * transform below only ever rewrites real code and can never corrupt the inside
 * of a string literal or a comment.
 *
 * Deliberate limitations, following standard practice for this family:
 *  - a string array is only resolved when its rotation count can be proven from
 *    the IIFE, because resolving against an un-rotated table silently returns the
 *    wrong string for every call site, which is worse than no decode at all;
 *  - arrays whose entries are still base64/RC4 encoded are reported, not guessed,
 *    since emitting the encoded blob would look like a decoded string.
 */
object JsDeobfuscator {

    data class Report(
        val arraysFound: Int = 0,
        val arraysResolved: Int = 0,
        val referencesReplaced: Int = 0,
        val constantFolds: Int = 0,
        val notes: List<String> = emptyList()
    )

    private enum class Kind { CODE, STRING, COMMENT }

    private class Segment(val kind: Kind, val text: String)

    private val IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*"
    private val NUMBER = "(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)"

    private val KEYWORDS_BEFORE_REGEX = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw",
        "case", "do", "else", "yield", "await"
    )

    // Fixed patterns are compiled once. They used to be rebuilt for every code
    // segment on every deobfuscation pass, which is a lot of wasted compilation
    // on a large minified bundle.
    private val OBFUSCATED_NAME = Regex("^_0x[0-9a-f]+$", RegexOption.IGNORE_CASE)
    private val BASE64_BLOB = Regex("^[A-Za-z0-9+/]{16,}={0,2}$")
    private val WHITESPACE = Regex("\\s+")
    private val DEBUGGER_STATEMENT = Regex("\\bdebugger\\s*;?")
    private val NOT_NOT_EMPTY_ARRAY = Regex("!\\s*!\\s*\\[\\s*\\]")
    private val NOT_EMPTY_ARRAY = Regex("!\\s*\\[\\s*\\]")
    private val NOT_NOT_EMPTY_STRING = Regex("!\\s*!\\s*''")
    private val NOT_EMPTY_STRING = Regex("!\\s*''")

    private data class StringArray(
        val name: String,
        val values: List<String>,
        val rotation: Int = 0,
        val decoderName: String? = null,
        val decoderOffset: Int = 0
    ) {
        /** Resolves a raw call or index argument to the plaintext entry, if in range. */
        fun lookup(rawIndex: Int): String? {
            val size = values.size
            if (size == 0) return null
            var index = (rawIndex - decoderOffset + rotation) % size
            if (index < 0) index += size
            return values.getOrNull(index)
        }
    }

    fun deobfuscate(source: String): Pair<String, Report> {
        return try {
            run(source)
        } catch (e: Throwable) {
            source to Report(notes = listOf("Stopped early: ${e.javaClass.simpleName}"))
        }
    }

    private fun run(source: String): Pair<String, Report> {
        var segments = split(source)
        var arraysFound = 0
        var arraysResolved = 0
        var references = 0
        var folds = 0
        val notes = linkedSetOf<String>()

        for (round in 0 until 4) {
            val arrayPass = resolveStringArrays(segments)
            segments = arrayPass.first
            arraysFound = maxOf(arraysFound, arrayPass.second.arraysFound)
            arraysResolved = maxOf(arraysResolved, arrayPass.second.arraysResolved)
            references += arrayPass.second.referencesReplaced
            notes.addAll(arrayPass.third)

            val foldPass = foldConstants(segments)
            segments = foldPass.first
            folds += foldPass.second

            if (arrayPass.second.referencesReplaced == 0 && foldPass.second == 0) {
                break
            }
        }

        val text = segments.joinToString("") { it.text }
        return text to Report(
            arraysFound = arraysFound,
            arraysResolved = arraysResolved,
            referencesReplaced = references,
            constantFolds = folds,
            notes = notes.toList()
        )
    }

    // ---------------------------------------------------------------- arrays

    private fun resolveStringArrays(
        segments: List<Segment>
    ): Triple<List<Segment>, Report, List<String>> {
        val notes = linkedSetOf<String>()

        val candidates = mutableListOf<StringArray>()
        for (segment in segments) {
            if (segment.kind != Kind.CODE) continue
            val found = collectArrayLiterals(segment.text) ?: continue
            val (name, values) = found
            if (values.size >= 3 && looksObfuscated(name)) {
                candidates += StringArray(name, values)
            }
        }

        if (candidates.isEmpty()) {
            return Triple(segments, Report(), notes.toList())
        }

        val wholeCode = segments.joinToString("") {
            if (it.kind == Kind.CODE) it.text else " "
        }

        val resolvable = mutableListOf<StringArray>()
        for (candidate in candidates) {
            if (looksEncoded(candidate.values)) {
                notes.add("${candidate.name} is base64/RC4 encoded, not decoded")
                continue
            }
            val rotation = detectRotation(wholeCode, candidate.name)
            if (rotation == null) {
                notes.add("Could not prove the rotation of ${candidate.name}, left as is")
                continue
            }
            val withRotation = candidate.copy(rotation = rotation)
            val decoder = readDecoderOffset(wholeCode, candidate.name)
            resolvable += if (decoder != null) {
                withRotation.copy(decoderName = decoder.first, decoderOffset = decoder.second)
            } else {
                withRotation
            }
        }

        if (resolvable.isEmpty()) {
            return Triple(segments, Report(arraysFound = candidates.size), notes.toList())
        }

        var references = 0
        val updated = segments.map { segment ->
            if (segment.kind != Kind.CODE) {
                segment
            } else {
                var text = segment.text
                for (array in resolvable) {
                    val decoderName = array.decoderName
                    if (decoderName != null) {
                        val (afterDecoder, n2) = substituteCalls(text, array, decoderName)
                        text = afterDecoder
                        references += n2
                    }
                    val (afterIndex, n1) = substituteIndexes(text, array)
                    text = afterIndex
                    references += n1
                }
                Segment(Kind.CODE, text)
            }
        }

        return Triple(
            updated,
            Report(
                arraysFound = candidates.size,
                arraysResolved = resolvable.size,
                referencesReplaced = references
            ),
            notes.toList()
        )
    }

    private fun looksObfuscated(name: String): Boolean =
        OBFUSCATED_NAME.matches(name)

    /**
     * Treats an array as still encoded when most entries are long base64 blobs
     * that do not decode to printable text.
     */
    private fun looksEncoded(values: List<String>): Boolean {
        if (values.isEmpty()) return false
        val blob = BASE64_BLOB
        val suspicious = values.count { blob.matches(it) }
        if (suspicious * 10 < values.size * 6) return false
        val decodesToText = values.filter { blob.matches(it) }.count {
            val bytes = decodeBase64(it)
            bytes.isNotEmpty() && bytes.all { b -> b.toInt() in 32..126 }
        }
        return decodesToText * 10 < suspicious * 6
    }

    /** Reads NAME = [ 'a', 'b', ... ] and returns the literal values. */
    private fun collectArrayLiterals(code: String): Pair<String, List<String>>? {
        val matcher = Regex("(?:var|let|const)\\s+($IDENTIFIER)\\s*=\\s*\\[").find(code)
            ?: return null
        val name = matcher.groupValues[1]
        val open = matcher.range.last
        val close = findMatchingBracket(code, open) ?: return null
        val body = code.substring(open + 1, close)

        val values = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c == '"' || c == '\'' -> {
                    val end = scanStringEnd(body, i, c)
                    if (end <= i + 1) return null
                    values += body.substring(i + 1, end - 1)
                    i = end
                }
                else -> return null
            }
        }
        if (values.isEmpty()) return null
        return name to values
    }

    /**
     * Detects the rotation IIFE for [arrayName] and returns how many left
     * rotations to apply, or null when it cannot be proven.
     *
     * Handles the two constant-count forms the common obfuscators emit:
     *   while (--count) { arr.push(arr.shift()); }  ->  count - 1
     *   while (count--) { arr.push(arr.shift()); }  ->  count
     */
    private fun detectRotation(code: String, arrayName: String): Int? {
        val name = Regex.escape(arrayName)
        val normalized = code.replace(WHITESPACE, " ")
        val shuffle = "$name\\s*\\.push\\s*\\(\\s*$name\\s*\\.shift\\s*\\(\\s*\\)\\s*\\)"

        val prefixed = Regex("while\\s*\\(\\s*--\\s*($IDENTIFIER)\\s*\\)\\s*\\{[^{}]*$shuffle")
        val postfix = Regex("while\\s*\\(\\s*($IDENTIFIER)\\s*--\\s*\\)\\s*\\{[^{}]*$shuffle")

        prefixed.find(normalized)?.let { match ->
            val count = counterArgument(normalized, match.groupValues[1], match.range.last)
                ?: return null
            val rotations = count - 1
            return if (rotations < 0) null else rotations
        }

        postfix.find(normalized)?.let { match ->
            val count = counterArgument(normalized, match.groupValues[1], match.range.last)
                ?: return null
            return if (count < 0) null else count
        }

        // A shuffle exists but its count is not a plain literal: refuse to guess.
        return if (Regex(shuffle).containsMatchIn(normalized)) null else 0
    }

    /** Finds the IIFE argument that seeds [counterName], searching after the loop. */
    private fun counterArgument(normalized: String, counterName: String, loopEnd: Int): Int? {
        val name = Regex.escape(counterName)
        val near = normalized.substring(loopEnd, (loopEnd + 400).coerceAtMost(normalized.length))
        val argRegex = Regex("\\(\\s*[^)]*\\b$name\\s*,\\s*($NUMBER)")
        val match = argRegex.find(near) ?: argRegex.find(normalized) ?: return null
        return parseNumeric(match.groupValues[1])
    }

    /**
     * Reads the offset from an accessor of the shape
     *   function dec(a, b) { a = a - 0x1a3; ... arr[a] ... }
     * and returns the accessor name plus the value subtracted.
     */
    private fun readDecoderOffset(code: String, arrayName: String): Pair<String, Int>? {
        val normalized = code.replace(WHITESPACE, " ")
        val functionRe = Regex(
            "function\\s+($IDENTIFIER)\\s*\\(\\s*($IDENTIFIER)\\s*,\\s*($IDENTIFIER)\\s*\\)\\s*\\{"
        )
        for (match in functionRe.findAll(normalized)) {
            val decoderName = match.groupValues[1]
            val paramName = match.groupValues[2]
            val bodyStart = match.range.last
            val body = normalized.substring(bodyStart, (bodyStart + 400).coerceAtMost(normalized.length))
            if (!body.contains(arrayName)) continue
            val offsetRe = Regex("\\b${Regex.escape(paramName)}\\s*=\\s*${Regex.escape(paramName)}\\s*-\\s*(-?$NUMBER)")
            val offsetMatch = offsetRe.find(body) ?: continue
            val offset = parseNumeric(offsetMatch.groupValues[1]) ?: continue
            return decoderName to offset
        }
        return null
    }

    private fun substituteIndexes(code: String, array: StringArray): Pair<String, Int> {
        val pattern = Regex("\\b${Regex.escape(array.name)}\\s*\\[\\s*[\"']?($NUMBER)[\"']?\\s*]")
        var count = 0
        val result = pattern.replace(code) { match ->
            val index = parseNumeric(match.groupValues[1])
            val value = index?.let { array.lookup(it) }
            if (value == null) {
                match.value
            } else {
                count++
                quote(value)
            }
        }
        return result to count
    }

    private fun substituteCalls(
        code: String,
        array: StringArray,
        decoderName: String
    ): Pair<String, Int> {
        val pattern = Regex(
            "\\b${Regex.escape(decoderName)}\\s*\\(\\s*[\"']?($NUMBER)[\"']?\\s*(?:,[^()]*)?\\)"
        )
        var count = 0
        val result = pattern.replace(code) { match ->
            val index = parseNumeric(match.groupValues[1])
            val value = index?.let { array.lookup(it) }
            if (value == null) {
                match.value
            } else {
                count++
                quote(value)
            }
        }
        return result to count
    }

    // ------------------------------------------------------------- constants

    private fun foldConstants(segments: List<Segment>): Pair<List<Segment>, Int> {
        var count = 0
        val updated = segments.map { segment ->
            if (segment.kind != Kind.CODE) {
                segment
            } else {
                var text = segment.text
                val before = text
                text = foldOpaquePredicates(text)
                text = foldHexMath(text)
                text = foldBracketAccess(text)
                text = DEBUGGER_STATEMENT.replace(text) { "" }
                if (text != before) count++
                Segment(Kind.CODE, text)
            }
        }
        return updated to count
    }

    private fun foldOpaquePredicates(text: String): String {
        var out = text
        out = out.replace(NOT_NOT_EMPTY_ARRAY, "true")
        out = out.replace(NOT_EMPTY_ARRAY, "false")
        out = out.replace(NOT_NOT_EMPTY_STRING, "true")
        out = out.replace(NOT_EMPTY_STRING, "false")
        return out
    }

    /** Evaluates arithmetic made only of hex or decimal literals. */
    private fun foldHexMath(text: String): String {
        val pattern = Regex(
            "(?<![\\w$.])(-?(?:0[xX][0-9a-fA-F]+|\\d+))\\s*([+\\-*])\\s*" +
                "(-?(?:0[xX][0-9a-fA-F]+|\\d+))(?![\\w$.])"
        )
        var out = text
        repeat(3) {
            out = pattern.replace(out) { match ->
                val left = parseNumeric(match.groupValues[1]) ?: return@replace match.value
                val right = parseNumeric(match.groupValues[3]) ?: return@replace match.value
                val value = when (match.groupValues[2]) {
                    "+" -> left + right
                    "-" -> left - right
                    else -> left * right
                }
                if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) match.value else "($value)"
            }
        }
        return out
    }

    /** Turns obj['prop'] into obj.prop when the key is a plain identifier. */
    private fun foldBracketAccess(text: String): String {
        val pattern = Regex("(\\b$IDENTIFIER)\\s*\\[\\s*[\"']([A-Za-z_$][A-Za-z0-9_$]*)[\"']\\s*\\]")
        return pattern.replace(text) { match ->
            "${match.groupValues[1]}.${match.groupValues[2]}"
        }
    }

    // --------------------------------------------------------------- helpers

    private fun quote(value: String): String {
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

    private fun parseNumeric(raw: String): Int? {
        val value = raw.trim()
        return try {
            when {
                value.startsWith("0x") || value.startsWith("0X") ->
                    value.substring(2).toLong(16).toInt()
                value.startsWith("-0x") || value.startsWith("-0X") ->
                    ("-" + value.substring(3)).toLong(16).toInt()
                else -> value.toDoubleOrNull()?.toInt()
            }
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun decodeBase64(value: String): ByteArray {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in value.trimEnd('=')) {
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

    private fun findMatchingBracket(text: String, openIndex: Int): Int? {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
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
        if (index > 0 && !source[index - 1].isWhitespace()) return false
        var j = index - 1
        while (j >= 0 && source[j].isWhitespace()) j--
        if (j < 0) return true
        var k = j
        while (k > 0 && (source[k - 1].isLetterOrDigit() || source[k - 1] == '_' || source[k - 1] == '$')) {
            k--
        }
        return source.substring(k, j + 1) in KEYWORDS_BEFORE_REGEX
    }

    private fun split(source: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val code = StringBuilder()
        var i = 0
        var lastSignificant = '\u0000'

        fun flushCode() {
            if (code.isNotEmpty()) {
                segments += Segment(Kind.CODE, code.toString())
                code.setLength(0)
            }
        }

        while (i < source.length) {
            val c = source[i]

            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                flushCode()
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                segments += Segment(Kind.COMMENT, source.substring(i, end))
                i = end
                continue
            }

            if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                flushCode()
                val end = source.indexOf("*/", i + 2).let {
                    if (it < 0) source.length else it + 2
                }
                segments += Segment(Kind.COMMENT, source.substring(i, end))
                i = end
                continue
            }

            if (c == '"' || c == '\'' || c == '`') {
                flushCode()
                val end = scanStringEnd(source, i, c).coerceAtLeast(i + 1)
                segments += Segment(Kind.STRING, source.substring(i, end))
                lastSignificant = c
                i = end
                continue
            }

            if (c == '/' && regexAllowed(lastSignificant, source, i)) {
                flushCode()
                val end = scanRegexEnd(source, i).coerceAtLeast(i + 1)
                segments += Segment(Kind.STRING, source.substring(i, end))
                lastSignificant = '/'
                i = end
                continue
            }

            code.append(c)
            if (!c.isWhitespace()) lastSignificant = c
            i++
        }
        flushCode()
        return segments
    }
}
