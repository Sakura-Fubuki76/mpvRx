package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.AnimeSubject
import app.gyrolet.mpvrx.presentation.components.RemoteImage

@Composable
internal fun AnimeCreditsContent(subject: AnimeSubject) {
  val voices = remember(subject.cast) {
    subject.cast.flatMap { character -> character.actors.map { it to character.name } }
      .groupBy { it.first.id }.values.map { rows -> rows.first().first.copy(role = rows.map { it.second }.distinct().joinToString(" · ")) }
  }
  Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (voices.isNotEmpty()) {
      Text(stringResource(R.string.anime_voices), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
      LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(voices, key = { it.id }) { actor -> CreditCard(actor.name, actor.role, actor.image) }
      }
    }
    if (subject.cast.isNotEmpty()) {
      Text(stringResource(R.string.anime_characters), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
      LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(subject.cast, key = { it.id }) { character ->
          CreditCard(character.name, character.role, character.image,
            portrait = true)
        }
      }
    }
    if (subject.staff.isNotEmpty()) {
      Text(stringResource(R.string.anime_staff), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
      LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(subject.staff, key = { it.id }) { person -> CreditCard(person.name, person.role, person.image) }
      }
    }
  }
}

@Composable
private fun CreditCard(name: String, role: String, image: String, portrait: Boolean = false) {
  Surface(Modifier.width(152.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(96.dp, 128.dp), contentAlignment = Alignment.Center) {
        Box((if (portrait) Modifier.fillMaxSize() else Modifier.size(88.dp)).clip(if (portrait) RoundedCornerShape(16.dp) else CircleShape)
          .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
          if (image.isNotBlank()) RemoteImage(image, name, Modifier.fillMaxSize(), if (portrait) ContentScale.Fit else ContentScale.Crop)
          else Text(name.take(1), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
      }
      Text(name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
      Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 3, maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
  }
}
