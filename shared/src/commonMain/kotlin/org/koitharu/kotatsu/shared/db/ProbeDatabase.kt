package org.koitharu.kotatsu.shared.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * A minimal Room database used to prove the Room KMP setup before the real 16-entity
 * schema is ported onto it.
 *
 * It exists to answer one question that Phase 1 Agent A flagged as the top data-layer
 * risk: the Android app has 50 `withTransaction` blocks that call suspend DAO methods
 * from inside, and Room KMP's replacement is
 * `useWriterConnection { it.immediateTransaction { ... } }`. If the JVM writer pool is
 * not re-entrant, a DAO call nested inside a transaction **deadlocks rather than
 * throwing**: no stack trace, green build, hung app. A class name in the jar suggests a
 * re-entrancy shim exists, but a class name is not behaviour.
 */
@Entity(tableName = "probe")
data class ProbeEntity(
	@PrimaryKey val id: Long,
	val value: String,
)

@Dao
interface ProbeDao {

	@Insert
	suspend fun insert(entity: ProbeEntity)

	@Query("SELECT * FROM probe WHERE id = :id")
	suspend fun find(id: Long): ProbeEntity?

	@Query("SELECT COUNT(*) FROM probe")
	suspend fun count(): Int
}

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = false)
@ConstructedBy(ProbeDatabaseConstructor::class)
abstract class ProbeDatabase : RoomDatabase() {

	abstract fun probeDao(): ProbeDao
}

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object ProbeDatabaseConstructor : RoomDatabaseConstructor<ProbeDatabase> {

	override fun initialize(): ProbeDatabase
}
