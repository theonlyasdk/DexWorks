package com.asdk.tools.dexworks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

    private const val TAG = "AppLoad"

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
        // SimpleDateFormat is not thread safe and is expensive to build, and this
        // is called from bind paths, so keep one per thread instead of per call.
        val sdf = dateFormat.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .also { dateFormat.set(it) }
        return sdf.format(Date(timestamp))
    }

    private val dateFormat = ThreadLocal<SimpleDateFormat>()

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
        knownNames: Map<String, String> = emptyMap(),
        progressStride: Int = 8,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): List<AppItem> {
        val pm = context.packageManager
        val tQueryStart = SystemClock.elapsedRealtime()
        // GET_META_DATA forces a meta-data Bundle to be parsed for every single
        // package. Nothing here reads it, so it is pure cost on a device with
        // hundreds of apps.
        val flags = 0
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
        val tQueryDone = SystemClock.elapsedRealtime()

        val total = packages.size
        val slots = arrayOfNulls<AppItem>(total)
        val labelMs = java.util.concurrent.atomic.AtomicLong(0)
        val sizeMs = java.util.concurrent.atomic.AtomicLong(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val lastPublished = java.util.concurrent.atomic.AtomicInteger(0)

        // Resolving a label makes the framework load that app's resources, which
        // measured at 1727ms of a 2116ms load for 481 packages, and it is by far
        // the dominant cost. Two things fix that: skip it for packages whose name we
        // already know, and resolve the remainder across several cores.
        val workers = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
        val chunk = maxOf(1, (total + workers - 1) / workers)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            var start = 0
            while (start < total) {
                val from = start
                val to = minOf(total, start + chunk)
                pool.execute {
                    for (index in from until to) {
                        val pkg = packages[index]
                        val appInfo = pkg.applicationInfo
                        if (appInfo != null) {
                            val cachedName = knownNames[pkg.packageName]
                            val name: String
                            if (cachedName != null) {
                                name = cachedName
                            } else {
                                val labelStart = SystemClock.elapsedRealtime()
                                name = appInfo.loadLabel(pm).toString().ifBlank { pkg.packageName }
                                labelMs.addAndGet(SystemClock.elapsedRealtime() - labelStart)
                            }

                            val sizeStart = SystemClock.elapsedRealtime()
                            val size = File(appInfo.sourceDir).length()
                            sizeMs.addAndGet(SystemClock.elapsedRealtime() - sizeStart)

                            slots[index] = AppItem(
                                name = name,
                                packageName = pkg.packageName,
                                versionName = pkg.versionName ?: "N/A",
                                versionCode = getVersionCode(pkg),
                                sizeBytes = size,
                                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                sourceDir = appInfo.sourceDir,
                                // Icons are loaded lazily by AppIconLoader; decoding one
                                // for every app made this list take seconds to appear.
                                icon = null
                            )
                        }
                        // The throttle lives here rather than in the caller: this
                        // lambda runs on four worker threads, so a caller-side
                        // counter would be mutated concurrently and would still be
                        // entered once per package.
                        val completed = done.incrementAndGet()
                        val published = lastPublished.get()
                        if (completed - published >= progressStride || completed >= total) {
                            lastPublished.set(completed)
                            onProgress?.invoke(completed, total)
                        }
                    }
                }
                start = to
            }
            pool.shutdown()
            if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
            throw e
        } finally {
            pool.shutdown()
        }

        val appList = ArrayList<AppItem>(total)
        for (slot in slots) {
            if (slot != null) appList.add(slot)
        }
        val tFieldsDone = SystemClock.elapsedRealtime()

        // Sort alphabetically by name
        appList.sortBy { it.name.lowercase(Locale.getDefault()) }
        val tSortDone = SystemClock.elapsedRealtime()
        AppListCache.recordTiming(
            context,
            "  breakdown: query=${tQueryDone - tQueryStart}ms label=${labelMs}ms " +
                "size=${sizeMs}ms sort=${tSortDone - tFieldsDone}ms " +
                "total=${tSortDone - tQueryStart}ms count=${appList.size} flags=$flags"
        )
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

    /**
     * [fullComponents] requests activities, services, receivers, providers,
     * permissions and signing certificates. That is a full manifest plus signature
     * parse, so callers that only need a label or an icon should pass false.
     */
    fun getPackageArchiveInfo(
        context: Context,
        apkPath: String,
        fullComponents: Boolean = true
    ): PackageInfo? {
        val pm = context.packageManager
        val flags = if (fullComponents) {
            (PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES).toLong()
        } else {
            0L
        }

        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, flags.toInt())
            }
        } catch (e: Exception) {
            null
        } ?: try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, 0)
            }
        } catch (_: Exception) {
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

/** Converts dp to pixels, replacing the inline density maths repeated across activities. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Float form for translations and radii that must stay fractional. */
fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
fun Float.dp(): Float = this * android.content.res.Resources.getSystem().displayMetrics.density
val Int.dp: Int get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
val Float.dp: Float get() = this * android.content.res.Resources.getSystem().displayMetrics.density

