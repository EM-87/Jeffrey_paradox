package com.em87.weirdclock

import android.Manifest
import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment :
        PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val chimePlayer = ChimePlayer()

        private val notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

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

            findPreference<SwitchPreferenceCompat>(Prefs.ALARM_ENABLED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) maybeRequestNotificationPermission()
                    true
                }

            findPreference<SeekBarPreference>(Prefs.TIME_SPEED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue != 100) showSpeedWarning()
                    true
                }

            updateAlarmTimeSummary()
            updateAlarmAvailability()
        }

        private fun showSpeedWarning() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.speed_warning_title)
                .setMessage(R.string.speed_warning_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun updateAlarmAvailability() {
            val realSpeed = (preferenceManager.sharedPreferences
                ?.getInt(Prefs.TIME_SPEED, 100) ?: 100) == 100
            findPreference<SwitchPreferenceCompat>(Prefs.ALARM_ENABLED)?.apply {
                isEnabled = realSpeed
                setSummary(
                    if (realSpeed) R.string.pref_alarm_enabled_summary
                    else R.string.pref_alarm_disabled_by_speed
                )
            }
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
            if (key == Prefs.ALARM_ENABLED || key == Prefs.ALARM_TIME || key == Prefs.TIME_SPEED) {
                AlarmScheduler.update(requireContext())
            }
            if (key == Prefs.TIME_SPEED) updateAlarmAvailability()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                Prefs.TEST_BELLS -> {
                    playTestBells()
                    return true
                }
                Prefs.ALARM_TIME -> {
                    showTimePicker()
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

        private fun showTimePicker() {
            val prefs = preferenceManager.sharedPreferences ?: return
            val stored = prefs.getString(Prefs.ALARM_TIME, AlarmScheduler.DEFAULT_TIME)
                ?: AlarmScheduler.DEFAULT_TIME
            val parts = stored.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 30
            TimePickerDialog(
                requireContext(),
                { _, h, m ->
                    prefs.edit()
                        .putString(Prefs.ALARM_TIME, String.format(java.util.Locale.US, "%02d:%02d", h, m))
                        .apply()
                    updateAlarmTimeSummary()
                },
                hour,
                minute,
                true
            ).show()
        }

        private fun updateAlarmTimeSummary() {
            val stored = preferenceManager.sharedPreferences
                ?.getString(Prefs.ALARM_TIME, AlarmScheduler.DEFAULT_TIME)
            findPreference<Preference>(Prefs.ALARM_TIME)?.summary = stored
        }

        private fun maybeRequestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
