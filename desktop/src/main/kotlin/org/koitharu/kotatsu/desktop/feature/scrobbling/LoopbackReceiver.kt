package org.koitharu.kotatsu.desktop.feature.scrobbling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/** What arrived on the loopback redirect. */
sealed interface RedirectOutcome {

	data class Code(val code: String, val state: String?) : RedirectOutcome

	/** The service refused, most often `access_denied` when the user pressed Cancel. */
	data class Failed(val error: String, val description: String?) : RedirectOutcome

	data object TimedOut : RedirectOutcome
}

/**
 * A one-shot HTTP listener on the loopback interface, for an OAuth redirect.
 *
 * There is no intent filter on a desktop, so the redirect has to come back to a port this
 * process is holding. Constructing the object binds the socket; the port is only knowable
 * after that, and the port is part of the redirect URI that goes into the authorize URL,
 * so binding *must* happen before the browser opens. Building it this way makes that
 * ordering impossible to get wrong: there is no way to read [redirectUri] without having
 * bound first.
 *
 * Bound to the loopback address specifically, not to the wildcard: on a shared or
 * multi-homed machine a wildcard bind would accept an authorization code posted by
 * anything that can reach the host.
 */
class LoopbackReceiver(
	bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
	/** The path the service is told to redirect to. Anything else gets a 404. */
	private val path: String = "/oauth/callback",
) : Closeable {

	private val server = ServerSocket(0, BACKLOG, bindAddress)

	val port: Int get() = server.localPort

	/**
	 * The redirect URI to register with the service and to send in the authorize request.
	 *
	 * Literal `127.0.0.1` rather than `localhost`: RFC 8252 section 8.3 recommends the
	 * literal address, because `localhost` depends on a name resolution the user's host
	 * may resolve to something else (or to ::1 while this socket is on IPv4).
	 */
	val redirectUri: String get() = "http://127.0.0.1:$port$path"

	/**
	 * Waits for the redirect, for at most [timeoutMillis] in total.
	 *
	 * Returns [RedirectOutcome.TimedOut] rather than blocking forever, because the user may
	 * simply close the browser tab and nothing else would ever release this thread or the
	 * port. Connections that are not the redirect (a favicon fetch, a stray probe) are
	 * answered and ignored, and the remaining budget keeps counting down across them.
	 */
	suspend fun awaitRedirect(timeoutMillis: Long): RedirectOutcome = withContext(Dispatchers.IO) {
		// `accept` is a blocking call that cancellation cannot interrupt, so a cancelled
		// sign-in would otherwise keep the port for the rest of the timeout. Closing the
		// socket from the cancellation handler makes the blocked accept return at once.
		val onCancel = coroutineContext.job.invokeOnCompletion { close() }
		try {
			acceptUntil(System.currentTimeMillis() + timeoutMillis)
		} finally {
			onCancel.dispose()
		}
	}

	private fun acceptUntil(deadline: Long): RedirectOutcome {
		while (true) {
			val remaining = deadline - System.currentTimeMillis()
			if (remaining <= 0L) {
				return RedirectOutcome.TimedOut
			}
			// Bounded by the remaining budget rather than the whole timeout, so a
			// succession of unrelated connections cannot extend the wait indefinitely.
			server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
			val socket = try {
				server.accept()
			} catch (e: SocketTimeoutException) {
				return RedirectOutcome.TimedOut
			} catch (e: SocketException) {
				// The listener was closed from elsewhere, which on this path means the
				// sign-in was cancelled. There will be no redirect either way.
				return RedirectOutcome.TimedOut
			}
			val outcome = socket.use { handle(it) }
			if (outcome != null) {
				return outcome
			}
		}
	}

	/** Null when the connection was not the redirect and the wait should continue. */
	private fun handle(socket: Socket): RedirectOutcome? {
		socket.soTimeout = READ_TIMEOUT_MILLIS
		val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
		val requestLine = try {
			reader.readLine()
		} catch (e: SocketTimeoutException) {
			null
		}
		if (requestLine.isNullOrBlank()) {
			return null
		}
		// "GET /oauth/callback?code=... HTTP/1.1". Only the target is needed; the headers
		// and any body are irrelevant and are not read, which also means a client that
		// never finishes sending them cannot hold this thread.
		val target = requestLine.split(' ').getOrNull(1) ?: return null
		if (target.substringBefore('?') != path) {
			respond(socket, "404 Not Found", NOT_FOUND_PAGE)
			return null
		}
		val params = parseQuery(target.substringAfter('?', ""))
		val error = params["error"]
		if (error != null) {
			respond(socket, "200 OK", resultPage(DENIED_TITLE, describeError(error, params["error_description"])))
			return RedirectOutcome.Failed(error, params["error_description"])
		}
		val code = params["code"]
		if (code.isNullOrEmpty()) {
			respond(socket, "400 Bad Request", resultPage(DENIED_TITLE, "The redirect carried no authorization code."))
			return RedirectOutcome.Failed("invalid_request", "The redirect carried no authorization code.")
		}
		respond(socket, "200 OK", resultPage(SUCCESS_TITLE, "You can close this tab and go back to DropSauce."))
		return RedirectOutcome.Code(code, params["state"])
	}

	private fun respond(socket: Socket, status: String, html: String) {
		val bytes = html.toByteArray(StandardCharsets.UTF_8)
		val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
		writer.write("HTTP/1.1 $status\r\n")
		writer.write("Content-Type: text/html; charset=utf-8\r\n")
		writer.write("Content-Length: ${bytes.size}\r\n")
		writer.write("Connection: close\r\n")
		writer.write("\r\n")
		writer.write(html)
		writer.flush()
	}

	override fun close() {
		// Idempotent and never throws: this runs in a `finally`, where an exception would
		// replace whatever the flow was actually reporting.
		try {
			server.close()
		} catch (e: Exception) {
			System.err.println("Tracking: could not close the loopback listener: ${e.message}")
		}
	}

	private companion object {

		const val BACKLOG = 2
		const val READ_TIMEOUT_MILLIS = 5_000
		const val SUCCESS_TITLE = "Signed in"
		const val DENIED_TITLE = "Sign-in failed"

		val NOT_FOUND_PAGE = resultPage("Not here", "This address is not part of the sign-in.")
	}
}

