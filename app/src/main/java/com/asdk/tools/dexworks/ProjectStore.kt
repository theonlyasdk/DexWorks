package com.asdk.tools.dexworks

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProjectIconOption(
    val key: String,
    val labelRes: Int,
    val iconRes: Int
)

object ProjectIconCatalog {
    const val DEFAULT_KEY = "folder_open"

    private val options = listOf(
        ProjectIconOption("folder_open", R.string.project_icon_folder, R.drawable.ic_folder_open),
        ProjectIconOption("folder", R.string.project_icon_folder_filled, R.drawable.ic_folder_filled),
        ProjectIconOption("app", R.string.project_icon_app, R.drawable.ic_nav_browse),
        ProjectIconOption("decompile", R.string.project_icon_decompile, R.drawable.ic_tool_decompile),
        ProjectIconOption("disassemble", R.string.project_icon_disassemble, R.drawable.ic_tool_disassemble),
        ProjectIconOption("extract", R.string.project_icon_extract, R.drawable.ic_tool_extract),
        ProjectIconOption("manifest", R.string.project_icon_manifest, R.drawable.ic_tool_manifest),
        ProjectIconOption("security", R.string.project_icon_security, R.drawable.ic_shield),
        ProjectIconOption("widgets", R.string.project_icon_widgets, R.drawable.ic_widgets),
        ProjectIconOption("settings", R.string.project_icon_settings, R.drawable.ic_settings),
        ProjectIconOption("file", R.string.project_icon_file, R.drawable.ic_file_open),
        ProjectIconOption("agenda", R.string.project_icon_agenda, android.R.drawable.ic_menu_agenda),
        ProjectIconOption("add", R.string.project_icon_add, android.R.drawable.ic_menu_add),
        ProjectIconOption("landscape", R.string.project_icon_landscape, android.R.drawable.ic_menu_always_landscape_portrait),
        ProjectIconOption("call", R.string.project_icon_call, android.R.drawable.ic_menu_call),
        ProjectIconOption("camera", R.string.project_icon_camera, android.R.drawable.ic_menu_camera),
        ProjectIconOption("close", R.string.project_icon_close, android.R.drawable.ic_menu_close_clear_cancel),
        ProjectIconOption("compass", R.string.project_icon_compass, android.R.drawable.ic_menu_compass),
        ProjectIconOption("crop", R.string.project_icon_crop, android.R.drawable.ic_menu_crop),
        ProjectIconOption("day", R.string.project_icon_day, android.R.drawable.ic_menu_day),
        ProjectIconOption("delete", R.string.project_icon_delete, android.R.drawable.ic_menu_delete),
        ProjectIconOption("directions", R.string.project_icon_directions, android.R.drawable.ic_menu_directions),
        ProjectIconOption("edit", R.string.project_icon_edit, android.R.drawable.ic_menu_edit),
        ProjectIconOption("gallery", R.string.project_icon_gallery, android.R.drawable.ic_menu_gallery),
        ProjectIconOption("help", R.string.project_icon_help, android.R.drawable.ic_menu_help),
        ProjectIconOption("info", R.string.project_icon_info, android.R.drawable.ic_menu_info_details),
        ProjectIconOption("manage", R.string.project_icon_manage, android.R.drawable.ic_menu_manage),
        ProjectIconOption("map", R.string.project_icon_map, android.R.drawable.ic_menu_mapmode),
        ProjectIconOption("month", R.string.project_icon_month, android.R.drawable.ic_menu_month),
        ProjectIconOption("more", R.string.project_icon_more, android.R.drawable.ic_menu_more),
        ProjectIconOption("calendar", R.string.project_icon_calendar, android.R.drawable.ic_menu_my_calendar),
        ProjectIconOption("location", R.string.project_icon_location, android.R.drawable.ic_menu_mylocation),
        ProjectIconOption("places", R.string.project_icon_places, android.R.drawable.ic_menu_myplaces),
        ProjectIconOption("people", R.string.project_icon_people, R.drawable.ic_nav_projects),
        ProjectIconOption("play", R.string.project_icon_play, R.drawable.ic_tool_decompile),
        ProjectIconOption("preferences", R.string.project_icon_preferences, android.R.drawable.ic_menu_preferences),
        ProjectIconOption("history", R.string.project_icon_history, android.R.drawable.ic_menu_recent_history),
        ProjectIconOption("image", R.string.project_icon_image, android.R.drawable.ic_menu_report_image),
        ProjectIconOption("revert", R.string.project_icon_revert, android.R.drawable.ic_menu_revert),
        ProjectIconOption("rotate", R.string.project_icon_rotate, android.R.drawable.ic_menu_rotate),
        ProjectIconOption("save", R.string.project_icon_save, android.R.drawable.ic_menu_save),
        ProjectIconOption("search", R.string.project_icon_search, android.R.drawable.ic_menu_search),
        ProjectIconOption("send", R.string.project_icon_send, android.R.drawable.ic_menu_send),
        ProjectIconOption("settings_gear", R.string.project_icon_settings_gear, R.drawable.ic_settings),
        ProjectIconOption("share", R.string.project_icon_share, android.R.drawable.ic_menu_share),
        ProjectIconOption("slideshow", R.string.project_icon_slideshow, android.R.drawable.ic_menu_slideshow),
        ProjectIconOption("sort_alpha", R.string.project_icon_sort_alpha, android.R.drawable.ic_menu_sort_alphabetically),
        ProjectIconOption("sort_size", R.string.project_icon_sort_size, android.R.drawable.ic_menu_sort_by_size),
        ProjectIconOption("today", R.string.project_icon_today, android.R.drawable.ic_menu_today),
        ProjectIconOption("upload_video", R.string.project_icon_upload_video, android.R.drawable.ic_menu_upload_you_tube),
        ProjectIconOption("view", R.string.project_icon_view, android.R.drawable.ic_menu_view),
        ProjectIconOption("week", R.string.project_icon_week, android.R.drawable.ic_menu_week),
        ProjectIconOption("zoom", R.string.project_icon_zoom, android.R.drawable.ic_menu_zoom)
    )

