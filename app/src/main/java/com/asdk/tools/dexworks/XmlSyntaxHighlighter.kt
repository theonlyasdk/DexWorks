package com.asdk.tools.dexworks

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

object XmlSyntaxHighlighter {

    fun highlight(context: Context, xml: String): Spannable {
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return highlightString(xml, isDarkMode)
    }

    fun highlightLines(lines: List<String>, isDarkMode: Boolean): List<CharSequence> {
        return lines.map { highlightString(it, isDarkMode) }
    }

    fun highlightString(xml: String, isDarkMode: Boolean): Spannable {
        val colorTag = if (isDarkMode) Color.parseColor("#E06C75") else Color.parseColor("#C7254E")
        val colorAttr = if (isDarkMode) Color.parseColor("#E5C07B") else Color.parseColor("#A85D00")
        val colorValue = if (isDarkMode) Color.parseColor("#98C379") else Color.parseColor("#2E7D32")
        val colorComment = if (isDarkMode) Color.parseColor("#7F848E") else Color.parseColor("#757575")
        val colorDecl = if (isDarkMode) Color.parseColor("#56B6C2") else Color.parseColor("#00838F")

        val spannable = SpannableString(xml)
        val len = xml.length
        var i = 0

        while (i < len) {
            val c = xml[i]

            // 1. Comments
            if (c == '<' && i + 4 <= len && xml.startsWith("<!--", i)) {
                val start = i
                val endIdx = xml.indexOf("-->", i + 4)
                val end = if (endIdx == -1) len else endIdx + 3
                spannable.setSpan(ForegroundColorSpan(colorComment), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end
                continue
            }

            // 2. XML Declaration
            if (c == '<' && i + 2 <= len && xml.startsWith("<?", i)) {
                val start = i
                val endIdx = xml.indexOf("?>", i + 2)
                val end = if (endIdx == -1) len else endIdx + 2
                spannable.setSpan(ForegroundColorSpan(colorDecl), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end
                continue
            }

            // 3. Tags (<tag, </tag>)
            if (c == '<') {
                var tagStart = i + 1
                if (tagStart < len && xml[tagStart] == '/') {
                    tagStart++
                }

                var tagEnd = tagStart
                while (tagEnd < len && !xml[tagEnd].isWhitespace() && xml[tagEnd] != '>' && xml[tagEnd] != '/') {
                    tagEnd++
                }
                if (tagEnd > tagStart) {
                    spannable.setSpan(ForegroundColorSpan(colorTag), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i = tagEnd
                continue
            }

            // 4. Attribute String Values ("value" or 'value')
            if (c == '"' || c == '\'') {
                val quote = c
                val valStart = i
                var valEnd = i + 1
                while (valEnd < len && xml[valEnd] != quote) {
                    valEnd++
                }
                if (valEnd < len) valEnd++
                spannable.setSpan(ForegroundColorSpan(colorValue), valStart, valEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = valEnd
                continue
            }

            // 5. Attribute Names (e.g. android:name= or package=)
            if (c.isLetter() || c == '_' || c == ':') {
                val attrStart = i
                var attrEnd = i
                while (attrEnd < len && (xml[attrEnd].isLetterOrDigit() || xml[attrEnd] == '_' || xml[attrEnd] == '-' || xml[attrEnd] == ':')) {
                    attrEnd++
                }

                var lookAhead = attrEnd
                while (lookAhead < len && xml[lookAhead].isWhitespace()) {
                    lookAhead++
                }
                if (lookAhead < len && xml[lookAhead] == '=') {
                    spannable.setSpan(ForegroundColorSpan(colorAttr), attrStart, attrEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = attrEnd
                    continue
                } else {
                    i = attrEnd
                    continue
                }
            }

            i++
        }

        return spannable
    }

    fun generateLineNumbers(lineCount: Int): String {
        val sb = StringBuilder(lineCount * 4)
        for (i in 1..lineCount) {
            sb.append(i).append('\n')
        }
        return sb.toString()
    }
}
