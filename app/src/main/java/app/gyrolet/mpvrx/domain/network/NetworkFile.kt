/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import androidx.compose.runtime.Immutable

/**
 * Represents a file or directory on a network share
 */
@Immutable
data class NetworkFile(
  val name: String,
  val path: String,
  val size: Long,
  val isDirectory: Boolean,
  val lastModified: Long = 0,
  val mimeType: String? = null,
  val durationMs: Long = 0,
  val width: Int = 0,
  val height: Int = 0,
  val videoCount: Int? = null,
  val folderScanComplete: Boolean = false,
  val fps: Float = 0f,
  val videoCodec: String = "",
  val hasEmbeddedSubtitles: Boolean = false,
  val subtitleCodec: String = "",
)
