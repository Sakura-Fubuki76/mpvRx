/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.fonts.GoogleFontsRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.utils.media.listTreeFilesSafely
import app.gyrolet.mpvrx.utils.media.openPersistedTreeDocument
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import `is`.xyz.mpv.Utils
import java.io.File

/**
 * Everything libmpv needs on disk before it will initialize: the bundled assets, the user's MPV
 * directory (config, scripts, script-modules, shaders, fonts) and the subtitle font directory.
 *
 * libmpv parses mpv.conf and loads Lua scripts *during* [is.xyz.mpv.MPVLib.init], so this has to
 * complete first or the core starts with whatever happens to be on disk. That used to make it
 * PlayerActivity's private startup work, which meant a core warm-up could only happen once an
 * Activity already existed — and the Activity could not initialize the core without this. This
 * object is the shared seam that breaks that cycle: [PlaybackCorePrewarmer] prepares the assets and
 * initializes the core from the application context long before any Activity is on screen.
 *
 * Everything here is idempotent and cheap on a warm cache, so both callers may run it.
 */
object PlaybackStartupAssets : KoinComponent {
  private const val TAG = "PlaybackStartupAssets"
  private const val MPV_ASSET_SYNC_PREFERENCES = "mpv_asset_sync"
  private const val USER_MPV_ASSET_SELECTION = "user_mpv_asset_selection_v1"

  /**
   * Serializes the whole tree copy. [prepare] runs on the prewarmer's worker while PlayerActivity
   * runs it again from its own IO coroutine, and two concurrent writers to the same scripts/ and
   * fonts/ trees would leave half-copied files behind.
   */
  private val assetLock = Any()

  private val advancedPreferences: AdvancedPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val mpvConfigCache: MpvConfigCache by inject()
  private val googleFontsRepository: GoogleFontsRepository by inject()

  // The installed version cannot change while the process lives, so it is resolved once.
  @Volatile private var cachedLongVersionCode: Long? = null

  private var appContext: Context? = null

  /** The bound application context. Every entry point below resolves through here. */
  private val context: Context get() = requireAppContext()
  private val filesDir: File get() = context.filesDir
  private val contentResolver: ContentResolver get() = context.contentResolver

  @Volatile private var cachedAssetSyncPreferences: android.content.SharedPreferences? = null

  private val assetSyncPreferences: android.content.SharedPreferences
    get() =
      cachedAssetSyncPreferences
        ?: context
          .getSharedPreferences(MPV_ASSET_SYNC_PREFERENCES, Context.MODE_PRIVATE)
          .also { cachedAssetSyncPreferences = it }

  /**
   * Binds this object to an application context without doing any work.
   *
   * [prepare] is what normally does this, but PlayerActivity reaches for the individual sync
   * helpers directly (to remove scripts a preference just disabled, to refresh the user's directory
   * after startup) and must not depend on [prepare] having run first in this process.
   */
  fun attach(context: Context) {
    appContext = context.applicationContext
  }

  private fun requireAppContext(): Context =
    appContext ?: error("PlaybackStartupAssets.attach() must be called before using it")

