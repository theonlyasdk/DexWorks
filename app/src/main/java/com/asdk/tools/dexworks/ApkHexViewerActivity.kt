package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityApkHexViewerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ApkHexViewerActivity : AppCompatActivity(), HexDumpView.Listener {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_ENTRY_PATH = "extra_entry_path"
        private const val FULL_LOAD_MAX_FALLBACK = 4L * 1024 * 1024

        fun createIntent(context: Context, apkPath: String, entryPath: String): Intent {
            return Intent(context, ApkHexViewerActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_ENTRY_PATH, entryPath)
            }
        }
    }

    private lateinit var binding: ActivityApkHexViewerBinding
    private lateinit var apkPath: String
    private lateinit var entryPath: String

    private var totalSize: Long = 0L
    private var isPaged: Boolean = false
    private var pageIndex: Int = 0
    private var pageCount: Int = 1
    private var pageStart: Long = 0L
    private var pageSize: Long = FULL_LOAD_MAX_FALLBACK
    private var openZipRef: ZipFile? = null

    private lateinit var sheetBehavior: BottomSheetBehavior<android.view.View>
    private var sheetVisible: Boolean = false
    private var peekHeightPx: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityApkHexViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH).orEmpty()
        pageSize = AppPrefs.hexPageSizeBytes(this)

        binding.toolbar.title = getString(R.string.title_hex_viewer)
        binding.toolbar.subtitle = entryPath
        binding.toolbar.setNavigationOnClickListener { finish() }

        peekHeightPx = resources.getDimensionPixelSize(R.dimen.hex_sheet_peek)
        sheetBehavior = BottomSheetBehavior.from(binding.byteSheet.root)
        sheetBehavior.isHideable = false
        binding.byteSheet.sheetHeader.setOnClickListener {
            sheetBehavior.state = when (sheetBehavior.state) {
                BottomSheetBehavior.STATE_EXPANDED -> BottomSheetBehavior.STATE_COLLAPSED
                BottomSheetBehavior.STATE_COLLAPSED -> BottomSheetBehavior.STATE_EXPANDED
                else -> BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
        // The sheet covers a different amount of the hex view depending on whether
        // it is collapsed, half or fully expanded, so the usable height has to be
        // tracked live for centring to land in the right place.
        sheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: android.view.View, newState: Int) {
                    updateHexBottomInset()
                }

                override fun onSlide(bottomSheet: android.view.View, slideOffset: Float) {
                    updateHexBottomInset()
                }
            }
        )

        binding.hexView.listener = this
        binding.btnPrevPage.setOnClickListener { if (pageIndex > 0) loadPage(pageIndex - 1) }
        binding.btnNextPage.setOnClickListener { if (pageIndex < pageCount - 1) loadPage(pageIndex + 1) }
        binding.byteSheet.btnPrevByte.setOnClickListener {
            val current = binding.hexView.getSelectedOffset()
            if (current > 0) {
                val prev = current - 1
                if (prev < pageStart && pageIndex > 0) {
                    loadPage(pageIndex - 1, selectOffsetAfterLoad = prev)
                } else {
                    binding.hexView.selectOffset(prev)
                binding.hexView.centerOffsetInView(prev)
                }
            }
        }
        binding.byteSheet.btnNextByte.setOnClickListener {
            val current = binding.hexView.getSelectedOffset()
            if (current >= 0 && current < totalSize - 1) {
                val next = current + 1
                if (next >= pageStart + pageSize && pageIndex < pageCount - 1) {
                    loadPage(pageIndex + 1, selectOffsetAfterLoad = next)
                } else {
                    binding.hexView.selectOffset(next)
                binding.hexView.centerOffsetInView(next)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheetVisible && sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED -> {
                        sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                    }
                    sheetVisible && sheetBehavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                    isPaged && pageIndex > 0 -> loadPage(pageIndex - 1)
                    else -> finish()
                }
            }
        })

        loadEntry()
    }

    private fun loadEntry() {
        if (apkPath.isBlank() || entryPath.isBlank()) {
            showError(getString(R.string.error_loading_hex))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Held open for the whole session. Re-opening per page meant
                    // re-parsing the APK central directory on every page change.
                    val zip = openZip()
                    val entry = zip.getEntry(entryPath)
                        ?: throw IllegalStateException("Entry not found: $entryPath")
                    entry.size
                }
            }
            result.onSuccess { size ->
                totalSize = size
                isPaged = size > pageSize
                pageCount = if (isPaged) ((size + pageSize - 1) / pageSize).toInt() else 1
                loadPage(0)
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.error_loading_hex))
            }
        }
    }

    private fun openZip(): ZipFile = openZipRef ?: ZipFile(File(apkPath)).also { openZipRef = it }

    private fun loadPage(index: Int, selectOffsetAfterLoad: Long? = null) {
        pageIndex = index
        pageStart = index.toLong() * pageSize
        binding.layoutLoading.isVisible = true
        binding.layoutError.isVisible = false
        binding.groupPaging.isVisible = isPaged
        binding.btnPrevPage.isEnabled = pageIndex > 0
        binding.btnNextPage.isEnabled = pageIndex < pageCount - 1
        binding.textPageIndicator.text = getString(R.string.hex_page, pageIndex + 1, pageCount)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = openZip()
                    val entry = zip.getEntry(entryPath)
                        ?: throw IllegalStateException("Entry not found: $entryPath")
                    val remaining = (totalSize - pageStart).coerceAtLeast(0)
                    val length = minOf(remaining, pageSize).toInt()
                    val buffer = ByteArray(length)
                    zip.getInputStream(entry).use { input ->
                        var skipped = 0L
                        while (skipped < pageStart) {
                            val s = input.skip(pageStart - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                        var read = 0
                        while (read < length) {
                            val count = input.read(buffer, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        buffer
                    }
                }
            }
            binding.layoutLoading.isVisible = false
            result.onSuccess { bytes ->
                binding.hexView.setData(bytes, pageStart, totalSize)
                binding.hexView.isVisible = true
                binding.textRange.text = getString(
                    R.string.hex_range,
                    String.format("%08X", pageStart),
                    String.format("%08X", pageStart + bytes.size),
                    AppInfoUtils.formatFileSize(totalSize)
                )
                if (selectOffsetAfterLoad != null) {
                    binding.hexView.selectOffset(selectOffsetAfterLoad)
                    // Centring has to wait for the new data, otherwise it would
                    // scroll using the previous page geometry.
                    binding.hexView.centerOffsetInView(selectOffsetAfterLoad)
                }
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.error_loading_hex))
            }
        }
    }

    override fun onByteSelected(offset: Long, value: Int, printable: Boolean) {
        val sheet = binding.byteSheet
        sheet.btnPrevByte.isEnabled = offset > 0
        sheet.btnNextByte.isEnabled = offset < totalSize - 1
        val charLabel = if (printable) "  '${value.toChar()}'" else ""
        sheet.textSheetSummary.text = getString(
            R.string.hex_sheet_summary,
            String.format("0x%08X", offset),
            String.format("0x%02X", value),
            charLabel
        )
        val row = (offset - pageStart) / HexDumpView.BYTES_PER_ROW
        val column = (offset - pageStart) % HexDumpView.BYTES_PER_ROW
        sheet.textDetailDecimal.text = getString(R.string.hex_detail_decimal, value)
        sheet.textDetailHex.text = getString(R.string.hex_detail_hex, String.format("0x%02X", value))
        sheet.textDetailOctal.text = getString(R.string.hex_detail_octal, String.format("0%o", value))
        sheet.textDetailBinary.text = getString(
            R.string.hex_detail_binary,
            String.format("%8s", Integer.toBinaryString(value)).replace(' ', '0')
        )
        sheet.textDetailCharacter.text = getString(
            R.string.hex_detail_character,
            if (printable) "'${value.toChar()}'" else getString(R.string.hex_detail_not_printable)
        )
        sheet.textDetailBits.text = getString(
            R.string.hex_detail_bits,
            Integer.bitCount(value)
        )
        sheet.textDetailPosition.text = getString(
            R.string.hex_detail_position,
            row,
            column
        )
        sheet.textDetailPage.text = getString(
            R.string.hex_detail_page,
            pageIndex + 1,
            pageCount
        )
        sheet.textDetailTotal.text = getString(
            R.string.hex_detail_total,
            AppInfoUtils.formatFileSize(totalSize)
        )

        if (!sheetVisible) {
            sheetVisible = true
            binding.byteSheet.root.isVisible = true
            updateHexBottomInset()
            binding.byteSheet.root.post {
                sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        } else {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    /** Amount of the hex view currently covered by the sheet, in pixels. */
    private fun updateHexBottomInset() {
        val sheet = binding.byteSheet.root
        if (!sheet.isVisible) {
            binding.hexView.setBottomInset(0)
            return
        }
        val hexLocation = IntArray(2)
        val sheetLocation = IntArray(2)
        binding.hexView.getLocationInWindow(hexLocation)
        sheet.getLocationInWindow(sheetLocation)
        val occluded = (hexLocation[1] + binding.hexView.height) - sheetLocation[1]
        binding.hexView.setBottomInset(occluded.coerceAtLeast(0))
    }

    private fun showError(message: String) {
        closeZip()
        binding.layoutError.isVisible = true
        binding.textErrorMessage.text = message
        binding.hexView.isVisible = false
    }

    private fun closeZip() {
        try {
            openZipRef?.close()
        } catch (e: Exception) {
            // ignore
        }
        openZipRef = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeZip()
    }
}
