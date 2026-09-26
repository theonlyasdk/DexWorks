package com.asdk.tools.dexworks

/**
 * Shared compiled patterns.
 *
 * These were being rebuilt as literal `Regex(...)` values inside functions that
 * run per item and per save/share tap, and the same filename-sanitising pattern
 * had been duplicated across eight files.
 */
object SanitizedNames {
    val UNSAFE_CHARS = Regex("[^a-zA-Z0-9._-]")

    fun sanitize(value: String): String = value.replace(UNSAFE_CHARS, "_")
}
