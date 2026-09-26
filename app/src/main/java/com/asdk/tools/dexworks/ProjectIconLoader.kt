package com.asdk.tools.dexworks

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/**
 * Loads a project's APK icon on demand, keyed by the APK path.
 *
 * Resolving every project's icon up front meant parsing each project's APK before
 * the list could be shown, and any project whose icon failed to decode silently
 * kept its generic catalogue icon forever. Loading per visible row means each row
 * resolves its own icon, and the cache makes revisits instant.
 */
class ProjectIconLoader(maxEntries: Int = 48) {

    // Decodes run on a 2-parallel dispatcher, not the shared IO pool. Each decode
    // parses an APK manifest plus its resource table, so letting every visible
    // row parse at once saturated the CPU and stalled the swipe animation and
    // the project-open transition. Icons now pop in progressively instead.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val cache = LruCache<String, Drawable>(maxEntries)
    private val inFlight = HashSet<String>()

    fun load(apkPath: String?, imageView: ImageView, placeholderIconRes: Int) {
        if (apkPath.isNullOrBlank()) {
            imageView.setImageResource(placeholderIconRes)
            return
        }
        imageView.setTag(R.id.tag_app_icon_package, apkPath)

        cache.get(apkPath)?.let {
            imageView.setImageTintList(null)
            imageView.setImageDrawable(it)
            return
        }
        imageView.setImageResource(placeholderIconRes)

        synchronized(inFlight) {
            if (!inFlight.add(apkPath)) return
        }

        val appContext = imageView.context.applicationContext
        val viewRef = WeakReference(imageView)

        scope.launch {
            // The existence check lives here on IO. It used to sit at the top of
            // load(), which put a file stat on the main thread for every bind.
            if (!File(apkPath).isFile) {
                synchronized(inFlight) { inFlight.remove(apkPath) }
                return@launch
            }
            val drawable = decode(appContext, apkPath)
            synchronized(inFlight) { inFlight.remove(apkPath) }
            if (drawable != null) {
                cache.put(apkPath, drawable)
                imageView.post {
                    val view = viewRef.get() ?: return@post
                    if (view.getTag(R.id.tag_app_icon_package) == apkPath) {
                        view.setImageTintList(null)
                        view.setImageDrawable(drawable)
                    }
                }
            }
        }
    }

    fun clear() {
        cache.evictAll()
        synchronized(inFlight) { inFlight.clear() }
    }

    private fun decode(context: Context, apkPath: String): Drawable? {
        val info = AppInfoUtils.getPackageArchiveInfo(context, apkPath, fullComponents = false)
            ?: return null
        val appInfo = info.applicationInfo ?: return null
        val pm = context.packageManager

        // 1) Standard shortcut — works for most APKs, but can fail on adaptive-icon APKs
        //    because AdaptiveIconDrawable construction may throw when the archive isn't installed.
        val viaShortcut = try {
            appInfo.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
        if (viaShortcut != null) return viaShortcut

        // 2) Resolve the raw drawable resource through a Resources object built for the APK.
        //    This bypasses AdaptiveIconDrawable construction and works for uninstalled archives.
        val viaResources = try {
            if (appInfo.icon != 0) {
                val resources = pm.getResourcesForApplication(appInfo)
                resources.getDrawable(appInfo.icon, null)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        if (viaResources != null) return viaResources

        // 3) Last resort: ask PackageManager for the application icon directly.
        //    This can sometimes succeed when the above paths fail (e.g., legacy icon resources).
        return try {
            pm.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }
    }
}

