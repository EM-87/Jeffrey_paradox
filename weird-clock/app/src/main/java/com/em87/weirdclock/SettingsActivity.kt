package com.em87.weirdclock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
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

    class SettingsFragment : PreferenceFragmentCompat() {

        private val chimePlayer = ChimePlayer()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                Prefs.TEST_BELLS -> {
                    chimePlayer.playBellSequence(3, pairGrouping = false)
                    return true
                }
                Prefs.SET_ALARM -> {
                    try {
                        startActivity(Intent(AlarmClock.ACTION_SET_ALARM))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.no_alarm_app, Toast.LENGTH_SHORT).show()
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
    }
}
