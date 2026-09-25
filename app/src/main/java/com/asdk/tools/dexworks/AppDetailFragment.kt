package com.asdk.tools.dexworks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.FragmentAppDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppDetailFragment : Fragment() {

    private var _binding: FragmentAppDetailBinding? = null
    private val binding get() = _binding!!

    private var skeletonAnimator: ObjectAnimator? = null

    private var packageInfo: PackageInfo? = null
    private var actualApkPath: String = ""
    private var isExternal: Boolean = false

    private var currentAnimator: Animator? = null
    private var startScale: Float = 0f
    private var startTranslationX: Float = 0f
    private var startTranslationY: Float = 0f
    private var backPressedCallback: OnBackPressedCallback? = null

    private lateinit var fileSaveHelper: FileSaveHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileSaveHelper = FileSaveHelper.from(this)
    }

    fun isExternalApk(): Boolean = isExternal
    fun hasApkPath(): Boolean = actualApkPath.isNotBlank()
    fun isZoomViewerOpen(): Boolean = _binding?.layoutIconViewerOverlay?.isVisible == true

    fun hasSignature(): Boolean {
        val pkg = packageInfo ?: return false
        val sha256 = AppInfoUtils.getSha256Fingerprint(pkg)
        return sha256.isNotBlank() && !sha256.equals("Not available", ignoreCase = true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupIconZoomViewer()

        val openActivitiesInspector = View.OnClickListener {
            openInspector(ActivityInspectorActivity::createIntent)
        }
        binding.textActivitiesCount.setOnClickListener(openActivitiesInspector)
        binding.textActivitiesLabel.setOnClickListener(openActivitiesInspector)

        binding.btnRetryDetails.setOnClickListener {
            loadDetails()
        }

        loadDetails()
    }

    private fun loadDetails() {
        val packageName = arguments?.getString("packageName")
        val apkPath = arguments?.getString("apkPath")

        binding.layoutDetailError.isVisible = false
        showSkeleton()

        viewLifecycleOwner.lifecycleScope.launch {
            val packageInfo = withContext(Dispatchers.IO) {
                when {
                    !apkPath.isNullOrBlank() -> AppInfoUtils.getPackageArchiveInfo(requireContext(), apkPath)
                    !packageName.isNullOrBlank() -> AppInfoUtils.getPackageInfo(requireContext(), packageName)
                    else -> null
                }
            }

            hideSkeleton()

            if (packageInfo != null) {
                populateUI(packageInfo, apkPath)
            } else {
                binding.layoutDetailContent.isVisible = false
                binding.layoutDetailError.isVisible = true
                Snackbar.make(binding.root, R.string.toast_apk_parse_error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showSkeleton() {
        binding.layoutDetailContent.isVisible = false
        binding.layoutSkeleton.isVisible = true
        skeletonAnimator?.cancel()
        skeletonAnimator = ObjectAnimator.ofFloat(binding.layoutSkeleton, "alpha", 1f, 0.4f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun hideSkeleton() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        binding.layoutSkeleton.alpha = 1f
        binding.layoutSkeleton.isVisible = false
        binding.layoutDetailContent.isVisible = true
    }

    private fun setupIconZoomViewer() {
        binding.detailAppIcon.setOnClickListener {
            val drawable = binding.detailAppIcon.drawable ?: return@setOnClickListener
            zoomImageFromThumb(binding.detailAppIcon, drawable)
        }

        binding.detailAppIcon.setOnLongClickListener {
            val drawable = binding.detailAppIcon.drawable
            val pkg = packageInfo
            if (drawable != null && pkg != null) {
                val appName = pkg.applicationInfo?.loadLabel(requireContext().packageManager)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
                fileSaveHelper.saveIcon(drawable, appName)
                true
            } else {
                false
            }
        }

        binding.layoutIconViewerOverlay.setOnClickListener {
            closeIconZoomViewer()
        }

        binding.imageFullscreenIcon.setOnClickListener {
            closeIconZoomViewer()
        }

        binding.imageFullscreenIcon.setOnLongClickListener {
            val drawable = binding.imageFullscreenIcon.drawable
            val pkg = packageInfo
            if (drawable != null && pkg != null) {
                val appName = pkg.applicationInfo?.loadLabel(requireContext().packageManager)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
                fileSaveHelper.saveIcon(drawable, appName)
                true
            } else {
                false
            }
        }

        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeIconZoomViewer()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun zoomImageFromThumb(thumbView: View, drawable: Drawable) {
        currentAnimator?.cancel()

        val expandedImageView = binding.imageFullscreenIcon
        val overlay = binding.layoutIconViewerOverlay

        expandedImageView.setImageDrawable(drawable)

        val startBounds = Rect()
        val globalOffset = Point()
        thumbView.getGlobalVisibleRect(startBounds)
        binding.root.getGlobalVisibleRect(Rect(), globalOffset)
        startBounds.offset(-globalOffset.x, -globalOffset.y)

        overlay.isVisible = true
        overlay.alpha = 0f

        expandedImageView.post {
            if (_binding == null) return@post

            val expandedBounds = Rect()
            expandedImageView.getGlobalVisibleRect(expandedBounds)
            expandedBounds.offset(-globalOffset.x, -globalOffset.y)

            val startScaleX = startBounds.width().toFloat() / expandedBounds.width().toFloat()
            val startScaleY = startBounds.height().toFloat() / expandedBounds.height().toFloat()
            val startScaleFinal = maxOf(startScaleX, startScaleY)

            val startCenterX = startBounds.centerX().toFloat()
            val startCenterY = startBounds.centerY().toFloat()
            val expandedCenterX = expandedBounds.centerX().toFloat()
            val expandedCenterY = expandedBounds.centerY().toFloat()

            val deltaX = startCenterX - expandedCenterX
            val deltaY = startCenterY - expandedCenterY

            this.startScale = startScaleFinal
            this.startTranslationX = deltaX
            this.startTranslationY = deltaY

            expandedImageView.pivotX = expandedImageView.width / 2f
            expandedImageView.pivotY = expandedImageView.height / 2f
            expandedImageView.scaleX = startScaleFinal
            expandedImageView.scaleY = startScaleFinal
            expandedImageView.translationX = deltaX
            expandedImageView.translationY = deltaY

            backPressedCallback?.isEnabled = true

            val set = AnimatorSet()
            set.playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(expandedImageView, View.TRANSLATION_X, deltaX, 0f),
                ObjectAnimator.ofFloat(expandedImageView, View.TRANSLATION_Y, deltaY, 0f),
                ObjectAnimator.ofFloat(expandedImageView, View.SCALE_X, startScaleFinal, 1f),
                ObjectAnimator.ofFloat(expandedImageView, View.SCALE_Y, startScaleFinal, 1f)
            )
            set.duration = 260
            set.interpolator = FastOutSlowInInterpolator()
            set.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    currentAnimator = null
                }
            })
            set.start()
            currentAnimator = set
        }
    }

    fun closeIconZoomViewer() {
        if (!isZoomViewerOpen()) return
        currentAnimator?.cancel()

        val expandedImageView = binding.imageFullscreenIcon
        val overlay = binding.layoutIconViewerOverlay

        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(overlay, View.ALPHA, 0f),
            ObjectAnimator.ofFloat(expandedImageView, View.TRANSLATION_X, startTranslationX),
            ObjectAnimator.ofFloat(expandedImageView, View.TRANSLATION_Y, startTranslationY),
            ObjectAnimator.ofFloat(expandedImageView, View.SCALE_X, startScale),
            ObjectAnimator.ofFloat(expandedImageView, View.SCALE_Y, startScale)
        )
        set.duration = 220
        set.interpolator = FastOutSlowInInterpolator()
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (_binding != null) {
                    overlay.isVisible = false
                }
                backPressedCallback?.isEnabled = false
                currentAnimator = null
            }
            override fun onAnimationCancel(animation: Animator) {
                if (_binding != null) {
                    overlay.isVisible = false
                }
                backPressedCallback?.isEnabled = false
                currentAnimator = null
            }
        })
        set.start()
        currentAnimator = set
    }

    private fun populateUI(pkg: PackageInfo, externalApkPath: String?) {
        val context = requireContext()
        val pm = context.packageManager
        val appInfo = pkg.applicationInfo

        this.packageInfo = pkg
        this.actualApkPath = externalApkPath ?: appInfo?.sourceDir ?: ""
        this.isExternal = !externalApkPath.isNullOrBlank()
        activity?.invalidateOptionsMenu()

        // App Icon & Name
        val icon = appInfo?.loadIcon(pm)
        if (icon != null) {
            binding.detailAppIcon.setImageDrawable(icon)
        } else {
            binding.detailAppIcon.setImageResource(R.drawable.ic_app_placeholder)
        }

        val appName = appInfo?.loadLabel(pm)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
        binding.detailAppName.text = appName
        binding.detailPackageName.text = pkg.packageName

        // Badge Type
        val isExternal = !externalApkPath.isNullOrBlank()
        val isSystem = if (appInfo != null) (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 else false
        when {
            isExternal -> {
                binding.detailBadgeType.text = getString(R.string.badge_external_apk)
                binding.layoutDates.isVisible = false
                binding.btnOpenApp.isVisible = false
                binding.btnAppInfo.isVisible = false
            }
            isSystem -> {
                binding.detailBadgeType.text = getString(R.string.badge_system_app)
            }
            else -> {
                binding.detailBadgeType.text = getString(R.string.badge_user_app)
            }
        }

        // Action: Open App
        binding.btnOpenApp.setOnClickListener {
            val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Snackbar.make(binding.root, R.string.toast_app_not_launchable, Snackbar.LENGTH_SHORT).show()
            }
        }

        // Action: App System Info
        binding.btnAppInfo.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg.packageName, null)
            }
            startActivity(intent)
        }

        // Action: Share APK
        val actualApkPath = externalApkPath ?: appInfo?.sourceDir ?: ""
        binding.btnShareApk.setOnClickListener {
            if (actualApkPath.isNotBlank()) {
                val sourceFile = File(actualApkPath)
                if (!sourceFile.exists()) {
                    Snackbar.make(binding.root, R.string.toast_apk_parse_error, Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val context = requireContext()
                    val uri = withContext(Dispatchers.IO) {
                        try {
                            val shareDir = File(context.cacheDir, "shared_apks")
                            if (!shareDir.exists()) {
                                shareDir.mkdirs()
                            } else {
                                shareDir.listFiles()?.forEach { it.delete() }
                            }

                            val targetName = appName.ifBlank { pkg.packageName }.ifBlank { "app" }
                            val sanitizedAppName = targetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            val versionName = pkg.versionName ?: "1.0"
                            val fileName = "${sanitizedAppName}_${versionName}.apk"
                            val targetFile = File(shareDir, fileName)

                            sourceFile.copyTo(targetFile, overwrite = true)
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (uri != null && isAdded) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.android.package-archive"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "$appName APK")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.action_extract_apk)))
                    } else if (isAdded) {
                        Snackbar.make(binding.root, R.string.toast_apk_save_failed, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Version & Codes
        binding.textVersionName.text = pkg.versionName ?: "N/A"
        binding.textVersionCode.text = AppInfoUtils.getVersionCode(pkg).toString()

        // Min & Target SDK
        val minSdk = if (appInfo != null) AppInfoUtils.getMinSdkVersion(appInfo) else 21
        val targetSdk = appInfo?.targetSdkVersion ?: 0
        binding.textMinSdk.text = "API $minSdk"
        binding.textTargetSdk.text = "API $targetSdk"
        binding.textMinSdk.setOnClickListener { showSdkInfoDialog(minSdk) }
        binding.textTargetSdk.setOnClickListener { showSdkInfoDialog(targetSdk) }

        // APK Size & Path
        val apkFile = File(actualApkPath)
        val sizeBytes = if (apkFile.exists()) apkFile.length() else 0L
        binding.textApkSize.text = AppInfoUtils.formatFileSize(sizeBytes)
        binding.layoutApkSize.setOnClickListener {
            startApkSizeAnalysis(actualApkPath)
        }
        binding.textApkPath.text = actualApkPath

        binding.btnCopyPath.setOnClickListener {
            copyToClipboard("APK Path", actualApkPath)
            Snackbar.make(binding.root, R.string.toast_path_copied, Snackbar.LENGTH_SHORT).show()
        }

        // Dates
        if (!isExternal) {
            binding.textInstallDate.text = AppInfoUtils.formatDate(pkg.firstInstallTime)
            binding.textUpdateDate.text = AppInfoUtils.formatDate(pkg.lastUpdateTime)
            binding.textInstallSource.text = AppInfoUtils.getInstallationSource(context, pkg.packageName, isSystem)
        } else {
            binding.textInstallSource.text = "External APK File"
        }

        // Components
        val actCount = pkg.activities?.size ?: 0
        val srvCount = pkg.services?.size ?: 0
        val recCount = pkg.receivers?.size ?: 0
        val prvCount = pkg.providers?.size ?: 0
        val totalComponents = actCount + srvCount + recCount + prvCount

        if (totalComponents > 0) {
            binding.gridComponents.isVisible = true
            binding.layoutEmptyComponents.isVisible = false
            binding.textActivitiesCount.text = actCount.toString()
            binding.textServicesCount.text = srvCount.toString()
            binding.textReceiversCount.text = recCount.toString()
            binding.textProvidersCount.text = prvCount.toString()
        } else {
            binding.gridComponents.isVisible = false
            binding.layoutEmptyComponents.isVisible = true
        }

        // Certificate Fingerprint
        val sha256 = AppInfoUtils.getSha256Fingerprint(pkg)
        if (sha256.isNotBlank() && !sha256.equals("Not available", ignoreCase = true)) {
            binding.layoutCertContent.isVisible = true
            binding.layoutEmptySignatures.isVisible = false
            binding.textCertSha256.text = sha256
            binding.btnCopyCert.setOnClickListener {
                copyToClipboard("SHA-256", sha256)
                Snackbar.make(binding.root, R.string.toast_path_copied, Snackbar.LENGTH_SHORT).show()
            }
        } else {
            binding.layoutCertContent.isVisible = false
            binding.layoutEmptySignatures.isVisible = true
        }

        // Permissions
        val permissions = pkg.requestedPermissions
        val permCount = permissions?.size ?: 0
        binding.textPermissionsHeader.text = getString(R.string.label_permissions, permCount)
        binding.layoutPermissionsContainer.removeAllViews()

        if (!permissions.isNullOrEmpty()) {
            binding.layoutPermissionsContainer.isVisible = true
            binding.layoutEmptyPermissions.isVisible = false
            for (permission in permissions) {
                val permTextView = TextView(context).apply {
                    text = permission
                    setPadding(0, 6, 0, 6)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextIsSelectable(true)
                }
                binding.layoutPermissionsContainer.addView(permTextView)
            }
        } else {
            binding.layoutPermissionsContainer.isVisible = false
            binding.layoutEmptyPermissions.isVisible = true
        }
    }

    private fun showSdkInfoDialog(apiLevel: Int) {
        val info = AppInfoUtils.getSdkVersionInfo(apiLevel)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_sdk_title))
            .setMessage(getString(R.string.dialog_sdk_message, info.version, info.name, info.codename))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startApkSizeAnalysis(apkPath: String) {
        if (apkPath.isBlank() || !File(apkPath).exists()) {
            Snackbar.make(binding.root, R.string.error_analyzing_apk_size, Snackbar.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = com.asdk.tools.dexworks.databinding.DialogApkSizeAnalysisBinding.inflate(layoutInflater)
        dialogBinding.layoutLoading.isVisible = true
        dialogBinding.layoutContent.isVisible = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_apk_size_analysis)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val breakdown = withContext(Dispatchers.IO) {
                    ApkSizeAnalyzer.analyzeApk(apkPath)
                }
                if (!isAdded || _binding == null) return@launch

                dialogBinding.textTotalSize.text = getString(
                    R.string.label_apk_total_size,
                    AppInfoUtils.formatFileSize(breakdown.totalCompressedSize)
                )
                dialogBinding.textUncompressedAndFiles.text = "${getString(
                    R.string.label_apk_uncompressed_size,
                    AppInfoUtils.formatFileSize(breakdown.totalUncompressedSize)
                )} • ${getString(R.string.label_apk_file_count, breakdown.totalFiles)}"

                val slices = breakdown.categories.map {
                    CylinderChartView.Slice(
                        id = it.id,
                        label = getString(it.nameResId),
                        percentage = it.percentage,
                        color = it.color
                    )
                }
                dialogBinding.cylinderChart.setData(slices, animate = true)

                dialogBinding.layoutCategoriesContainer.removeAllViews()
                for (item in breakdown.categories) {
                    val itemBinding = com.asdk.tools.dexworks.databinding.ItemApkCategoryBreakdownBinding.inflate(
                        layoutInflater,
                        dialogBinding.layoutCategoriesContainer,
                        false
                    )
                    itemBinding.textCategoryName.setText(item.nameResId)
                    itemBinding.textCategoryPercentage.text = String.format(java.util.Locale.getDefault(), "%.1f%%", item.percentage)
                    itemBinding.progressCategory.setIndicatorColor(item.color)
                    itemBinding.progressCategory.progress = item.percentage.toInt().coerceIn(0, 100)
                    itemBinding.textCategorySize.text = "${AppInfoUtils.formatFileSize(item.compressedSize)} • ${getString(R.string.label_apk_file_count, item.fileCount)}"
                    itemBinding.textCategoryUncompressed.text = getString(R.string.label_apk_original_size, AppInfoUtils.formatFileSize(item.uncompressedSize))

                    dialogBinding.layoutCategoriesContainer.addView(itemBinding.root)
                }

                dialogBinding.layoutLoading.isVisible = false
                dialogBinding.layoutContent.isVisible = true
            } catch (e: Exception) {
                if (!isAdded || _binding == null) return@launch
                dialog.dismiss()
                Snackbar.make(binding.root, R.string.error_analyzing_apk_size, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
    }

    fun onMenuAction(itemId: Int): Boolean {
        val pkg = packageInfo ?: return false
        when (itemId) {
            R.id.action_save_apk_to -> {
                fileSaveHelper.saveApk(pkg, actualApkPath, forcePickLocation = false)
                return true
            }
            R.id.action_inspect_manifest -> {
                openInspector(ManifestInspectorActivity::createIntent)
                return true
            }
            R.id.action_inspect_activities -> {
                openInspector(ActivityInspectorActivity::createIntent)
                return true
            }
            R.id.action_import_as_project -> {
                importAsProject()
                return true
            }
        }
        return false
    }

    private fun importAsProject() {
        val pkg = packageInfo ?: return
        if (actualApkPath.isBlank()) return
        val appName = pkg.applicationInfo?.loadLabel(requireContext().packageManager)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
        startActivity(ProjectWizardActivity.createIntent(requireContext(), actualApkPath, appName))
    }

    private fun openInspector(
        createIntent: (Context, String, String, String) -> Intent
    ) {
        val pkg = packageInfo ?: return
        if (actualApkPath.isBlank()) return
        val appName = pkg.applicationInfo?.loadLabel(requireContext().packageManager)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
        startActivity(createIntent(requireContext(), actualApkPath, appName, pkg.packageName))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        currentAnimator?.cancel()
        currentAnimator = null
        _binding = null
    }
}
