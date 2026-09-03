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

    companion object {
        /**
         * How far a row that hangs off another one is drawn in.
         *
         * Enough to be seen without being read as a different list — the
         * point is that "Bell style" belongs to "Hourly bells", not that it
         * is somewhere else entirely.
         */
        const val NESTED_INDENT_DP = 24f

        /**
         * How many lines a summary may run to before somebody should have
         * written less.
         *
         * Well above the longest one here, which is eleven — this is not a
         * budget to spend, it is the line past which a settings row has
         * stopped being a settings row. AndroidX's own cap of ten is below
         * what this app already writes, so it had to move; leaving it at
         * no cap at all would mean nothing ever noticed a runaway string.
         */
        const val WHOLE_SUMMARY = 16
    }

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
            nest(parent, children)
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
         * The same, but a row deep: each key is shown only while every
         * switch above it in the list is on.
         *
         * Two [follows] calls cannot do this. `follows(a, b)` then
         * `follows(b, c)` leaves `c` showing whenever `b` is on — even with
         * `a` off and `b` itself hidden, so the page ends with a row
         * indented under nothing. And putting `c` in both calls does not
         * fix it either: the second call decides, and it is the one that
         * does not know about `a`.
         *
         * Each switch still gets exactly one listener, which is the rule
         * this whole mechanism exists to keep.
         */
        protected fun followsChain(vararg keys: String) {
            for (i in 1 until keys.size) nested[keys[i]] = i
            val switches = keys.map { findPreference<SwitchPreferenceCompat>(it) }

            // [changed] is the switch being answered right now: its stored
            // value is still the old one while the listener runs, so it is
            // read from the answer instead.
            fun apply(changed: Int, to: Boolean) {
                var on = true
                for (i in keys.indices) {
                    if (i > 0) findPreference<Preference>(keys[i])?.isVisible = on
                    on = on && (if (i == changed) to else switches[i]?.isChecked == true)
                }
            }
            apply(-1, false)
            for (i in 0 until keys.size - 1) {
                switches[i]?.setOnPreferenceChangeListener { _, newValue ->
                    apply(i, newValue == true)
                    true
                }
            }
        }

        /**
         * The rows that hang off another row, and how deep, so each is
         * drawn that many steps in.
         *
         * Registered by [follows], [followsChain] and [visibleWhen] rather
         * than listed anywhere: hanging off a switch and being drawn as
         * though you do are the same fact, and two lists of it would be
         * two lists to keep in step.
         *
         * A depth rather than a yes-or-no, because a row two deep drawn at
         * one step in is a row that claims to belong to the wrong parent.
         * The comets hang off the solar system, which hangs off the sky
         * token, and level with the solar system they read as a second
         * thing the sky token governs.
         */
        private val nested = HashMap<String, Int>()

        /** How deep the row at [key] hangs, zero if it hangs off nothing. */
        private fun depthOf(key: String): Int = nested[key] ?: 0

        /** Records [children] as hanging one step below [parent]. */
        private fun nest(parent: String, children: Array<out String>) {
            val below = depthOf(parent) + 1
            for (child in children) nested[child] = maxOf(depthOf(child), below)
        }

        /**
         * The step itself.
         *
         * An inset on the row as the list lays it out. Two other ways were
         * tried and rejected: the attribute that reserves room for an icon,
         * which this theme grants to every row already so it changed
         * nothing at all, and a subclass of the preference adapter, which
         * is library-private and rightly refused. This is the public way
         * and the only one of the three that moves anything.
         */
        override fun onCreateRecyclerView(
            inflater: android.view.LayoutInflater,
            parent: android.view.ViewGroup,
            savedInstanceState: Bundle?
        ): androidx.recyclerview.widget.RecyclerView {
            val list = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            val step = (NESTED_INDENT_DP * resources.displayMetrics.density).toInt()
            list.addItemDecoration(object :
                androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect,
                    view: android.view.View,
                    recycler: androidx.recyclerview.widget.RecyclerView,
                    state: androidx.recyclerview.widget.RecyclerView.State
                ) {
                    // By title, because the adapter that could name the row
                    // outright is library-private. Every row's title is its
                    // own on these screens.
                    val title = view.findViewById<android.widget.TextView>(android.R.id.title)
                    outRect.left = step * (nestedTitles()[title?.text?.toString()] ?: 0)
                }
            })
            // And the fading, done as each row arrives rather than once at
            // the start: the rows are recycled, so there is no moment at
            // which they all exist to be painted.
            list.addOnChildAttachStateChangeListener(
                object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: android.view.View) {
                        paintFaded(view)
                        letItFinish(view)
                    }

                    override fun onChildViewDetachedFromWindow(view: android.view.View) = Unit
                }
            )
            return list
        }

        /**
         * Lets a summary run to the end of its last sentence.
         *
         * AndroidX stops one at ten lines and does not ellipsise the
         * eleventh — it clips it, mid-word, behind a scroll bar that a
         * settings list will not let anybody drag. Ten lines is a sensible
         * cap for an app whose summaries are labels. It is the wrong one
         * here, where they are how the app explains itself and several of
         * them are a paragraph on purpose.
         *
         * Found on the row that could least afford it. *Hand shadows*
         * carries an extra sentence while the phone has no location fix,
         * which took it to eleven lines and cut it at "It will use yours
         * once the dial has" — and a phone with no fix yet is a first run,
         * the only time that sentence is ever shown at all.
         *
         * Still a cap, not a licence: [WHOLE_SUMMARY] is far above
         * anything here and [RowFitTest] fails if a row ever reaches it,
         * because a settings row that needs sixteen lines is a bug of a
         * different kind.
         */
        private fun letItFinish(view: android.view.View) {
            view.findViewById<android.widget.TextView>(android.R.id.summary)?.maxLines =
                WHOLE_SUMMARY
        }

        /** What the nested rows are called, and how deep — titles are how
         *  a row is known here, the adapter that could name it outright
         *  being library-private. */
        private fun nestedTitles(): Map<String, Int> =
            nested.mapNotNull { (key, depth) ->
                findPreference<Preference>(key)?.title?.toString()?.let { it to depth }
            }.toMap()

        /**
         * The rows drawn faded because the switch that gives them their
         * effect is off, and which switch that is.
         *
         * Different from [follows] on purpose, and the difference is what
         * the row is for. A row that is hidden until its parent is on keeps
         * the list short, which is right when the parent is on the same
         * screen and one thumb-length away. It is wrong when the parent is
         * two screens away: the bells' settings would simply not be on the
         * advanced page at all, and somebody who came looking for them
         * would conclude the app had lost them.
         *
         * So they stay, faded, and still work. Setting them up with the
         * bells switched off and turning the bells on afterwards is a
         * perfectly ordinary thing to want to do, and a disabled row would
         * refuse it — which is why this fades rather than disabling.
         *
         * And they are *not* drawn a step in, unlike the rows that hang off
         * a switch on their own page. An indent is a claim about the row
         * above: it says "I belong to that one". With the parent two
         * screens away there is no row above to belong to, so the indent
         * pointed at whatever happened to be there — the night hours
         * reading as a refinement of the hand shadows, a whole category
         * shunted right for no reason a reader could see. The fading is
         * what says these are waiting on something; it does not need the
         * step, and the step was saying something false.
         */
        private val dimmedBy = HashMap<String, () -> Boolean>()

        /** How faded a row whose switch is off is drawn. */
        private val fadedAlpha = 0.45f

        /**
         * Draws [children] faded whenever the preference at [parent] is
         * off, wherever that preference lives, without taking them away
         * and without making them unusable.
         */
        protected fun dimmedWhen(parent: String, vararg children: String) {
            for (child in children) {
                dimmedBy[child] = {
                    preferenceManager.sharedPreferences?.getBoolean(parent, false) == true
                }
            }
        }

        /**
         * The same, for a row whose effect depends on something that is not
         * a switch.
         *
         * The weekday is the case that needed it. It is a real question on
         * three of the four alphabets and an impossible one on the fourth:
         * the panel writes its date on a rail of Rome's module, and Rome's
         * module has eight letters in it — none of the Latin day names fit
         * inside I, V, X, L, C, D, M and N. So the row cannot be answered
         * while that script is chosen, and a switch that is drawn, tapped
         * and ignored is the thing this app keeps having to go back and
         * remove.
         */
        protected fun dimmedUnless(child: String, bright: () -> Boolean) {
            dimmedBy[child] = bright
        }


        /** Which faded rows are called what, and what each is waiting on. */
        private fun dimmedTitles(): Map<String, () -> Boolean> =
            dimmedBy.mapNotNull { (key, bright) ->
                findPreference<Preference>(key)?.title?.toString()?.let { it to bright }
            }.toMap()

        /**
         * Fades a row as it comes on screen, or restores it.
         *
         * Both halves matter: the list recycles its rows, so a view that
         * carried a faded row a moment ago will carry an ordinary one next,
         * and a version that only ever faded would leave grey rows
         * scattered down the page.
         */
        private fun paintFaded(view: android.view.View) {
            val title = view.findViewById<android.widget.TextView>(android.R.id.title)
                ?.text?.toString()
            val bright = dimmedTitles()[title]
            if (bright == null) {
                view.alpha = 1f
                return
            }
            view.alpha = if (bright()) 1f else fadedAlpha
        }

        /**
         * Takes off this screen every row the chosen face has no use for.
         *
         * Called by each screen before it does anything else, so the rest
         * of a screen's set-up — the nesting, the fading, the conditional
         * rows — only ever sees rows that are really there. A row removed
         * here is gone rather than hidden: it is not a thing you might want
         * later, it is a question this clock cannot be asked. There is no
         * minute hand on a screenful of digits to switch off.
         *
         * The rule lives in [FaceOptions] and not here, and defaults to
         * "both", so a third face means revisiting one table.
         */
        protected fun keepOnlyFor(face: Face) {
            val screen = preferenceScreen ?: return
            val doomed = ArrayList<Preference>()
            fun walk(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    val row = group.getPreference(i)
                    if (row is androidx.preference.PreferenceGroup) walk(row)
                    val key = row.key
                    if (key != null && !FaceOptions.shows(face, key)) doomed += row
                }
            }
            walk(screen)
            for (row in doomed) row.parent?.removePreference(row)
            // And a category left with nothing in it is a heading over a
            // hole, so it goes too.
            val empty = ArrayList<androidx.preference.PreferenceGroup>()
            for (i in 0 until screen.preferenceCount) {
                val row = screen.getPreference(i)
                if (row is androidx.preference.PreferenceGroup && row.preferenceCount == 0 &&
                    row.title?.isNotEmpty() == true
                ) {
                    empty += row
                }
            }
            for (row in empty) screen.removePreference(row)
            // The rows that ask the same question on both faces answer to
            // different names on each.
            for (i in 0 until screen.preferenceCount) renameFor(face, screen.getPreference(i))
        }

        private fun renameFor(face: Face, row: Preference) {
            if (row is androidx.preference.PreferenceGroup) {
                for (i in 0 until row.preferenceCount) renameFor(face, row.getPreference(i))
            }
            val key = row.key ?: return
            FaceOptions.titleFor(face, key)?.let { row.setTitle(it) }
            FaceOptions.summaryFor(face, key)?.let { row.setSummary(it) }
        }

        /** The face this app is wearing, for the screens to build against. */
        protected fun face(): Face {
            val prefs = preferenceManager.sharedPreferences ?: return Face.ANALOG
            return Face.of(prefs)
        }

        /** For the tests: paints one row as the list would when it arrives. */
        internal fun paintRowForTest(view: android.view.View) = paintFaded(view)

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
            nest(key, children)
            val on = preferenceManager.sharedPreferences?.getBoolean(key, false) == true
            children.forEach { findPreference<Preference>(it)?.isVisible = on }
        }

        /**
         * What the bar at the top says this screen is.
         *
         * Every screen said "Settings", which is true of all three and
         * useful about none: two taps down there is no way to tell the
         * advanced page from the too-advanced one except by recognising a
         * row on it. The default stays for the first screen, where it is
         * the right answer.
         */
        open fun titleRes(): Int = R.string.settings_title

        override fun onStart() {
            super.onStart()
            (activity as? androidx.appcompat.app.AppCompatActivity)
                ?.supportActionBar?.setTitle(titleRes())
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
            keepOnlyFor(face())
            // The day of the week, which one of the numerals cannot write.
            // The panel's date is a rail of Rome's module, Rome's module
            // has eight letters in it, and every Latin day name wants one
            // it has not got — see [CometPanel.rails].
            //
            // Registered here and not beside the numerals it depends on,
            // which is two screens away: the fading is looked up on the
            // screen the *row* is on, so a rule written next to its cause
            // rather than next to its effect never fires at all. It was,
            // for one build.
            dimmedUnless(Prefs.SHOW_WEEKDAY) {
                preferenceManager.sharedPreferences?.let {
                    DigitStyle.of(it) != DigitStyle.COMET
                } ?: true
            }
            // And the alphabet follows the mechanism it is written on.
            //
            // Two of the three can be printed as well as lit — ours and
            // theirs are alphabets, and a flip card can be stamped with
            // either. The Comet's nine cannot: they are the shape nine
            // pieces of metal make when they are lit, and a card with
            // them printed on it would be a photograph of a display, not
            // a card. So the row keeps its two answers on a printed
            // mechanism and gains the third on a lit one, rather than
            // offering a choice that silently changes the mechanism back
            // — which is what it did.
            // The calendar row says out loud when it has been switched on
            // and refused, which is a state somebody can otherwise sit in
            // for weeks wondering why the month page is empty. Read every
            // time the screen is opened, because the answer can change in
            // the system settings while this app is not looking.
            findPreference<SwitchPreferenceCompat>(Prefs.AGENDA)?.let { row ->
                row.summary =
                    if (row.isChecked && !AgendaStore.allowed(requireContext())) {
                        // The refusal *instead of* the ordinary
                        // description, not in front of it. Prepending it
                        // gave a row that said "there is nothing to draw"
                        // and then "Off." under a switch that was visibly
                        // on — see the pictures taken of this row, which
                        // is the only reason anybody saw it.
                        getString(R.string.pref_agenda_denied) + " " +
                            getString(R.string.pref_agenda_promise)
                    } else {
                        getString(R.string.pref_agenda_summary)
                    }
            }
            // Changing the face changes what every screen is *about*, so
            // the screens are built again rather than edited in place.
            // Without this the row above says "digits" while the fifty rows
            // under it go on asking about hands until you back out and come
            // in again.
            findPreference<ListPreference>(Prefs.FACE)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { activity?.recreate() }
                true
            }
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
            follows(Prefs.WORLD_CLOCK, Prefs.WORLD_SECONDS, Prefs.WORLD_CITIES)
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
                Prefs.WORLD_CITIES -> {
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
            findPreference<Preference>(Prefs.WORLD_CITIES)?.summary =
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

        override fun titleRes(): Int = R.string.title_advanced

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
            keepOnlyFor(face())
            // The dial in your hand is the flat one. A wall dial is set on
            // a wall and an equatorial plate is tilted to the equator, and
            // neither is in the plane a phone held level is in, so turning
            // the phone says nothing about either — see [Sundial.pointable].
            dimmedUnless(Prefs.SUNDIAL_COMPASS) {
                Sundial.pointable(
                    Sundial.Kind.entries.firstOrNull {
                        it.key == preferenceManager.sharedPreferences
                            ?.getString(Prefs.SUNDIAL_KIND, null)
                    } ?: Sundial.Kind.HORIZONTAL
                )
            }

            // How a date is written is no question at all while the dial is
            // not showing one, and that switch lives on the first screen.
            visibleWhen(Prefs.SHOW_DATE, Prefs.DATE_FORMAT, Prefs.DATE_ORDER)

            // A shadow has to fall on something, and where the clock is
            // standing decides what the sun does to it: across the face,
            // or at it.
            follows(Prefs.HAND_SHADOWS, Prefs.SHADOW_SURFACE)

            // Breathing is how the blink is done, so it means nothing
            // with the blink off.
            dimmedWhen(Prefs.BLINK_COLON, Prefs.COLON_BREATHES, Prefs.COLON_PERIOD)

            // The rows whose switch is two screens away. Faded rather than
            // hidden, and still usable — see [dimmedWhen]. Setting the bell
            // style up before turning the bells on is an ordinary thing to
            // want, and hiding the row would have refused it while looking
            // like the app had lost the setting.
            dimmedWhen(Prefs.NIGHT_DIM, Prefs.NIGHT_WINDOW)
            dimmedWhen(
                Prefs.BELLS,
                Prefs.BELL_MARKS, Prefs.BELL_STYLE, Prefs.BELLS_BACKGROUND,
                Prefs.BELL_PRIORITY, Prefs.TEST_BELLS
            )
            dimmedWhen(Prefs.ALARM_MARKERS, Prefs.MARK_COLORS)
            dimmedWhen(Prefs.ORRERY, Prefs.MOON_PHASE, Prefs.COMETS, Prefs.ZODIAC)
            // Which calendar the plate is read under is not a question
            // while the plate carries no date. Faded and not hidden,
            // because the switch that governs it is on the first screen
            // and a row that vanishes two screens from its cause reads as
            // a setting the app has lost.
            dimmedWhen(Prefs.SHOW_DATE, Prefs.SUNDIAL_CALENDAR)
            // And two needles with nothing to point at while the
            // network switch is off.
            dimmedWhen(Prefs.WEATHER, Prefs.SUNDIAL_GLASS, Prefs.HEMISPHERE_CLOUDS)
            // Where the sun is nailed means nothing while the phone is
            // doing the pointing. Registered on this screen because that
            // is the screen both rows are on — see [FadedRowTest], which
            // exists because this rule was once written on the wrong one.
            // Everything drawn round the rim is exact on the two flat
            // views and meaningless on the ball, so none of it is drawn
            // there — see [Hemisphere.hasRimScale]. The rows fade rather
            // than vanish: changing the view back is one tap.
            for (key in listOf(
                Prefs.HEMISPHERE_RING, Prefs.HEMISPHERE_NUMBERS,
                Prefs.HEMISPHERE_MOON, Prefs.HEMISPHERE_ALARMS
            )) {
                dimmedUnless(key) {
                    Hemisphere.hasRimScale(
                        Hemisphere.View.entries.firstOrNull {
                            it.key == preferenceManager.sharedPreferences
                                ?.getString(Prefs.HEMISPHERE_VIEW, null)
                        } ?: Hemisphere.View.NORTH
                    )
                }
            }
            dimmedUnless(Prefs.HEMISPHERE_SUN_AT) {
                preferenceManager.sharedPreferences
                    ?.getBoolean(Prefs.HEMISPHERE_COMPASS, false) != true
            }

            // The shadows need a place to put the sun over. Without one
            // they still work, from a middle latitude, and the row says so
            // rather than leaving somebody to wonder why their December
            // shadows are the wrong length.
            DayNight.configure(requireContext())
            if (!DayNight.hasFix()) {
                findPreference<Preference>(Prefs.HAND_SHADOWS)?.let {
                    it.summary = getString(R.string.pref_hand_shadows_summary) + " " +
                        getString(R.string.pref_hand_shadows_nofix)
                }
            }

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

        override fun titleRes(): Int = R.string.title_very_advanced

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.very_advanced_preferences, rootKey)
            keepOnlyFor(face())

            // Both of the second hand's refinements are questions about a
            // hand that may not be there.
            // The tick joins them: it is the sound of the second hand
            // moving, and a dial with no second hand on it has nothing to
            // make that sound.
            follows(
                Prefs.SECOND_HAND,
                Prefs.SMOOTH_SECONDS, Prefs.FAST_HAND, Prefs.TICKING
            )

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
            // Whether there is a key at all, said out loud. An
            // EditTextPreference with a fixed summary looks the same empty
            // as full, and this one is deliberately not in the backup — so
            // the first thing after a restore would otherwise be a switch
            // that says it is on, a row that says nothing, and a house
            // that never hears anything again.
            findPreference<Preference>(Prefs.IFTTT_KEY)?.let { row ->
                val stored = preferenceManager.sharedPreferences
                    ?.getString(Prefs.IFTTT_KEY, null)
                val note = getString(R.string.pref_ifttt_key_summary)
                row.summary = if (Ifttt.usable(stored)) {
                    getString(R.string.pref_ifttt_key_set, note)
                } else {
                    getString(R.string.pref_ifttt_key_none, note)
                }
            }
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
            updateBackupFolderSummary()

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
                    offerRestorePoints()
                    return true
                }
                Prefs.BACKUP_FOLDER -> {
                    folderLauncher.launch(null)
                    return true
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        /**
         * The days there is a backup for, offered before the file picker.
         *
         * The app has been keeping one a day in a folder of its own, and
         * making somebody go and find it in a file browser — among
         * everything else in Documents, by a filename — is asking them to
         * do the work the automatic backup was for. So the days come
         * first, newest at the top, and "a file instead" is the last line
         * for anybody restoring something they saved themselves.
         *
         * With no folder chosen there is nothing to list, and the picker
         * opens as it always did.
         */
        private fun offerRestorePoints() {
            val folder = preferenceManager.sharedPreferences
                ?.getString(Prefs.BACKUP_FOLDER, "").orEmpty()
            val tree = folder.takeIf { it.isNotBlank() }?.let {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    requireContext(), Uri.parse(it)
                )
            }
            val points = tree?.listFiles()?.mapNotNull { it.name }
                ?.let { Backup.pointsIn(it) }
                .orEmpty()
            if (points.isEmpty()) {
                // Anything, not just application/json: file managers and
                // cloud providers hand these back as octet-stream or
                // text/plain often enough that filtering hides the very
                // file the user is looking for.
                importLauncher.launch(arrayOf("*/*"))
                return
            }
            val labels = points.map { dayLabel(it) } + getString(R.string.backup_from_a_file)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_backup_import_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which >= points.size) {
                        importLauncher.launch(arrayOf("*/*"))
                        return@setItems
                    }
                    tree?.findFile(points[which])?.uri?.let { readBackup(it) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** A restore point's day, written the way the phone writes dates. */
        private fun dayLabel(name: String): String {
            val day = Backup.savedOn(name) ?: return name
            val (year, month, dayOfMonth) = CivilDays.dateOf(day)
            val cal = java.util.Calendar.getInstance().apply {
                set(year, month - 1, dayOfMonth, 12, 0, 0)
            }
            return android.text.format.DateFormat.getMediumDateFormat(requireContext())
                .format(cal.time)
        }

        /**
         * The folder the app keeps its own restore points in.
         *
         * A folder rather than a file, and granted once rather than asked
         * for each time: the whole point is that it happens without anybody
         * remembering to do it. Documents is the obvious place and the
         * picker opens there, but it is the user's choice — a backup they
         * cannot find is a backup they do not have.
         */
        private val folderLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) return@registerForActivityResult
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Toast.makeText(
                        requireContext(), R.string.backup_folder_refused, Toast.LENGTH_LONG
                    ).show()
                    return@registerForActivityResult
                }
                preferenceManager.sharedPreferences?.edit()
                    ?.putString(Prefs.BACKUP_FOLDER, uri.toString())
                    // A folder just chosen has nothing in it yet, so the
                    // first restore point is due now rather than tomorrow.
                    ?.putLong(Prefs.BACKUP_AT, 0L)
                    ?.commit()
                Backup.autoSave(requireContext())
                updateBackupFolderSummary()
            }

        /**
         * The row says where the restore points go, or that nobody has
         * said yet.
         *
         * A row reading only "Automatic backups" gives no way to find out
         * where they are, which is the one thing you need to know on the
         * day you want one.
         */
        protected fun updateBackupFolderSummary() {
            val row = findPreference<Preference>(Prefs.BACKUP_FOLDER) ?: return
            val stored = preferenceManager.sharedPreferences
                ?.getString(Prefs.BACKUP_FOLDER, "").orEmpty()
            row.summary = if (stored.isBlank()) {
                getString(R.string.pref_backup_folder_summary)
            } else {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    requireContext(), android.net.Uri.parse(stored)
                )
                getString(
                    R.string.pref_backup_folder_where,
                    tree?.name ?: android.net.Uri.parse(stored).lastPathSegment.orEmpty()
                )
            }
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
