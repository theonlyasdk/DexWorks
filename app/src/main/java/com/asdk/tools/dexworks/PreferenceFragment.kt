package com.asdk.tools.dexworks

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PreferenceFragment : PreferenceFragmentCompat() {

    private var fileSaveHelper: FileSaveHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created here, not inside a click listener: the helper registers
        // an ActivityResult launcher, which is only legal before the fragment starts.
        fileSaveHelper = FileSaveHelper.from(this)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference !is ListPreference) {
            super.onDisplayPreferenceDialog(preference)
            return
        }

        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return
        val selectedIndex = entryValues.indexOf(preference.value).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.dialogTitle ?: preference.title)
            .setSingleChoiceItems(entries, selectedIndex) { dialog, which ->
                val newValue = entryValues.getOrNull(which)
                if (newValue != null && preference.callChangeListener(newValue)) {
                    preference.value = newValue.toString()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // The folder picker returns asynchronously, so refresh once we are back.
        updateSavedFolderSummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>(AppPrefs.KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            applyTheme(newValue as? String)
            true
        }

        findPreference<Preference>(KEY_SAVED_FOLDER)?.let { preference ->
            updateSavedFolderSummary(preference)
            preference.setOnPreferenceClickListener {
                showSavedFolderDialog()
                true
            }
        }
        findPreference<Preference>(KEY_CLEAR_CACHE)?.setOnPreferenceClickListener {
            clearCache()
            true
        }

        findPreference<AboutPreference>(KEY_ABOUT)?.let { preference ->
            val colorPrimary = com.google.android.material.color.MaterialColors.getColor(
                preference.context,
                androidx.appcompat.R.attr.colorPrimary,
                0
            )
            preference.icon?.setTint(colorPrimary)
            val versionName = readVersionName()
            preference.summary = getString(
                R.string.settings_about_version,
                versionName
            )
            preference.onShortTap = {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_about_toast, versionName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            preference.onLongPress5Seconds = {
                startActivity(Intent(requireContext(), VibrationTestActivity::class.java))
            }
        }
    }

    private fun readVersionName(): String {
        return try {
            val context = requireContext()
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun savedFolderSummary(): String {
        val uri = fileSaveHelper?.getRememberedLocation()
        return if (uri == null) {
            getString(R.string.settings_saved_folder_summary)
        } else {
            "${getString(R.string.settings_saved_folder_summary)} (${uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()})"
        }
    }

    private fun updateSavedFolderSummary(preference: Preference) {
        preference.setSummary(savedFolderSummary())
    }

    private fun updateSavedFolderSummary() {
        findPreference<Preference>(KEY_SAVED_FOLDER)?.let { updateSavedFolderSummary(it) }
    }

    private fun showSavedFolderDialog() {
        val helper = fileSaveHelper ?: return
        val savedFolder = findPreference<Preference>(KEY_SAVED_FOLDER)

        if (helper.getRememberedLocation() == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_saved_folder)
                .setMessage(R.string.settings_saved_folder_summary)
                .setPositiveButton(R.string.settings_choose_folder) { _, _ ->
                    helper.pickRememberedFolder()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_saved_folder)
            .setMessage(savedFolderSummary())
            .setPositiveButton(R.string.settings_choose_folder) { _, _ ->
                helper.pickRememberedFolder()
            }
            .setNeutralButton(R.string.settings_forget_folder) { _, _ ->
                helper.forgetRememberedLocation()
                savedFolder?.let { updateSavedFolderSummary(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearCache() {
        val context = requireContext().applicationContext
        // Recursive deletes and listFiles walked the whole cache tree; doing that
        // on the main thread risked an ANR on a large cache.
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try {
                    var count = 0
                    context.cacheDir.listFiles()?.forEach { child ->
                        if (child.isDirectory) {
                            count += child.listFiles()?.size ?: 0
                        } else {
                            count++
                        }
                        @Suppress("ResultOfMethodCallIgnored")
                        child.deleteRecursively()
                    }
                    count
                } catch (e: Exception) {
                    -1
                }
            }

            val message = if (deleted < 0) {
                getString(R.string.settings_cache_failed)
            } else {
                getString(R.string.settings_cache_cleared)
            }
            val view = view ?: return@launch
            Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_SAVED_FOLDER = "remembered_save_location"
        const val KEY_CLEAR_CACHE = "clear_cache"
        const val KEY_ABOUT = "about"
    }
}

class AboutPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    var onLongPress5Seconds: (() -> Unit)? = null
    var onShortTap: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val view = holder.itemView
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var hasTriggeredLongPress = false

        val longPressRunnable = Runnable {
            hasTriggeredLongPress = true
            view.isPressed = false
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress5Seconds?.invoke()
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    hasTriggeredLongPress = false
                    v.isPressed = true
                    v.postDelayed(longPressRunnable, 5000L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        v.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    val wasPressed = v.isPressed
                    v.isPressed = false
                    if (!hasTriggeredLongPress && wasPressed) {
                        v.playSoundEffect(SoundEffectConstants.CLICK)
                        onShortTap?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }
}

