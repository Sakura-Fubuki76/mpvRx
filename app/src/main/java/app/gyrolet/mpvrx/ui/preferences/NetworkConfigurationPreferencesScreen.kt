/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

@Serializable
object NetworkConfigurationPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val enableP2pStreaming by preferences.enableP2pStreaming.collectAsState()
    val tmdbToken by preferences.tmdbArtworkToken.collectAsState()
    val tmdbApiKey by preferences.tmdbArtworkApiKey.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.ui_network),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backStack.popSafely() }) {
                Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
              }
            }
          },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val scrollState = rememberScrollState()
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .verticalScroll(scrollState)
              .padding(bottom = MaterialTheme.spacing.extraLarge),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          PreferenceSectionHeader(title = stringResource(R.string.tmdb_artwork_title))
          PreferenceCard {
            val tmdbFields:
              List<Triple<String, app.gyrolet.mpvrx.preferences.preference.Preference<String>, Int>> =
              listOf(
                Triple(tmdbToken, preferences.tmdbArtworkToken, R.string.tmdb_artwork_token),
                Triple(tmdbApiKey, preferences.tmdbArtworkApiKey, R.string.tmdb_artwork_key),
              )
            for ((value, preference, label) in tmdbFields) TextFieldPreference(
              value = value, onValueChange = { preference.set(it.trim()) }, textToValue = { it },
              title = { Text(stringResource(label)) },
              summary = { Text(stringResource(if (value.isBlank()) R.string.tmdb_artwork_unset else R.string.tmdb_artwork_saved)) },
              textField = { text, onChange, _ -> androidx.compose.material3.TextField(
                value = text, onValueChange = onChange, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              ) },
            )
            Text(stringResource(R.string.tmdb_artwork_summary), Modifier.padding(16.dp),
              style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          // Streaming tools live directly on Network. YT-DLP intentionally comes first,
          // followed by P2P, matching the order in which users choose a stream source.
          if (app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP) YtdlpManagerSections()

          if (!app.gyrolet.mpvrx.BuildConfig.IS_PLAY_STORE_BUILD) {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_p2p_streaming))
            ExpressivePreferenceGroup {
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_enable_p2p_streaming_title),
                value = enableP2pStreaming,
                onValueChange = preferences.enableP2pStreaming::set,
                title = { Text(stringResource(R.string.pref_enable_p2p_streaming_title)) },
                summary = { Text(stringResource(R.string.pref_enable_p2p_streaming_summary), color = MaterialTheme.colorScheme.outline) },
                groupIndex = 0,
                groupSize = 1,
              )
            }
          }
        }
      }
    }
  }
}
