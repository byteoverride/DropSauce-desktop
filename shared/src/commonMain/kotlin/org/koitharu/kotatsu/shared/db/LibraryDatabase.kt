package org.koitharu.kotatsu.shared.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
	entities = [
		MangaEntity::class,
		FavouriteCategoryEntity::class,
		FavouriteEntity::class,
		HistoryEntity::class,
	],
	version = 1,
	exportSchema = true,
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {

	abstract fun mangaDao(): MangaDao

	abstract fun favouriteCategoriesDao(): FavouriteCategoriesDao

	abstract fun favouritesDao(): FavouritesDao

	abstract fun historyDao(): HistoryDao
}

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {

	override fun initialize(): LibraryDatabase
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
