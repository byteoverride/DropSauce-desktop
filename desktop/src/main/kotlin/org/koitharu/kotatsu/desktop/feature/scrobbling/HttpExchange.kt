package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The one way this area talks to the network.
 *
 * An interface, and the only one, so every test in this area runs with no network at all.
 * The alternative that the Android code uses, an `OkHttpClient` with an `Authenticator` and
 * an `Interceptor` per service, puts the refresh-on-401 rule inside OkHttp where a test can
 * only reach it by standing up a local server. Here the rule lives in [ServiceSession] and
 * a test counts the requests directly.
 */
fun interface HttpExchange {

	suspend fun execute(request: HttpRequest): HttpResponse
}

/** A request body, in the two shapes the tracking APIs actually take. */
sealed interface HttpBody {

	/** `application/x-www-form-urlencoded`. A list, not a map: MAL repeats no key but order matters for readable tests. */
	data class Form(val fields: List<Pair<String, String>>) : HttpBody

	/** `application/json; charset=utf-8`, already serialised. */
	data class Json(val text: String) : HttpBody
}

data class HttpRequest(
	val method: String,
	val url: String,
	val headers: Map<String, String> = emptyMap(),
	val body: HttpBody? = null,
)

data class HttpResponse(
	val code: Int,
	val body: String,
) {

	val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The production [HttpExchange].
 *
 * Its own [OkHttpClient], not one from `FeatureContext.clientFor`, because that client
 * carries a manga source's headers and interceptors. Sending a source's User-Agent and
 * cookie jar to AniList would be wrong in both directions.
 */
class OkHttpExchange(private val client: OkHttpClient) : HttpExchange {

	override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
		val builder = Request.Builder().url(request.url)
		for ((name, value) in request.headers) {
			builder.header(name, value)
		}
		val body = when (val payload = request.body) {
			null -> null
			is HttpBody.Form -> FormBody.Builder().apply {
				for ((name, value) in payload.fields) {
					add(name, value)
				}
			}.build()

			is HttpBody.Json -> payload.text.toRequestBody(JSON_MEDIA_TYPE)
		}
		builder.method(request.method, body)
		client.newCall(builder.build()).execute().use { response ->
			// Read the body even on a failure: every one of these APIs puts the reason a
			// call was rejected in the body, and a bare status code is not something the
			// user can act on.
			HttpResponse(code = response.code, body = response.body.string())
		}
	}

	private companion object {

		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
