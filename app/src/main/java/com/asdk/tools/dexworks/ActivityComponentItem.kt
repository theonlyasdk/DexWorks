package com.asdk.tools.dexworks

data class ActivityComponentItem(
    val name: String,
    val simpleName: String,
    val isExported: Boolean,
    val isAlias: Boolean = false,
    val targetActivity: String? = null,
    val permission: String? = null,
    val launchMode: String? = null,
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList(),
    val isLauncher: Boolean = false
)
