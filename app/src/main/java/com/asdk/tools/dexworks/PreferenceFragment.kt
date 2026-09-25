package com.asdk.tools.dexworks

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PreferenceFragment : PreferenceFragmentCompat() {
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, newValue ->
            applyTheme(newValue as? String)
            true
        }
    }
}