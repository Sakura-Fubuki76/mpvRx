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
import android.net.Uri
import android.util.Log
import com.yubyf.truetypeparser.TTFFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies font files from the selected directory to the app's internal storage. */
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
          val pending = File(destinationDir, ".${target.name}.tmp")
          try {
            context.contentResolver.openInputStream(node.document.uri)?.use { input ->
              pending.outputStream().use { input.copyTo(it) }
              java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
              copied++
            }
          } finally { pending.delete() }
        }
      })
    File(context.filesDir, "font-sources.json").delete()
    copied
  }.onFailure { Log.e("SubtitlesPreferences", "Error copying fonts", it) }.getOrDefault(0)
}

// getSimplifiedPathFromUri is defined in AdvancedPreferencesScreen.kt within this package.

/** Represents a custom font discovered in the app's internal fonts directory. */
data class CustomFontEntry(
  val familyName: String,
  val file: File,
)

/**
 * Loads custom fonts (family name + file) from the app's internal fonts directory for previews.
 * Returns one entry per family name (first match kept if duplicates exist).
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
