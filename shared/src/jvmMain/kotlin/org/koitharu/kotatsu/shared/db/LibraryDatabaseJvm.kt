package org.koitharu.kotatsu.shared.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path

/**
 * Opens the library database at [path], creating it if needed.
 *
 * [BundledSQLiteDriver] ships its own SQLite, so the app does not depend on whatever the
 * host has installed.
 */
fun openLibraryDatabase(
	path: Path,
	defaultCategoryTitle: String = "Read later",
	dispatcher: CoroutineDispatcher = Dispatchers.IO,
	now: () -> Long = System::currentTimeMillis,
): LibraryDatabase = Room.databaseBuilder<LibraryDatabase>(name = path.toString())
	.setDriver(BundledSQLiteDriver())
	.setQueryCoroutineContext(dispatcher)
	.addCallback(PrePopulateCallback(defaultCategoryTitle, now))
	.build()
