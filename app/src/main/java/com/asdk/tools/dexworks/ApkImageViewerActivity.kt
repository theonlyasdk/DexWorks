package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.asdk.tools.dexworks.databinding.ActivityApkImageViewerBinding
import com.asdk.tools.dexworks.databinding.ItemApkImagePageBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ApkImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_ENTRY_PATH = "extra_entry_path"
        private const val MAX_DIMENSION_FALLBACK = 2048
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        fun createIntent(context: Context, apkPath: String, entryPath: String): Intent {
            return Intent(context, ApkImageViewerActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_ENTRY_PATH, entryPath)
            }
        }
    }

    private lateinit var binding: ActivityApkImageViewerBinding
    private lateinit var fileSaveHelper: FileSaveHelper
    private lateinit var apkPath: String
    private lateinit var entryPath: String
    private var imagePaths: List<String> = emptyList()
    private var maxDimension: Int = MAX_DIMENSION_FALLBACK

    private val bitmapCache: android.util.LruCache<String, Bitmap> = run {
        val limit = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        object : android.util.LruCache<String, Bitmap>(limit) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityApkImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH).orEmpty()
        maxDimension = AppPrefs.imageMaxSize(this)
        fileSaveHelper = FileSaveHelper.from(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_viewer_actions)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_viewer_more) {
                showActionsSheet()
                true
            } else {
                false
            }
        }

        binding.layoutLoading.isVisible = true
        binding.layoutError.isVisible = false

        lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) {
                if (apkPath.isBlank() || entryPath.isBlank()) {
                    emptyList()
                } else {
                    listSiblingImages(apkPath, entryPath)
                }
            }
            binding.layoutLoading.isVisible = false

            if (paths.isEmpty()) {
                showError(getString(R.string.error_loading_image))
                return@launch
            }

            imagePaths = paths
            binding.pager.adapter = ImagePagerAdapter(paths)
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateHeader(position)
                }
            })
            val startIndex = imagePaths.indexOf(entryPath).coerceAtLeast(0)
            binding.pager.setCurrentItem(startIndex, false)
            updateHeader(startIndex)
        }
    }

    private fun updateHeader(position: Int) {
        val path = imagePaths.getOrNull(position) ?: return
        binding.toolbar.subtitle = path
        val showIndicator = imagePaths.size > 1
        binding.textPageIndicator.isVisible = showIndicator
        if (showIndicator) {
            binding.textPageIndicator.text =
                getString(R.string.apk_image_page_indicator, position + 1, imagePaths.size)
        }
    }

    private fun currentPath(): String =
        imagePaths.getOrNull(binding.pager.currentItem) ?: entryPath

    private fun showActionsSheet() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_viewer_actions, null)
        content.findViewById<View>(R.id.action_share).setOnClickListener {
            dialog.dismiss()
            shareCurrentImage()
        }
        content.findViewById<View>(R.id.action_save_to).setOnClickListener {
            dialog.dismiss()
            saveCurrentImage()
        }
        content.findViewById<View>(R.id.action_copy).setOnClickListener {
            dialog.dismiss()
            copyCurrentImage()
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun shareCurrentImage() {
        val path = currentPath()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkEntryFiles.materialize(applicationContext, apkPath, path)
            }
            if (file == null) {
                Snackbar.make(binding.root, R.string.viewer_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val uri = ApkEntryFiles.uriFor(applicationContext, file) ?: return@launch
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(path)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.viewer_share)))
        }
    }

    private fun saveCurrentImage() {
        val path = currentPath()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkEntryFiles.materialize(applicationContext, apkPath, path)
            }
            if (file == null) {
                Snackbar.make(binding.root, R.string.viewer_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            fileSaveHelper.saveFile(
                file,
                path.substringAfterLast('/'),
                forcePickLocation = true
            )
        }
    }

    private fun copyCurrentImage() {
        val path = currentPath()
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val file = ApkEntryFiles.materialize(applicationContext, apkPath, path)
                if (file == null) null else ApkEntryFiles.uriFor(applicationContext, file)
            }
            if (uri == null) {
                Snackbar.make(binding.root, R.string.viewer_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newUri(contentResolver, path.substringAfterLast('/'), uri)
            )
            Snackbar.make(binding.root, R.string.viewer_copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeFor(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    private fun listSiblingImages(apkPath: String, entryPath: String): List<String> {
        val parent = entryPath.substringBeforeLast('/', "")
        val found = ArrayList<String>()

        ZipFile(File(apkPath)).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.substringBeforeLast('/', "") != parent) continue
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension in IMAGE_EXTENSIONS) found += name
            }
        }

        return found.sortedBy { it.lowercase() }
    }

    private fun decodeEntry(path: String): Bitmap {
        bitmapCache.get(path)?.let { return it }
        val bytes = ZipFile(File(apkPath)).use { zip ->
            val entry = zip.getEntry(path)
                ?: throw IllegalStateException("Entry not found: $path")
            zip.getInputStream(entry).use { it.readBytes() }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Not a decodable image")
        }

        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / (sample * 2) >= maxDimension || h / (sample * 2) >= maxDimension) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Could not decode image")
        // Swiping back to a page used to re-open the APK and re-decode the image
        // every time; the cache makes a revisited page instant.
        bitmapCache.put(path, decoded)
        return decoded
    }

    private fun showError(message: String) {
        binding.layoutError.isVisible = true
        binding.textErrorMessage.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.pager.adapter = null
    }

    private inner class ImagePagerAdapter(
        private val paths: List<String>
    ) : RecyclerView.Adapter<ImagePagerAdapter.PageHolder>() {

        inner class PageHolder(val binding: ItemApkImagePageBinding) :
            RecyclerView.ViewHolder(binding.root) {
            var boundPath: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val itemBinding = ItemApkImagePageBinding.inflate(layoutInflater, parent, false)
            return PageHolder(itemBinding)
        }

        override fun getItemCount(): Int = paths.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val path = paths[position]
            holder.boundPath = path
            holder.binding.imageView.setImageDrawable(null)
            holder.binding.layoutLoading.isVisible = true
            holder.binding.textErrorMessage.isVisible = false

            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decodeEntry(path) }.getOrNull()
                }
                if (holder.boundPath != path) return@launch
                holder.binding.layoutLoading.isVisible = false
                if (bitmap == null) {
                    holder.binding.textErrorMessage.isVisible = true
                } else {
                    holder.binding.imageView.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.boundPath = null
            holder.binding.imageView.setImageDrawable(null)
            super.onViewRecycled(holder)
        }
    }
}
