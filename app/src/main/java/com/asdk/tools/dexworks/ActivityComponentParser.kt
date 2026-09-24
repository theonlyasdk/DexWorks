package com.asdk.tools.dexworks

import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser
import java.io.File

object ActivityComponentParser {

    fun parseActivities(apkPath: String): List<ActivityComponentItem> {
        val file = File(apkPath)
        if (!file.exists()) return emptyList()

        val activities = mutableListOf<ActivityComponentItem>()
        var parser: XmlResourceParser? = null

        try {
            val assetManager = AssetManager::class.java.getConstructor().newInstance()
            val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPathMethod.invoke(assetManager, apkPath) as Int
            if (cookie == 0) return emptyList()

            parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")
            var eventType = parser.eventType

            var currentPackage = ""
            var currentActivityName: String? = null
            var currentSimpleName = ""
            var isExported = false
            var isAlias = false
            var targetActivity: String? = null
            var permission: String? = null
            var launchMode: String? = null
            var currentActions = mutableListOf<String>()
            var currentCategories = mutableListOf<String>()
            var currentSchemes = mutableListOf<String>()
            var inActivity = false
            var inIntentFilter = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        if (tagName == "manifest") {
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i) == "package") {
                                    currentPackage = parser.getAttributeValue(i) ?: ""
                                }
                            }
                        } else if (tagName == "activity" || tagName == "activity-alias") {
                            inActivity = true
                            isAlias = tagName == "activity-alias"
                            currentActivityName = null
                            isExported = false
                            targetActivity = null
                            permission = null
                            launchMode = null
                            currentActions = mutableListOf()
                            currentCategories = mutableListOf()
                            currentSchemes = mutableListOf()

                            for (i in 0 until parser.attributeCount) {
                                val attrName = parser.getAttributeName(i)
                                val attrVal = parser.getAttributeValue(i) ?: ""
                                when (attrName) {
                                    "name" -> currentActivityName = attrVal
                                    "exported" -> isExported = attrVal.equals("true", ignoreCase = true) || attrVal == "1"
                                    "targetActivity" -> targetActivity = attrVal
                                    "permission" -> permission = attrVal
                                    "launchMode" -> launchMode = parseLaunchMode(attrVal)
                                }
                            }

                            if (currentActivityName != null) {
                                if (currentActivityName.startsWith(".")) {
                                    currentActivityName = "$currentPackage$currentActivityName"
                                } else if (!currentActivityName.contains(".")) {
                                    currentActivityName = "$currentPackage.$currentActivityName"
                                }
                                currentSimpleName = currentActivityName.substringAfterLast('.')
                            }
                        } else if (inActivity && tagName == "intent-filter") {
                            inIntentFilter = true
                        } else if (inActivity && inIntentFilter) {
                            when (tagName) {
                                "action" -> {
                                    for (i in 0 until parser.attributeCount) {
                                        if (parser.getAttributeName(i) == "name") {
                                            parser.getAttributeValue(i)?.let { currentActions.add(it) }
                                        }
                                    }
                                }
                                "category" -> {
                                    for (i in 0 until parser.attributeCount) {
                                        if (parser.getAttributeName(i) == "name") {
                                            parser.getAttributeValue(i)?.let { currentCategories.add(it) }
                                        }
                                    }
                                }
                                "data" -> {
                                    for (i in 0 until parser.attributeCount) {
                                        if (parser.getAttributeName(i) == "scheme") {
                                            parser.getAttributeValue(i)?.let { currentSchemes.add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name
                        if (tagName == "intent-filter") {
                            inIntentFilter = false
                        } else if (tagName == "activity" || tagName == "activity-alias") {
                            if (currentActivityName != null) {
                                val isLauncher = currentCategories.contains("android.intent.category.LAUNCHER")

                                activities.add(
                                    ActivityComponentItem(
                                        name = currentActivityName,
                                        simpleName = currentSimpleName,
                                        isExported = isExported,
                                        isAlias = isAlias,
                                        targetActivity = targetActivity,
                                        permission = permission,
                                        launchMode = launchMode,
                                        actions = currentActions.toList(),
                                        categories = currentCategories.toList(),
                                        dataSchemes = currentSchemes.toList(),
                                        isLauncher = isLauncher
                                    )
                                )
                            }
                            inActivity = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // fallback
        } finally {
            try {
                parser?.close()
            } catch (e: Exception) {}
        }

        return activities
    }

    private fun parseLaunchMode(mode: String): String {
        return when (mode) {
            "0", "standard" -> "standard"
            "1", "singleTop" -> "singleTop"
            "2", "singleTask" -> "singleTask"
            "3", "singleInstance" -> "singleInstance"
            "4", "singleInstancePerTask" -> "singleInstancePerTask"
            else -> mode
        }
    }
}
