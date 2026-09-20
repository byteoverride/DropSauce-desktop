package org.koitharu.kotatsu.shared.db

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Settles the top data-layer risk before any repository code depends on it.
 *
 * The Android app has 50 `withTransaction` blocks that call suspend DAO methods from
 * inside. Room KMP's replacement is `useWriterConnection { it.immediateTransaction { } }`.
 * If the JVM writer pool is not re-entrant, a nested DAO call **deadlocks rather than
 * throwing**: no stack trace, green build, hung app. That failure mode is invisible to a
 * compile check, so it gets a harness.
 *
 * The timeout is the detector, so [the timeout detector actually fires] is the positive
 * control: without it, a passing nested-call test would only prove that nothing hung
 * *this run*, which is an absence-of-evidence argument.
 */
class TransactionReentrancyTest {

	private lateinit var db: ProbeDatabase
	private lateinit var file: java.io.File

	@BeforeTest
	fun setUp() {
		file = Files.createTempFile("probe", ".db").toFile()
		file.delete()
		db = Room.databaseBuilder<ProbeDatabase>(name = file.absolutePath)
			.setDriver(BundledSQLiteDriver())
			.setQueryCoroutineContext(Dispatchers.IO)
			.build()
	}

	@AfterTest
	fun tearDown() {
		db.close()
		file.delete()
	}

	@Test
	fun `the timeout detector actually fires`() = runBlocking {
		// POSITIVE CONTROL. Proves the harness can observe a hang at all.
		assertFailsWith<TimeoutCancellationException> {
			withTimeout(DETECT_MS) { delay(DETECT_MS * 20) }
		}
		Unit
	}

	@Test
	fun `room on jvm with the bundled driver actually works`() = runBlocking {
		withTimeout(DETECT_MS) {
			db.probeDao().insert(ProbeEntity(1, "one"))
			assertEquals("one", assertNotNull(db.probeDao().find(1)).value)
			assertEquals(1, db.probeDao().count())
		}
	}

	@Test
	fun `a suspend dao call nested inside a writer transaction does not deadlock`() =
		runBlocking {
			// The shape of all 50 withTransaction sites in :app.
			withTimeout(DETECT_MS) {
				db.useWriterConnection { transactor ->
					transactor.immediateTransaction {
						db.probeDao().insert(ProbeEntity(10, "inside"))
						val found = db.probeDao().find(10)
						assertEquals("inside", assertNotNull(found).value)
					}
				}
			}
			assertEquals("inside", assertNotNull(db.probeDao().find(10)).value)
		}

	@Test
	fun `a failing transaction rolls back`() = runBlocking {
		db.probeDao().insert(ProbeEntity(20, "before"))
		runCatching {
			withTimeout(DETECT_MS) {
				db.useWriterConnection { transactor ->
					transactor.immediateTransaction {
						db.probeDao().insert(ProbeEntity(21, "should not survive"))
						throw IllegalStateException("deliberate")
					}
				}
			}
		}
		assertEquals(null, db.probeDao().find(21))
		assertNotNull(db.probeDao().find(20))
		Unit
	}

	private companion object {

		/** Generous for real work, far under any plausible deadlock. */
		const val DETECT_MS = 5_000L
	}
}
