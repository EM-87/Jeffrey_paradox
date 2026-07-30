package com.em87.weirdclock

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        SystemChrome.paint(this)
        // The toolbar carries the status bar on its shoulders; the list
        // below it keeps clear of the gesture bar.
        SystemChrome.padForBars(findViewById(R.id.settings_root), bottom = false)
        SystemChrome.padForBars(findViewById(R.id.settings_container), top = false)

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

        /** Taps so far on the version row. Seven opens the hidden metronome. */
        private var versionTaps = 0

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            updateCitiesSummary()
            // The panic button only appears when something is actually
            // lying at the bottom of the dial.
            findPreference<Preference>(Prefs.REASSEMBLE)?.isVisible =
                preferenceManager.sharedPreferences
                    ?.getBoolean(Prefs.NEEDS_REASSEMBLY, false) == true
            // Installed version, so it's always clear which build is running.
            findPreference<Preference>("pref_version")?.summary = try {
                val info = requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0)
                "v${info.versionName}"
            } catch (e: Exception) {
                ""
            }
        }

        override fun onPause() {
            // Background bells re-arm whenever their settings change.
            BellScheduler.update(requireContext())
            super.onPause()
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
                "pref_world_cities" -> {
                    showCitiesDialog()
                    return true
                }
                "pref_version" -> {
                    // In the finest Android tradition: seven taps on the
                    // version number and you're a drummer now.
                    versionTaps++
                    when {
                        versionTaps >= 7 -> {
                            versionTaps = 0
                            Toast.makeText(
                                requireContext(), R.string.egg_unlocked, Toast.LENGTH_SHORT
                            ).show()
                            startActivity(Intent(requireContext(), BpmActivity::class.java))
                        }
                        versionTaps >= 4 -> Toast.makeText(
                            requireContext(),
                            getString(R.string.egg_countdown, 7 - versionTaps),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onDestroy() {
            chimePlayer.release()
            super.onDestroy()
        }

        // -------------------------------------------------- world cities

        private fun cityName(tzId: String): String =
            tzId.substringAfterLast('/').replace('_', ' ')

        private fun currentCities(): MutableList<String> {
            val prefs = preferenceManager.sharedPreferences ?: return mutableListOf()
            val set = prefs.getStringSet(Prefs.WORLD_TZS, null)
                ?: setOfNotNull(prefs.getString(Prefs.WORLD_TZ, "UTC"))
            return set.toMutableList().apply { sort() }
        }

        private fun saveCities(cities: Collection<String>) {
            preferenceManager.sharedPreferences?.edit()
                ?.putStringSet(Prefs.WORLD_TZS, HashSet(cities))
                ?.apply()
            updateCitiesSummary()
        }

        private fun updateCitiesSummary() {
            findPreference<Preference>("pref_world_cities")?.summary =
                currentCities().joinToString(" · ") { cityName(it) }
                    .ifBlank { getString(R.string.world_add_city) }
        }

        /** Current cities (tap one to remove it) plus the add entry. */
        private fun showCitiesDialog() {
            val cities = currentCities()
            val items = (
                cities.map { "✕  ${cityName(it)}" } + getString(R.string.world_add_city)
                ).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_world_cities_title)
                .setItems(items) { _, which ->
                    if (which < cities.size) {
                        cities.removeAt(which)
                        saveCities(cities)
                        showCitiesDialog()
                    } else {
                        showAddCityDialog()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Type-ahead over every city the timezone database knows. */
        private fun showAddCityDialog() {
            val ids = java.util.TimeZone.getAvailableIDs().filter {
                it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/")
            }
            val names = ids.map { cityName(it) }
            val input = android.widget.AutoCompleteTextView(requireContext()).apply {
                hint = getString(R.string.world_city_hint)
                threshold = 1
                setAdapter(
                    android.widget.ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        names.distinct().sorted()
                    )
                )
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val wrapper = android.widget.FrameLayout(requireContext()).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.world_add_city)
                .setView(wrapper)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val typed = input.text.toString().trim()
                    val idx = names.indexOfFirst { it.equals(typed, ignoreCase = true) }
                    val cities = currentCities()
                    when {
                        idx < 0 -> Toast.makeText(
                            requireContext(), R.string.world_city_not_found, Toast.LENGTH_SHORT
                        ).show()
                        cities.size >= 6 -> Toast.makeText(
                            requireContext(), R.string.world_bubble_limit, Toast.LENGTH_SHORT
                        ).show()
                        else -> {
                            cities.add(ids[idx])
                            saveCities(cities)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

    /** Second layer: fun, harmless, and out of the average user's way. */
    class AdvancedSettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
            // The floating hourglass draws over other apps, and Android only
            // lets it once the user has said so somewhere we cannot ask.
            findPreference<androidx.preference.ListPreference>(Prefs.COUNTDOWN_FLOAT)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == Prefs.FLOAT_OVERLAY &&
                        !Settings.canDrawOverlays(requireContext())
                    ) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${requireContext().packageName}")
                            )
                        )
                    }
                    true
                }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "pref_very_advanced") {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, VeryAdvancedSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }
    }

    /** Third layer: the machinery that can make the clock lie. */
    class VeryAdvancedSettingsFragment :
        PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.very_advanced_preferences, rootKey)

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

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "pref_system_time") {
                // Android forbids apps from setting the clock, so this hands
                // over to the system's own date & time screen, where the
                // network resync toggle and the manual setting both live.
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                return true
            }
            return super.onPreferenceTreeClick(preference)
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