    fun all(): List<ProjectIconOption> = options

    fun find(key: String): ProjectIconOption? = options.firstOrNull { it.key == key }

    fun iconRes(key: String): Int = find(key)?.iconRes ?: R.drawable.ic_folder_open
}

object ProjectStore {
    private const val PROJECTS_DIRECTORY = "projects"
    private const val PROJECTS_FILE = "projects.json"
    private const val APK_FILE_NAME = "project.apk"

    @Volatile
    private var cachedProjects: List<ProjectItem>? = null

    fun preload(context: Context) {
        loadProjects(context, forceReload = true)
    }

    fun getCachedProjects(): List<ProjectItem>? = cachedProjects

    /**
     * Finds an existing project that already points at the same APK, so an import
     * can offer to open it instead of silently creating a duplicate.
     */
    fun findByApkPath(context: Context, apkPath: String): ProjectItem? {
        if (apkPath.isBlank()) return null
        val target = try {
            File(apkPath).canonicalPath
        } catch (e: Exception) {
            apkPath
        }
        val projects = loadProjects(context)
        return projects.firstOrNull { project ->
            val candidate = project.apkPath
                ?: File(project.path, APK_FILE_NAME).takeIf { it.isFile }?.absolutePath
            candidate != null && runCatching { File(candidate).canonicalPath }.getOrNull() == target
        }
    }

