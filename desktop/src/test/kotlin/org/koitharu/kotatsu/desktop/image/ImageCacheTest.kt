package org.koitharu.kotatsu.desktop.image

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a failed image is remembered.
 *
 * Two reports came in as "the cover is sometimes missing" and "this page could not be
 * loaded", and neither could be answered, because every failure here looked identical
 * from outside and a run of them lasted until the app was restarted.
 */
class ImageCacheTest {

	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val client = OkHttpClient()
	private val responses = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()
	private val hits = AtomicInteger(0)

	init {
		server.createContext("/image") { exchange: HttpExchange ->
			hits.incrementAndGet()
			val (code, body) = responses.poll() ?: (500 to ByteArray(0))
			exchange.sendResponseHeaders(code, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		}
		server.start()
	}

	@AfterTest
	fun tearDown() = server.stop(0)

	private val url get() = "http://127.0.0.1:${server.address.port}/image"

	@Test
	fun `a real image is decoded and cached`(): Unit = runBlocking {
		responses += 200 to onePixelPng()
		val cache = ImageCache()
		assertNotNull(cache.load(url, client))
		// Second call is served from memory, not the network.
		assertNotNull(cache.load(url, client))
		assertEquals(1, hits.get())
		assertNull(cache.failureReason(url))
	}

	@Test
	fun `an http error is reported as itself, not as a missing cover`(): Unit = runBlocking {
		responses += 403 to ByteArray(0)
		val cache = ImageCache()
		assertNull(cache.load(url, client))
		assertEquals("the source answered 403", cache.failureReason(url))
	}

	@Test
	fun `something Skia cannot decode says so and is not retried`(): Unit = runBlocking {
		repeat(2) { responses += 200 to "<html>not an image</html>".toByteArray() }
		val cache = ImageCache()
		assertNull(cache.load(url, client))
		assertEquals("this image format is not supported", cache.failureReason(url))
		assertTrue(cache.isExhausted(url), "a format failure will not fix itself")
		// Not asked again, because the answer cannot change.
		assertNull(cache.load(url, client))
		assertEquals(1, hits.get())
	}

	// The reported bug. A blip while a grid loads used to blank those covers until the
	// app was restarted: no retry on scrolling back, and no way to ask again.
	@Test
	fun `a run of transient failures expires instead of lasting the session`(): Unit = runBlocking {
		var clock = 1_000L
		val cache = ImageCache(now = { clock })
		repeat(3) {
			responses += 503 to ByteArray(0)
			assertNull(cache.load(url, client))
		}
		assertTrue(cache.isExhausted(url), "three misses should pause further requests")

		// Once the window passes, the url is worth another try, and it succeeds.
		clock += 61_000L
		assertTrue(!cache.isExhausted(url), "a transient failure must not be permanent")
		responses += 200 to onePixelPng()
		assertNotNull(cache.load(url, client))
	}

	@Test
	fun `a transport failure names the transport, not the decoder`(): Unit = runBlocking {
		val cache = ImageCache()
		// Nothing is listening on this port.
		val dead = "http://127.0.0.1:1/image"
		assertNull(cache.load(dead, client))
		val reason = cache.failureReason(dead)
		assertNotNull(reason)
		assertTrue(
			reason != "this image format is not supported",
			"a refused connection was reported as a format problem: $reason",
		)
	}

	/** The smallest valid PNG, so the decode path is exercised rather than mocked. */
	private fun onePixelPng(): ByteArray = java.util.Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
	)
}
