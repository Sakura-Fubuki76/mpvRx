/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

@Serializable
object NetworkConfigurationPreferencesScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    if (!BuildConfig.ENABLE_YTDLP && !BuildConfig.ENABLE_TORRENT) {
      // Old saved navigation stacks may still contain this former settings destination.
      PreferencesScreen.Content()
      return
    }
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val enableP2pStreaming by preferences.enableP2pStreaming.collectAsState()
    val tmdbToken by preferences.tmdbArtworkToken.collectAsState()
    val tmdbApiKey by preferences.tmdbArtworkApiKey.collectAsState()

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.ui_network),
          onBack = { backStack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(NetworkConfigurationPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          contentPadding = settingsContentPadding(PaddingValues(bottom = 24.dp)),
          modifier =
            Modifier
              .fillMaxSize()
              .tvContentFocus()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          item(key = "tmdb_header") {
          PreferenceSectionHeader(title = stringResource(R.string.tmdb_artwork_title))
          }
          item(key = "tmdb_settings") {
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
          }
          if (BuildConfig.ENABLE_YTDLP) {
            item(key = "ytdlp") { YtdlpManagerSections() }
          }

          if (BuildConfig.ENABLE_TORRENT) {
            item(key = "p2p_header") {
              PreferenceSectionHeader(
                position = if (BuildConfig.ENABLE_YTDLP) PreferenceSectionPosition.Middle else PreferenceSectionPosition.First,
                title = stringResource(R.string.pref_section_p2p_streaming),
                modifier = Modifier.settingsSearchTarget(R.string.pref_section_p2p_streaming),
              )
            }
            item(key = "p2p_settings") {
              PreferenceCard {
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_enable_p2p_streaming_title),
                  value = enableP2pStreaming,
                  onValueChange = preferences.enableP2pStreaming::set,
                  title = { Text(stringResource(R.string.pref_enable_p2p_streaming_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_enable_p2p_streaming_summary),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}