internal fun parseQuery(query: String): Map<String, String> {
	if (query.isEmpty()) {
		return emptyMap()
	}
	val result = LinkedHashMap<String, String>()
	for (pair in query.split('&')) {
		if (pair.isEmpty()) {
			continue
		}
		val name = pair.substringBefore('=')
		val value = pair.substringAfter('=', "")
		result[decode(name)] = decode(value)
	}
	return result
}

private fun decode(value: String): String = try {
	URLDecoder.decode(value, StandardCharsets.UTF_8)
} catch (e: IllegalArgumentException) {
	// A malformed percent escape is the service's problem, not a reason to lose the rest
	// of the query. Keeping the raw text at least makes the failure readable in the UI.
	value
}

/** Turns an OAuth error code into something a person can read. */
internal fun describeError(error: String, description: String?): String = when {
	!description.isNullOrBlank() -> description
	error == "access_denied" -> "You declined access, so nothing was connected."
	error == "invalid_client" -> "The service rejected the client id."
	error == "invalid_scope" -> "The service rejected the requested permissions."
	else -> "The service returned \"$error\"."
}

/**
 * The page the browser lands on.
 *
 * Self-contained, no network references, because the browser tab may well be the only
 * thing the user is looking at and a half-loaded page reads as a failure. Escaped rather
 * than interpolated raw: [message] can carry a service-supplied `error_description`.
 */
internal fun resultPage(title: String, message: String): String = """
	<!doctype html>
	<html lang="en">
	<head>
	<meta charset="utf-8">
	<title>${escapeHtml(title)}</title>
	<style>
	body { font-family: system-ui, sans-serif; background: #1b1b1f; color: #e4e2e6;
	       display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
	main { max-width: 28rem; padding: 2rem; text-align: center; }
	h1 { font-size: 1.4rem; font-weight: 600; margin: 0 0 0.75rem; }
	p { margin: 0; line-height: 1.5; color: #c8c5cd; }
	</style>
	</head>
	<body><main><h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p></main></body>
	</html>
""".trimIndent()

internal fun escapeHtml(value: String): String = buildString(value.length) {
	for (char in value) {
		when (char) {
			'&' -> append("&amp;")
			'<' -> append("&lt;")
			'>' -> append("&gt;")
			'"' -> append("&quot;")
			'\'' -> append("&#39;")
			else -> append(char)
		}
	}
}
