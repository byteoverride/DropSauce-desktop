package org.koitharu.kotatsu.shared.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
	entities = [
		MangaEntity::class,
		FavouriteCategoryEntity::class,
		FavouriteEntity::class,
		HistoryEntity::class,
		BookmarkEntity::class,
		DownloadEntity::class,
		LocalMangaEntity::class,
		StatsEntity::class,
		TrackEntity::class,
	],
	version = 3,
	exportSchema = true,
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {

	abstract fun mangaDao(): MangaDao

	abstract fun favouriteCategoriesDao(): FavouriteCategoriesDao

	abstract fun favouritesDao(): FavouritesDao

	abstract fun historyDao(): HistoryDao

	abstract fun bookmarksDao(): BookmarksDao

	abstract fun downloadsDao(): DownloadsDao

	abstract fun localLibraryDao(): LocalLibraryDao

	abstract fun statsDao(): StatsDao

	abstract fun tracksDao(): TracksDao
}

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {

	override fun initialize(): LibraryDatabase
}

/**
 * Adds `manga.chapters_count`, so the library can be filtered by length without
 * refetching every title.
 *
 * Written against [SQLiteConnection] rather than `SupportSQLiteDatabase`. Per
 * DECISIONS.md D14 that is the overload Room actually calls on both platforms, so a
 * migration written this way would serve Android too.
 */
val Migration1To2: Migration = object : Migration(1, 2) {

	override fun migrate(connection: SQLiteConnection) {
		connection.execSQL(
			"ALTER TABLE manga ADD COLUMN chapters_count INTEGER NOT NULL DEFAULT 0",
		)
	}
}

/**
 * Adds the tables for bookmarks, downloads, the local library, statistics and chapter
 * tracking.
 *
 * Written as explicit DDL rather than generated, because a migration has to reproduce
 * exactly what Room expects for version 3; any drift shows up at runtime as "Room cannot
 * verify the data integrity", which is how the v1-to-v2 change first failed.
 */
val Migration2To3: Migration = object : Migration(2, 3) {

	override fun migrate(connection: SQLiteConnection) {
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `bookmarks` (`manga_id` INTEGER NOT NULL, " +
				"`page_id` INTEGER NOT NULL, `chapter_id` INTEGER NOT NULL, " +
				"`page` INTEGER NOT NULL, `scroll` INTEGER NOT NULL, " +
				"`image_url` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
				"`percent` REAL NOT NULL, PRIMARY KEY(`manga_id`, `page_id`), " +
				"FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE )",
		)
		connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_manga_id` ON `bookmarks` (`manga_id`)")
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `downloads` (`manga_id` INTEGER NOT NULL, " +
				"`chapter_id` INTEGER NOT NULL, `chapter_title` TEXT NOT NULL, " +
				"`chapter_number` REAL NOT NULL, `state` TEXT NOT NULL, " +
				"`pages_total` INTEGER NOT NULL, `pages_done` INTEGER NOT NULL, " +
				"`path` TEXT NOT NULL, `error` TEXT, `created_at` INTEGER NOT NULL, " +
				"`updated_at` INTEGER NOT NULL, PRIMARY KEY(`manga_id`, `chapter_id`))",
		)
		connection.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_manga_id` ON `downloads` (`manga_id`)")
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `local_index` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
				"`path` TEXT NOT NULL, `title` TEXT NOT NULL, `cover_entry` TEXT, " +
				"`format` TEXT NOT NULL, `chapters_count` INTEGER NOT NULL, " +
				"`size_bytes` INTEGER NOT NULL, `added_at` INTEGER NOT NULL)",
		)
		connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_index_path` ON `local_index` (`path`)")
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
				"`manga_id` INTEGER NOT NULL, `started_at` INTEGER NOT NULL, " +
				"`duration` INTEGER NOT NULL, `pages` INTEGER NOT NULL)",
		)
		connection.execSQL("CREATE INDEX IF NOT EXISTS `index_stats_manga_id` ON `stats` (`manga_id`)")
		connection.execSQL(
			"CREATE TABLE IF NOT EXISTS `tracks` (`manga_id` INTEGER PRIMARY KEY NOT NULL, " +
				"`last_chapter_id` INTEGER NOT NULL, `last_chapter_date` INTEGER NOT NULL, " +
				"`chapters_new` INTEGER NOT NULL, `last_check` INTEGER NOT NULL, " +
				"`last_error` TEXT, FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE )",
		)
	}
}

/**
 * Seeds the default favourite category on a fresh database.
 *
 * Phase 1 Agent A found this is not optional. The Android app's
 * `DatabasePrePopulateCallback` seeds "Read later" in `onCreate`, and that row is not in
 * the schema's CREATE statements. Without it a fresh library opens with zero categories,
 * so there is nowhere to put a favourite, and a restore then deletes the category it
 * brings in because the Android side matches on the localized title.
 */
class PrePopulateCallback(
	private val defaultCategoryTitle: String,
	private val now: () -> Long,
) : RoomDatabase.Callback() {

	override fun onCreate(connection: SQLiteConnection) {
		super.onCreate(connection)
		val timestamp = now()
		connection.execSQL(
			"INSERT INTO favourite_categories " +
				"(created_at, sort_key, title, `order`, track, show_in_lib) " +
				"VALUES ($timestamp, 0, '${defaultCategoryTitle.replace("'", "''")}', " +
				"'NEWEST', 1, 1)",
		)
	}
}
