package com.asdk.tools.dexworks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val isSystemApp: Boolean,
    val sourceDir: String,
    val icon: Drawable? = null
)

object AppInfoUtils {

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return "N/A"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    fun getMinSdkVersion(appInfo: ApplicationInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appInfo.minSdkVersion
        } else {
            21
        }
    }

    data class SdkVersionInfo(
        val apiLevel: Int,
        val version: String,
        val name: String,
        val codename: String
    )

    private val sdkVersionTable = mapOf(
        21 to SdkVersionInfo(21, "5.0", "Lollipop", "Lollipop"),
        22 to SdkVersionInfo(22, "5.1", "Lollipop", "Lollipop"),
        23 to SdkVersionInfo(23, "6.0", "Marshmallow", "Marshmallow"),
        24 to SdkVersionInfo(24, "7.0", "Nougat", "Nougat"),
        25 to SdkVersionInfo(25, "7.1", "Nougat", "Nougat"),
        26 to SdkVersionInfo(26, "8.0", "Oreo", "Oreo"),
        27 to SdkVersionInfo(27, "8.1", "Oreo", "Oreo"),
        28 to SdkVersionInfo(28, "9", "Pie", "Pie"),
        29 to SdkVersionInfo(29, "10", "Android 10", "Quince Tart"),
        30 to SdkVersionInfo(30, "11", "Android 11", "Red Velvet Cake"),
        31 to SdkVersionInfo(31, "12", "Android 12", "Snow Cone"),
        32 to SdkVersionInfo(32, "12L", "Android 12L", "Snow Cone"),
        33 to SdkVersionInfo(33, "13", "Android 13", "Tiramisu"),
        34 to SdkVersionInfo(34, "14", "Android 14", "Upside Down Cake"),
        35 to SdkVersionInfo(35, "15", "Android 15", "Vanilla Ice Cream"),
        36 to SdkVersionInfo(36, "16", "Android 16", "Baklava")
    )

    fun getSdkVersionInfo(apiLevel: Int): SdkVersionInfo {
        return sdkVersionTable[apiLevel]
            ?: SdkVersionInfo(apiLevel, "API $apiLevel", "Unknown", "Unknown")
    }

    fun getInstalledApps(
        context: Context,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): List<AppItem> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        val total = packages.size
        val appList = ArrayList<AppItem>(total)
        var count = 0

        for (pkg in packages) {
            count++
            val appInfo = pkg.applicationInfo
            if (appInfo != null) {
                val name = appInfo.loadLabel(pm).toString().ifBlank { pkg.packageName }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val file = File(appInfo.sourceDir)
                val size = if (file.exists()) file.length() else 0L
                val icon = try {
                    appInfo.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }

                appList.add(
                    AppItem(
                        name = name,
                        packageName = pkg.packageName,
                        versionName = pkg.versionName ?: "N/A",
                        versionCode = getVersionCode(pkg),
                        sizeBytes = size,
                        isSystemApp = isSystem,
                        sourceDir = appInfo.sourceDir,
                        icon = icon
                    )
                )
            }
            onProgress?.invoke(count, total)
        }

        // Sort alphabetically by name
        appList.sortBy { it.name.lowercase(Locale.getDefault()) }
        return appList
    }

    fun getPackageInfo(context: Context, packageName: String): PackageInfo? {
        val pm = context.packageManager
        val flags = (PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES).toLong()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags.toInt())
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getPackageArchiveInfo(context: Context, apkPath: String): PackageInfo? {
        val pm = context.packageManager
        val flags = (PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES).toLong()

        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, flags.toInt())
            }
        } catch (e: Exception) {
            null
        } ?: return null

        // Essential: set source paths so icons and labels can be loaded from the archive
        info.applicationInfo?.sourceDir = apkPath
        info.applicationInfo?.publicSourceDir = apkPath
        return info
    }

    fun getSha256Fingerprint(packageInfo: PackageInfo): String {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val cert = signatures[0].toByteArray()
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(cert)
                digest.joinToString(":") { String.format("%02X", it) }
            } else {
                "Not available"
            }
        } catch (e: Exception) {
            "Unable to calculate (${e.localizedMessage ?: "error"})"
        }
    }

    fun getInstallationSource(context: Context, packageName: String, isSystemApp: Boolean): String {
        val pm = context.packageManager
        val installerPackageName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = pm.getInstallSourceInfo(packageName)
                sourceInfo.installingPackageName ?: sourceInfo.initiatingPackageName ?: sourceInfo.originatingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) {
            null
        }

        return when (installerPackageName) {
            "com.android.vending" -> "Google Play Store"
            "com.amazon.venezia" -> "Amazon Appstore"
            "com.sec.android.app.samsungapps" -> "Samsung Galaxy Store"
            "com.huawei.appmarket" -> "Huawei AppGallery"
            "com.xiaomi.mipicks" -> "Xiaomi GetApps"
            "org.fdroid.fdroid", "org.fdroid.fdroid.privileged" -> "F-Droid"
            "com.aurora.store" -> "Aurora Store"
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> "Package Installer"
            "com.google.android.documentsui", "com.android.documentsui" -> "File Manager"
            "com.android.shell", "adb" -> "ADB / Sideload"
            null, "" -> {
                if (isSystemApp) "Pre-installed (System)" else "Unknown / Sideloaded"
            }
            else -> {
                try {
                    val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(installerPackageName, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(installerPackageName, 0)
                    }
                    val label = pm.getApplicationLabel(appInfo).toString()
                    if (label.isNotBlank()) label else installerPackageName
                } catch (e: Exception) {
                    installerPackageName
                }
            }
        }
    }
}

