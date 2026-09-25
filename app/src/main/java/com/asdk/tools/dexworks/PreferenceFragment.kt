package com.asdk.tools.dexworks

import android.net.Uri
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

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

        findPreference<Preference>(KEY_ABOUT)?.let { preference ->
            preference.summary = getString(
                R.string.settings_about_version,
                readVersionName()
            )
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
            getString(R.string.settings_saved_folder_none)
        } else {
            uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
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
                .setTitle(R.string.settings_choose_folder)
                .setMessage(R.string.settings_saved_folder_none)
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
        val deleted = try {
            val dir = requireContext().cacheDir
            var count = 0
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) count += child.listFiles()?.size ?: 0 else count++
                @Suppress("ResultOfMethodCallIgnored")
                child.deleteRecursively()
            }
            count
        } catch (e: Exception) {
            -1
        }

        val message = if (deleted < 0) {
            getString(R.string.settings_cache_failed)
        } else {
            getString(R.string.settings_cache_cleared)
        }
        val view = view ?: return
        Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_SAVED_FOLDER = "remembered_save_location"
        const val KEY_CLEAR_CACHE = "clear_cache"
        const val KEY_ABOUT = "about"
    }
}
