package com.asdk.tools.dexworks

import android.graphics.Color
import java.io.File
import java.util.zip.ZipFile

data class ApkCategoryItem(
    val id: String,
    val nameResId: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val fileCount: Int,
    val percentage: Float,
    val color: Int
)

data class ApkSizeBreakdown(
    val totalCompressedSize: Long,
    val totalUncompressedSize: Long,
    val totalFiles: Int,
    val categories: List<ApkCategoryItem>
)

object ApkSizeAnalyzer {

    fun analyzeApk(apkPath: String): ApkSizeBreakdown {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            throw IllegalArgumentException("Cannot read APK file at: $apkPath")
        }

        var totalCompressed = 0L
        var totalUncompressed = 0L
        var totalFiles = 0

        var dexCompressed = 0L
        var dexUncompressed = 0L
        var dexCount = 0

        var nativeCompressed = 0L
        var nativeUncompressed = 0L
        var nativeCount = 0

        var resCompressed = 0L
        var resUncompressed = 0L
        var resCount = 0

        var assetsCompressed = 0L
        var assetsUncompressed = 0L
        var assetsCount = 0

        var sigCompressed = 0L
        var sigUncompressed = 0L
        var sigCount = 0

        var otherCompressed = 0L
        var otherUncompressed = 0L
        var otherCount = 0

        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                totalFiles++
                val cSize = if (entry.compressedSize >= 0) entry.compressedSize else entry.size
                val uSize = if (entry.size >= 0) entry.size else cSize

                totalCompressed += cSize
                totalUncompressed += uSize

                val name = entry.name
                when {
                    name.endsWith(".dex", ignoreCase = true) -> {
                        dexCompressed += cSize
                        dexUncompressed += uSize
                        dexCount++
                    }
                    name.startsWith("lib/", ignoreCase = true) -> {
                        nativeCompressed += cSize
                        nativeUncompressed += uSize
                        nativeCount++
                    }
                    name == "resources.arsc" || name.startsWith("res/", ignoreCase = true) -> {
                        resCompressed += cSize
                        resUncompressed += uSize
                        resCount++
                    }
                    name.startsWith("assets/", ignoreCase = true) -> {
                        assetsCompressed += cSize
                        assetsUncompressed += uSize
                        assetsCount++
                    }
                    name.startsWith("META-INF/", ignoreCase = true) || name.equals("AndroidManifest.xml", ignoreCase = true) -> {
                        sigCompressed += cSize
                        sigUncompressed += uSize
                        sigCount++
                    }
                    else -> {
                        otherCompressed += cSize
                        otherUncompressed += uSize
                        otherCount++
                    }
                }
            }
        }

        // Fallback if total is 0
        val safeTotal = if (totalCompressed > 0) totalCompressed.toFloat() else 1f

        val rawCategories = listOf(
            Triple(
                Triple("dex", R.string.label_category_dex, Color.parseColor("#4285F4")),
                Pair(dexCompressed, dexUncompressed),
                dexCount
            ),
            Triple(
                Triple("native", R.string.label_category_native_libs, Color.parseColor("#0F9D58")),
                Pair(nativeCompressed, nativeUncompressed),
                nativeCount
            ),
            Triple(
                Triple("resources", R.string.label_category_resources, Color.parseColor("#FF9800")),
                Pair(resCompressed, resUncompressed),
                resCount
            ),
            Triple(
                Triple("assets", R.string.label_category_assets, Color.parseColor("#9C27B0")),
                Pair(assetsCompressed, assetsUncompressed),
                assetsCount
            ),
            Triple(
                Triple("signatures", R.string.label_category_signatures, Color.parseColor("#00ACC1")),
                Pair(sigCompressed, sigUncompressed),
                sigCount
            ),
            Triple(
                Triple("other", R.string.label_category_other, Color.parseColor("#78909C")),
                Pair(otherCompressed, otherUncompressed),
                otherCount
            )
        )

        val categories = rawCategories
            .filter { (_, sizes, count) -> sizes.first > 0 || count > 0 }
            .map { (meta, sizes, count) ->
                val (id, nameRes, color) = meta
                val (cSize, uSize) = sizes
                val pct = (cSize.toFloat() / safeTotal) * 100f
                ApkCategoryItem(
                    id = id,
                    nameResId = nameRes,
                    compressedSize = cSize,
                    uncompressedSize = uSize,
                    fileCount = count,
                    percentage = pct,
                    color = color
                )
            }
            .sortedByDescending { it.compressedSize }

        return ApkSizeBreakdown(
            totalCompressedSize = totalCompressed,
            totalUncompressedSize = totalUncompressed,
            totalFiles = totalFiles,
            categories = categories
        )
    }
}
