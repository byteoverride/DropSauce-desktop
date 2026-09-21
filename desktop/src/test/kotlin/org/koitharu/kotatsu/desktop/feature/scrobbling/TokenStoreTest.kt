package org.koitharu.kotatsu.desktop.feature.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The token file holds live credentials, so its mode is part of the contract.
 *
 * The permission assertions run against the real filesystem, and the first test is a
 * positive control: it proves the check can see a wide mode before any test uses it to
 * claim a narrow one. Without that, "the permissions are 0600" and "the assertion does
 * nothing" look identical from the outside.
 */
class TokenStoreTest {

	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var dir: Path
	private lateinit var file: Path

	private val ownerOnly = PosixFilePermissions.fromString("rw-------")

	@Before
	fun setUp() {
		dir = temp.newFolder("config").toPath()
		file = dir.resolve(TokenStore.FILE_NAME)
		assertTrue(
			"this test is meaningless off a POSIX filesystem",
			dir.fileSystem.supportedFileAttributeViews().contains("posix"),
		)
	}

	@Test
	fun `positive control - the permission check can see a world-readable file`() {
		val control = dir.resolve("control.json")
		Files.writeString(control, "{}")
		Files.setPosixFilePermissions(control, PosixFilePermissions.fromString("rw-rw-rw-"))

		val observed = Files.getPosixFilePermissions(control)

		assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), observed)
		assertNotEquals(ownerOnly, observed)
	}

	@Test
	fun `the token file is created owner-only`() {
		val store = TokenStore(file)

		store.put(TrackingService.AniList, StoredTokens(accessToken = "secret-access"))

		assertTrue(Files.isRegularFile(file))
		assertEquals(ownerOnly, Files.getPosixFilePermissions(file))
	}

	@Test
	fun `a rewrite keeps the mode and leaves no temporary file behind`() {
		val store = TokenStore(file)
		store.put(TrackingService.AniList, StoredTokens(accessToken = "one"))
		store.put(TrackingService.MyAnimeList, StoredTokens(accessToken = "two"))
		store.update(TrackingService.AniList) { it.copy(lastSyncAt = 42L) }

		assertEquals(ownerOnly, Files.getPosixFilePermissions(file))
		val leftovers = Files.list(dir).use { stream ->
			stream.map { it.fileName.toString() }.toList()
		}
		assertEquals(listOf(TokenStore.FILE_NAME), leftovers)
	}

	@Test
	fun `a file left world-readable by an earlier write is narrowed on the next one`() {
		val store = TokenStore(file)
		store.put(TrackingService.AniList, StoredTokens(accessToken = "one"))
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))

		store.put(TrackingService.AniList, StoredTokens(accessToken = "two"))

		assertEquals(ownerOnly, Files.getPosixFilePermissions(file))
	}

	@Test
	fun `tokens survive a reopen and a removal is persisted`() {
		TokenStore(file).put(
			TrackingService.MyAnimeList,
			StoredTokens(accessToken = "at", refreshToken = "rt", expiresAt = 1_000L, accountName = "someone"),
		)

		val reopened = TokenStore(file)
		val loaded = requireNotNull(reopened.get(TrackingService.MyAnimeList))
		assertEquals("at", loaded.accessToken)
		assertEquals("rt", loaded.refreshToken)
		assertEquals(1_000L, loaded.expiresAt)
		assertEquals("someone", loaded.accountOrNull()?.nickname)
		assertNull(reopened.get(TrackingService.AniList))

		reopened.remove(TrackingService.MyAnimeList)
		assertNull(TokenStore(file).get(TrackingService.MyAnimeList))
	}

	@Test
	fun `an unreadable file reads as no tokens rather than throwing`() {
		Files.writeString(file, "this is not json")

		assertNull(TokenStore(file).get(TrackingService.AniList))
	}

	@Test
	fun `expiry is judged with a skew so a token expiring mid-flight counts as expired`() {
		val tokens = StoredTokens(accessToken = "at", expiresAt = 100_000L)

		assertTrue(tokens.isExpired(100_000L))
		assertTrue("inside the skew window", tokens.isExpired(100_000L - StoredTokens.SKEW_MILLIS + 1))
		assertTrue(!tokens.isExpired(100_000L - StoredTokens.SKEW_MILLIS - 1))
		// A service that never said when the token expires is never pre-emptively renewed.
		assertTrue(!StoredTokens(accessToken = "at", expiresAt = 0L).isExpired(Long.MAX_VALUE))
	}
}
