package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.AnimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AnimeArtworkDialog(subject: AnimeSubject, logo: Boolean, repository: AnimeRepository, onDismiss: () -> Unit) {
  var name by remember(subject.id) { mutableStateOf(subject.name) }
  var bangumiId by remember(subject.id) { mutableStateOf(subject.id.toString()) }
  var tmdbId by remember(subject.id) { mutableStateOf(subject.tmdbId.takeIf { it > 0 }?.toString().orEmpty()) }
  var tmdbType by remember { mutableStateOf(subject.artworkBinding?.type?.takeIf { it in listOf("tv", "movie") } ?: if (subject.format == "剧场版") "movie" else "tv") }
  var target by remember { mutableStateOf(if (logo) AnimeArtworkTarget.LOGO else AnimeArtworkTarget.DETAIL_POSTER) }
  var lastKind by remember { mutableStateOf(if (logo && tmdbId.isNotBlank()) AnimeArtworkQueryKind.TMDB_ID else AnimeArtworkQueryKind.NAME) }
  var page by remember { mutableStateOf<AnimeArtworkSearchPage?>(null) }
  var choices by remember { mutableStateOf<List<AnimeArtworkChoice>?>(null) }
  var selected by remember { mutableStateOf<AnimeArtworkHit?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  fun search(kind: AnimeArtworkQueryKind = lastKind) {
    val query = when (kind) { AnimeArtworkQueryKind.NAME -> name.trim(); AnimeArtworkQueryKind.BANGUMI_ID -> bangumiId; AnimeArtworkQueryKind.TMDB_ID -> tmdbId }
    if (query.isBlank()) return
    lastKind = kind; busy = true; error = false; choices = null; selected = null; page = null
    scope.launch {
      try {
        val result = repository.searchArtwork(query, kind, target, tmdbType)
        page = result
        if (logo) {
          // Display logo assets directly; a search-result poster is never a logo candidate.
          choices = emptyList()
          for (hit in result.hits.filter { it.provider == "TMDB" }) {
            try {
              val assets = repository.artworkChoices(hit, target).map { it.copy(label = hit.title + " · " + it.label) }
              choices = (choices.orEmpty() + assets).distinctBy { it.url }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
          }
        } else if (kind == AnimeArtworkQueryKind.TMDB_ID && result.hits.size == 1) {
          selected = result.hits.single()
          choices = repository.artworkChoices(result.hits.single(), target)
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { error = true }
      finally { busy = false }
    }
  }
  fun save(url: String?) {
    busy = true; error = false
    scope.launch {
      try { repository.setArtwork(subject.id, url, target); onDismiss() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { error = true }
      finally { busy = false }
    }
  }
  LaunchedEffect(subject.id, target) { search() }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth(.94f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.extraLarge) {
      BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val inputsHeight = maxHeight * .45f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (logo) R.string.anime_logo_match else R.string.anime_poster_match), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.anime_close)) }
          }
          if (!logo) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = target == AnimeArtworkTarget.DETAIL_POSTER, enabled = !busy,
              onClick = { target = AnimeArtworkTarget.DETAIL_POSTER }, label = { Text(stringResource(R.string.anime_artwork_detail)) })
            FilterChip(selected = target == AnimeArtworkTarget.LIBRARY_POSTER, enabled = !busy,
              onClick = { target = AnimeArtworkTarget.LIBRARY_POSTER }, label = { Text(stringResource(R.string.anime_artwork_library)) })
          }
          Column(Modifier.fillMaxWidth().heightIn(max = inputsHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtworkQueryField(name, { name = it }, stringResource(R.string.anime_artwork_name), !busy, false) { search(AnimeArtworkQueryKind.NAME) }
            ArtworkQueryField(bangumiId, { bangumiId = it.filter(Char::isDigit) }, stringResource(R.string.anime_artwork_bangumi_id), !busy, true) { search(AnimeArtworkQueryKind.BANGUMI_ID) }
            ArtworkQueryField(tmdbId, { tmdbId = it.filter(Char::isDigit) }, stringResource(R.string.anime_artwork_tmdb_id), !busy, true) { search(AnimeArtworkQueryKind.TMDB_ID) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = tmdbType == "tv", enabled = !busy, onClick = { tmdbType = "tv" }, label = { Text(stringResource(R.string.anime_artwork_tv)) })
              FilterChip(selected = tmdbType == "movie", enabled = !busy, onClick = { tmdbType = "movie" }, label = { Text(stringResource(R.string.anime_artwork_movie)) })
            }
          }
          if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
          if (error || page?.failedSources?.isNotEmpty() == true) Text(stringResource(R.string.anime_artwork_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          if (page?.tmdbAvailable == false) Text(stringResource(R.string.anime_artwork_credentials), style = MaterialTheme.typography.bodySmall)
          selected?.let { hit ->
            Row {
              Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
              TextButton(enabled = !busy, onClick = { selected = null; choices = null }) { Text(stringResource(R.string.anime_artwork_back)) }
            }
          }
          val assets = choices
          LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (assets != null) {
              if (assets.isEmpty() && !busy) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.anime_artwork_none)) }
              items(assets, key = { it.url }) { asset -> ArtworkChoiceCard(asset.preview, asset.label, logo, !busy) { save(asset.url) } }
            } else if (!logo) {
              for (provider in listOf("Bangumi", "TMDB")) {
                val hits = page?.hits.orEmpty().filter { it.provider == provider }
                item(span = { GridItemSpan(maxLineSpan) }) { Text(provider, style = MaterialTheme.typography.titleSmall) }
                if (hits.isEmpty() && page != null && !busy) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.anime_artwork_none), style = MaterialTheme.typography.bodySmall) }
                items(hits, key = { "${it.provider}:${it.type}:${it.id}" }) { hit ->
                  ArtworkChoiceCard(hit.preview, hit.title, false, !busy) {
                    if (hit.provider == "Bangumi") save(hit.preview)
                    else {
                      busy = true; error = false; selected = hit
                      scope.launch {
                        try { choices = repository.artworkChoices(hit, target) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true; selected = null; choices = null }
                        finally { busy = false }
                      }
                    }
                  }
                }
              }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { save(null) }) { Text(stringResource(R.string.anime_artwork_auto)) }
            if (logo) TextButton(enabled = !busy, onClick = { save("") }) { Text(stringResource(R.string.anime_artwork_text_title)) }
          }
        }
      }
    }
  }
}

@Composable
private fun ArtworkQueryField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, numeric: Boolean, onSearch: () -> Unit) {
  val valid = if (numeric) value.toLongOrNull()?.let { it > 0 } == true else value.isNotBlank()
  OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) },
    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { if (valid && enabled) onSearch() }),
    trailingIcon = { TextButton(enabled = enabled && valid, onClick = onSearch) { Text(stringResource(R.string.anime_search)) } })
}

@Composable
private fun ArtworkChoiceCard(preview: String, label: String, logo: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column(Modifier.padding(6.dp)) {
      RemoteImage(preview, label, Modifier.fillMaxWidth().aspectRatio(if (logo) 2f else 2f / 3f), contentScale = ContentScale.Fit)
      Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
  }
}
