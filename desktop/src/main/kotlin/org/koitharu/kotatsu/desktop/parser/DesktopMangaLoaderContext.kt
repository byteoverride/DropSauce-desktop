package org.koitharu.kotatsu.desktop.parser

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Desktop host for the kotatsu-parsers catalogue. See DECISIONS.md D1.
 *
 * One instance per parser. That is not an arbitrary choice:
 * `MangaParser` extends `okhttp3.Interceptor`, and `MangaParserWrapper.intercept` is the
 * only place a parser's `requestHeaders` (User-Agent, Referer) are merged into an
 * outgoing request. Nothing in the library installs that interceptor. A host that shares
 * one client across every parser therefore sends no per-source headers and collects 403s,
 * while still compiling and still working against undemanding sources. So the client this
 * context hands back has its own parser installed, which means the parser and the context
 * have to be paired. Use [DesktopParsers.create] rather than constructing this directly.
 */
internal class DesktopMangaLoaderContext(
	private val baseHttpClient: OkHttpClient,
	private val cookieJarInstance: CookieJar,
	private val configProvider: (MangaSource) -> MangaSourceConfig,
	private val userAgent: String,
) : MangaLoaderContext() {

	/**
	 * Set once by [DesktopParsers.create] immediately after `newParserInstance` returns.
	 * It cannot be a constructor parameter because creating the parser requires the
	 * context that is supposed to hold it.
	 */
	@Volatile
	private var parser: MangaParser? = null

	/**
	 * Built eagerly-but-indirectly: the interceptor resolves [parser] when a request is
	 * made, not when the client is assembled.
	 *
	 * This matters. Several parsers (HMANGABAT, MANGANELO_COM, MANGAKAKALOT, MANGANATO
	 * and others) read `context.httpClient` from their own constructor, which runs inside
	 * `newParserInstance` and therefore before [attach] can possibly have been called.
	 * Resolving the parser at build time threw for all of them. They only *hold* the
	 * client during construction; nothing issues a request until well after attach, so
	 * late binding is both sufficient and correct.
	 */
	private val client: OkHttpClient by lazy {
		baseHttpClient.newBuilder()
			.addInterceptor(
				Interceptor { chain ->
					val installed = checkNotNull(parser) {
						"A request was made through DesktopMangaLoaderContext before its parser " +
							"was attached. Build parsers through DesktopParsers.create."
					}
					installed.intercept(chain)
				},
			)
			.build()
	}

	fun attach(value: MangaParser) {
		check(parser == null) { "Parser already attached" }
		parser = value
	}

	override val httpClient: OkHttpClient
		get() = client

	override val cookieJar: CookieJar
		get() = cookieJarInstance

	override fun getConfig(source: MangaSource): MangaSourceConfig = configProvider(source)

	override fun getDefaultUserAgent(): String = userAgent

	override fun createBitmap(width: Int, height: Int): Bitmap =
		AwtBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

	override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
		val body = response.body ?: return response
		val bytes = body.bytes()
		val decoded = ImageIO.read(bytes.inputStream())
			?: throw IllegalStateException(
				"Cannot decode a ${bytes.size} byte image for descrambling; " +
					"ImageIO has no reader for it (WebP and AVIF are not supported)",
			)
		val result = redraw(AwtBitmap(decoded)) as AwtBitmap
		val out = ByteArrayOutputStream(bytes.size)
		check(ImageIO.write(result.image, DESCRAMBLE_OUTPUT_FORMAT, out)) {
			"No ImageIO writer for $DESCRAMBLE_OUTPUT_FORMAT"
		}
		return response.newBuilder()
			.body(out.toByteArray().toResponseBody(DESCRAMBLE_OUTPUT_MEDIA_TYPE))
			.build()
	}

	/**
	 * Not supported on desktop, deliberately. See DECISIONS.md D17.
	 *
	 * Every call site in the catalogue uses the two-argument overload and runs its script
	 * inside a loaded page, reading `window.localStorage` and `window.location.search`.
	 * An embeddable engine cannot provide a DOM, an origin or navigation, so a partial
	 * implementation would be a wrong answer rather than a limited one. Measured blast
	 * radius is 11 of 1270 sources.
	 */
	override suspend fun evaluateJs(script: String): String = throw UnsupportedOperationException(
		"JavaScript evaluation is not available on desktop; this source cannot be used",
	)

	override suspend fun evaluateJs(baseUrl: String, script: String): String =
		throw UnsupportedOperationException(
			"JavaScript evaluation is not available on desktop; this source cannot be used",
		)

	private companion object {

		// PNG because descrambling is lossless reassembly; re-encoding as JPEG would add
		// generational artefacts to every page of an affected source.
		const val DESCRAMBLE_OUTPUT_FORMAT = "png"
		val DESCRAMBLE_OUTPUT_MEDIA_TYPE = "image/png".toMediaTypeOrNull()
	}
}

/** Creates parsers with their context correctly paired. */
internal object DesktopParsers {

	fun create(
		source: MangaParserSource,
		httpClient: OkHttpClient,
		cookieJar: CookieJar,
		userAgent: String,
		configProvider: (MangaSource) -> MangaSourceConfig,
	): MangaParser {
		val context = DesktopMangaLoaderContext(httpClient, cookieJar, configProvider, userAgent)
		val parser = context.newParserInstance(source)
		context.attach(parser)
		return parser
	}
}
