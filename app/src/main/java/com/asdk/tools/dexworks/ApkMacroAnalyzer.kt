package com.asdk.tools.dexworks

import android.content.Context
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

    /**
     * One analysis module's worth of output.
     *
     * Every module answers the same four questions, so they share a single shape
     * and the result screen renders them all through one function instead of
     * growing a near-identical renderer per module.
     */
    data class MacroResult(
        val headline: String,
        val description: String,
        val confidenceText: String,
        val chips: List<String>,
        val evidence: List<String>
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

    private fun entriesOf(apkPath: String): List<Pair<String, Long>> {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) return emptyList()
        return try {
            ZipFile(file).use { zip ->
                val out = mutableListOf<Pair<String, Long>>()
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry: ZipEntry = zipEntries.nextElement()
                    out.add(entry.name to entry.size)
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun archiveInfo(context: Context, apkPath: String) =
        AppInfoUtils.getPackageArchiveInfo(context, apkPath)

    fun analyzeComponents(context: Context, apkPath: String): MacroResult {
        val info = archiveInfo(context, apkPath)
            ?: return unreadableMacro("Components")
        val activities = info.activities.orEmpty()
        val services = info.services.orEmpty()
        val receivers = info.receivers.orEmpty()
        val providers = info.providers.orEmpty()
        val total = activities.size + services.size + receivers.size + providers.size
        // Counted per array: concatenating them widens the element type to
        // ComponentInfo, which does not declare `exported`.
        val exported = activities.count { it.exported } +
            services.count { it.exported } +
            receivers.count { it.exported }

        return MacroResult(
            headline = "$total components",
            description = "Declared components: ${activities.size} activities, ${services.size} services, " +
                "${receivers.size} receivers, ${providers.size} providers. " +
                "$exported of them are exported and can be reached by other apps.",
            confidenceText = if (total == 0) "No Components" else "Manifest Parsed",
            chips = listOf(
                "Activities: ${activities.size}",
                "Services: ${services.size}",
                "Receivers: ${receivers.size}",
                "Providers: ${providers.size}",
                "Exported: $exported"
            ),
            evidence = (activities.take(40) + services.take(20) + receivers.take(20) + providers.take(20))
                .map { it.name }
        )
    }

    fun analyzePermissions(context: Context, apkPath: String): MacroResult {
        val info = archiveInfo(context, apkPath)
            ?: return unreadableMacro("Permissions")
        val permissions = info.requestedPermissions.orEmpty().sorted()
        val dangerous = permissions.filter { it.substringAfterLast('.') in DANGEROUS_PERMISSION_SUFFIXES }

        return MacroResult(
            headline = "${permissions.size} permissions",
            description = "The manifest requests ${permissions.size} permissions, " +
                "${dangerous.size} of them at the dangerous protection level. " +
                "Every declared permission is listed in the evidence below.",
            confidenceText = if (permissions.isEmpty()) "None Requested" else "${dangerous.size} Dangerous",
            chips = if (dangerous.isEmpty()) {
                listOf("All normal level")
            } else {
                dangerous.map { it.substringAfterLast('.') }
            },
            evidence = permissions
        )
    }

    fun analyzeSigning(context: Context, apkPath: String): MacroResult {
        val entries = entriesOf(apkPath)
        if (entries.isEmpty()) return unreadableMacro("Signing")

        val metaInf = entries.filter { it.first.startsWith("META-INF/", ignoreCase = true) }
        val signatureFiles = metaInf.filter {
            val upper = it.first.uppercase()
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.endsWith(".MF")
        }
        val v1 = signatureFiles.any { it.first.uppercase().endsWith(".SF") }
        val v2OrNewer = metaInf.any { it.first.equals("META-INF/MANIFEST.MF", true) }
        val info = archiveInfo(context, apkPath)
        val debuggable = info?.applicationInfo?.flags?.and(
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        ) != null
        // ApplicationInfo.FLAG_TESTONLY is not exposed to the SDK, so the value
        // is spelled out here.
        val testOnly = info?.applicationInfo?.flags?.and(FLAG_TESTONLY) != null

        return MacroResult(
            headline = if (debuggable) "Debug Build" else "Release Build",
            description = "Signature material found: ${signatureFiles.size} META-INF entries. " +
                "v1 (JAR) signing: ${if (v1) "present" else "absent"}. " +
                "v2+ signing block: ${if (v2OrNewer) "declared" else "not declared"}. " +
                "Debuggable: ${if (debuggable) "yes" else "no"}. " +
                "Test only: ${if (testOnly) "yes" else "no"}.",
            confidenceText = when {
                debuggable -> "Debuggable"
                testOnly -> "Test Only"
                else -> "Standard Signing"
            },
            chips = buildList {
                add("v1: ${if (v1) "yes" else "no"}")
                add("v2+: ${if (v2OrNewer) "yes" else "no"}")
                add("Debuggable: ${if (debuggable) "yes" else "no"}")
                add("Test only: ${if (testOnly) "yes" else "no"}")
            },
            evidence = signatureFiles.map { it.first }
        )
    }

    fun analyzeResources(apkPath: String): MacroResult {
        val entries = entriesOf(apkPath)
        if (entries.isEmpty()) return unreadableMacro("Resources")

        val arsc = entries.firstOrNull { it.first == "resources.arsc" }?.second ?: 0L
        val res = entries.filter { it.first.startsWith("res/") }
        val assets = entries.filter { it.first.startsWith("assets/") }
        val layouts = res.count { it.first.startsWith("res/layout") }
        val drawables = res.count { it.first.startsWith("res/drawable") }
        val xmls = res.count { it.first.startsWith("res/xml") }
        val dex = entries.filter { it.first.endsWith(".dex", ignoreCase = true) }
        val largest = entries.sortedByDescending { it.second }.take(10)

        return MacroResult(
            headline = "${humanSize(arsc)} resource table",
            description = "resources.arsc holds the compiled resource table. " +
                "${res.size} compiled resources, ${assets.size} raw assets, " +
                "${dex.size} DEX files, ${humanSize(dex.sumOf { it.second })} of bytecode.",
            confidenceText = "Archive Scanned",
            chips = listOf(
                "res/: ${res.size}",
                "assets/: ${assets.size}",
                "Layouts: $layouts",
                "Drawables: $drawables",
                "XML: $xmls",
                "DEX: ${dex.size}"
            ),
            evidence = largest.map { it.first }
        )
    }

    fun analyzeCompatibility(context: Context, apkPath: String): MacroResult {
        val info = archiveInfo(context, apkPath)
            ?: return unreadableMacro("Compatibility")
        val appInfo = info.applicationInfo
        val minSdk = appInfo?.let { AppInfoUtils.getMinSdkVersion(it) } ?: 0
        val targetSdk = appInfo?.targetSdkVersion ?: 0
        val features = info.reqFeatures.orEmpty()
        val densities = densityBuckets(apkPath)

        return MacroResult(
            headline = "API $minSdk – $targetSdk",
            description = "The app supports API $minSdk and up, and targets API $targetSdk. " +
                "${features.size} hardware or software features are declared as required. " +
                "Density buckets present in res/: ${densities.joinToString(", ")}.",
            confidenceText = if (minSdk >= 23) "Modern Baseline" else "Legacy Baseline",
            chips = buildList {
                add("minSdk: $minSdk")
                add("targetSdk: $targetSdk")
                add("Required features: ${features.size}")
            },
            evidence = features.map { it.name }
        )
    }

    private fun densityBuckets(apkPath: String): List<String> {
        val buckets = linkedSetOf<String>()
        entriesOf(apkPath).forEach { (path, _) ->
            val density = DENSITY_BUCKETS.firstOrNull { path.startsWith("res/$it") }
            if (density != null) buckets.add(density)
        }
        return buckets.toList()
    }

    private fun unreadableMacro(subject: String) = MacroResult(
        headline = "Unavailable",
        description = "The APK file could not be read, so $subject could not be analysed.",
        confidenceText = "No Data",
        chips = emptyList(),
        evidence = emptyList()
    )

    private fun humanSize(bytes: Long): String = AppInfoUtils.formatFileSize(bytes)

    private const val FLAG_TESTONLY = 0x02000000

    private val DENSITY_BUCKETS = listOf(
        "drawable-ldpi", "drawable-mdpi", "drawable-hdpi", "drawable-xhdpi",
        "drawable-xxhdpi", "drawable-xxxhdpi"
    )

    private val DANGEROUS_PERMISSION_SUFFIXES = setOf(
        "ACCEPT_HANDOVER", "ACCESS_BACKGROUND_LOCATION", "ACCESS_COARSE_LOCATION",
        "ACCESS_FINE_LOCATION", "ACCESS_MEDIA_LOCATION", "ACTIVITY_RECOGNITION",
        "ADD_VOICEMAIL", "ANSWER_PHONE_CALLS", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
        "BLUETOOTH_SCAN", "BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "CALL_PHONE",
        "CAMERA", "GET_ACCOUNTS", "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS",
        "PROCESS_OUTGOING_CALLS", "READ_CALENDAR", "READ_CALL_LOG", "READ_CONTACTS",
        "READ_EXTERNAL_STORAGE", "READ_MEDIA_AUDIO", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "READ_PHONE_NUMBERS", "READ_PHONE_STATE", "READ_SMS",
        "RECEIVE_MMS", "RECEIVE_SMS", "RECEIVE_WAP_PUSH", "RECORD_AUDIO",
        "SEND_SMS", "USE_BIOMETRIC", "USE_SIP", "UWB_RANGING", "WRITE_CALENDAR",
        "WRITE_CALL_LOG", "WRITE_CONTACTS", "WRITE_EXTERNAL_STORAGE"
    )
}
