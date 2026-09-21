package org.koitharu.kotatsu.desktop.feature.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the client id comes from, and what happens when there is none.
 *
 * There is no compiled-in id in this build and there must not be one. The important
 * behaviour is therefore the negative: nothing is invented, and the absence is reported
 * in words that say what to do.
 */
class CredentialsTest {

	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var dir: Path

	@Before
	fun setUp() {
		dir = temp.newFolder("config").toPath()
	}

	@Test
	fun `nothing configured means no credentials, not a placeholder`() {
		val source = DefaultCredentialsSource(dir, env = { null })

		for (service in TrackingService.entries) {
			assertNull(service.label, source.credentialsFor(service))
		}
	}

	@Test
	fun `the environment supplies the id and the secret`() {
		val env = mapOf(
			"DROPSAUCE_ANILIST_CLIENT_ID" to "  anilist-id  ",
			"DROPSAUCE_ANILIST_CLIENT_SECRET" to "anilist-secret",
			"DROPSAUCE_MAL_CLIENT_ID" to "mal-id",
		)
		val source = DefaultCredentialsSource(dir, env = env::get)

		val anilist = requireNotNull(source.credentialsFor(TrackingService.AniList))
		assertEquals("anilist-id", anilist.clientId)
		assertEquals("anilist-secret", anilist.clientSecret)

		val mal = requireNotNull(source.credentialsFor(TrackingService.MyAnimeList))
		assertEquals("mal-id", mal.clientId)
		// MyAnimeList is a public client; no secret is not an error there.
		assertNull(mal.clientSecret)
	}

	@Test
	fun `a blank variable counts as unset`() {
		val source = DefaultCredentialsSource(dir, env = mapOf("DROPSAUCE_MAL_CLIENT_ID" to "   ")::get)

		assertNull(source.credentialsFor(TrackingService.MyAnimeList))
	}

	@Test
	fun `the config file is read when the environment is empty`() {
		Files.writeString(
			dir.resolve(CREDENTIALS_FILE_NAME),
			"""{"services":{"MAL":{"client_id":"from-file"},"ANILIST":{"client_id":"a","client_secret":"b"}}}""",
		)
		val source = DefaultCredentialsSource(dir, env = { null })

		assertEquals("from-file", source.credentialsFor(TrackingService.MyAnimeList)?.clientId)
		assertEquals("b", source.credentialsFor(TrackingService.AniList)?.clientSecret)
	}

	@Test
	fun `the environment wins over the file`() {
		Files.writeString(
			dir.resolve(CREDENTIALS_FILE_NAME),
			"""{"services":{"MAL":{"client_id":"from-file"}}}""",
		)
		val source = DefaultCredentialsSource(dir, env = mapOf("DROPSAUCE_MAL_CLIENT_ID" to "from-env")::get)

		assertEquals("from-env", source.credentialsFor(TrackingService.MyAnimeList)?.clientId)
	}

	@Test
	fun `a malformed file reads as nothing configured rather than throwing`() {
		Files.writeString(dir.resolve(CREDENTIALS_FILE_NAME), "{ not json")

		assertNull(DefaultCredentialsSource(dir, env = { null }).credentialsFor(TrackingService.MyAnimeList))
	}

	@Test
	fun `an entry with an empty id is not treated as configured`() {
		Files.writeString(dir.resolve(CREDENTIALS_FILE_NAME), """{"services":{"MAL":{"client_id":""}}}""")

		assertNull(DefaultCredentialsSource(dir, env = { null }).credentialsFor(TrackingService.MyAnimeList))
	}

	@Test
	fun `the unavailable message names the variable and the file`() {
		val anilist = unavailableReason(TrackingService.AniList, dir)
		val mal = unavailableReason(TrackingService.MyAnimeList, dir)

		assertTrue(anilist, anilist.contains("DROPSAUCE_ANILIST_CLIENT_ID"))
		// AniList cannot complete a token exchange without a secret, so the message says so.
		assertTrue(anilist, anilist.contains("DROPSAUCE_ANILIST_CLIENT_SECRET"))
		assertTrue(anilist, anilist.contains(CREDENTIALS_FILE_NAME))

		assertTrue(mal, mal.contains("DROPSAUCE_MAL_CLIENT_ID"))
		// MyAnimeList needs no secret, so asking for one would be misleading.
		assertTrue(mal, !mal.contains("DROPSAUCE_MAL_CLIENT_SECRET"))
	}
}
