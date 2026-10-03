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
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.FoldersPreferencesScreen
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
    val storages = connections.filter { it.protocol in setOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST) && (selected.isEmpty() || it.id in selected) }
    val stack = LocalBackStack.current
    Scaffold(topBar = {
      TopAppBar(title = { Text(stringResource(R.string.cloud_library_mode_cloud)) }, actions = {
        IconButton(onClick = { stack.navigateTo(FoldersPreferencesScreen) }) {
          Icon(Icons.RoundedFilled.Settings, contentDescription = stringResource(R.string.pref_folders_title))
        }
      })
    }) { padding ->
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (storages.isEmpty()) item { Text(stringResource(R.string.cloud_select_storages_hint)) }
        items(storages, key = { it.id }) { storage ->
          Card(onClick = { stack.navigateTo(NetworkBrowserScreen(storage.id, storage.name)) }, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Icon(Icons.RoundedFilled.Folder, contentDescription = null)
              Column { Text(storage.name, style = MaterialTheme.typography.titleMedium); Text(storage.protocol.displayName) }
            }
          }
        }
      }
    }
  }
}
