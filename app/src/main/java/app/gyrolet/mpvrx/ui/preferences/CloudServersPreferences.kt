package app.gyrolet.mpvrx.ui.preferences

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.dialogs.ConnectionEditorSheet
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkBrowserScreen
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingViewModel
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import me.zhanghai.compose.preference.Preference
import org.koin.compose.koinInject

@Composable
internal fun CloudServersPreferences() {
  val context = LocalContext.current
  val stack = LocalBackStack.current
  val model: NetworkStreamingViewModel = viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as Application))
  val connections by model.connections.collectAsState()
  val preferences = koinInject<BrowserPreferences>()
  val hideEmpty by preferences.hideEmptyCloudFolders.collectAsState()
  val mp4 by preferences.advancedMp4Thumbnails.collectAsState()
  val mkv by preferences.advancedMkvThumbnails.collectAsState()
  val sprites by preferences.cloudSpritePreviews.collectAsState()
  var editor by remember { mutableStateOf<NetworkConnection?>(null) }
  var deleting by remember { mutableStateOf<NetworkConnection?>(null) }
  Column {
    PreferenceSectionHeader(title = "WebDAV / OpenList")
    PreferenceCard {
      connections.filter { it.protocol in setOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST) }.forEach { server ->
        ServerPreferenceItem(
          title = { Text(server.name) },
          summary = { Text("${server.protocol.displayName} • ${server.host}:${server.port}${server.path}", color = MaterialTheme.colorScheme.outline) },
          icon = { Icon(Icons.RoundedFilled.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
          onClick = { stack.navigateTo(NetworkBrowserScreen(server.id, server.name)) },
          trailing = {
            var expanded by remember { mutableStateOf(false) }
            Box {
              IconButton(onClick = { expanded = true }) { Icon(Icons.RoundedFilled.MoreVert, contentDescription = stringResource(R.string.pref_server_more_options)) }
              DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit)) }, onClick = { expanded = false; editor = server })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { expanded = false; deleting = server })
              }
            }
          },
        )
        PreferenceDivider()
      }
      Preference(
        title = { Text(stringResource(R.string.cloud_add_server)) },
        summary = { Text(stringResource(R.string.cloud_add_server_summary), color = MaterialTheme.colorScheme.outline) },
        icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
        onClick = { editor = NetworkConnection(name = "", protocol = NetworkProtocol.WEBDAV, host = "", port = 80) },
      )
      PreferenceDivider()
      SwitchPreference(value = hideEmpty, onValueChange = preferences.hideEmptyCloudFolders::set,
        title = { Text(stringResource(R.string.cloud_hide_empty_folders)) })
      SwitchPreference(value = mp4, onValueChange = preferences.advancedMp4Thumbnails::set,
        title = { Text(stringResource(R.string.cloud_advanced_mp4)) })
      SwitchPreference(value = mkv, onValueChange = preferences.advancedMkvThumbnails::set,
        title = { Text(stringResource(R.string.cloud_advanced_mkv)) })
      SwitchPreference(value = sprites, onValueChange = preferences.cloudSpritePreviews::set,
        title = { Text(stringResource(R.string.cloud_sprite_previews)) })
    }
  }
  editor?.let { server ->
    ConnectionEditorSheet(title = stringResource(R.string.cloud_add_server), initialConnection = server,
      isEditing = server.id != 0L, onDismiss = { editor = null },
      allowedProtocols = listOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST),
      onSave = { value, replace ->
        if (server.id == 0L) model.addConnection(value) else model.updateConnection(value, replace)
        editor = null
      })
  }
  deleting?.let { server ->
    AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
      text = { Text(server.name) }, confirmButton = { TextButton(onClick = { model.deleteConnection(server); deleting = null }) { Text(stringResource(R.string.delete)) } },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.generic_cancel)) } })
  }
}
