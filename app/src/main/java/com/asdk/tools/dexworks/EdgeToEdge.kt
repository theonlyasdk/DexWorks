package com.asdk.tools.dexworks

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Applies system bar and IME insets as padding on [view].
 *
 * Nine activities repeated this same listener block; consolidating it means an
 * inset-policy change happens in one place instead of nine.
 */
fun ComponentActivity.enableEdgeToEdgeWithPadding(view: View) {
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(view)
}

/**
 * Insets for a top app bar, which must only take the status bar at its top.
 *
 * The general helper pads all four sides, so pointing it at a header gave that
 * header the navigation bar inset at its bottom — a dead band under the search
 * bar on every device with a navigation bar, which grew to the full keyboard
 * height while typing. The bottom of the window belongs to the content below the
 * header, not to the header itself.
 */
fun ComponentActivity.enableEdgeToEdgeWithTopPadding(view: View) {
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, 0)
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
