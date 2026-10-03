package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.browser.dialogs.ConnectionEditorSheet
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** The cloud counterpart of the local library root. Each selected storage keeps its own identity. */
@Serializable
object CloudLibraryScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable override fun Content() {
    val context = LocalContext.current
    val preferences = koinInject<BrowserPreferences>()
    val selection by preferences.cloudStorageSelection.collectAsState()
    val selected = selection.split(',').mapNotNull(String::toLongOrNull).toSet()
    val model: NetworkStreamingViewModel = viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as Application))
    val connections by model.connections.collectAsState()
    val statuses by model.connectionStatuses.collectAsState()
    var editing by remember { mutableStateOf<app.gyrolet.mpvrx.domain.network.NetworkConnection?>(null) }
    var deleting by remember { mutableStateOf<app.gyrolet.mpvrx.domain.network.NetworkConnection?>(null) }
    val storages = connections.filter { it.protocol in setOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST) && (selected.isEmpty() || it.id in selected) }
    val stack = LocalBackStack.current
    Scaffold(topBar = {
      TopAppBar(title = { Text("WebDAV") }, actions = {
        IconButton(onClick = { stack.navigateTo(MediaServersPreferencesScreen) }) {
          Icon(Icons.RoundedFilled.Settings, contentDescription = stringResource(R.string.pref_media_servers_title))
        }
      })
    }) { padding ->
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (storages.isEmpty()) item { Text(stringResource(R.string.cloud_select_storages_hint)) }
        items(storages, key = { it.id }) { storage ->
          val status = statuses[storage.id]
          app.gyrolet.mpvrx.ui.browser.cards.NetworkConnectionCard(
            connection = storage,
            onConnect = model::connect,
            onDisconnect = model::disconnect,
            onEdit = { editing = it },
            onDelete = { deleting = it },
            onBrowse = { stack.navigateTo(NetworkBrowserScreen(it.id, it.name)) },
            onAutoConnectChange = { connection, enabled -> model.updateConnection(connection.copy(autoConnect = enabled)) },
            isConnected = status?.isConnected == true,
            isConnecting = status?.isConnecting == true,
            error = status?.error,
          )
        }
      }
    }
    editing?.let { connection ->
      ConnectionEditorSheet(title = stringResource(R.string.cloud_add_server), initialConnection = connection,
        isEditing = true, allowedProtocols = listOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST),
        onDismiss = { editing = null }, onSave = { value, replace -> model.updateConnection(value, replace); editing = null })
    }
    deleting?.let { connection ->
      AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(connection.name) },
        confirmButton = { TextButton(onClick = { model.deleteConnection(connection); deleting = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.generic_cancel)) } })
    }
  }
}