  /**
   * Prepares the assets for a core that is about to initialize on [context]'s behalf, and reports
   * how long it took.
   *
   * Safe to call concurrently with itself and safe to call on an already-warm cache: in the common
   * case (the prewarmer ran at app launch) this is four cheap directory probes.
   */
  fun prepare(context: Context) {
    attach(context)
    val startedAt = SystemClock.elapsedRealtime()
    synchronized(assetLock) {
      syncBundledAssetsIfNeeded()
      prepareUserMpvAssetsForStartup()
      googleFontsRepository.syncMpvFonts()
      sanitizeInternalFontsDirectory()
    }
    Log.d(TAG, "MPV startup assets ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
  }

  /**
   * Whether a full user-directory sync has already run in this process.
   *
   * [prepareUserMpvAssetsForStartup] does the expensive SAF tree walk when the cached selection
   * does not match the preferences. When it does, PlayerActivity has no reason to schedule its
   * deferred re-sync afterwards — it would only repeat work that already happened.
   *
   * Process state rather than Activity state on purpose: the walk may now be performed by
   * [PlaybackCorePrewarmer] at app launch, before any Activity exists, and the Activity's own
   * [prepare] call will then find the cache already current and skip it. Consulting this instead of
   * an Activity-scoped flag is what keeps the prewarm from causing a redundant second walk.
   */
  @Volatile
  var userMpvAssetsFullySynced: Boolean = false
    private set

  private fun prepareUserMpvAssetsForStartup() {
    app.gyrolet.mpvrx.domain.fonts.SubtitleFontCache.migrateLegacyBank(context)
    ensureConfigCacheForStartup()
    val syncPreferences = assetSyncPreferences
    val currentSelection = currentUserMpvAssetSelection()
    val storedSelection = syncPreferences.getString(USER_MPV_ASSET_SELECTION, null)
    val cacheReady = hasLaunchReadyUserMpvAssetCache()
    val canAdoptExistingCache =
      storedSelection == null &&
        cacheReady &&
        cachedConfigsMatchPreferences() &&
        cachedScriptsMatchSelection()

    if (cacheReady && cachedScriptsMatchSelection() && (storedSelection == currentSelection || canAdoptExistingCache)) {
      if (canAdoptExistingCache) rememberUserMpvAssetSelection(syncPreferences)
      Log.d(TAG, "Using cached MPV user assets for startup")
      return
    }

    syncFromUserMpvDirectory()
    rememberUserMpvAssetSelection(syncPreferences)
    userMpvAssetsFullySynced = true
  }

  private fun ensureConfigCacheForStartup() {
    mpvConfigCache.ensureCurrent()
    writeTextFileIfChanged(File(filesDir, "input.conf"), advancedPreferences.inputConf.get())
  }

  private fun currentUserMpvAssetSelection(): String {
    val selectedScripts = advancedPreferences.selectedLuaScripts.get().sorted().joinToString("\u0000")
    val mpvConfig = advancedPreferences.mpvConf.get()
    val inputConfig = advancedPreferences.inputConf.get()
    return buildString {
      append("v1|uri=")
      append(advancedPreferences.mpvConfStorageUri.get())
      append("|lua=")
      append(advancedPreferences.enableLuaScripts.get())
      append("|scripts=")
      append(selectedScripts)
      append("|mpv=")
      append(mpvConfig.length)
      append(':')
      append(mpvConfig.hashCode())
      append("|input=")
      append(inputConfig.length)
      append(':')
      append(inputConfig.hashCode())
    }
  }

  private fun hasLaunchReadyUserMpvAssetCache(): Boolean =
    File(filesDir, "mpv.conf").isFile &&
      File(filesDir, "input.conf").isFile &&
      File(filesDir, "scripts").isDirectory &&
      File(filesDir, "script-modules").isDirectory &&
      File(filesDir, "shaders").isDirectory &&
      File(filesDir, "font-bank").isDirectory

  private fun cachedConfigsMatchPreferences(): Boolean =
    cachedConfigMatchesPreference("mpv.conf", advancedPreferences.mpvConf.get()) &&
      cachedConfigMatchesPreference("input.conf", advancedPreferences.inputConf.get())

  private fun cachedConfigMatchesPreference(
    fileName: String,
    preferenceContent: String,
  ): Boolean =
    runCatching { File(filesDir, fileName).readText() == preferenceContent }.getOrDefault(false)

  private fun cachedScriptsMatchSelection(): Boolean {
    val cachedScripts =
      File(filesDir, "scripts")
        .listFiles()
        ?.asSequence()
        ?.filter { file -> file.isFile && file.extension.lowercase() in setOf("lua", "js") }
        ?.map(File::getName)
        ?.toSet()
        .orEmpty()
    return if (advancedPreferences.enableLuaScripts.get()) {
      cachedScripts == advancedPreferences.selectedLuaScripts.get()
    } else {
      cachedScripts.isEmpty()
    }
  }

  internal fun rememberUserMpvAssetSelection() {
    rememberUserMpvAssetSelection(assetSyncPreferences)
  }

  internal fun rememberUserMpvAssetSelection(syncPreferences: android.content.SharedPreferences) {
    syncPreferences.edit().putString(USER_MPV_ASSET_SELECTION, currentUserMpvAssetSelection()).apply()
  }
  internal fun syncFromUserMpvDirectory() {
    synchronized(assetLock) {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree =
      if (mpvConfStorageUri.isNotBlank()) {
        openPersistedTreeDocument(context, mpvConfStorageUri)
      } else {
        null
      }

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      val rootChildren = listTreeFilesSafely(tree)
      syncConfigFiles(tree, rootChildren)
      syncScripts(tree, rootChildren)
      syncScriptOpts(tree, rootChildren)
      syncShaders(tree, rootChildren)
      syncFonts(tree, rootChildren)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: use preferences-based config (no user directory set)
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyMPVConfigFromPreferences()
    }
    removeDisabledCachedScripts()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName, rootChildren)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            when (configName) {
              "mpv.conf" -> mpvConfigCache.update(content)
              "input.conf" -> {
                writeTextFileIfChanged(File(filesDir, configName), content)
                advancedPreferences.inputConf.set(content)
              }
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent =
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.get()
              "input.conf" -> advancedPreferences.inputConf.get()
              else -> ""
            }
          if (configName == MpvConfigCache.FILE_NAME) {
            mpvConfigCache.ensureCurrent()
          } else {
            writeTextFileIfChanged(File(filesDir, configName), prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Scripts Sync ====================

  /**
   * Syncs all script files (.lua, .js) from the user's MPV directory.
   * Looks in scripts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncScripts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptsDir = File(filesDir, "scripts")
    internalScriptsDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalScriptsDir)
      Log.d(TAG, "Scripts disabled, skipping")
      return
    }

    val scriptsSubdir = findSubdirCaseInsensitive(tree, "scripts", rootChildren)
    val sourceDir = scriptsSubdir ?: tree
    val scriptExtensions = setOf("lua", "js")
    val selectedScripts = advancedPreferences.selectedLuaScripts.get()
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalScriptsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in scriptExtensions },
        allowedNames = selectedScripts,
        deleteMissing = true,
      )
    val supportCount = syncScriptSupportDirectories(scriptsSubdir)

    Log.d(
      TAG,
      "Scripts sync: $count file(s), $supportCount helper file(s) from " +
        "${if (scriptsSubdir != null) "scripts/" else "root"}",
    )
  }

  /**
   * Syncs helper folders from scripts/ and mirrors Lua modules into mpv's internal
   * script-modules path so require() works without exposing a separate user folder.
   */
  private fun syncScriptSupportDirectories(scriptsSubdir: DocumentFile?): Int {
    val internalScriptsDir = File(filesDir, "scripts")
    val internalModulesDir = File(filesDir, "script-modules")
    internalModulesDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalModulesDir)
      return 0
    }

    clearDirectoryContents(internalModulesDir)

    var copiedCount = 0

    if (scriptsSubdir != null) {
      listTreeFilesSafely(scriptsSubdir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        if (!document.isDirectory) return@forEach

        copiedCount +=
          syncRecursiveDocumentDirectory(
            sourceDir = document,
            destinationDir = File(internalScriptsDir, name),
            includeFile = { true },
            deleteMissing = true,
          )

        copiedCount +=
          syncRecursiveDocumentDirectory(
            sourceDir = document,
            destinationDir = File(internalModulesDir, name),
            includeFile = { fileName -> fileName.endsWith(".lua", ignoreCase = true) },
            deleteMissing = true,
          )
      }
    }

    return copiedCount
  }

  // ==================== Script Options Sync ====================

  /**
   * Syncs all files from script-opts/ subfolder (case-insensitive).
   */
  private fun syncScriptOpts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptOptsDir = File(filesDir, "script-opts")
    internalScriptOptsDir.mkdirs()

    val scriptOptsSubdir = findSubdirCaseInsensitive(tree, "script-opts", rootChildren)
    if (scriptOptsSubdir == null) {
      Log.d(TAG, "No script-opts/ subfolder found, skipping")
      return
    }

    val count =
      syncFlatDocumentDirectory(
        sourceDir = scriptOptsSubdir,
        destinationDir = internalScriptOptsDir,
        includeFile = { true },
        deleteMissing = true,
      )

    Log.d(TAG, "Script-opts sync: $count file(s)")
  }

