package org.koitharu.kotatsu.desktop.parser

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source configuration.
 *
 * v1 answers every key with its declared default. Parsers use this for things like the
 * preferred domain and page size; overriding them is a settings feature that v1 does not
 * expose, and returning the default is the correct behaviour in its absence rather than a
 * placeholder.
 */
internal class DefaultSourceConfig : MangaSourceConfig {

	override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
}

/**
 * In-memory cookie jar, shared across sources.
 *
 * Sources that set a session cookie during a browse keep it for the life of the process.
 * Cookies are not persisted to disk in v1, so a restart starts a fresh session; that is a
 * real limitation, not a failure, and it matters only for sources that require login,
 * which v1 does not support anyway.
 */
internal class InMemoryCookieJar : CookieJar {

	private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val now = System.currentTimeMillis()
		val cookies = store[url.host] ?: return emptyList()
		synchronized(cookies) {
			cookies.removeAll { it.expiresAt < now }
			return cookies.filter { it.matches(url) }
		}
	}

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) return
		val existing = store.getOrPut(url.host) { ArrayList() }
		synchronized(existing) {
			for (cookie in cookies) {
				existing.removeAll { it.name == cookie.name && it.path == cookie.path }
				existing.add(cookie)
			}
		}
	}
}
