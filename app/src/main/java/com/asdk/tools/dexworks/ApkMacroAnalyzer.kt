package com.asdk.tools.dexworks

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ApkMacroAnalyzer {

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    data class FrameworkResult(
        val primaryFramework: String,
        val confidence: Confidence,
        val description: String,
        val matchedFiles: List<String>,
        val secondaryFrameworks: List<String>,
        val totalFilesScanned: Int
    )

    data class ArchitectureResult(
        val abis: List<String>,
        val nativeLibraries: List<String>,
        val is64BitSupported: Boolean,
        val totalNativeSize: Long,
        val isPureDex: Boolean
    )

    data class ProtectionResult(
        val packerDetected: String?,
        val isObfuscated: Boolean,
        val matchedMarkers: List<String>,
        val details: String
    )

    fun analyzeFramework(apkPath: String): FrameworkResult {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            return FrameworkResult(
                primaryFramework = "Unknown",
                confidence = Confidence.LOW,
                description = "APK file could not be read or does not exist.",
                matchedFiles = emptyList(),
                secondaryFrameworks = emptyList(),
                totalFilesScanned = 0
            )
        }

        val entries = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    entries.add(zipEntries.nextElement().name)
                }
            }
        } catch (e: Exception) {
            return FrameworkResult(
                primaryFramework = "Error",
                confidence = Confidence.LOW,
                description = "Failed to inspect APK archive: ${e.localizedMessage ?: "Unknown error"}",
                matchedFiles = emptyList(),
                secondaryFrameworks = emptyList(),
                totalFilesScanned = 0
            )
        }

        val flutterMatches = entries.filter {
            it.contains("libflutter.so", ignoreCase = true) ||
                    it.contains("libapp.so", ignoreCase = true) ||
                    it.startsWith("assets/flutter_assets/", ignoreCase = true)
        }

        val reactNativeMatches = entries.filter {
            it.contains("libreactnativejni.so", ignoreCase = true) ||
                    it.contains("libhermes.so", ignoreCase = true) ||
                    it.contains("libjsc.so", ignoreCase = true) ||
                    it.contains("index.android.bundle", ignoreCase = true)
        }

        val unityMatches = entries.filter {
            it.contains("libunity.so", ignoreCase = true) ||
                    it.contains("libil2cpp.so", ignoreCase = true) ||
                    it.startsWith("assets/bin/Data/", ignoreCase = true)
        }

        val unrealMatches = entries.filter {
            it.contains("libUE4.so", ignoreCase = true) ||
                    it.contains("libUnreal.so", ignoreCase = true)
        }

        val godotMatches = entries.filter {
            it.contains("libgodot_android.so", ignoreCase = true) ||
                    it.endsWith(".pck", ignoreCase = true)
        }

        val xamarinMatches = entries.filter {
            it.contains("libmonodroid.so", ignoreCase = true) ||
                    it.contains("libmonosgen-2.0.so", ignoreCase = true) ||
                    it.startsWith("assemblies/", ignoreCase = true)
        }

        val cordovaMatches = entries.filter {
            it.contains("cordova.js", ignoreCase = true) ||
                    it.contains("cordova_plugins.js", ignoreCase = true) ||
                    it.startsWith("assets/www/", ignoreCase = true)
        }

        val composeMatches = entries.filter {
            it.startsWith("META-INF/androidx.compose.", ignoreCase = true) ||
                    it.contains("androidx.compose.ui", ignoreCase = true)
        }

        val kotlinMatches = entries.filter {
            it.startsWith("kotlin/", ignoreCase = true) ||
                    it.endsWith(".kotlin_module", ignoreCase = true) ||
                    it.startsWith("META-INF/kotlin-stdlib", ignoreCase = true)
        }

        val detectedSecondary = mutableListOf<String>()

        return when {
            flutterMatches.isNotEmpty() -> {
                if (kotlinMatches.isNotEmpty()) detectedSecondary.add("Kotlin Native Android Embedding")
                if (composeMatches.isNotEmpty()) detectedSecondary.add("Jetpack Compose")
                FrameworkResult(
                    primaryFramework = "Flutter",
                    confidence = Confidence.HIGH,
                    description = "Built with Google Flutter SDK using Dart language and ahead-of-time (AOT) compiled native code.",
                    matchedFiles = flutterMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            reactNativeMatches.isNotEmpty() -> {
                val hasHermes = reactNativeMatches.any { it.contains("hermes", ignoreCase = true) }
                val engine = if (hasHermes) "Hermes JavaScript Engine" else "JavaScriptCore Engine"
                detectedSecondary.add(engine)
                if (kotlinMatches.isNotEmpty()) detectedSecondary.add("Kotlin Native Bridge")
                FrameworkResult(
                    primaryFramework = "React Native",
                    confidence = Confidence.HIGH,
                    description = "Built with Meta React Native framework executing JavaScript/TypeScript via $engine.",
                    matchedFiles = reactNativeMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            unityMatches.isNotEmpty() -> {
                val hasIl2cpp = unityMatches.any { it.contains("il2cpp", ignoreCase = true) }
                val scriptingBackend = if (hasIl2cpp) "IL2CPP (AOT C++)" else "Mono Scripting Backend"
                detectedSecondary.add(scriptingBackend)
                FrameworkResult(
                    primaryFramework = "Unity",
                    confidence = Confidence.HIGH,
                    description = "Built with Unity Game Engine using C# scripts running on $scriptingBackend.",
                    matchedFiles = unityMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            unrealMatches.isNotEmpty() -> {
                FrameworkResult(
                    primaryFramework = "Unreal Engine",
                    confidence = Confidence.HIGH,
                    description = "Built with Epic Games Unreal Engine featuring high-performance native C++ graphics pipeline.",
                    matchedFiles = unrealMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            godotMatches.isNotEmpty() -> {
                FrameworkResult(
                    primaryFramework = "Godot Engine",
                    confidence = Confidence.HIGH,
                    description = "Built with Godot Game Engine running GDScript / C# game binaries.",
                    matchedFiles = godotMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            xamarinMatches.isNotEmpty() -> {
                FrameworkResult(
                    primaryFramework = "Xamarin / .NET MAUI",
                    confidence = Confidence.HIGH,
                    description = "Built with Microsoft .NET MAUI / Xamarin platform running on Mono CLR runtime.",
                    matchedFiles = xamarinMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            cordovaMatches.isNotEmpty() -> {
                FrameworkResult(
                    primaryFramework = "Cordova / Capacitor",
                    confidence = Confidence.HIGH,
                    description = "Built with hybrid web technologies (HTML/CSS/JavaScript) packaged in an embedded WebView.",
                    matchedFiles = cordovaMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            composeMatches.isNotEmpty() -> {
                if (kotlinMatches.isNotEmpty()) detectedSecondary.add("Kotlin Standard Library")
                FrameworkResult(
                    primaryFramework = "Jetpack Compose",
                    confidence = Confidence.HIGH,
                    description = "Native Android application built with Android's modern declarative UI toolkit (Jetpack Compose).",
                    matchedFiles = composeMatches,
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            kotlinMatches.isNotEmpty() -> {
                FrameworkResult(
                    primaryFramework = "Native Android (Kotlin)",
                    confidence = Confidence.HIGH,
                    description = "Native Android application written in Kotlin utilizing standard Android Views and SDK.",
                    matchedFiles = kotlinMatches.take(15),
                    secondaryFrameworks = detectedSecondary,
                    totalFilesScanned = entries.size
                )
            }
            else -> {
                val hasDex = entries.any { it.endsWith(".dex", ignoreCase = true) }
                val name = if (hasDex) "Native Android (Java/Kotlin)" else "Generic Android Package"
                FrameworkResult(
                    primaryFramework = name,
                    confidence = Confidence.MEDIUM,
                    description = "Standard Android application package with Dalvik/ART bytecode without third-party cross-platform frameworks.",
                    matchedFiles = entries.filter { it.endsWith(".dex", ignoreCase = true) },
                    secondaryFrameworks = emptyList(),
                    totalFilesScanned = entries.size
                )
            }
        }
    }

    fun analyzeArchitecture(apkPath: String): ArchitectureResult {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            return ArchitectureResult(emptyList(), emptyList(), false, 0L, true)
        }

        val abis = mutableSetOf<String>()
        val libraries = mutableSetOf<String>()
        var totalSize = 0L

        try {
            ZipFile(file).use { zip ->
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry: ZipEntry = zipEntries.nextElement()
                    val name = entry.name
                    if (name.startsWith("lib/") && name.endsWith(".so", ignoreCase = true)) {
                        val parts = name.split('/')
                        if (parts.size >= 3) {
                            abis.add(parts[1])
                            libraries.add(parts.last())
                            totalSize += entry.size
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val has64Bit = abis.contains("arm64-v8a") || abis.contains("x86_64")
        return ArchitectureResult(
            abis = abis.toList().sorted(),
            nativeLibraries = libraries.toList().sorted(),
            is64BitSupported = has64Bit,
            totalNativeSize = totalSize,
            isPureDex = abis.isEmpty()
        )
    }

    fun analyzeProtection(apkPath: String): ProtectionResult {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            return ProtectionResult(null, false, emptyList(), "Could not read APK")
        }

        val matchedMarkers = mutableListOf<String>()
        var packerName: String? = null

        try {
            ZipFile(file).use { zip ->
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val name = zipEntries.nextElement().name.lowercase()
                    when {
                        name.contains("libsecmain.so") || name.contains("libsecexe.so") -> {
                            packerName = "Bangcle / SecNeo"
                            matchedMarkers.add(name)
                        }
                        name.contains("libtup.so") || name.contains("libshex.so") -> {
                            packerName = "Tencent Legu"
                            matchedMarkers.add(name)
                        }
                        name.contains("libjiagu.so") || name.contains("libprotectclass.so") -> {
                            packerName = "Qihoo 360 Jiagu"
                            matchedMarkers.add(name)
                        }
                        name.contains("libexec.so") || name.contains("libexecmain.so") -> {
                            packerName = "Ijiami"
                            matchedMarkers.add(name)
                        }
                        name.contains("libbaiduprotect.so") -> {
                            packerName = "Baidu Protect"
                            matchedMarkers.add(name)
                        }
                        name.contains("dexguard") -> {
                            packerName = "DexGuard"
                            matchedMarkers.add(name)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val isObfuscated = packerName != null || matchedMarkers.isNotEmpty()
        val details = when {
            packerName != null -> "Third-party application hardening / DEX packer detected ($packerName)."
            else -> "No known third-party reinforcement packers detected in APK library binaries."
        }

        return ProtectionResult(
            packerDetected = packerName,
            isObfuscated = isObfuscated,
            matchedMarkers = matchedMarkers,
            details = details
        )
    }
}
