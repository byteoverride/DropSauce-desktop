package org.koitharu.kotatsu.desktop.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.desktop.image.ImageCache
import org.koitharu.kotatsu.desktop.ui.RemoteImage
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * One title in a grid or a strip.
 *
 * [client] must be the client belonging to the source the title came from, because cover
 * images are served behind the same Referer and User-Agent checks as the pages.
 */
@Composable
fun CoverCard(
	manga: Manga,
	client: OkHttpClient,
	images: ImageCache,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		RemoteImage(
			url = manga.coverUrl,
			client = client,
			cache = images,
			contentDescription = manga.title,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(COVER_ASPECT)
				.clip(RoundedCornerShape(10.dp)),
		)
		Text(
			text = manga.title,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** Typical manga cover proportions; keeps a grid from jumping before covers load. */
const val COVER_ASPECT = 0.7f

/** How close to the end of a grid to get before asking for the next page. */
const val GRID_PREFETCH_DISTANCE = 8

/** Cover width in the horizontal strips the global search uses. */
val STRIP_ITEM_WIDTH = 132.dp
