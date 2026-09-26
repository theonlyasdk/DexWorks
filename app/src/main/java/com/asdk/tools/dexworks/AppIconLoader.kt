package com.asdk.tools.dexworks

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Loads app icons on demand instead of decoding one for every installed app while
 * the list is being built.
 *
 * Eagerly decoding every icon is the single most expensive part of loading the
 * browse list: each call forces the framework to open and parse that app's
 * resources, and a typical device has well over a hundred installed packages while
 * only the handful of rows actually on screen are ever visible. Loading lazily and
 * caching means only the rows the user actually scrolls past pay the cost, once.
 */
class AppIconLoader(private val maxEntries: Int = 96) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LruCache<String, Drawable>(maxEntries)
    private val inFlight = HashSet<String>()

    /**
     * Shows the placeholder immediately, then swaps in the real icon once it has
     * been decoded. The view is tagged with the package it is waiting for so a
     * recycled row can never be given another app's icon.
     */
    fun load(packageName: String, imageView: ImageView) {
        imageView.setTag(R.id.tag_app_icon_package, packageName)

        cache.get(packageName)?.let {
            imageView.setImageDrawable(it)
            return
        }

        imageView.setImageResource(R.drawable.ic_app_placeholder)

        synchronized(inFlight) {
            if (!inFlight.add(packageName)) return
        }

        // The application context avoids holding the Activity alive, and the view
        // is held weakly so a scrolled-away row can be collected mid-load.
        val appContext = imageView.context.applicationContext
        val viewRef = WeakReference(imageView)

        scope.launch {
            val drawable = decodeIcon(appContext, packageName)
            synchronized(inFlight) { inFlight.remove(packageName) }
            if (drawable == null) return@launch

            cache.put(packageName, drawable)
            withContext(Dispatchers.Main) {
                val view = viewRef.get() ?: return@withContext
                if (view.getTag(R.id.tag_app_icon_package) == packageName) {
                    view.setImageDrawable(drawable)
                }
            }
        }
    }

    fun clear() {
        cache.evictAll()
        synchronized(inFlight) { inFlight.clear() }
    }

    private fun decodeIcon(context: Context, packageName: String): Drawable? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            appInfo.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
    }
}