  // ==================== Shaders Sync ====================

  /**
   * Syncs shader files (.glsl, .hook, .comp) from the user's MPV directory.
   * Looks in shaders/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncShaders(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val shadersDir = File(filesDir, "shaders")
    shadersDir.mkdirs()

    val shadersSubdir = findSubdirCaseInsensitive(tree, "shaders", rootChildren)
    val sourceDir = shadersSubdir ?: tree
    val shaderExtensions = setOf("glsl", "hook", "comp")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = shadersDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in shaderExtensions },
        protectedNames = Anime4KManager.BUILT_IN_SHADER_FILES,
        deleteMissing = true,
      )

    Log.d(TAG, "Shaders sync: $count file(s)")
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncFonts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalFontsDir = File(filesDir, "font-bank")
    internalFontsDir.mkdirs()
    internalFontsDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts", rootChildren)
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalFontsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in fontExtensions },
        deleteMissing = false,
      )

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  private fun syncBundledAssetsIfNeeded() {
    val syncPrefs = assetSyncPreferences
    val currentVersion = longVersionCode()

    val assetsAlreadyPrepared =
      File(filesDir, "mpv.conf").exists() &&
        File(filesDir, "input.conf").exists() &&
        File(filesDir, "scripts").exists()

    if (assetsAlreadyPrepared && syncPrefs.getLong("bundled_assets_version", -1L) == currentVersion) {
      return
    }

    Utils.copyAssets(context)
    syncPrefs.edit().putLong("bundled_assets_version", currentVersion).apply()
  }

  /** The installed version cannot change while the process lives, so it is resolved once. */
  private fun longVersionCode(): Long =
    cachedLongVersionCode
      ?: runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
      }.getOrDefault(-1L).also { cachedLongVersionCode = it }
  internal fun syncSubtitleFontsFromPreferenceFolder() {
    val sourceDir = resolveSubtitleFontSourceDirectory() ?: return

    val destinationDir = File(filesDir, "font-bank")
    destinationDir.mkdirs()
    destinationDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
    syncFontDirectory(sourceDir, destinationDir)
  }

  private fun resolveSubtitleFontSourceDirectory(): DocumentFile? {
    val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
    if (fontsFolderUri.isBlank()) return null

    val sourceDir = openPersistedTreeDocument(context, fontsFolderUri) ?: return null

    // Older builds auto-pointed the subtitle font folder at the whole storage/config root.
    // Use its fonts/ child instead so playback never recursively scans a large media folder.
    if (fontsFolderUri == advancedPreferences.mpvConfStorageUri.get()) {
      return findSubdirCaseInsensitive(sourceDir, "fonts")
    }

    return sourceDir
  }

  private fun syncFontDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
  ): Int {
    destinationDir.mkdirs()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      val name = document.name ?: return@forEach
      when {
        document.isDirectory -> {
          copiedCount += syncFontDirectory(document, destinationDir)
        }
        document.isFile -> {
          val extension = name.substringAfterLast('.', "").lowercase()
          if (extension !in setOf("ttf", "otf", "ttc", "woff", "woff2")) {
            return@forEach
          }

          if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
            copiedCount++
          }
        }
      }
    }

    return copiedCount
  }

  private fun syncRecursiveDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedFiles = mutableSetOf<String>()
    val expectedDirs = mutableSetOf<String>()
    var copiedCount = 0

    fun syncDirectory(
      currentSourceDir: DocumentFile,
      currentDestinationDir: File,
      relativeDir: String,
    ) {
      currentDestinationDir.mkdirs()
      listTreeFilesSafely(currentSourceDir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        val relativePath = if (relativeDir.isBlank()) name else "$relativeDir/$name"

        when {
          document.isDirectory -> {
            expectedDirs += relativePath
            syncDirectory(
              currentSourceDir = document,
              currentDestinationDir = File(currentDestinationDir, name),
              relativeDir = relativePath,
            )
          }
          document.isFile && includeFile(name) -> {
            expectedFiles += relativePath
            if (copyDocumentToFileIfNeeded(document, File(currentDestinationDir, name))) {
              copiedCount++
            }
          }
        }
      }
    }

    syncDirectory(sourceDir, destinationDir, relativeDir = "")

    if (deleteMissing) {
      pruneDirectoryToExpected(destinationDir, expectedFiles, expectedDirs, relativeDir = "")
    }

    return copiedCount
  }

  private fun pruneDirectoryToExpected(
    directory: File,
    expectedFiles: Set<String>,
    expectedDirs: Set<String>,
    relativeDir: String,
  ) {
    directory.listFiles()?.forEach { existingFile ->
      val relativePath =
        if (relativeDir.isBlank()) {
          existingFile.name
        } else {
          "$relativeDir/${existingFile.name}"
        }

      when {
        existingFile.isDirectory -> {
          pruneDirectoryToExpected(existingFile, expectedFiles, expectedDirs, relativePath)
          val isExpected = relativePath in expectedDirs
          val isEmpty = existingFile.listFiles()?.isEmpty() != false
          if (!isExpected || isEmpty) {
            existingFile.deleteRecursively()
          }
        }
        existingFile.isFile && relativePath !in expectedFiles -> existingFile.delete()
      }
    }
  }

  private fun syncFlatDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    allowedNames: Set<String>? = null,
    protectedNames: Set<String> = emptySet(),
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedNames = mutableSetOf<String>()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      if (!document.isFile) return@forEach
      val name = document.name ?: return@forEach
      if (!includeFile(name)) return@forEach
      if (allowedNames != null && name !in allowedNames) return@forEach

      expectedNames += name
      if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
        copiedCount++
      }
    }

    if (deleteMissing) {
      destinationDir.listFiles()?.forEach { existingFile ->
        if (existingFile.isFile &&
          existingFile.name !in expectedNames &&
          existingFile.name !in protectedNames
        ) {
          existingFile.delete()
        }
      }
    }

    return copiedCount
  }

  private fun copyDocumentToFileIfNeeded(
    source: DocumentFile,
    target: File,
  ): Boolean {
    val sourceLength = source.length()
    val sourceLastModified = source.lastModified()

    if (target.exists() &&
      sourceLength >= 0L &&
      target.length() == sourceLength &&
      sourceLastModified > 0L &&
      target.lastModified() == sourceLastModified
    ) {
      return false
    }

    target.parentFile?.mkdirs()
    contentResolver.openInputStream(source.uri)?.use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    } ?: return false

    if (sourceLastModified > 0L) {
      target.setLastModified(sourceLastModified)
    }
    return true
  }

  private fun writeTextFileIfChanged(
    target: File,
    content: String,
  ) {
    if (target.exists() && runCatching { target.readText() }.getOrNull() == content) {
      return
    }

    target.parentFile?.mkdirs()
    target.writeText(content)
  }
  internal fun removeDisabledCachedScripts() {
    val enabled = advancedPreferences.enableLuaScripts.get()
    val selected = if (enabled) advancedPreferences.selectedLuaScripts.get() else emptySet()
    File(filesDir, "scripts").listFiles()?.forEach { file ->
      if (!enabled || file.isFile && file.extension.lowercase() in setOf("lua", "js") && file.name !in selected) {
        check(file.deleteRecursively()) { "Could not remove cached script ${file.name}" }
      }
    }
    if (!enabled) clearDirectoryContents(File(filesDir, "script-modules"))
  }

  // ==================== Helpers ====================

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      mpvConfigCache.ensureCurrent()
      writeTextFileIfChanged(File(filesDir, "input.conf"), advancedPreferences.inputConf.get())
      // Ensure scripts directory exists even without user dir
      File(filesDir, "scripts").mkdirs()
      File(filesDir, "script-modules").mkdirs()
      File(filesDir, "font-bank").mkdirs()
      File(filesDir, "shaders").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  private fun sanitizeInternalFontsDirectory() {
    val fontsDir = File(filesDir, "font-bank")
    if (!fontsDir.exists()) {
      return
    }

    fontsDir.listFiles()?.filter { it.isDirectory }?.forEach { nestedDir ->
      nestedDir.deleteRecursively()
    }
  }

  private fun clearDirectoryContents(directory: File) {
    directory.listFiles()?.forEach { child ->
      if (child.isDirectory) {
        child.deleteRecursively()
      } else {
        child.delete()
      }
    }
  }

  private fun isSafeDocumentFileName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && !name.contains('\\')

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }
}
