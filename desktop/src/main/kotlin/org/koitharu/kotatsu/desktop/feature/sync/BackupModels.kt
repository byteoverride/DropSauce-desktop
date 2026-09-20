package org.koitharu.kotatsu.desktop.feature.sync

import org.koitharu.kotatsu.shared.db.BookmarkEntity
import org.koitharu.kotatsu.shared.db.FavouriteCategoryEntity
import org.koitharu.kotatsu.shared.db.FavouriteEntity
import org.koitharu.kotatsu.shared.db.HistoryEntity
import org.koitharu.kotatsu.shared.db.MangaEntity

/**
 * The on-disk backup format, kept field-compatible with the Android app.
 *
 * DECISIONS.md D15 makes backup/restore the interop path between the two apps, so the zip
 * entry names and the JSON field names here are copied from
 * `app/.../backup/local/data/model/BackupModels.kt` and `BackupSection.kt` rather than
 * invented. Where the desktop schema has no equivalent the field is still written with a
 * neutral value, so an Android restore reading this file finds what it expects.
 *
 * Deliberate divergences, all of them forward-compatible because both sides ignore
 * unknown keys:
 *
 *  - `manga.chapters_count` is a desktop-only field. Android's `Json` is configured with
 *    `ignoreUnknownKeys = true`, so it drops it; without it, a restored desktop library
 *    would lose the count the length filter needs.
 *  - `manga.tags`, `manga.description`, `manga.nsfw` and `manga.source_title` are written
 *    empty or false. The desktop `manga` table stores none of them (it is a projection, see
 *    `LibraryRepository`), so there is nothing truthful to put there.
 *  - `category.download_new_chapters` is written as false. Desktop has no per-category
 *    download setting; the field exists only so Android's decoder sees a familiar shape.
 *  - Settings go under the entry name `settings_desktop`, **not** `settings`. Android's
 *    `settings` entry is a flat dump of its own SharedPreferences keys, and its restore path
 *    (`LocalBackupRepository.restoreAppSettings`) calls `settings.upsertAll` on whatever it
 *    finds. Writing desktop keys there would inject junk into an Android install's
 *    preferences. `BackupSection.of()` returns null for an unknown entry name and the
 *    restore loop skips it, so a separate name is silently ignored by Android instead.
 *  - The `chapters`, `sources`, `source_settings`, `scrobbling`, `statistics`, `feed` and
 *    `manga_prefs` sections are not written. Desktop either has no such table or, for
 *    chapters, deliberately does not persist one.
 *
 * Also inherited, and not fixable here: per D15, `history.scroll` is stored by Android in
 * view pixels at the image view's fit scale. It round-trips as a number and means nothing
 * on the other side, so desktop writes what it has (always 0f today) and never acts on an
 * imported value.
 */
object BackupFormat {

	/** Matches Android's `BackupIndex.FORMAT_VERSION`. */
	const val FORMAT_VERSION = 1

	/**
	 * `:app`'s `applicationId`. Hardcoded because `:desktop` has no `BuildConfig`; the
	 * desktop build is the same product and writes the same id so Android accepts the file.
	 */
	const val APP_ID = "org.haziffe.dropsauce"

	/** Mirrors `:app`'s `versionCode` 72 (`versionName` 0.9.6). */
	const val APP_VERSION = 72
}

/** Zip entry names. The first six match Android's `BackupSection`; see [BackupFormat]. */
enum class BackupSection(val entryName: String, val label: String) {

	INDEX("index", "index"),
	HISTORY("history", "history"),
	CATEGORIES("categories", "categories"),
	FAVOURITES("favourites", "favourites"),
	BOOKMARKS("bookmarks", "bookmarks"),
	SETTINGS("settings_desktop", "settings"),
	;

	companion object {

		fun of(entryName: String): BackupSection? {
			val name = entryName.lowercase()
			return entries.firstOrNull { it.entryName == name }
		}
	}
}

