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


    /**
     * A preference screen, with the two things every one of them needs.
     *
     * [follows] is the conditional-row rule: a row whose answer only means
     * something once another row is switched on is not shown until it is.
     * [go] is the way down, which every screen now uses to reach every
     * other, because the screens are a fan and no longer a ladder.
     */
    abstract class Screen : PreferenceFragmentCompat() {

        /**
         * Shows [children] only while the switch at [parent] is on.
         *
         * All of them in one call, and not one call each, because a
         * preference has room for exactly one change listener: asking twice
         * about the same switch quietly threw the first answer away. Which
         * is what happened — the second hand had two refinements under it,
         * and turning the hand off hid the second one and left the first
         * sitting there smoothing a hand that was no longer drawn.
         */
        protected fun follows(parent: String, vararg children: String) {
            val rows = children.mapNotNull { findPreference<Preference>(it) }
            val on = findPreference<SwitchPreferenceCompat>(parent)
            fun apply(visible: Boolean) { rows.forEach { it.isVisible = visible } }
            apply(on?.isChecked == true)
            on?.setOnPreferenceChangeListener { _, newValue ->
                apply(newValue == true)
                true
            }
        }

        /**
         * Shows [children] only while the preference [key] is on, wherever
         * that preference lives.
         *
         * The sibling version above needs both rows on one screen, because
         * that is all `android:dependency` and a change listener can see.
         * This one reads the stored value instead, for the case where the
         * switch and the question it governs belong on different screens —
         * whether the dial shows a date at all is something you decide once
         * and forget, and how it is written is a decision for the screen
         * where the rest of the dial's spelling lives.
         */
        protected fun visibleWhen(key: String, vararg children: String) {
            val on = preferenceManager.sharedPreferences?.getBoolean(key, false) == true
            children.forEach { findPreference<Preference>(it)?.isVisible = on }
        }

        protected fun go(screen: PreferenceFragmentCompat) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settings_container, screen)
                .addToBackStack(null)
                .commit()
        }

        /**
         * Month and day only: a birthday repeats, so the year it first
         * happened is not what the calendar needs — and asking for it makes
         * the field feel like an identity form rather than a date to mark.
         */
        protected fun showBirthdayDialog() {
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

        /**
         * The birthday row reads back the date it holds, because a settings
         * row that says only "Birthday" gives no way to check what the app
         * thinks yours is.
         */
        protected fun updateBirthdaySummary() {
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
    }

    /** The simple menu: only what the average user comes looking for. */
    class RootSettingsFragment : Screen() {

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
            updateBirthdaySummary()
            // A row whose answer only matters once another row is on does
            // not appear until it is — and appears straight away when it
            // does, rather than on the next visit to this screen. Which is
            // most of what keeps this list short enough to read.
            //
            // Hiding rather than grâce-and-grey, deliberately: a disabled
            // row still costs a line of scrolling and still has to be read
            // past to find out it is not the one you want.
            follows(Prefs.NIGHT_DIM, Prefs.NIGHT_WINDOW)
            follows(
                Prefs.BELLS,
                Prefs.BELL_MARKS, Prefs.BELL_STYLE, Prefs.TEST_BELLS, Prefs.BELLS_BACKGROUND
            )
            follows(Prefs.WORLD_CLOCK, "pref_world_cities")
            follows(Prefs.MOON_PHASE, Prefs.ORRERY)
            // Installed version, so it's always clear which build is running.
            // What the *system* thinks is armed, read back from
             // AlarmManager rather than from our own list.
            //
            // Its whole reason for existing is a question nobody could
            // answer from here: the little clock in the status bar is drawn
            // by Android whenever some app has an alarm clock registered,
            // and when it does not appear there is no way to tell from
            // inside the app whether the registration failed or the phone
            // simply is not drawing it. This says which.
            findPreference<Preference>("pref_armed")?.summary = run {
                val manager = requireContext()
                    .getSystemService(android.app.AlarmManager::class.java)
                val next = manager?.nextAlarmClock
                if (next == null) {
                    getString(R.string.pref_armed_none)
                } else {
                    val at = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                    ).format(java.util.Date(next.triggerTime))
                    getString(R.string.pref_armed_at, at)
                }
            }
            // And what the tick has actually been doing. A tick heard to
            // skip and never measured is an argument rather than a bug;
            // these three numbers say which half is at fault — lateness is
            // the scheduler's, a refusal is the audio system's, and a beat
            // that never ran is neither.
            findPreference<Preference>("pref_tick_precision")?.summary = run {
                val beat = MainActivity.lastTickRecord
                if (beat == null || beat.played == 0L) {
                    getString(R.string.pref_tick_precision_none)
                } else {
                    getString(
                        R.string.pref_tick_precision_at,
                        beat.played, beat.worstLagMs, beat.lost, beat.refused
                    )
                }
            }
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
                Prefs.ADVANCED -> {
                    go(AdvancedSettingsFragment())
                    return true
                }
                // Both deeper screens hang off this one now. They used to be
                // a ladder, and coming back to the clock from the far end
                // meant pressing Back three times to see what you had done.
                "pref_very_advanced" -> {
                    go(VeryAdvancedSettingsFragment())
                    return true
                }
                Prefs.BIRTHDAY -> {
                    showBirthdayDialog()
                    return true
                }
                Prefs.CYCLE -> {
                    CycleSheet(requireActivity()) { }.show()
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

        /**
         * Plays a sample of whatever bell style is currently selected.
         *
         * This was the third copy of the striking rule, and the one most
         * likely to drift: adding a style meant remembering that the button
         * which exists to let you hear it also needed telling, or it would
         * cheerfully play the previous style instead.
         */
        private fun playTestBells() {
            val prefs = preferenceManager.sharedPreferences ?: return
            chimePlayer.play(Bells.sample(prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT)))
        }
    }

    /** The things you set once and then live with. */
    class AdvancedSettingsFragment : Screen() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
            // How a date is written is no question at all while the dial is
            // not showing one, and that switch lives on the first screen.
            visibleWhen(Prefs.SHOW_DATE, Prefs.DATE_FORMAT, Prefs.DATE_ORDER)

            // How many hours the dial carries is a list, and "some other
            // number" is one of its answers; the slider that asks which is
            // no question at all until then.
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
        }
    }

    /**
     * The machinery: what answers to a finger, what can make the clock lie,
     * and the two ways your data leaves the phone.
     */
    class VeryAdvancedSettingsFragment :
        Screen(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) writeBackup(uri) }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) readBackup(uri) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.very_advanced_preferences, rootKey)

            // Both of the second hand's refinements are questions about a
            // hand that may not be there.
            follows(Prefs.SECOND_HAND, Prefs.SMOOTH_SECONDS, Prefs.FAST_HAND)

            findPreference<SeekBarPreference>(Prefs.TIME_SPEED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue != 100) showSpeedWarning()
                    true
                }

            // The floating hourglass draws over other apps, and Android only
            // lets it once the user has said so somewhere we cannot ask.
            findPreference<ListPreference>(Prefs.COUNTDOWN_FLOAT)
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

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "pref_system_time" -> {
                    // Android forbids apps from setting the clock, so this
                    // hands over to the system's own date & time screen,
                    // where the network resync toggle and the manual
                    // setting both live.
                    startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
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
            }
            return super.onPreferenceTreeClick(preference)
        }

        /**
         * The backup file, written and read wherever the user keeps files.
         *
         * Through the document picker rather than to a folder of our own:
         * it needs no permission, works on every version the app supports,
         * and — the point of the whole exercise — lands somewhere that
         * survives the app being uninstalled.
         */
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
