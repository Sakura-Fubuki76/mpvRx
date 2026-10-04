package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.AnimeSubject
import app.gyrolet.mpvrx.presentation.components.RemoteImage

@Composable
internal fun AnimeCreditsContent(subject: AnimeSubject) {
  Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (subject.cast.isNotEmpty()) {
      Text(stringResource(R.string.anime_cast), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
      LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(subject.cast, key = { it.id }) { character ->
          val actor = character.actors.firstOrNull()
          CreditCard(actor?.name?.takeIf { it.isNotBlank() } ?: character.name,
            if (actor != null) character.name else character.role,
            actor?.image?.takeIf { it.isNotBlank() } ?: character.image,
            character.actors.drop(1).joinToString(" · ") { it.name })
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
private fun CreditCard(name: String, role: String, image: String, additional: String = "") {
  Surface(Modifier.width(152.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (image.isNotBlank()) RemoteImage(image, name, Modifier.fillMaxSize(), ContentScale.Crop)
        else Text(name.take(1), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
      }
      Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
      if (additional.isNotBlank()) Text(additional, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}
