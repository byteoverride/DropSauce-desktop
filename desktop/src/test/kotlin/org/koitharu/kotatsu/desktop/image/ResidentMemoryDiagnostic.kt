package org.koitharu.kotatsu.desktop.image

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a cached page actually costs the process.
 *
 * [ImageCache] charges each entry `width * height * 4`, on the assumption that holding an
 * `ImageBitmap` means holding its pixels. Skia's `Image.makeFromEncoded` is lazy, so that
 * assumption may be wrong in either direction, and the whole memory budget is built on
 * it. If the pixels are not resident then the budget is throttling the cache for no
 * reason; if they are, it is doing exactly its job.
 *
 * Measured as resident set size, not heap, because Skia allocates off-heap and a heap
 * measurement would miss the thing being asked about.
 */
class ResidentMemoryDiagnostic {

	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val client = OkHttpClient()
	private lateinit var page: ByteArray

	@BeforeTest
	fun setUp() {
		assumeTrue("set -Dlive=true to run", System.getProperty("live") == "true")
		assumeTrue("needs /proc to read RSS", File("/proc/self/status").exists())
		page = encode(WIDTH, HEIGHT)
		server.createContext("/page") { exchange ->
			exchange.responseHeaders.add("Content-Type", "image/jpeg")
			exchange.sendResponseHeaders(200, page.size.toLong())
			exchange.responseBody.use { it.write(page) }
		}
		server.start()
	}

	@AfterTest
	fun tearDown() {
		if (::page.isInitialized) server.stop(0)
	}

	@Test
	fun `measure what holding decoded pages costs`() = runBlocking {
		val decodedEach = WIDTH.toLong() * HEIGHT * 4 / 1_000_000
		println("RESIDENT page ${WIDTH}x$HEIGHT  encoded ${page.size / 1024} KB  charged as $decodedEach MB each")

		// A budget far larger than the test will use, so eviction plays no part.
		val cache = ImageCache(maxEntries = 1000, maxBytes = 8L * 1024 * 1024 * 1024)
		val base = rss()
		println("RESIDENT baseline ${base}MB")

		val count = 12
		repeat(count) { index ->
			cache.load("http://127.0.0.1:${server.address.port}/page?i=$index", client)
			if (index % 4 == 3) println("RESIDENT after ${index + 1} pages: ${rss()}MB  (cache says ${cache.heldBytes() / 1_000_000}MB)")
		}

		val grown = rss() - base
		val chargedTotal = decodedEach * count
		println("RESIDENT growth after $count pages: ${grown}MB, cache charged ${chargedTotal}MB")
		println(
			"RESIDENT verdict: " + when {
				grown > chargedTotal * 0.6 -> "pixels ARE resident; the byte budget models reality"
				grown < chargedTotal * 0.2 -> "pixels are NOT resident; the budget is charging for memory nobody is using"
				else -> "partly resident; Skia is keeping some rasters and dropping others"
			},
		)

		// Drawing is what forces a lazy image to rasterise, so the number above is the
		// cost of merely holding one. Anything the reader has actually shown may cost
		// more, and that is the figure a budget would need.
		println("RESIDENT note: nothing here was drawn, so this is the holding cost only")
	}

	private fun rss(): Long {
		Runtime.getRuntime().gc()
		Thread.sleep(300)
		val line = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }
		return line.filter(Char::isDigit).toLong() / 1024
	}

	private fun encode(width: Int, height: Int): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val random = java.util.Random(7)
		for (y in 0 until height step 2) {
			for (x in 0 until width step 2) {
				image.setRGB(x, y, random.nextInt())
			}
		}
		return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
	}

	private companion object {

		const val WIDTH = 900
		const val HEIGHT = 12000
	}
}
