package app.gyrolet.mpvrx.ui.preferences

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingViewModel
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import me.zhanghai.compose.preference.Preference
import org.koin.compose.koinInject

@Composable
internal fun CloudFolderPreferences() {
  val preferences = koinInject<BrowserPreferences>()
  val cloudMode by preferences.cloudLibraryMode.collectAsState()
  val stored by preferences.cloudStorageSelection.collectAsState()
  val selected = stored.split(',').mapNotNull(String::toLongOrNull).toSet()
  val context = LocalContext.current
  val model: NetworkStreamingViewModel = viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as Application))
  val connections by model.connections.collectAsState()
  val storages = connections.filter { it.protocol in setOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST) }
  val stack = LocalBackStack.current
  Column {
    PreferenceSectionHeader(title = stringResource(R.string.cloud_library_source))
    PreferenceCard {
      SwitchPreference(value = cloudMode, onValueChange = preferences.cloudLibraryMode::set,
        title = { Text(stringResource(R.string.cloud_library_mode)) },
        summary = { Text(stringResource(if (cloudMode) R.string.cloud_library_mode_cloud else R.string.cloud_library_mode_local)) })
      storages.forEach { storage ->
        PreferenceDivider()
        val checked = selected.isEmpty() || storage.id in selected
        fun toggle() {
          val current = if (selected.isEmpty()) storages.map { it.id }.toSet() else selected
          val next = if (storage.id in current) current - storage.id else current + storage.id
          preferences.cloudStorageSelection.set(if (next.isEmpty()) "-1" else next.joinToString(","))
        }
        ServerPreferenceItem(title = { Text(storage.name) }, summary = { Text(storage.protocol.displayName) },
          icon = { Checkbox(checked = checked, onCheckedChange = { toggle() }) }, trailing = {}, onClick = { toggle() })
      }
      PreferenceDivider()
      Preference(title = { Text(stringResource(R.string.pref_media_servers_title)) },
        onClick = { stack.navigateTo(MediaServersPreferencesScreen) })
    }
  }
}
