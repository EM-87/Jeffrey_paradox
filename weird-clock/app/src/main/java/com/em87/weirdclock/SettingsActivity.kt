package com.em87.weirdclock

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (!supportFragmentManager.popBackStackImmediate()) finish()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, RootSettingsFragment())
                .commit()
        }
    }

    /** The simple menu: only what the average user comes looking for. */
    class RootSettingsFragment : PreferenceFragmentCompat() {

        private val chimePlayer = ChimePlayer()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                Prefs.TEST_BELLS -> {
                    playTestBells()
                    return true
                }
                Prefs.REASSEMBLE -> {
                    // Panic button: the clock picks everything up on resume.
                    preferenceManager.sharedPreferences?.edit()
                        ?.putBoolean(Prefs.REASSEMBLE_PENDING, true)
                        ?.apply()
                    Toast.makeText(requireContext(), R.string.reassemble_done, Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                    return true
                }
                Prefs.ADVANCED -> {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.settings_container, AdvancedSettingsFragment())
                        .addToBackStack(null)
                        .commit()
                    return true
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onDestroy() {
            chimePlayer.release()
            super.onDestroy()
        }

        /** Plays a sample of whatever bell style is currently selected. */
        private fun playTestBells() {
            val prefs = preferenceManager.sharedPreferences ?: return
            when (prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT)) {
                Prefs.BELL_STYLE_SHIPS -> chimePlayer.playBellSequence(
                    4, pairGrouping = true,
                    frequency = ChimePlayer.SHIPS_HZ, ringSeconds = 2.0
                )
                Prefs.BELL_STYLE_SINGLE -> chimePlayer.playBellSequence(
                    1, pairGrouping = false,
                    frequency = ChimePlayer.GONG_HZ, ringSeconds = 4.5
                )
                else -> chimePlayer.playBellSequence(
                    3, pairGrouping = false,
                    frequency = ChimePlayer.GRANDFATHER_HZ, ringSeconds = 3.0, interval = 1.3
                )
            }
        }
    }

    /** Everything weird lives here, out of the average user's way. */
    class AdvancedSettingsFragment :
        PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)

            val preset = findPreference<ListPreference>(Prefs.HOURS_PRESET)
            val custom = findPreference<SeekBarPreference>(Prefs.HOURS_CUSTOM)
            fun updateCustomVisibility(value: String?) {
                custom?.isVisible = value == Prefs.HOURS_CUSTOM_VALUE
            }
            updateCustomVisibility(preset?.value)
            preset?.setOnPreferenceChangeListener { _, newValue ->
                updateCustomVisibility(newValue as? String)
                true
            }

            findPreference<SeekBarPreference>(Prefs.TIME_SPEED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue != 100) showSpeedWarning()
                    true
                }
        }

        private fun showSpeedWarning() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.speed_warning_title)
                .setMessage(R.string.speed_warning_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == Prefs.TIME_SPEED) {
                AlarmScheduler.update(requireContext())
            }
        }
    }
}