    fun findByName(context: Context, name: String): ProjectItem? {
        if (name.isBlank()) return null
        return loadProjects(context).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun loadProjects(context: Context, forceReload: Boolean = false): List<ProjectItem> {
        if (!forceReload && cachedProjects != null) {
            return cachedProjects!!
        }

        val file = File(context.filesDir, PROJECTS_FILE)
        if (!file.exists()) {
            cachedProjects = emptyList()
            return emptyList()
        }

        return try {
            val array = JSONArray(file.readText())
            val projects = mutableListOf<ProjectItem>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val path = item.optString("path").trim()
                if (name.isBlank() || path.isBlank()) continue
                val apkPath = item.optString("apkPath").takeIf { it.isNotBlank() }
                val resolvedApkPath = apkPath
                    ?: File(path, APK_FILE_NAME).takeIf { it.isFile }?.absolutePath
                    ?: path.takeIf { File(it).isFile && it.endsWith(".apk", ignoreCase = true) }

                projects += ProjectItem(
                    name = name,
                    path = path,
                    lastModified = item.optLong("lastModified", 0L),
                    apkPath = resolvedApkPath,
                    iconKey = item.optString("iconKey", ProjectIconCatalog.DEFAULT_KEY),
                    useApkIcon = item.optBoolean("useApkIcon", resolvedApkPath != null)
                )
            }
            cachedProjects = projects
            projects
        } catch (e: Exception) {
            cachedProjects = emptyList()
            emptyList()
        }
    }

    fun createProject(
        context: Context,
        name: String,
        iconKey: String,
        useApkIcon: Boolean,
        apkUri: Uri?
    ): ProjectItem? {
        val projectsRoot = File(context.filesDir, PROJECTS_DIRECTORY)
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) return null

        val safeName = name.trim()
            .replace(SanitizedNames.UNSAFE_CHARS, "_")
            .ifBlank { "Project" }
        var projectDirectory = File(projectsRoot, safeName)
        var suffix = 2
        while (projectDirectory.exists()) {
            projectDirectory = File(projectsRoot, "${safeName}_$suffix")
            suffix++
        }
        if (!projectDirectory.mkdirs()) return null

        var apkPath: String? = null
        return try {
            if (apkUri != null) {
                val targetFile = File(projectDirectory, APK_FILE_NAME)
                val input = context.contentResolver.openInputStream(apkUri)
                if (input == null) {
                    projectDirectory.deleteRecursively()
                    return null
                }
                input.use { source ->
                    targetFile.outputStream().use { target ->
                        source.copyTo(target)
                    }
                }
                apkPath = targetFile.absolutePath
            }

            val project = ProjectItem(
                name = name.trim(),
                path = projectDirectory.absolutePath,
                lastModified = projectDirectory.lastModified(),
                apkPath = apkPath,
                iconKey = iconKey,
                useApkIcon = useApkIcon && apkPath != null
            )
            val projects = loadProjects(context)
                .filterNot { it.path == project.path }
                .plus(project)
            if (!saveProjects(context, projects)) {
                projectDirectory.deleteRecursively()
                return null
            }
            project
        } catch (e: Exception) {
            projectDirectory.deleteRecursively()
            null
        }
    }

    /**
     * Deletes a project: removes its directory if it lives inside the projects
     * folder, then drops its entry. A legacy path pointing at an APK outside the
     * projects folder only loses its entry, never the file itself.
     * Callers must invoke this off the main thread.
     */
    fun deleteProject(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val target = File(path)
            val root = File(context.filesDir, PROJECTS_DIRECTORY)
            val inside = try {
                target.canonicalPath == root.canonicalPath ||
                    target.canonicalPath.startsWith(root.canonicalPath + File.separator)
            } catch (e: Exception) {
                false
            }
            if (inside) {
                target.deleteRecursively()
            }
            val remaining = loadProjects(context).filterNot { it.path == path }
            saveProjects(context, remaining)
        } catch (e: Exception) {
            false
        }
    }

    private fun saveProjects(context: Context, projects: List<ProjectItem>): Boolean {
        return try {
            val array = JSONArray()
            projects.forEach { project ->
                array.put(
                    JSONObject().apply {
                        put("name", project.name)
                        put("path", project.path)
                        put("lastModified", project.lastModified)
                        put("apkPath", project.apkPath.orEmpty())
                        put("iconKey", project.iconKey)
                        put("useApkIcon", project.useApkIcon)
                    }
                )
            }
            File(context.filesDir, PROJECTS_FILE).writeText(array.toString())
            cachedProjects = projects
            true
        } catch (e: Exception) {
            false
        }
    }
}
