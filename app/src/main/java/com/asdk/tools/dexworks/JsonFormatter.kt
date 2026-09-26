package com.asdk.tools.dexworks

/**
 * Order-preserving JSON pretty printer.
 *
 * Round-tripping through org.json would be simpler but it rebuilds the document from
 * a HashMap, which silently reorders keys and drops duplicate ones. This only changes
 * whitespace, so the formatted output still describes the same data.
 */
object JsonFormatter {

    private const val INDENT = "  "

    fun prettify(source: String): String {
        val out = StringBuilder(source.length + source.length / 4)
        var depth = 0
        var i = 0
        val len = source.length

        fun newline() {
            out.append('\n')
            repeat(depth.coerceAtLeast(0)) { out.append(INDENT) }
        }

        while (i < len) {
            val c = source[i]
            when {
                c == '"' -> {
                    // Copy the string literal verbatim, honouring backslash escapes
                    // so a brace or comma inside a string is never treated as syntax.
                    val start = i
                    i++
                    while (i < len) {
                        if (source[i] == '\\') {
                            i += 2
                            continue
                        }
                        if (source[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                    if (i > len) i = len
                    out.append(source, start, i)
                }

                c == '{' || c == '[' -> {
                    val next = nextMeaningful(source, i + 1)
                    if (next < 0) {
                        // Nothing inside, so keep it on one line.
                        out.append(c)
                        i++
                    } else {
                        out.append(c)
                        depth++
                        newline()
                        i++
                    }
                }

                c == '}' || c == ']' -> {
                    if (out.isNotEmpty() && out.last() == '\n') {
                        out.setLength(out.length - 1)
                        repeat(depth.coerceAtLeast(0)) { out.append(INDENT) }
                    }
                    out.append(c)
                    depth--
                    i++
                }

                c == ',' -> {
                    out.append(',')
                    newline()
                    i++
                }

                c == ':' -> {
                    out.append(": ")
                    i++
                }

                c.isWhitespace() -> i++

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Index of the next non-whitespace character after [from], or -1. */
    private fun nextMeaningful(source: String, from: Int): Int {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i++
        val c = source.getOrNull(i) ?: return -1
        // An immediately closing brace means the object or array is empty.
        return if (c == '}' || c == ']') -1 else i
    }
}
