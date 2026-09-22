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


	// The reported crash. The cache counted entries, and entries are not comparable: three
	// hundred covers is about a gigabyte and three hundred webtoon pages is thirteen. A
	// machine with room for the first dies on the second, with no stack trace, because a
	// failed native allocation does not produce one.
	@Test
	fun `the budget is bytes, so big images are held in smaller numbers`(): Unit = runBlocking {
		// 64 MB of room. A 4 megapixel page is 16 MB decoded, so four fit and no more.
		val cache = ImageCache(maxEntries = 300, maxBytes = 64L * 1024 * 1024)
		repeat(6) { index ->
			responses += 200 to png(width = 2000, height = 2000)
			assertNotNull(cache.load(url + "?p=$index", client))
		}
		assertTrue(
			cache.heldCount() <= 4,
			"kept ${cache.heldCount()} big pages in a 64 MB budget",
		)
		assertTrue(
			cache.heldBytes() <= 64L * 1024 * 1024,
			"held ${cache.heldBytes() / 1024 / 1024} MB against a 64 MB budget",
		)
	}

	@Test
	fun `small images are still held in large numbers`(): Unit = runBlocking {
		val cache = ImageCache(maxEntries = 300, maxBytes = 64L * 1024 * 1024)
		repeat(20) { index ->
			responses += 200 to png(width = 100, height = 100)
			assertNotNull(cache.load(url + "?s=$index", client))
		}
		// 40 KB each, so the byte budget is nowhere near reached and nothing is dropped.
		assertEquals(20, cache.heldCount())
	}

	@Test
	fun `the entry count still caps how many are tracked`(): Unit = runBlocking {
		val cache = ImageCache(maxEntries = 3, maxBytes = 1024L * 1024 * 1024)
		repeat(8) { index ->
			responses += 200 to png(width = 50, height = 50)
			assertNotNull(cache.load(url + "?n=$index", client))
		}
		assertEquals(3, cache.heldCount())
	}

	// Whatever the budget, the page being drawn right now has to survive being cached.
	@Test
	fun `an image larger than the whole budget is still returned`(): Unit = runBlocking {
		val cache = ImageCache(maxEntries = 300, maxBytes = 1L)
		responses += 200 to png(width = 800, height = 800)
		assertNotNull(cache.load(url, client), "a page bigger than the budget came back null")
	}

	@Test
	fun `lowering the budget at runtime evicts down to it`(): Unit = runBlocking {
		val cache = ImageCache(maxEntries = 300, maxBytes = 256L * 1024 * 1024)
		repeat(6) { index ->
			responses += 200 to png(width = 2000, height = 2000)
			cache.load(url + "?r=$index", client)
		}
		assertEquals(6, cache.heldCount())

		cache.maxBytes = 32L * 1024 * 1024

		assertTrue(cache.heldCount() < 6, "lowering the budget kept ${cache.heldCount()} images")
	}

	/** A real PNG of the given size, so the decoded cost is the one being measured. */
	private fun png(width: Int, height: Int): ByteArray {
		val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
		val out = java.io.ByteArrayOutputStream()
		javax.imageio.ImageIO.write(image, "png", out)
		return out.toByteArray()
	}

	/** The smallest valid PNG, so the decode path is exercised rather than mocked. */
	private fun onePixelPng(): ByteArray = java.util.Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
	)
}
