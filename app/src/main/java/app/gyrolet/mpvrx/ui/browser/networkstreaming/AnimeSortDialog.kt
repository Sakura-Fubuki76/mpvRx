package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.AnimeSortType
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.dialogs.SortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.treeBrowserFields
import app.gyrolet.mpvrx.ui.icons.Icons
import org.koin.compose.koinInject

@Composable
internal fun AnimeSortDialog(isOpen: Boolean, onDismiss: () -> Unit) {
  if (!isOpen) return
  val preferences = koinInject<BrowserPreferences>()
  val type by preferences.animeSortType.collectAsState()
  val order by preferences.animeSortOrder.collectAsState()
  val labels = listOf(stringResource(R.string.anime_sort_name), stringResource(R.string.anime_sort_year), stringResource(R.string.anime_sort_score))
  val ascending = stringResource(R.string.anime_sort_ascending)
  val descending = stringResource(R.string.anime_sort_descending)
  SortDialog(
    isOpen = isOpen, onDismiss = onDismiss, title = stringResource(R.string.anime_sort_title),
    sortType = labels[type.ordinal], types = labels,
    onSortTypeChange = { label -> labels.indexOf(label).takeIf { it >= 0 }?.let { preferences.animeSortType.set(AnimeSortType.entries[it]) } },
    sortOrderAsc = order.isAscending,
    onSortOrderChange = { preferences.animeSortOrder.set(if (it) SortOrder.Ascending else SortOrder.Descending) },
    icons = listOf(Icons.RoundedFilled.Title, Icons.RoundedFilled.CalendarToday, Icons.RoundedFilled.Star),
    getLabelForType = { _, _ -> ascending to descending },
    visibilityToggles = treeBrowserFields(MediaLayoutMode.GRID, cloud = true, anime = true),
    enableViewModeOptions = false, enableLayoutModeOptions = false,
  )
}