/** What produced the file, and whether this build can read it. */
data class BackupIndex(
	val appId: String,
	val appVersion: Int,
	val createdAt: Long,
	val formatVersion: Int,
) {

	fun toJson(): JsonObject = jsonObject(
		"app_id" to jsonOf(appId),
		"app_version" to jsonOf(appVersion),
		"created_at" to jsonOf(createdAt),
		"format_version" to jsonOf(formatVersion),
	)

	companion object {

		fun current(now: Long) = BackupIndex(
			appId = BackupFormat.APP_ID,
			appVersion = BackupFormat.APP_VERSION,
			createdAt = now,
			formatVersion = BackupFormat.FORMAT_VERSION,
		)

		/**
		 * Reads the index.
		 *
		 * Android writes it as a one-element array (it reuses the array writer for every
		 * section), so both an array and a bare object are accepted.
		 */
		fun fromJson(value: JsonValue): BackupIndex {
			val obj = when (value) {
				is JsonArray -> value.items.firstOrNull()?.asObject("index entry")
					?: throw BackupFormatException("the index entry is an empty array")

				else -> value.asObject("index entry")
			}
			// These two are the identity check: a zip that merely happens to contain a file
			// called "index" will not have them, and must not be treated as a backup.
			val appId = obj.requireString("app_id")
			val formatVersion = obj.requireInt("format_version")
			return BackupIndex(
				appId = appId,
				appVersion = obj.int("app_version"),
				createdAt = obj.long("created_at"),
				formatVersion = formatVersion,
			)
		}
	}
}

/**
 * A manga as it travels inside a favourite, history or bookmark record.
 *
 * Field names are Android's. `authors` is serialised as `author` there, so it is here too.
 */
data class MangaBackup(
	val id: Long,
	val title: String,
	val altTitle: String?,
	val url: String,
	val publicUrl: String,
	val rating: Float,
	val contentRating: String?,
	val coverUrl: String?,
	val largeCoverUrl: String?,
	val state: String?,
	val author: String?,
	val source: String,
	val chaptersCount: Int,
) {

	fun toJson(): JsonObject = jsonObject(
		"id" to jsonOf(id),
		"title" to jsonOf(title),
		"alt_title" to jsonOf(altTitle),
		"url" to jsonOf(url),
		"public_url" to jsonOf(publicUrl),
		"rating" to jsonOf(rating),
		"nsfw" to jsonOf(false),
		"content_rating" to jsonOf(contentRating),
		// Android declares cover_url non-null, so an absent cover is "" rather than null.
		"cover_url" to jsonOf(coverUrl ?: ""),
		"large_cover_url" to jsonOf(largeCoverUrl),
		"state" to jsonOf(state),
		"author" to jsonOf(author),
		"description" to JsonNull,
		"source" to jsonOf(source),
		"source_title" to JsonNull,
		"tags" to jsonArrayOf(emptyList()),
		"chapters_count" to jsonOf(chaptersCount),
	)

	fun toEntity() = MangaEntity(
		mangaId = id,
		title = title,
		altTitle = altTitle,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = contentRating,
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		state = state,
		author = author,
		source = source,
		chaptersCount = chaptersCount,
	)

	companion object {

		fun of(entity: MangaEntity) = MangaBackup(
			id = entity.mangaId,
			title = entity.title,
			altTitle = entity.altTitle,
			url = entity.url,
			publicUrl = entity.publicUrl,
			rating = entity.rating,
			contentRating = entity.contentRating,
			coverUrl = entity.coverUrl,
			largeCoverUrl = entity.largeCoverUrl,
			state = entity.state,
			author = entity.author,
			source = entity.source,
			chaptersCount = entity.chaptersCount,
		)

		fun fromJson(obj: JsonObject) = MangaBackup(
			id = obj.requireLong("id"),
			title = obj.requireString("title"),
			altTitle = obj.string("alt_title"),
			url = obj.string("url", "").orEmpty(),
			publicUrl = obj.string("public_url", "").orEmpty(),
			rating = obj.float("rating", -1f),
			contentRating = obj.string("content_rating"),
			coverUrl = obj.string("cover_url")?.takeIf { it.isNotEmpty() },
			largeCoverUrl = obj.string("large_cover_url"),
			state = obj.string("state"),
			// Android's field is "author"; older Kotatsu files used the same name.
			author = obj.string("author"),
			source = obj.requireString("source"),
			chaptersCount = obj.int("chapters_count"),
		)
	}
}

