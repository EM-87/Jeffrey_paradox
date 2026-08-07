package com.em87.weirdclock

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

        /**
         * The backup file, written and read wherever the user keeps files.
         *
         * Through the document picker rather than to a folder of our own:
         * it needs no permission, works on every version the app supports,
         * and — the point of the whole exercise — lands somewhere that
         * survives the app being uninstalled.
         */
        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) writeBackup(uri) }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) readBackup(uri) }

        private fun writeBackup(uri: Uri) {
            val ok = try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(Backup.export(requireContext()).toByteArray())
                } != null
            } catch (e: java.io.IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            Toast.makeText(
                requireContext(),
                if (ok) R.string.backup_saved else R.string.backup_failed,
                Toast.LENGTH_SHORT
            ).show()
        }

        private fun readBackup(uri: Uri) {
            val text = try {
                requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: java.io.IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            val restored = text?.let { Backup.import(requireContext(), it) }
            if (restored == null) {
                Toast.makeText(requireContext(), R.string.backup_unreadable, Toast.LENGTH_LONG)
                    .show()
                return
            }
            // Every alarm in the restored file needs its place in the
            // system's alarm queue back; the queue is not ours and knew
            // nothing about the restore.
            AlarmScheduler.update(requireContext())
            Toast.makeText(
                requireContext(),
                getString(R.string.backup_restored, restored.alarms, restored.reminders),
                Toast.LENGTH_LONG
            ).show()
            // The settings screen is now showing values that no longer
            // exist, and so is the clock behind it. Start again from the
            // restored file rather than write the stale ones back over it.
            requireActivity().finishAffinity()
        }

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
                "pref_backup_export" -> {
                    exportLauncher.launch("weird-clock-backup.json")
                    return true
                }
                "pref_backup_import" -> {
                    // Anything, not just application/json: file managers and
                    // cloud providers hand these back as octet-stream or
                    // text/plain often enough that filtering hides the very
                    // file the user is looking for.
                    importLauncher.launch(arrayOf("*/*"))
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
            updateBirthdaySummary()
            // Marks by the sun need somewhere to stand. Without a fix the
            // app falls back to the dial's two turns — which is the honest
            // thing to do and, until now, entirely silent: the setting said
            // "by the sun" and the dots carried on saying morning and
            // evening, with nothing anywhere to explain why.
            findPreference<ListPreference>(Prefs.MARK_COLORS)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == DayNight.MARKS_SUN &&
                        DayNight.wantsLocation(requireContext())
                    ) {
                        Toast.makeText(
                            requireContext(),
                            R.string.mark_colors_needs_location,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }
        }

        /**
         * The birthday row reads back the date it holds, because a settings
         * row that says only "Birthday" gives no way to check what the app
         * thinks yours is.
         */
        private fun updateBirthdaySummary() {
            val pref = findPreference<Preference>(Prefs.BIRTHDAY) ?: return
            val stored = preferenceManager.sharedPreferences?.getInt(Prefs.BIRTHDAY, 0) ?: 0
            pref.summary = if (stored == 0) {
                getString(R.string.pref_birthday_summary)
            } else {
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.MONTH, stored / 100 - 1)
                    set(java.util.Calendar.DAY_OF_MONTH, stored % 100)
                }
                java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
                    .format(cal.time)
            }
        }

        /**
         * Month and day only: a birthday repeats, so the year it first
         * happened is not what the calendar needs — and asking for it makes
         * the field feel like an identity form rather than a date to mark.
         */
        private fun showBirthdayDialog() {
            val stored = preferenceManager.sharedPreferences?.getInt(Prefs.BIRTHDAY, 0) ?: 0
            val cal = java.util.Calendar.getInstance()
            if (stored != 0) {
                cal.set(java.util.Calendar.MONTH, stored / 100 - 1)
                cal.set(java.util.Calendar.DAY_OF_MONTH, stored % 100)
            }
            // A leap year, so 29 February can be picked at all.
            val picker = android.app.DatePickerDialog(
                requireContext(),
                { _, _, month, day ->
                    preferenceManager.sharedPreferences?.edit()
                        ?.putInt(Prefs.BIRTHDAY, (month + 1) * 100 + day)?.apply()
                    updateBirthdaySummary()
                },
                2024, cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            picker.setButton(
                android.app.DatePickerDialog.BUTTON_NEUTRAL,
                getString(R.string.pref_birthday_clear)
            ) { _, _ ->
                preferenceManager.sharedPreferences?.edit()?.remove(Prefs.BIRTHDAY)?.apply()
                updateBirthdaySummary()
            }
            picker.show()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == Prefs.BIRTHDAY) {
                showBirthdayDialog()
                return true
            }
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
