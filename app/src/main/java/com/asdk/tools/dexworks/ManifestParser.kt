package com.asdk.tools.dexworks

import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser
import java.io.File

object ManifestParser {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APP_NS = "http://schemas.android.com/apk/res-auto"

    fun decodeManifest(apkPath: String): Result<String> {
        val file = File(apkPath)
        if (!file.exists()) {
            return Result.failure(IllegalArgumentException("APK file does not exist: $apkPath"))
        }

        var parser: XmlResourceParser? = null
        return try {
            val assetManager = AssetManager::class.java.getConstructor().newInstance()
            val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPathMethod.invoke(assetManager, apkPath) as Int
            if (cookie == 0) {
                return Result.failure(IllegalStateException("Could not add asset path for APK"))
            }

            parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")
            val xml = formatXml(parser)
            Result.success(xml)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                parser?.close()
            } catch (e: Exception) {
                // Ignore close error
            }
        }
    }

    private fun formatXml(parser: XmlPullParser): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var indentLevel = 0
        var eventType = parser.eventType
        var isRoot = true

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val indent = "    ".repeat(indentLevel)
                    val tagName = parser.name ?: "tag"

                    sb.append(indent).append("<").append(tagName)

                    if (isRoot) {
                        sb.append("\n").append(indent).append("    ")
                            .append("xmlns:android=\"http://schemas.android.com/apk/res/android\"")
                        isRoot = false
                    }

                    val attributeCount = parser.attributeCount
                    if (attributeCount > 0) {
                        for (i in 0 until attributeCount) {
                            val fullName = getAttributeFullName(parser, i)
                            val attrValue = getAttributeValueSafe(parser, i)

                            sb.append("\n").append(indent).append("    ")
                                .append(fullName).append("=\"").append(escapeXml(attrValue)).append("\"")
                        }
                    }

                    sb.append(">\n")
                    indentLevel++
                }

                XmlPullParser.END_TAG -> {
                    indentLevel = (indentLevel - 1).coerceAtLeast(0)
                    val indent = "    ".repeat(indentLevel)
                    val tagName = parser.name ?: "tag"
                    sb.append(indent).append("</").append(tagName).append(">\n")
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        val indent = "    ".repeat(indentLevel)
                        sb.append(indent).append(escapeXml(text)).append("\n")
                    }
                }
            }
            eventType = parser.next()
        }

        return sb.toString()
    }

    private fun getAttributeFullName(parser: XmlPullParser, index: Int): String {
        val attrName = parser.getAttributeName(index) ?: return "attr_$index"
        if (attrName.contains(":")) {
            return attrName
        }

        val namespace = try {
            parser.getAttributeNamespace(index)
        } catch (e: Exception) {
            null
        }

        return when (namespace) {
            ANDROID_NS -> "android:$attrName"
            APP_NS -> "app:$attrName"
            else -> if (!namespace.isNullOrEmpty() && namespace.startsWith("http://schemas.android.com/apk/res/")) {
                "app:$attrName"
            } else {
                attrName
            }
        }
    }

    private fun getAttributeValueSafe(parser: XmlPullParser, index: Int): String {
        val value = try {
            parser.getAttributeValue(index)
        } catch (e: Exception) {
            null
        }
        if (value != null) return value

        if (parser is XmlResourceParser) {
            try {
                val resId = parser.getAttributeResourceValue(index, 0)
                if (resId != 0) {
                    return "@0x${Integer.toHexString(resId)}"
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return ""
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