data class CategoryBackup(
	val categoryId: Long,
	val createdAt: Long,
	val sortKey: Int,
	val title: String,
	val order: String,
	val track: Boolean,
	val isVisibleInLibrary: Boolean,
) {

	fun toJson(): JsonObject = jsonObject(
		"category_id" to jsonOf(categoryId),
		"created_at" to jsonOf(createdAt),
		"sort_key" to jsonOf(sortKey),
		"title" to jsonOf(title),
		"order" to jsonOf(order),
		"track" to jsonOf(track),
		"download_new_chapters" to jsonOf(false),
		"show_in_lib" to jsonOf(isVisibleInLibrary),
	)

	/** [id] is supplied by the caller: the stored id is remapped on restore, never reused. */
	fun toEntity(id: Long) = FavouriteCategoryEntity(
		categoryId = id,
		createdAt = createdAt,
		sortKey = sortKey,
		title = title,
		order = order,
		track = track,
		isVisibleInLibrary = isVisibleInLibrary,
	)

	companion object {

		fun of(entity: FavouriteCategoryEntity) = CategoryBackup(
			categoryId = entity.categoryId,
			createdAt = entity.createdAt,
			sortKey = entity.sortKey,
			title = entity.title,
			order = entity.order,
			track = entity.track,
			isVisibleInLibrary = entity.isVisibleInLibrary,
		)

		fun fromJson(obj: JsonObject) = CategoryBackup(
			categoryId = obj.requireLong("category_id"),
			createdAt = obj.long("created_at"),
			sortKey = obj.int("sort_key"),
			title = obj.requireString("title"),
			order = obj.string("order", "NEWEST").orEmpty(),
			track = obj.bool("track", true),
			isVisibleInLibrary = obj.bool("show_in_lib", true),
		)
	}
}

data class FavouriteBackup(
	val mangaId: Long,
	val categoryId: Long,
	val sortKey: Int,
	val createdAt: Long,
	val manga: MangaBackup,
) {

	fun toJson(): JsonObject = jsonObject(
		"manga_id" to jsonOf(mangaId),
		"category_id" to jsonOf(categoryId),
		"sort_key" to jsonOf(sortKey),
		"pinned" to jsonOf(false),
		"created_at" to jsonOf(createdAt),
		"manga" to manga.toJson(),
	)

	/** [categoryId] is remapped on restore, so it is passed in rather than read back. */
	fun toEntity(localCategoryId: Long) = FavouriteEntity(
		mangaId = mangaId,
		categoryId = localCategoryId,
		sortKey = sortKey,
		createdAt = createdAt,
		deletedAt = 0L,
	)

	companion object {

		fun of(favourite: FavouriteEntity, manga: MangaEntity) = FavouriteBackup(
			mangaId = favourite.mangaId,
			categoryId = favourite.categoryId,
			sortKey = favourite.sortKey,
			createdAt = favourite.createdAt,
			manga = MangaBackup.of(manga),
		)

		fun fromJson(obj: JsonObject) = FavouriteBackup(
			mangaId = obj.requireLong("manga_id"),
			categoryId = obj.requireLong("category_id"),
			sortKey = obj.int("sort_key"),
			createdAt = obj.long("created_at"),
			manga = MangaBackup.fromJson(obj.obj("manga")),
		)
	}
}

data class HistoryBackup(
	val mangaId: Long,
	val createdAt: Long,
	val updatedAt: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Float,
	val percent: Float,
	val chaptersCount: Int,
	val manga: MangaBackup,
) {

	fun toJson(): JsonObject = jsonObject(
		"manga_id" to jsonOf(mangaId),
		"created_at" to jsonOf(createdAt),
		"updated_at" to jsonOf(updatedAt),
		"chapter_id" to jsonOf(chapterId),
		"page" to jsonOf(page),
		// D15: pixels at Android's fit scale. Written for shape, never acted on here.
		"scroll" to jsonOf(scroll),
		"percent" to jsonOf(percent),
		"chapters" to jsonOf(chaptersCount),
		"manga" to manga.toJson(),
	)

	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		chaptersCount = chaptersCount,
		deletedAt = 0L,
	)

	companion object {

		fun of(history: HistoryEntity, manga: MangaEntity) = HistoryBackup(
			mangaId = history.mangaId,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = history.chapterId,
			page = history.page,
			scroll = history.scroll,
			percent = history.percent,
			chaptersCount = history.chaptersCount,
			manga = MangaBackup.of(manga),
		)

		fun fromJson(obj: JsonObject) = HistoryBackup(
			mangaId = obj.requireLong("manga_id"),
			createdAt = obj.long("created_at"),
			updatedAt = obj.long("updated_at"),
			chapterId = obj.long("chapter_id"),
			page = obj.int("page"),
			scroll = obj.float("scroll"),
			percent = obj.float("percent"),
			chaptersCount = obj.int("chapters"),
			manga = MangaBackup.fromJson(obj.obj("manga")),
		)
	}
}

