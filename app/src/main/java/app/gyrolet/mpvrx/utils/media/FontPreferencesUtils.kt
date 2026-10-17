/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import com.yubyf.truetypeparser.TTFFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Index local fonts using private symlinks; copy only sources requiring provider access. */
@SuppressLint("UseKtx")
fun copyFontsFromDirectory(context: Context, uriString: String): Int {
  return runCatching {
    val destinationDir = File(context.filesDir, "font-bank").apply { mkdirs() }
    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(uriString))
      ?: return@runCatching 0
    data class Node(val document: androidx.documentfile.provider.DocumentFile, val relative: String)
    var copied = 0
    app.gyrolet.mpvrx.domain.fonts.walkFontTree(Node(root, ""), { it.document.uri.toString() },
      { it.document.isDirectory }, { node ->
        node.document.listFiles().mapNotNull { child -> child.name?.let { name ->
          Node(child, if (node.relative.isEmpty()) name else "${node.relative}/$name")
        } }
      }, { node ->
        if (app.gyrolet.mpvrx.domain.fonts.isFontFile(node.document.name.orEmpty())) {
          val target = File(destinationDir, app.gyrolet.mpvrx.domain.fonts.fontStorageName(node.relative))
          if (app.gyrolet.mpvrx.domain.fonts.FontDocumentStorage.store(context, node.document.uri,
              target, node.document.length(), node.document.lastModified())) copied++
        }
      })
    app.gyrolet.mpvrx.domain.fonts.FontDocumentStorage.relinkActive(context)
    File(context.filesDir, "font-sources.json").delete()
    copied
  }.onFailure { Log.e("SubtitlesPreferences", "Error copying fonts", it) }.getOrDefault(0)
}

// getSimplifiedPathFromUri is defined in AdvancedPreferencesScreen.kt within this package.

/** A physical font file, not a deduplicated family-name entry. */
data class CustomFontEntry(
  val familyName: String,
  val file: File,
)

/**
 * mpv selects fonts by family name, but users still need to see every imported file.
 * Give files that share a family a distinct, human-readable label without changing
 * the family name sent to mpv.
 */
data class SubtitleFontChoice(
  val label: String,
  val familyName: String,
)

fun subtitleFontChoices(
  downloadedFamilies: List<String>,
  customFonts: List<CustomFontEntry>,
): List<SubtitleFontChoice> {
  val downloaded = downloadedFamilies.distinct().toSet()
  val customFamilyCounts = customFonts.groupingBy { it.familyName }.eachCount()
  return (
    downloaded.map { family -> SubtitleFontChoice(label = family, familyName = family) } +
      customFonts.map { entry ->
        val nameConflict = (customFamilyCounts[entry.familyName] ?: 0) > 1 || entry.familyName in downloaded
        SubtitleFontChoice(
          label = if (nameConflict) "${entry.familyName} (${entry.file.name})" else entry.familyName,
          familyName = entry.familyName,
        )
      }
    ).distinctBy { it.label }
    .sortedBy { it.label.lowercase(Locale.ROOT) }
}

/**
 * Loads one entry for every valid custom font file, including files with matching
 * internal family names. A failed name-table parse must not silently discard a
 * font that Android's font engine can open.
 */
suspend fun loadCustomFontEntries(context: Context): List<CustomFontEntry> =
  withContext(Dispatchers.IO) {
    val fontsDir = File(context.filesDir, "font-bank")
    if (!fontsDir.exists()) return@withContext emptyList()

    val fontFiles = app.gyrolet.mpvrx.domain.fonts.fontBankFiles(fontsDir)

    val entries = mutableListOf<CustomFontEntry>()
    val seenFamilies = mutableSetOf<String>()

    for (fontFile in fontFiles) {
      val families = app.gyrolet.mpvrx.domain.fonts.FontNameReader.familyNames(fontFile)
        .ifEmpty {
          if (fontFile.extension.lowercase() !in setOf("woff", "woff2")) return@ifEmpty emptySet()
          runCatching { fontFile.inputStream().use { TTFFile.open(it).families.values.toSet() } }
            .getOrDefault(emptySet())
        }
      for (familyName in families) {
        if (familyName.isNotBlank() && seenFamilies.add(familyName)) {
          entries += CustomFontEntry(familyName, fontFile)
        }
      }
    }

    entries.sortedBy { it.familyName }
  }
