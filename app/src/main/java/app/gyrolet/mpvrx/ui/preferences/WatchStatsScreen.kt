package app.gyrolet.mpvrx.ui.preferences

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.WatchMediaStats
import app.gyrolet.mpvrx.repository.WatchStatsRepository
import app.gyrolet.mpvrx.repository.WatchStatsSnapshot
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.icons.Icon
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProfileWatchStatistics() {
  val repository = koinInject<WatchStatsRepository>()
  val scope = rememberCoroutineScope()
  val revision by repository.revision.collectAsStateWithLifecycle()
  val stats by produceState(WatchStatsSnapshot(), repository, revision) { value = repository.snapshot() }
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  var mediaFilter by rememberSaveable { mutableStateOf(0) }

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.watch_stats_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        enabled = stats.totalSeconds > 0 || stats.sessions > 0 || stats.media.isNotEmpty(),
        onClick = { confirmReset = true },
      ) { Text(stringResource(R.string.watch_stats_reset)) }
    }
    WatchTimeHero(stats)
    WatchSplit(stats)
    WeeklyActivity(stats.days)
    Text(
      text = stringResource(R.string.watch_stats_top_media),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(top = 4.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(R.string.pref_all_sources, R.string.watch_stats_video, R.string.watch_stats_audio).forEachIndexed { index, label ->
        FilterChip(selected = mediaFilter == index, onClick = { mediaFilter = index }, label = { Text(stringResource(label)) })
      }
    }
    val topMedia = stats.media.entries
      .filter { it.value.seconds > 0 && (mediaFilter == 0 || it.value.isAudio == (mediaFilter == 2)) }
      .sortedByDescending { it.value.seconds }.take(8)
    if (topMedia.isEmpty()) {
      Text(
        text = stringResource(R.string.watch_stats_empty),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
      )
    } else {
      topMedia.forEach { (_, media) -> WatchMediaRow(media) }
    }
  }
  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text(stringResource(R.string.watch_stats_reset)) },
      text = { Text(stringResource(R.string.watch_stats_reset_confirm)) },
      confirmButton = {
        TextButton(onClick = {
          confirmReset = false
          scope.launch { repository.clear() }
        }) {
          Text(stringResource(R.string.watch_stats_reset), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.generic_cancel)) } },
    )
  }
}

@Composable
private fun WatchTimeHero(stats: WatchStatsSnapshot) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(stringResource(R.string.watch_stats_total_time), style = MaterialTheme.typography.labelLarge)
      Text(
        text = formatWatchDuration(stats.totalSeconds),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.watch_stats_sessions, stats.sessions),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
}

@Composable
private fun WatchSplit(stats: WatchStatsSnapshot) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    StatTile(stringResource(R.string.watch_stats_video), stats.videoSeconds, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
    StatTile(stringResource(R.string.watch_stats_audio), stats.audioSeconds, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
  }
}

@Composable
private fun StatTile(label: String, seconds: Long, accent: Color, modifier: Modifier = Modifier) {
  Card(modifier = modifier, shape = RoundedCornerShape(8.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
      Text(formatWatchDuration(seconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun WeeklyActivity(days: Map<String, Long>) {
  val today = LocalDate.now()
  val dates = remember(today) { (6 downTo 0).map { today.minusDays(it.toLong()) } }
  val values = dates.map { days[it.toString()] ?: 0L }
  val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
  val color = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.watch_stats_last_seven_days), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      val chartDescription = dates.zip(values).joinToString { (date, seconds) -> "$date: ${formatWatchDuration(seconds)}" }
      Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = chartDescription }) {
        val gap = 8.dp.toPx()
        val width = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { index, value ->
          val barHeight = (size.height * (value.toFloat() / max.toFloat())).coerceAtLeast(3.dp.toPx())
          drawRoundRect(
            color = if (value > 0) color else color.copy(alpha = 0.15f),
            topLeft = Offset(index * (width + gap), size.height - barHeight),
            size = Size(width, barHeight.coerceAtLeast(3.dp.toPx())),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
          )
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dates.forEach { date ->
          Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
}

@Composable
private fun WatchMediaRow(media: WatchMediaStats) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      if (!media.artworkUri.isNullOrBlank()) {
        RemoteImage(
          url = media.artworkUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Icon(
          imageVector = if (media.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(Modifier.weight(1f)) {
      Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
      Text(
        stringResource(if (media.isAudio) R.string.watch_stats_audio else R.string.watch_stats_video),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(formatWatchDuration(media.seconds), style = MaterialTheme.typography.labelLarge)
  }
}

private fun formatWatchDuration(seconds: Long): String {
  return DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))
}
