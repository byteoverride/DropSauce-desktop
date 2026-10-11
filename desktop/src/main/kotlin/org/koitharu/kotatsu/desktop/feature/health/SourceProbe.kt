package org.koitharu.kotatsu.desktop.feature.health

import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What happened when a source was asked for its front page.
 *
 * The catalogue already carries an `isBroken` flag, and it is not the same question. That
 * flag is a judgement made upstream when the parser was written; this is whether the site
 * answered *today*. Measured on this machine's own source selection: nine of fifteen
 * failed, and only some of those were flagged broken. `MANGAJINX` is not flagged and its
 * domain no longer resolves at all.
 *
 * The verdicts are split by what a reader should *do* about them, not by exception type.
 */
enum class SourceVerdict {

	/** Answered with at least one title. */
	Ok,

	/** Answered, with nothing in it. Not necessarily dead; some sources gate their front page. */
	Empty,

	/**
	 * The domain does not resolve.
	 *
	 * The strongest signal there is, and the only one that is effectively permanent: a
	 * site that has lost its DNS entry is not coming back under that name. Treated as
	 * conclusive on a single observation, but **only** when the sweep's control host
	 * resolved, because every source looks like this when it is your own connection
	 * that is down.
	 */
	Gone,

	/** Reachable but the TLS handshake failed: an expired, self-signed or parked certificate. */
	Untrusted,

	/** Connected and then nothing, or never connected. Often transient. */
	Unreachable,

	/** Answered with an HTTP error: 403, 404, 5xx, or a Cloudflare 5xx variant. */
	Refused,

	/** Answered with something the parser could not read. The site is alive and has changed shape. */
	Changed,

	/** Answered, and wants credentials the app does not have. Not a fault. */
	NeedsAccount,

	/** Asked us to slow down. Says nothing about health; the source is plainly alive. */
	RateLimited,
}

/**
 * Whether this verdict means the source is worth offering to a reader.
 *
 * [SourceVerdict.RateLimited] and [SourceVerdict.NeedsAccount] are healthy: one proves
 * the site is up and talking, the other that it is up and asking for a login. Hiding
 * either would remove working sources, which is the failure mode this whole feature has
 * to avoid, because a catalogue that hides good sources is worse than one that lists
 * dead ones.
 */
val SourceVerdict.isUsable: Boolean
	get() = when (this) {
		SourceVerdict.Ok, SourceVerdict.RateLimited, SourceVerdict.NeedsAccount -> true
		SourceVerdict.Empty, SourceVerdict.Gone, SourceVerdict.Untrusted,
		SourceVerdict.Unreachable, SourceVerdict.Refused, SourceVerdict.Changed,
		-> false
	}

/**
 * Whether one observation of this verdict is enough to act on.
 *
 * Only a dead name is. Everything else gets the benefit of the doubt until it has failed
 * repeatedly, because a timeout at the wrong moment, a site restarting, or one bad
 * response is not a dead source, and a catalogue that forgets a good source after a
 * single blip will quietly shrink every time the network hiccups.
 */
val SourceVerdict.isConclusive: Boolean
	get() = this == SourceVerdict.Gone

/** One source's answer, with enough detail to tell a reader why. */
data class SourceReport(
	val source: String,
	val verdict: SourceVerdict,
	/** A one-line reason, for the row in the report. Empty when there is nothing to add. */
	val reason: String = "",
	val items: Int = 0,
	val millis: Long = 0,
)

/**
 * Turns whatever a source threw into a verdict.
 *
 * Deliberately exhaustive over the library's own exception types rather than matching on
 * message text, which changes between parser versions. The one place a message is read
 * is the HTTP status, because the status code is the useful part and the library folds
 * it into a jsoup exception rather than exposing it.
 */
fun classify(error: Throwable): Pair<SourceVerdict, String> = when (error) {
	is UnknownHostException -> SourceVerdict.Gone to "the address does not resolve"
	is SSLException -> SourceVerdict.Untrusted to "the certificate could not be verified"
	is SocketTimeoutException -> SourceVerdict.Unreachable to "timed out"
	is ConnectException, is NoRouteToHostException -> SourceVerdict.Unreachable to "could not connect"
	is TooManyRequestExceptions -> SourceVerdict.RateLimited to "asked us to slow down"
	is AuthRequiredException -> SourceVerdict.NeedsAccount to "needs an account"
	is NotFoundException -> SourceVerdict.Refused to "answered 404"
	is ContentUnavailableException -> SourceVerdict.Refused to (error.message ?: "content unavailable")
	is ParseException -> SourceVerdict.Changed to "answered with something the parser cannot read"
	else -> httpStatusOf(error)?.let { code ->
		SourceVerdict.Refused to "answered $code"
	} ?: fallback(error)
}

/**
 * The HTTP status, if this is a status failure.
 *
 * jsoup's `HttpStatusException` is not on the desktop classpath as a type we import, and
 * the library wraps several shapes, so the status is recovered from the message. Narrow
 * on purpose: it matches the library's own `Status=NNN` spelling and nothing else, so a
 * message that merely contains a number cannot be read as a status.
 */
private fun httpStatusOf(error: Throwable): Int? {
	val message = error.message ?: return null
	return STATUS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private val STATUS = Regex("""Status\s*=\s*(\d{3})""")

private fun fallback(error: Throwable): Pair<SourceVerdict, String> = when (error) {
	// Everything okhttp and okio throw that is not one of the above: a reset connection,
	// a truncated body, a protocol error. All "the network did not deliver".
	is IOException -> SourceVerdict.Unreachable to (error.message?.firstLine() ?: "no answer")
	else -> SourceVerdict.Changed to (error.message?.firstLine() ?: error::class.simpleName.orEmpty())
}

private fun String.firstLine(): String = lineSequence().first().take(MAX_REASON)

private const val MAX_REASON = 120