/** Android groups bookmarks by manga, one record per title, so this does too. */
data class BookmarkBackup(
	val manga: MangaBackup,
	val bookmarks: List<Item>,
) {

	data class Item(
		val mangaId: Long,
		val pageId: Long,
		val chapterId: Long,
		val page: Int,
		val scroll: Int,
		val imageUrl: String,
		val createdAt: Long,
		val percent: Float,
	) {

		fun toJson(): JsonObject = jsonObject(
			"manga_id" to jsonOf(mangaId),
			"page_id" to jsonOf(pageId),
			"chapter_id" to jsonOf(chapterId),
			"page" to jsonOf(page),
			"scroll" to jsonOf(scroll),
			"image_url" to jsonOf(imageUrl),
			"created_at" to jsonOf(createdAt),
			"percent" to jsonOf(percent),
		)

		fun toEntity() = BookmarkEntity(
			mangaId = mangaId,
			pageId = pageId,
			chapterId = chapterId,
			page = page,
			scroll = scroll,
			imageUrl = imageUrl,
			createdAt = createdAt,
			percent = percent,
		)

		companion object {

			fun of(entity: BookmarkEntity) = Item(
				mangaId = entity.mangaId,
				pageId = entity.pageId,
				chapterId = entity.chapterId,
				page = entity.page,
				scroll = entity.scroll,
				imageUrl = entity.imageUrl,
				createdAt = entity.createdAt,
				percent = entity.percent,
			)

			fun fromJson(obj: JsonObject) = Item(
				mangaId = obj.requireLong("manga_id"),
				pageId = obj.requireLong("page_id"),
				chapterId = obj.long("chapter_id"),
				page = obj.int("page"),
				scroll = obj.int("scroll"),
				imageUrl = obj.string("image_url", "").orEmpty(),
				createdAt = obj.long("created_at"),
				percent = obj.float("percent"),
			)
		}
	}

	fun toJson(): JsonObject = jsonObject(
		"manga" to manga.toJson(),
		"bookmarks" to jsonArrayOf(bookmarks.map { it.toJson() }),
	)

	companion object {

		fun fromJson(obj: JsonObject) = BookmarkBackup(
			manga = MangaBackup.fromJson(obj.obj("manga")),
			bookmarks = obj.array("bookmarks").map { Item.fromJson(it.asObject("bookmark")) },
		)
	}
}

/**
 * One settings value, in the envelope Android's `BackupPrimitive` uses.
 *
 * The `_t` discriminator and the `v` field are Android's, kept so the two sides could share
 * a settings section later without a format change, even though today they do not share one
 * (see [BackupFormat]).
 */
sealed interface BackupPrimitive {

	fun toJson(): JsonObject

	data class Str(val value: String) : BackupPrimitive {
		override fun toJson() = jsonObject("_t" to jsonOf("string"), "v" to jsonOf(value))
	}

	data class Bool(val value: Boolean) : BackupPrimitive {
		override fun toJson() = jsonObject("_t" to jsonOf("bool"), "v" to jsonOf(value))
	}

	data class Integer(val value: Int) : BackupPrimitive {
		override fun toJson() = jsonObject("_t" to jsonOf("int"), "v" to jsonOf(value))
	}

	data class Strings(val value: List<String>) : BackupPrimitive {
		override fun toJson() = jsonObject(
			"_t" to jsonOf("string_set"),
			"v" to jsonArrayOf(value.map { JsonString(it) }),
		)
	}

	companion object {

		fun fromJson(obj: JsonObject): BackupPrimitive = when (val type = obj.requireString("_t")) {
			"string" -> Str(obj.string("v", "").orEmpty())
			"bool" -> Bool(obj.bool("v", false))
			"int", "long" -> Integer(obj.int("v"))
			"string_set" -> Strings(obj.stringList("v"))
			// A float setting would be a future addition; refusing the whole restore over one
			// unreadable preference would be out of proportion, so it is reported instead.
			else -> throw BackupFormatException("unsupported setting type \"$type\"")
		}
	}
}
