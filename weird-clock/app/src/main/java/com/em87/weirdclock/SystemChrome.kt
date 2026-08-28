package com.em87.weirdclock

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The app's half of the deal with the system bars.
 *
 * The status and navigation bars are not ours to paint, but the space behind
 * them is: draw the app's own background all the way to the edges of the
 * screen and the bars stop being furniture and become part of the page. That
 * is the whole trick behind an app that looks full-screen while still showing
 * the clock and the battery.
 *
 * Two things have to be right for it to work. The window must be told to stop
 * fitting its content inside the bars — which Android 15 now does whether an
 * app asked for it or not, so an app that ignores this ends up with its own
 * headings underneath the clock — and the bar icons must be told which way to
 * contrast, or they vanish into a background of their own colour.
 */
object SystemChrome {

    /** Lets the background run under the bars and picks the icon contrast. */
    fun paint(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Up to Android 14 the bars have a colour of their own, and it has to
        // be cleared or it sits on top of the background as a solid strip.
        // From 15 these are ignored: the bars are transparent by decree.
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // No automatic scrim behind the gesture bar: we want the sand and
            // the dial to reach the very bottom of the glass.
            window.isNavigationBarContrastEnforced = false
        }
        val lightBackground = !isNight(activity)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }

    /**
     * Takes the status and navigation bars away, or gives them back.
     *
     * For the bedside clock — see [Bedside] — and nothing else. The clock
     * on its side is the whole screen, and the last two things on it that
     * are not the clock are the notification shade's row of icons and the
     * gesture bar. They come back on a swipe from the edge, which is the
     * behaviour anybody with a phone already knows, and they come back for
     * good the moment the phone is turned upright again.
     */
    fun bars(activity: Activity, hidden: Boolean) {
        val window = activity.window
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Keeps [view]'s content clear of the bars while its background stays
     * behind them — padding moves the content, never the paint.
     */
    fun padForBars(view: View, top: Boolean = true, bottom: Boolean = true) {
        val padLeft = view.paddingLeft
        val padTop = view.paddingTop
        val padRight = view.paddingRight
        val padBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(
                padLeft + bars.left,
                padTop + if (top) bars.top else 0,
                padRight + bars.right,
                padBottom + if (bottom) bars.bottom else 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun isNight(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}
