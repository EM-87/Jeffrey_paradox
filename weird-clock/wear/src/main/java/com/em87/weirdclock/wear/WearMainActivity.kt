package com.em87.weirdclock.wear

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * The watch app. Tap the dial to cycle themes, swipe up or down to change
 * the number of hours on it, swipe left or right to change its shape —
 * the weirdness survives the trip to the wrist, and the settings live in
 * the gestures because a watch has no room for a menu.
 */
class WearMainActivity : ComponentActivity() {

    private lateinit var clock: WearClockView
    private lateinit var prefs: android.content.SharedPreferences

    // Sides and the vertex offset that keeps each shape symmetric about
    // the vertical axis, exactly as on the phone.
    private val shapes = listOf(0 to 0f, 3 to 0f, 4 to 45f, 6 to 0f, 8 to 22.5f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("wear_clock", Context.MODE_PRIVATE)
        clock = WearClockView(this)
        setContentView(clock)
        applyPrefs()

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                bump("theme", WearThemes.ALL.size)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    bump("shape", shapes.size, if (dx > 0) 1 else -1)
                } else {
                    val hours = prefs.getInt("hours", 12) + if (dy < 0) 1 else -1
                    prefs.edit().putInt("hours", hours.coerceIn(2, 24)).apply()
                    applyPrefs()
                }
                return true
            }
        })
        clock.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun bump(key: String, size: Int, step: Int = 1) {
        val next = ((prefs.getInt(key, 0) + step) % size + size) % size
        prefs.edit().putInt(key, next).apply()
        applyPrefs()
    }

    private fun applyPrefs() {
        clock.theme = WearThemes.ALL[prefs.getInt("theme", 0).coerceIn(0, WearThemes.ALL.size - 1)]
        val (sides, offset) = shapes[prefs.getInt("shape", 0).coerceIn(0, shapes.size - 1)]
        clock.sides = sides
        clock.vertexOffsetDeg = offset
        clock.hoursOnDial = prefs.getInt("hours", 12)
        clock.romanNumerals = prefs.getBoolean("roman", false)
    }
}
