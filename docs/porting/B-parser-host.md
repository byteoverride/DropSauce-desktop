# B - Parser host: what `MangaLoaderContext` actually costs

Phase 1 recon, read-only. Agent B. Repo at `desktop-port` / `1d30c9b`.

Evidence base:

- App source under `app/src/main/kotlin/org/koitharu/kotatsu/{core/parser,core/network,core/model,core/exceptions,browser}`.
- The dependency jar `com.github.YakaTeam:kotatsu-parsers:21d4b79b5f`
  (7,223,495 bytes), extracted to a scratch dir. 3365 `.class` entries,
  of which 3213 are under `org/koitharu/kotatsu/parsers/site/`
  (`find org/koitharu/kotatsu/parsers/site -name '*.class' | wc -l` = 3213).
- A full `javap -c -p` disassembly of all 3365 classes
  (`xargs -n 400 javap -c -p` -> 704,580 lines, 36 MB, zero errors on stderr),
  reduced to a 91,372-row `class<TAB>invoked-method` table. Every call-count
  below is a count of real `invokevirtual` / `invokeinterface` sites in that
  table, not a text search.
- A class-hierarchy map built from the 3365 `extends` declarations in the
  same disassembly.

## 1. The contract, exactly

`javap -p org.koitharu.kotatsu.parsers.MangaLoaderContext` on the shipped jar:

```
public abstract class org.koitharu.kotatsu.parsers.MangaLoaderContext {
  public MangaLoaderContext();
  public abstract okhttp3.OkHttpClient getHttpClient();
  public abstract okhttp3.CookieJar getCookieJar();
  public final MangaParser newParserInstance(MangaParserSource);
  public final util.LinkResolver newLinkResolver(okhttp3.HttpUrl);
  public final util.LinkResolver newLinkResolver(java.lang.String);
  public java.lang.String encodeBase64(byte[]);
  public byte[] decodeBase64(java.lang.String);
  public java.util.List<java.util.Locale> getPreferredLocales();
  public abstract Object evaluateJs(String, Continuation<? super String>);
  public abstract Object evaluateJs(String, String, Continuation<? super String>);
  public java.lang.Void requestBrowserAction(MangaParser, String);
  public abstract config.MangaSourceConfig getConfig(model.MangaSource);
  public abstract java.lang.String getDefaultUserAgent();
  public abstract okhttp3.Response redrawImageResponse(
      okhttp3.Response, Function1<? super bitmap.Bitmap, ? extends bitmap.Bitmap>);
  public abstract bitmap.Bitmap createBitmap(int, int);
}
```

**Correction to ARCHITECTURE.md §12.** It says `MangaLoaderContext` is
"a 7-method abstract class". It is a 17-member class of which **8 are
abstract**, not 7 - §12's own list omits the second `evaluateJs`
overload while its prose says "`evaluateJs` x2". The count is 8:
`getHttpClient`, `getCookieJar`, `evaluateJs(String)`,
`evaluateJs(String, String)`, `getConfig`, `getDefaultUserAgent`,
`redrawImageResponse`, `createBitmap`.

Four members are non-abstract and already implemented in the jar, so the
desktop host inherits them for free:

| Member | Inherited body (from `javap -c`) | Callers in jar |
|---|---|---|
| `encodeBase64(byte[])` | `java.util.Base64.getEncoder().encodeToString` | 4 classes |
| `decodeBase64(String)` | `java.util.Base64.getDecoder().decode` | 15 sites / 12 classes |
| `getPreferredLocales()` | `listOf(Locale.getDefault())` | `MangaDexParser`, `WeebDex` |
| `requestBrowserAction(parser, url)` | **throws** `UnsupportedOperationException("Browser is not available")` | `Koharu`, `ZenMangaParser` |

`newParserInstance` and `newLinkResolver` are `final`; the host cannot
change them. `newParserInstance` is a one-line delegate to
`MangaParserFactoryKt.newParser(source, this)`, which switches on
`MangaParserSource.ordinal()`.

### Complete call tally of context members from inside the jar

Every `MangaLoaderContext.*` invoke site in the disassembly:

| Member | invoke sites | distinct calling classes |
|---|---|---|
| `getCookieJar` | 45 | 28 |
| `decodeBase64` | 15 | 12 |
| `getHttpClient` | 8 | 8 |
| `createBitmap` | 7 | 6 |
| `redrawImageResponse` | 6 | 6 |
| `evaluateJs(String,String)` | 5 | 4 |
| `encodeBase64` | 4 | 4 |
| `requestBrowserAction` | 3 | 2 |
| `newParserInstance` | 3 | 2 |
| `getPreferredLocales` | 3 | 2 |
| `getDefaultUserAgent` | 2 | 2 |
| `getConfig` | 2 | 2 |
| **`evaluateJs(String)`** | **0** | **0** |

### `getHttpClient` and `getCookieJar`

`AbstractMangaParser`'s constructor (`javap -c`, offsets 79-104) builds
the web client eagerly:

```
new OkHttpWebClient(context.getHttpClient(), source)
```

so **every** parser instantiation calls `getHttpClient()` and
`getDefaultUserAgent()` in its constructor (offsets 61-76 and 79-104).
1287 non-inner classes descend from `AbstractMangaParser`.

What `OkHttpWebClient` does with the client (`javap -c`):

- `addTags(Request.Builder)` calls
  `builder.tag(MangaSource.class, mangaSource)` on **every** request. This
  is the hook the host's own interceptors use to know which source a
  request belongs to - the app already relies on exactly this tag
  (`CommonHeadersInterceptor.kt:20`, `RateLimitInterceptor.kt:14`,
  `CloudFlareInterceptor.kt:25`).
- `addExtraHeaders(builder, headers)` calls `builder.headers(headers)`,
  which **replaces** the whole header set when a parser passes one.
- `ensureSuccess(Response)` maps status codes to exceptions and closes the
  body:
  - `404` -> `parsers.exception.NotFoundException`
  - `401` -> `parsers.exception.AuthRequiredException` if the request
    carries the `MangaSource` tag, else `org.jsoup.HttpStatusException`
  - any other `400..599` -> `org.jsoup.HttpStatusException`
  - There is **no** Cloudflare or rate-limit handling here. See §4 and §6.

Behaviours parsers assume the injected client already has:

1. **Cookies.** The client's `cookieJar` must be the *same object* that
   `getCookieJar()` returns. 28 parser classes read or write that jar
   directly (`GroupleParser`, `MadaraParser` and `MangaReaderParser`
   among them, which are template bases with 6, 550 and 259 concrete
   descendants respectively) and then expect the next request through
   `webClient` to send what they just stored. Handing out two different
   jars is a silent correctness bug, not a compile error.
2. **Redirect following.** `ensureSuccess` treats 3xx as success (it only
   throws for 400..599), so parsers that do not follow redirects
   themselves rely on OkHttp's default `followRedirects = true`.
3. **Decompression.** `graphQLQuery` and every `parseHtml` path read
   `response.body`. The app adds brotli and gzip explicitly
   (`NetworkModule.kt:46`, `GZipInterceptor.kt`); OkHttp handles gzip
   natively but not brotli or zstd.
4. **The `MangaSource` tag survives interceptors.** Anything the host
   inserts must not rebuild the request without carrying tags over.

`getCookieJar()` is used for two distinct jobs, and both matter:

- reading a cookie the site set (`CloudFlareHelper.getClearanceCookie`,
  `ExHentaiParser`, `GroupleParser`),
- writing a cookie the parser fabricated - e.g. login state, an age gate,
  a language selection. This requires the jar to be **mutable from
  outside a response**: `CookieJar.saveFromResponse(url, cookies)` called
  with a synthesised URL. A read-only or response-only jar breaks these.

### `evaluateJs`, and the thing that breaks D1's cost estimate

Only **4 classes in the entire jar** call `evaluateJs`, all of them the
`(String, String)` overload. The single-argument overload has **zero**
call sites anywhere in the jar.

| Caller | Method | What it evaluates |
|---|---|---|
| `parsers.util.WebViewHelper` | `getLocalStorageValue(domain, key)` | arg1 `"https://<domain>/"`, arg2 `window.localStorage.getItem("<key>")` |
| `parsers.util.WebViewHelper` | `getUrlValue(url, param)` | arg1 the url, arg2 `new URLSearchParams(window.location.search).get('<param>');` |
| `site.all.bato.BatoToParser` | `getPages` | |
| `site.mangareader.MangaReaderParser` | `getNetShieldCookie` | |
| `site.vi.YuriGardenParser` | `unscrambleImage` | |

`WebViewHelper` is in turn used by `site.all.Koharu`,
`site.ru.rulib.LibSocialParser` and `site.vi.GocTruyenTranhVui`.

The `WebViewHelper` bodies settle what the two arguments mean, and the
answer is worse than "a base URL":

```
getLocalStorageValue: evaluateJs("https://" + domain + "/",
                                 "window.localStorage.getItem(\"" + key + "\")")
getUrlValue:          evaluateJs(url,
                                 "new URLSearchParams(window.location.search).get('" + param + "');")
```

(Constant-pool strings `#29`, `#41`, `#55` of
`org.koitharu.kotatsu.parsers.util.WebViewHelper`, joined by
`StringConcatFactory.makeConcatWithConstants`.)

The first argument is **a page to navigate to**, and the script is
evaluated **inside that page's browsing context**: it reads that origin's
`localStorage` and that document's `window.location.search`. This is not
"run some JavaScript". It is "drive a browser".

**This contradicts PORTING_NOTES.md section C**, which lists the desktop
replacement for `evaluateJs` as GraalJS, and `DECISIONS.md` D16, which
budgets `org.graalvm.polyglot:js` for it. GraalJS has no DOM, no
`window`, no `localStorage`, no origin and no navigation. It can serve
the *single*-argument overload - which nothing calls - and it cannot
serve the two-argument one, which is the only one anything calls.

Return type is `String`, not JSON. Nothing in the jar parses the result
as JSON at the `evaluateJs` boundary:
`WebViewHelper.getLocalStorageValue` hands back the raw string. The
Android app has no implementation to compare against, so what the string
must be quoted/unquoted as is **unknown** and is settled by reading
`MangaReaderParser.getNetShieldCookie` and `YuriGardenParser.unscrambleImage`
bodies and matching them against upstream Kotatsu's own
`AndroidMangaLoaderContext`, which is not in this repo.

Blast radius if `evaluateJs` throws instead of working:

- `MangaReaderParser` is a template base with **259 concrete
  descendants**. Whether all 259 are affected depends on whether
  `getNetShieldCookie` is on the hot path or only on a challenge branch -
  **unknown** from the tally alone; settled by disassembling
  `MangaReaderParser.intercept` / `getNetShieldCookie` and finding its
  guard.
- `BatoToParser.getPages`, `YuriGardenParser.unscrambleImage`,
  `Koharu`, `LibSocialParser`, `GocTruyenTranhVui` fail at the point of
  call.

### `getConfig(MangaSource)` -> `MangaSourceConfig`

```
public interface MangaSourceConfig { <T> T get(ConfigKey<T>); }

public abstract class ConfigKey<T> {
  public final String key;
  public abstract T getDefaultValue();
}
```

Five concrete key types ship in the jar:

| Key | Value type | Default |
|---|---|---|
| `ConfigKey$Domain(vararg presetValues: String)` | `String` | `presetValues.first()` (`javap -c`: `ArraysKt.first`) |
| `ConfigKey$UserAgent(defaultValue: String)` | `String` | the ctor argument |
| `ConfigKey$PreferredImageServer(presetValues: Map<String,String>, defaultValue: String?)` | `String` | the ctor argument |
| `ConfigKey$ShowSuspiciousContent(defaultValue: Boolean)` | `Boolean` | the ctor argument |
| `ConfigKey$SplitByTranslations(defaultValue: Boolean)` | `Boolean` | the ctor argument |

Defaults therefore come **from the key object**, never from the host. A
host that stores nothing and implements

```kotlin
override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
```

is a *correct* `MangaSourceConfig` for a fresh install. What it loses is
persistence of a user's domain override, custom user agent and preferred
image server. `MangaParser.onCreateConfig(Collection<ConfigKey<*>>)` is
how a parser declares which keys it wants shown in a settings UI;
`AbstractMangaParser.onCreateConfig` adds `getConfigKeyDomain()` and
nothing else.

`getConfig` has only 2 invoke sites, both in
`core/AbstractMangaParser.config$delegate` and
`core/FlexibleMangaParser`, i.e. it is called once per parser instance,
lazily.

Storage the desktop host needs: a `Map<sourceName, Map<keyString, String|Boolean>>`
persisted to disk. `ConfigKey.key` is a public field, so it is the
natural map key. That is the whole requirement.

### `getDefaultUserAgent`

Only 2 invoke sites, both in parser base constructors
(`AbstractMangaParser` offset 70, `FlexibleMangaParser`). It is used to
build the *default value* of a `ConfigKey$UserAgent`:

```
userAgentKey = ConfigKey$UserAgent(context.getDefaultUserAgent())
getRequestHeaders() = Headers.Builder().add("User-Agent", config.get(userAgentKey)).build()
```

**No parser branches on the string.** Nothing in the jar reads the UA
back and compares it. A constant is acceptable. 37 classes construct
their own `ConfigKey$UserAgent` and so override the host's value
entirely, among them `GroupleParser` (6 descendants), `NepnepParser`,
`LineWebtoonsParser`, `Koharu`, `LibSocialParser`, `CuuTruyenParser`.

The jar ships 8 candidate constants in `parsers.network.UserAgents`
(`javap -p -constants`), of which the Linux-desktop-honest ones are:

```
CHROME_DESKTOP  = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.7444.59 Safari/537.36"
FIREFOX_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0"
```

It also ships `KOTATSU = "Kotatsu/9.0 (Android 16;;; en)"` and
`LEGACY_KOTATSU`. Picking a desktop UA is a behaviour change relative to
Android Kotatsu and some sites do fingerprint on it; that is a tuning
knob, not a blocker.

### `redrawImageResponse` and `createBitmap`

**6 parser classes, and the operation set is three methods wide.**

```
public interface org.koitharu.kotatsu.parsers.bitmap.Bitmap {
  int getWidth();
  int getHeight();
  void drawBitmap(Bitmap src, Rect srcRect, Rect dstRect);
}
public final class Rect { int left, top, right, bottom; /* + getWidth/getHeight */ }
```

Every `Bitmap` invoke site in the whole jar, by class:

| Class | `getWidth` | `getHeight` | `drawBitmap` | `Rect` ctors |
|---|---|---|---|---|
| `site.vi.MimiHentai` | 1 | 1 | 4 | 8 |
| `site.vi.YuriGardenParser` | 3 | 2 | 1 | 2 |
| `site.vi.CuuTruyenParser` | 3 | 1 | 1 | 2 |
| `site.all.MangaReaderToParser` | 1 | 1 | 1 | 2 |
| `site.all.MangaFireParser$webClient$2$newHttpClient$2$1` | 1 | 1 | 1 | 2 |
| `site.all.ExHentaiParser` | 0 | 0 | 1 | 2 |

There is no pixel access, no colour manipulation, no filter, no rotation
and no encode/decode in the parser-facing API. This is page
**descrambling**: cut rectangles out of a shuffled image and blit them
into a new one at the right place.

`java.awt.image.BufferedImage` + `Graphics2D.drawImage(img, dx1,dy1,dx2,dy2, sx1,sy1,sx2,sy2, null)`
implements all three methods directly. **A `BufferedImage` implementation
is enough.**

`redrawImageResponse(Response, (Bitmap) -> Bitmap)` is the only part with
real work in it, and all of it belongs to the *host*:

1. decode `response.body` bytes to a `Bitmap` (host's decoder),
2. apply the supplied `Function1`,
3. re-encode to bytes,
4. return a **new** `Response` with that body, headers preserved enough
   that the caller can keep reading it.

`ImageIO` covers JPEG/PNG/GIF/BMP out of the box; WebP needs
`twelvemonkeys` or a Skia path. The re-encode format choice is the
host's, and getting the `Content-Type` and `Content-Length` wrong here
will break whatever consumes the page afterwards.

### `requestBrowserAction`

The prompt's premise that it "has a default returning `Void`" is wrong,
and the difference matters. `java.lang.Void` is the *erasure of Kotlin
`Nothing`*. The inherited body (`javap -c`, offsets 12-21) is:

```
new java/lang/UnsupportedOperationException
ldc "Browser is not available"
athrow
```

So leaving the default in place means the parser gets an
`UnsupportedOperationException` thrown **synchronously at the call site**,
with the message `Browser is not available`. It does not return null, it
does not hang, and it does not retry. Two parsers call it:
`site.all.Koharu` and `site.ru.ZenMangaParser`. Both fail loudly, which
is the behaviour `DECISIONS.md` wants; the only gap is that
`UnsupportedOperationException` is not an `IOException`, so it will not
be caught by call sites that only catch `IOException` and it will
surface as a crash-shaped error rather than a source error. The app's
own error surface should map it.

## 2. Catalogue shape

I did not infer this from the bytecode; I **ran it**. A 55-line Java
class implementing all 8 abstract members (`run/StubCtx.java` in scratch)
compiles against the jar and runs on a plain JDK 21 with classpath
`kotlin-stdlib-2.3.21 + okhttp-jvm-5.3.2 + okio-jvm-3.18.1 +
kotlinx-coroutines-core-jvm-1.11.0 + jsoup-1.23.2 + org.json + collection-jvm`.
Positive control and result below.

### Enum metadata

```
public final class MangaParserSource extends Enum<MangaParserSource>
    implements MangaSource {
  private final String title;
  private final String locale;
  private final ContentType contentType;
  private final boolean isBroken;
  public final String getTitle(); getLocale(); getContentType(); isBroken();
  public String getName();          // from MangaSource
}
public interface MangaSource { String getName(); }
```

`MangaParserSource.values().length` **= 1270** (measured, not counted from
`javap`). Reflecting `getTitle` / `getLocale` / `getContentType` /
`isBroken` over all of them gives:

| Dimension | Values |
|---|---|
| `isBroken` | **380 true**, 890 false. **30% of the catalogue is flagged broken by the library itself.** |
| `contentType` | `MANGA` 955, `HENTAI` 298, `COMICS` 11, `OTHER` 5, `MANHWA` 1 |
| `locale` | 20 distinct. `en` 371, `pt` 141, `tr` 120, `id` 120, `es` 120, `fr` 106, `vi` 66, `ar` 64, `""` (empty, multi-language) 40, `th` 34, `ru` 26, `it` 18, `ja` 16, `zh` 12, `de` 8, `uk` 3, `pl` 2, `ko`/`cs`/`be` 1 each |

`ContentType` has 12 constants: `MANGA, MANHWA, MANHUA, HENTAI, COMICS,
NOVEL, ONE_SHOT, DOUJINSHI, IMAGE_SET, ARTIST_CG, GAME_CG, OTHER`.

**There is no domain on the enum.** Getting a source's domain requires
instantiating its parser: `MangaParser.getDomain()` resolves to
`config.get(configKeyDomain)`, and `ConfigKey$Domain.getDefaultValue()`
returns `presetValues.first()`. So a catalogue screen that wants to show
"mangadex.org" next to "MangaDex" must build a parser per source.

### Package grouping

1304 non-inner classes under `site/`, in two kinds of subpackage:

- **language packages**: `all`(40), `vi`(33), `en`(33), `ru`(24),
  `fr`(16), `pt`(12), `tr`(7), `id`(7), `ar`, `es`, `ja`, `zh`, `uk`,
  `be`, `scan`(8)
- **template packages**, each a shared base class plus per-site
  subclasses, often with a nested language dir
  (`madara/en/`, `madara/pt/`, ...): `madara`(552), `mangareader`(260),
  `zeistmanga`(50), `onemanga`(25), `wpcomics`(19), `mmrcms`(17),
  `keyoapp`(16), `hotcomics`(14), `galleryadults`(14), `madtheme`(12),
  `pizzareader`(10), `foolslide`(10), `manga18`(7), `liliana`(7),
  `iken`(7), `heancms`(7), plus ~20 smaller ones.

Transitive subclass counts from the hierarchy map:

| Base | Non-inner descendants |
|---|---|
| `core.AbstractMangaParser` | 1287 |
| `core.PagedMangaParser` | 1187 |
| `site.madara.MadaraParser` | 550 |
| `site.mangareader.MangaReaderParser` | 259 |
| `core.SinglePageMangaParser` | 74 |
| `site.ru.grouple.GroupleParser` | 6 |
| `site.ru.multichan.ChanParser` | 3 |
| `core.FlexibleMangaParser` | 2 |

Two templates account for 64% of the catalogue, which is why a single
template calling `evaluateJs` or `getCookieJar` has such a wide blast
radius (§1).

### Can the desktop app enumerate the catalogue from the enum alone?

**Yes for name, title, locale, content type and broken-flag; no for
domain.** Running the stub against every source:

```
ok=1270 fail=0
```

All 1270 `newParserInstance(source)` calls succeeded. Every one returned
`org.koitharu.kotatsu.parsers.core.MangaParserWrapper` (the factory always
wraps), every one produced a non-error `getDomain()` (1205 distinct
domains across 1270 sources), and every one produced a
`getRequestHeaders()["User-Agent"]`. The 380 `isBroken` sources
instantiate exactly like the rest; `isBroken` is metadata for the UI to
act on, not an instantiation guard.

Two things that fell out of running it and matter to the host:

1. **`newParserInstance` never returns the parser class.** It always
   returns `MangaParserWrapper`, whose suspend methods each wrap the
   delegate in `withContext(Dispatchers.Default)`. The host does not have
   to add its own dispatcher hop, and must not assume it can cast to a
   concrete parser type.
2. **`MangaParser` extends `okhttp3.Interceptor`, and that is
   load-bearing.** `MangaParserWrapper.intercept` (disassembled) does:

   ```
   headers = chain.request().headers().newBuilder()
       .mergeWith(delegate.requestHeaders, /* replaceExisting = */ false).build()
   request = chain.request().newBuilder().headers(headers).build()
   return delegate.intercept(ProxyChain(chain, request))
   ```

   So the parser's `requestHeaders` (its User-Agent, and for many parsers
   a `Referer`) are applied **only if the host installs the parser as an
   interceptor on the client used for that source's requests**. Nothing
   else in the library does it. A host that just builds one shared
   `OkHttpClient` and hands it to every parser will silently send no
   per-source headers at all, and a large number of sites will 403.
   `AbstractMangaParser.intercept` is a pass-through, so the merging
   happens entirely in the wrapper.

## 3. What the app's OkHttp stack does that a desktop host must reproduce

`core/network/` is 22 files / 1447 lines. Two clients are built, both in
`NetworkModule.kt`.

```
@BaseHttpClient  (NetworkModule.kt:59-101)
  dispatcher(maxRequests=64, maxRequestsPerHost=8)   // :76-81
  connect 20s / read 60s / write 20s                 // :82-84
  cookieJar(cookieJar)                               // :85
  proxySelector / proxyAuthenticator  <- ProxyProvider
  dns(DoHManager(cache, settings))                   // :88
  disableCertificateVerification() | installExtraCertificates(ctx)  // :89-93
  cache(cache)                                       // :94
  addInterceptor(GZipInterceptor())                  // :95
  addInterceptor(CloudFlareInterceptor())            // :96
  addInterceptor(RateLimitInterceptor())             // :97
  addInterceptor(CurlLoggingInterceptor())  // debug only

@MangaHttpClient = base.newBuilder()                 // :106-112
  addNetworkInterceptor(CacheLimitInterceptor())
  addInterceptor(commonHeadersInterceptor)
```

`HttpClients.kt` is 11 lines: two Hilt `@Qualifier` annotations, nothing else.

| Piece | File | Verdict | Notes |
|---|---|---|---|
| `HttpClients.kt` qualifiers | `HttpClients.kt:1-11` | **portable** | `javax.inject.Qualifier`, works under plain Dagger (D7) |
| `Dispatcher` / timeouts / cache | `NetworkModule.kt:76-94` | **portable** | `okhttp3.Cache` over a desktop dir; `LocalStorageManager.createHttpCache()` is Agent A/D territory |
| `GZipInterceptor` | `GZipInterceptor.kt` | **portable, but do not port it blind** | It sets `Content-Encoding: gzip` on the **request** (`:18`), not `Accept-Encoding`. That header describes a request body the app never gzips. It is very likely a bug; carrying it to desktop copies the bug. It also wraps non-`IOException` into `WrapperIOException` (`:23`), which *is* worth keeping because OkHttp only tolerates `IOException` from interceptors |
| `CacheLimitInterceptor` | `CacheLimitInterceptor.kt` | **portable** | pure OkHttp, caps `max-age` at 1h |
| `RateLimitInterceptor` | `RateLimitInterceptor.kt` | **portable** | pure OkHttp + `java.time`. See §6 |
| `CloudFlareInterceptor` | `CloudFlareInterceptor.kt` | **portable** | pure OkHttp, delegates detection to the jar's `CloudFlareHelper`. See §4 |
| `CommonHeaders` (app) | `CommonHeaders.kt` | **portable** | 12 string consts + `CACHE_CONTROL_NO_STORE`. Duplicates the jar's. See §6 |
| `CommonHeadersInterceptor` | `CommonHeadersInterceptor.kt` | **drop and rewrite** | see below |
| `DoHManager` / `DoHProvider` | `DoHManager.kt`, `DoHProvider.kt` | **portable as-is** | zero Android imports; `okhttp3.dnsoverhttps`, `java.net.InetAddress`. Its only non-portable coupling is `AppSettings.dnsOverHttps` |
| `SSLUtils.disableCertificateVerification` | `SSLUtils.kt:19-36` | **portable** | pure JSSE |
| `SSLUtils.installExtraCertificates` | `SSLUtils.kt:38-63` | **needs a desktop impl** | reads `*.pem` out of `context.assets`. Desktop equivalent is a resource dir or `$XDG_CONFIG_HOME/dropsauce/certs` |
| `proxy/ProxyProvider` | `ProxyProvider.kt` | **split** | The OkHttp half (`selector` `:39-47`, `ProxyAuthenticator` `:126-151`, `getProxy` `:105-124`) is pure `java.net` and ports as-is. `applyWebViewConfig()` (`:56-101`) is `androidx.webkit` and is dropped with the WebView |
| `webview/WebViewExecutor` | `WebViewExecutor.kt` | **drop** | 25 lines. See correction below |
| `webview/adblock/` | `AdBlock.kt`, `Rule.kt`, `RulesList.kt` (283 lines) | **drop** | only consumed by `browser/*` WebView clients (`BrowserClient.kt:14`, `CloudFlareClient.kt:7`, `CloudFlareInterceptClient.kt:10`, `BaseBrowserActivity.kt:13`) and `AdListUpdateService`. No WebView on desktop, no consumer |
| `imageproxy/*` | 5 files | **portable with edits** | the interceptor logic is plain OkHttp + Coil 3 `coil3.intercept.Interceptor`; `BaseImageProxyInterceptor.kt:3` imports `android.util.Log` (one line) and `androidx.collection.ArraySet` (multiplatform). `RealImageProxyInterceptor` depends on `AppSettings` and `processLifecycleScope` |
| `cookies/*` | 4 files | **see below** | |

### Correction to ARCHITECTURE.md §6 (loud)

> `webview/WebViewExecutor` - runs a headless `WebView` to clear
> Cloudflare challenges

**That is false in this fork.** `WebViewExecutor.kt` is 25 lines long and
its entire body is:

```kotlin
@Singleton
class WebViewExecutor @Inject constructor(@ApplicationContext private val context: Context) {
    val defaultUserAgent: String? by lazy {
        try { WebSettings.getDefaultUserAgent(context) }
        catch (e: AndroidRuntimeException) { ...; null }
    }
}
```

It never constructs a `WebView`, never loads a URL and never touches a
cookie. Its sole purpose is to read the system WebView's default
User-Agent string, and its sole consumer is `CommonHeadersInterceptor.kt:51`.
Cloudflare solving lives in `browser/cloudflare/` (§4), in a full
`Activity`, not here. Someone reading §6 would budget a headless-browser
port that does not exist.

For the desktop host this is good news: `WebViewExecutor` is replaced by
one string constant, which is exactly what `getDefaultUserAgent()` needs
(§1).

### `CommonHeadersInterceptor` is not reusable

```kotlin
val source = request.tag(MangaSource::class.java)
    ?: request.headers[CommonHeaders.MANGA_SOURCE]?.let { MangaSource(it) }
val repository = if (source != null) mangaRepositoryFactory.create(source) else null
val requestHeaders = when (repository) {
    is MihonMangaRepository -> (repository.mihonSource as? HttpSource)?.headers
    else -> null
}                                              // CommonHeadersInterceptor.kt:41-44
...
if (headersBuilder[USER_AGENT] == null) {
    headersBuilder[USER_AGENT] = webViewExecutor.defaultUserAgent ?: UserAgents.FIREFOX_MOBILE
}                                              // :49-52
```

The only branch that supplies per-source headers is
`is MihonMangaRepository`. For anything else it supplies **nothing**
except a fallback User-Agent. That is consistent with the fork having no
kotatsu-parsers backend today, and it means the desktop host cannot reuse
this class: it must instead install the `MangaParser` itself as the
interceptor, because that is where kotatsu-parsers puts its header merge
(§2, `MangaParserWrapper.intercept`).

It does confirm two reusable conventions, though:

- `request.tag(MangaSource::class.java)` is the routing key, which is
  exactly what `OkHttpWebClient.addTags` already sets on every parser
  request (§1). The desktop interceptors can read the same tag with no
  new plumbing.
- `X-Manga-Source` (`CommonHeaders.MANGA_SOURCE`) is an out-of-band
  fallback for callers that cannot set a tag (Coil), and it is stripped
  before the request goes out (`:39`).

### Cookies: what a desktop jar must actually do

`MutableCookieJar` (`MutableCookieJar.kt`, 21 lines) is the app's
abstraction:

```kotlin
interface MutableCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie>
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>)
    fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?)
    suspend fun clear(): Boolean
}
```

Its only Android coupling is `androidx.core.util.Predicate` (`:4`), which
is a one-token swap for `java.util.function.Predicate` or a Kotlin
`(Cookie) -> Boolean`. **The interface is effectively platform-free
already** and should be the desktop contract too.

`AndroidCookieJar` (`AndroidCookieJar.kt`, 54 lines) wraps
`android.webkit.CookieManager` and **is dropped**. Two behaviours of it
are worth naming because a naive desktop jar will not have them:

1. It shares one cookie store with the app's WebView, which is how a
   Cloudflare `cf_clearance` solved in `CloudFlareActivity` becomes
   visible to OkHttp without any explicit copy (§4).
2. `removeCookies` "deletes" by re-setting the cookie with an expiry in
   the past (`:44-47`), because `CookieManager` has no per-cookie delete.
   A desktop map-backed jar just removes the entry.

`PreferencesCookieJar` (`PreferencesCookieJar.kt`, 110 lines) is the
**closest thing to a ready-made desktop jar in the tree**, and it is
already the app's fallback when WebView is unavailable
(`NetworkModule.kt:45-51`). Its logic is entirely portable:

- in-memory `ArrayMap<String, CookieWrapper>` cache, lazily hydrated from
  the store on first `loadForRequest` (`:86-101`)
- key is `scheme://domain + path + "|" + name` (`CookieWrapper.key()`)
- persists only `cookie.persistent` cookies (`:52-54`)
- drops expired entries on read (`:31-40`)
- `@Synchronized` throughout

Its **only** platform dependency is the storage backend: two calls to
`context.getSharedPreferences` / `prefs.edit` and `prefs.all`. Swapping
that for a JSON or properties file under `$XDG_CONFIG_HOME/dropsauce/`
is a mechanical change of about 20 lines. `androidx.collection.ArrayMap`
is multiplatform (`collection-jvm` is already on the parsers' own
dependency list).

`CookieWrapper` (`CookieWrapper.kt`, 69 lines) needs **one** change:
`android.util.Base64` (`:3`) -> `java.util.Base64`. Note
`Base64.NO_WRAP` must become `Base64.getEncoder().withoutPadding()`-style
care - Android's `NO_WRAP` maps to `java.util.Base64.getEncoder()` (no
line breaks), which is the default on the JVM, so the swap is safe, but
**existing encoded cookies are not byte-compatible across the change if
padding differs**; irrelevant for a fresh desktop install, relevant if
anyone ever imports an Android cookie store.

Hard requirement from §1: the jar handed to `getCookieJar()` must be the
**same instance** installed on the client via `cookieJar(...)`, and it
must accept `saveFromResponse` calls made by parsers with a synthesised
URL, not only by OkHttp. `PreferencesCookieJar.saveFromResponse` does
accept that (it ignores `url` entirely and keys off the cookie's own
domain/path), so it satisfies the contract.

### The short version

A desktop host needs: `Dispatcher` + timeouts + `Cache` (copy),
`DoHManager` (copy), `ProxyProvider` minus `applyWebViewConfig` (copy),
`SSLUtils` with a file-based cert dir (small edit), `GZipInterceptor`
minus the bogus request header, `CacheLimitInterceptor`,
`RateLimitInterceptor`, `CloudFlareInterceptor` (all copies),
`PreferencesCookieJar` with a file store (small edit), and **a new
per-source interceptor that installs the `MangaParser` itself** in place
of `CommonHeadersInterceptor`. Dropped: `WebViewExecutor`,
`webview/adblock/`, `AndroidCookieJar`.

## 4. Cloudflare: the full flow and what happens with no resolver

### What triggers a challenge

Detection is entirely in the jar, `parsers.network.CloudFlareHelper.checkResponseForProtection(Response)`.
Disassembled, it is:

```
if (response.code != 403 && response.code != 503) return PROTECTION_NOT_DETECTED   // 0
doc = Jsoup.parse(response.peekBody(Long.MAX_VALUE).byteStream(), "UTF-8", url)
  // any IllegalStateException here -> return 0
if (doc.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null) return PROTECTION_BLOCKED  // 2
if (doc.getElementById("challenge-error-title") != null
 || doc.getElementById("challenge-error-text") != null
 || doc.selectFirst("form#challenge-form") != null
 || doc.selectFirst("iframe[src*='challenges.cloudflare.com']") != null
 || doc.selectFirst("div#turnstile-wrapper") != null
 || doc.selectFirst("div.cf-turnstile") != null
 || doc.getElementById("cf-wrapper") != null
 || doc.title() == "Just a moment..."
 || doc.title() == "Attention Required! | Cloudflare"
 || doc.body().text().contains("Verify you are human")
 || doc.body().text().contains("needs to review the security of your connection")
) return PROTECTION_CAPTCHA                                                         // 1
return PROTECTION_NOT_DETECTED
```

Two things worth flagging for the desktop host:

- It only ever looks at **403 and 503**. A source that challenges with
  200 is invisible to it.
- `peekBody(Long.MAX_VALUE)` buffers the **entire** body into memory for
  every 403/503 before jsoup parses it. On a 403 that is a large binary
  (an image CDN saying no), that is an unbounded allocation. It is
  already a latent problem on Android; it is the same on desktop.

`CloudFlareHelper.getClearanceCookie(cookieJar, url)` walks
`cookieJar.loadForRequest(HttpUrl.get(url))` looking for the cookie named
`cf_clearance` (`CF_CLEARANCE = "cf_clearance"`, `javap -p -constants`).
`isCloudFlareCookie(name)` is the name test.

### What the app does today

```
CloudFlareInterceptor (app interceptor, NetworkModule.kt:96)
  PROTECTION_BLOCKED -> response.close(); throw CloudFlareBlockedException(url, request.tag(MangaSource))
  PROTECTION_CAPTCHA -> response.close(); throw CloudFlareProtectedException(url, tag, request.headers)
```

Exception shape (`core/exceptions/`):

```
abstract class CloudFlareException(message, val state: Int) : okio.IOException(message)
    abstract val url: String ; abstract val source: MangaSource
class CloudFlareBlockedException   : CloudFlareException("Blocked by CloudFlare",   PROTECTION_BLOCKED)
class CloudFlareProtectedException : CloudFlareException("Protected by CloudFlare", PROTECTION_CAPTCHA)
    @Transient val headers: Headers
```

`okio.IOException` is a typealias for `java.io.IOException` on the JVM, so
these are legal to throw out of an OkHttp interceptor.

From there two independent paths exist.

**Path A - foreground, user-driven** (`core/exceptions/resolve/ExceptionResolver.kt:68`):
`resolveCF(e)` launches `CloudFlareActivity` through an
`ActivityResultContract` (`CloudFlareActivity.kt:284`) and suspends on a
continuation (`:126-127`).

**Path B - background, automatic**
(`CaptchaHandler.kt:120-126` -> `CaptchaAutoResolveCoordinator.resolveInBackground`):
launches the *same* activity hidden over the current foreground activity
(`CaptchaAutoResolveCoordinator.kt:94-97`), dedupes concurrent attempts
per source (`inFlight`), applies a 30 s success cooldown and a 60 s outer
timeout (`:114-116`) on top of the activity's own 45 s
(`CloudFlareActivity.kt:300`, `HIDDEN_TIMEOUT_MS = 45_000`).

Inside the activity, `CloudFlareClient` (67 lines) polls on every
`onPageStarted`:

```kotlin
private fun getClearance() = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
// checkClearance(): if clearance != null && clearance != oldClearance -> callback.onCheckPassed()
//                   else counter++; if counter >= 3 -> onLoopDetected()   // CloudFlareClient.kt:41-52
```

`CloudFlareInterceptClient` (124 lines) additionally replays main-frame
GETs through OkHttp with `sec-ch-ua`, `sec-ch-ua-full-version-list`,
`x-requested-with` and `accept-encoding` stripped, because those WebView
headers get Turnstile-blocked (`CloudFlareInterceptClient.kt:15-19, 100-111`).
It builds that client from the base client with `CloudFlareInterceptor`
explicitly removed (`:37-42`), otherwise displaying the challenge page
would throw.

### How the resolved cookie gets back into the jar

**It is never copied.** The WebView writes `cf_clearance` into
`android.webkit.CookieManager`; `AndroidCookieJar` *is*
`android.webkit.CookieManager` (`AndroidCookieJar.kt:13`), so OkHttp
reads the same store on its next request. That single shared store is
the whole mechanism. `CloudFlareClient.getClearance()` reads through the
same `MutableCookieJar` it was constructed with, which is the app's
singleton.

The activity then **verifies** rather than trusting the cookie
(`CloudFlareActivity.kt:196-250`): it re-issues the *originally blocked*
URL through `MangaHttpClient` (whose `CloudFlareInterceptor` will throw
again if the challenge is still live), and only on
`response.isSuccessful` does it set `RESULT_OK`, call
`captchaHandler.discard(source)` (which clears the DB `cf_state` and
cancels the notification) and finish. A clearance that fails
verification is recorded in `rejectedClearance` so the silent probe does
not loop on it.

### Does "fail with a clear error" actually happen with no resolver?

**Yes.** Verified by reading the chain, not assumed:

1. `CloudFlareInterceptor` is an **application** interceptor
   (`addInterceptor`, `NetworkModule.kt:96`), not a network interceptor.
   It closes the body and throws.
2. The thrown type is a `java.io.IOException` subclass, which OkHttp
   propagates unchanged to `Call.execute()` / the coroutine `await()`.
   OkHttp's `retryOnConnectionFailure` does not apply to exceptions
   thrown by application interceptors, so there is **no OkHttp-level
   retry**.
3. `grep -rn "retry" core/parser core/network` finds only
   `RateLimitInterceptor`'s `Retry-After` **header parsing**. There is no
   retry loop anywhere in the parser or network layer.
4. `CaptchaHandler.handleException` returns `false` on every path except
   a successful auto-resolve (`CaptchaHandler.kt:153`), so no caller
   loops on it either.
5. The parser sees `CloudFlareProtectedException` / `CloudFlareBlockedException`,
   both carrying `url`, `source` and `state`.
   `core/util/ext/Throwable.kt:73-74` already maps them to
   `R.string.captcha_required_message` / `R.string.blocked_by_server_message`,
   and `ExceptionResolver.canResolve` (`:253`) is what decides whether a
   "Solve" button appears. On desktop, wiring `canResolve` to return
   false for these is all that separates "clear error" from "offer to
   solve".

So `DECISIONS.md`'s claim holds: **clean typed exception, one shot, no
hang, no retry.** The parser does not swallow it and nothing spins.

Two caveats that are not in `DECISIONS.md`:

- `CloudFlareProtectedException.headers` is `@Transient`. It is captured
  precisely so a resolver can replay the request with the same headers.
  Dropping interactive solving makes that field dead weight, not a
  problem.
- The 380 `isBroken` sources (§2) and the Cloudflare-fronted sources are
  different sets and both will fail. A desktop catalogue screen that does
  not surface `isBroken` up front will look much worse than it is.

### Sibling exception worth naming

`core/exceptions/InteractiveActionRequiredException` (12 lines) carries
`source`, `url`, `userAgent`, `successCookieUrl`, `successCookieName` and
is resolved by `ExceptionResolver.resolveBrowserAction` (`:76, :120`).
This is the app-side counterpart of the jar's `requestBrowserAction`
(§1). On desktop, `requestBrowserAction`'s inherited body throws
`UnsupportedOperationException`, not this - so `Koharu` and
`ZenMangaParser` will surface a *different*, non-IOException error shape
than the app's own browser-action path. Worth normalising in the host
override:

```kotlin
override fun requestBrowserAction(parser: MangaParser, url: String): Void =
    throw InteractiveActionRequiredException(parser.source, url)
```

which is legal (Kotlin `Nothing` return) and gives the app a consistent
typed IOException.

## 5. Model surface, and a correction to ARCHITECTURE.md §7

104 distinct `import org.koitharu.kotatsu.parsers.*` lines exist across
`app/src/main/kotlin`, 686 import sites in total.

### ARCHITECTURE.md §7 is wrong

> The `com.github.YakaTeam:kotatsu-parsers` dependency **is** still present,
> but the app consumes only its **model and util** classes

The app consumes **seven** subpackages, not two:

| Subpackage | Distinct imports | Import sites |
|---|---|---|
| `parsers.model` | 34 | 434 |
| `parsers.util` | 54 | 216 |
| `parsers.exception` | 6 | 19 |
| `parsers.network` | 2 (`CloudFlareHelper`, `UserAgents`) | 10 |
| `parsers.ErrorMessages` | 5 constants | 5 |
| `parsers` root (`InternalParsersApi`) | 1 | 6 |
| `parsers.config` | 2 (`MangaSourceConfig`, `ConfigKey`) | 2 |

The `config` line is the one that changes the estimate, and in the
**good** direction:

```kotlin
// core/prefs/SourceSettings.kt:18
class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {
    override fun <T> get(key: ConfigKey<T>): T = when (key) {
        is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
            .ifNullOrEmpty { key.defaultValue }.sanitizeHeaderValue()
        is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
            ?.trim()?.takeIf(::isValidDomain) ?: key.defaultValue
        is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
        is ConfigKey.SplitByTranslations   -> prefs.getBoolean(key.key, key.defaultValue)
        is ConfigKey.PreferredImageServer  -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
    } as T
}
```

**`MangaLoaderContext.getConfig` is the one abstract member the app has
already implemented.** `SourceSettings` (a 100-line class) is a complete
`MangaSourceConfig` with per-source storage, domain validation, header
sanitising and a legacy-storage migration. Its only platform dependency
is `context.getSharedPreferences(getStorageName(source.name), MODE_PRIVATE)`
plus `androidx.core.content.edit`. Swapping the backing store makes it a
desktop `getConfig` implementation. §1's "a map persisted to disk" is
exactly what this already is.

Similarly, `InternalParsersApi` is opted into by six app repositories
(`LocalMangaRepository`, `MihonMangaRepository`, `LnMangaRepository`,
`EmptyMangaRepository`, `LazyMihonMangaRepository`, `AniListRepository`),
which means the app is already reaching past the public model surface
into the library's internal API. Any jar bump can break those six.

### Is anything in the jar Android-coupled?

**No, and I checked the jar rather than believing the metadata.**

Positive control: `grep -rlE "android[^x]" --include='*.class'` over all
3365 extracted classes returns **0 files**, while `grep -rl "androidx"`
over the same set returns **296 files** - so the detector fires on the
shape it is looking for and the real code does not contain it.

The 296 `androidx` hits are all `androidx.collection`:
`ArraySet` (950 references), `ScatterSet` (234), `ArrayMap` (166),
`SparseArrayCompat` (30), `MutableIntObjectMap` (28), and smaller. That
artifact publishes `collection-jvm`, which the jar's own POM already
declares:

```xml
jsoup 1.22.1 (compile) | kotlin-stdlib 2.2.10 (compile)
kotlinx-coroutines-core-jvm 1.10.2 | okhttp-jvm 5.3.2 | okio-jvm 3.16.4
org.json:json 20251224 | androidx.collection:collection-jvm 1.5.0
```

Note the POM asks for **`okhttp-jvm`**, not `okhttp`. Filtering the
whole disassembly for non-JDK, non-Kotlin package references leaves
exactly: `org/koitharu`, `okhttp3/*`, `org/jsoup`, `org/json`,
`androidx/collection`. Nothing else.

**Runtime proof, not inference:** the 1270-source probe in §2 ran on a
stock JDK 21 with only those jars on the classpath and instantiated every
parser, read every domain and built every `Headers` object. ARCHITECTURE
§12's claim that the jar "runs on desktop as-is" is confirmed by
execution.

### The model types the app uses

`Manga` (158 sites), `MangaSource` (68), `MangaChapter` (41),
`MangaTag` (36), `MangaPage` (26), `SortOrder` (24), `MangaListFilter` (24),
`MangaState` (14), `ContentRating` (13), `ContentType` (8),
`MangaListFilterCapabilities` (7), `MangaListFilterOptions` (6),
`Demographic` (5), plus the `RATING_UNKNOWN` / `YEAR_UNKNOWN` / `YEAR_MIN`
sentinels.

`parsers.util` usage is dominated by `runCatchingCancellable` (66 sites),
`nullIfEmpty` (16), `ifNullOrEmpty` (15), `await` (11), `mapToSet` (10).
These are pure Kotlin extension functions on JDK/okhttp/jsoup types; the
`util.json.*` family (14 sites) wraps `org.json`, which is why
`PORTING_NOTES.md` section E's note about not re-applying
`exclude group: 'org.json'` on desktop is correct and load-bearing.

## 6. Rate limiting and headers: app vs jar

### `CommonHeaders`: duplicate, not conflicting

Two objects with the same simple name exist:

| | `org.koitharu.kotatsu.core.network.CommonHeaders` | `org.koitharu.kotatsu.parsers.network.CommonHeaders` |
|---|---|---|
| Constants | 12 | 52 |
| `USER_AGENT` | `"User-Agent"` | `"User-Agent"` |
| `MANGA_SOURCE` | `"X-Manga-Source"` | `"X-Manga-Source"` |
| `CACHE_CONTROL`, `RETRY_AFTER`, `ACCEPT`, `ACCEPT_ENCODING`, `CONTENT_ENCODING` | identical strings | identical strings |
| App-only | `CACHE_CONTROL_NO_STORE: CacheControl`, `DATE_FORMAT` | - |
| Jar-only | - | `REFERER`, `SEC_FETCH_*`, `SEC_CH_UA*`, `X_WM_*`, `X_CLIENT_*`, `TOKEN`, ~40 more |

Every overlapping constant has the **same value**, including
`X-Manga-Source`, so the app's copy is a strict subset plus two extras.
No app file imports the jar's `CommonHeaders`
(`grep -rn "import org.koitharu.kotatsu.parsers.network.CommonHeaders"`
returns nothing), which is why the duplicate has never bitten.

**Verdict:** the desktop host should use the **jar's** `CommonHeaders`
and keep only `CACHE_CONTROL_NO_STORE` and `DATE_FORMAT` in app code.
Keeping two objects named `CommonHeaders` in the same source set will
force import aliases in every file that touches both, and `MANGA_SOURCE`
matching across the two is a coincidence nobody has ever had to maintain.

### Rate limiting: they solve different problems, keep both

**The app's `RateLimitInterceptor`** is *reactive*. It does nothing until
the server says 429, then closes the body and throws
`parsers.exception.TooManyRequestExceptions(url, retryAfter)` after
parsing `Retry-After` as either seconds or an RFC-1123 date
(`RateLimitInterceptor.kt:25-28`). `TooManyRequestExceptions extends
java.io.IOException`, so it is legal from an interceptor and propagates
cleanly. It applies to every request on the base client.

**The jar's `RateLimitHelper`** is *proactive*. It is a token-bucket
interceptor:

```
intercept(chain):
  if (!shouldLimit(request.url)) return chain.proceed(request)
  limiter = limiters.computeIfAbsent(request.url.host) { RateLimiter(permits, period) }
  token = limiter.acquire(chain.call(), request.url.toString())   // blocks
  response = chain.proceed(request)
  if (response.networkResponse() == null) limiter.release(token)  // cache hit, refund
  return response
```

`network.utils.RateLimiter` is a sliding window: `permits`, `periodMs`,
an `ArrayDeque<Long>` of timestamps and a `ReentrantLock`. The refund on
a cache hit is a nice detail the app's interceptor has no equivalent of.

It is installed **by parsers, not by the host**, through
`parsers.util.RateLimitKt.rateLimit(...)` on an `OkHttpClient.Builder`.
Exactly **3 classes in the whole jar** use it:
`site.vi.GocTruyenTranhVui`, `site.vi.KuroNeko`,
`site.vi.YuriGardenParser`. Each of them calls
`context.httpClient.newBuilder()` and adds its own limiter, which means
they inherit the host's cookie jar, DNS, proxy, cache and interceptors
from `newBuilder()` and layer a per-host throttle on top.

**They do not conflict and they do not duplicate.** Proactive throttling
for three sources that asked for it; reactive 429 handling for
everything. The desktop host should:

- install `RateLimitInterceptor` (the app's) on the shared client, as
  today,
- install **nothing** resembling `RateLimitHelper`, because the parsers
  that want it add it themselves,
- and make sure `getHttpClient()` returns a client whose `newBuilder()`
  produces a usable derivative, since 8 classes
  (`AbstractMangaParser`, `FlexibleMangaParser`, `BatoParser`,
  `MangaFireParser`, `TaiyoParser`, `GocTruyenTranhVui`, `KuroNeko`,
  `YuriGardenParser`) call `getHttpClient()` and the six site ones all
  derive a custom client from it.

One consequence worth stating: a parser-derived client built with
`newBuilder()` **keeps the host's application interceptors**, including
`CloudFlareInterceptor`. That is the behaviour the app's own
`CloudFlareInterceptClient` had to explicitly undo
(`CloudFlareInterceptClient.kt:38`,
`interceptors().removeAll { it is CloudFlareInterceptor }`). Nothing to
do on desktop, but it means a host interceptor is effectively global
over every parser-built client too.

## 7. What I will get wrong

### The live end-to-end proof first, because it changes the risk ranking

The `StubCtx` from §2 (8 abstract members, ~55 lines of Java, `evaluateJs`
and `redrawImageResponse` both throwing, `getConfig` returning
`key.defaultValue`, `createBitmap` returning a `BufferedImage`) was run
against real sites on plain JDK 21:

```
OK  MANGADEX      domain=mangadex.org         n=20  first="Kyou wa Kanojo ga Inai kara"
    details ok: chapters=95 cover=true
    pages ok:   n=4   url0=https://cmdxd98sb0x3yprd.mangadex.network/data/.../1-....png
OK  ANISASCANS    domain=anisascans.in        n=36   details chapters=213   pages n=33
OK  NGAMENKOMIK   domain=ngamenkomik05...     n=13   details chapters=3     pages n=30
OK  MUITOHENTAI   domain=www.muitohentai.com  n=24   details chapters=1     pages n=14
OK  SCANTRADUNION domain=scantrad-union.com   n=186  (details then timed out)
OK  MANHWALIST / VYMANGA                      n=0    (empty list, no exception)
FAIL MANGAPLUSPARSER_FR  HttpStatusException 403
FAIL WAMANGA             NotFoundException 404
FAIL MANGA_DENIZI        NotFoundException 404
FAIL NETTRUYEN1975       HttpStatusException 403
FAIL NECROSCANS          UnknownHostException (domain does not resolve)
FAIL YURIGARDEN          NotFoundException 404
```

`getList` -> `getDetails` -> `getPages` with real page URLs works
end-to-end on a stock JVM with no Android anything. **D1's mechanism is
proven, not inferred.**

Two calibrations from the same run:

- I first called `getList(1, order, filter, ...)` thinking `1` meant page
  one. The generated URLs showed `?page=2`, so the int is an **offset**,
  and half my "n=0" results were my own bug, not the library's. Anyone
  standing this up will make the same mistake once.
- Of 12 non-`isBroken` sources sampled at random across templates, about
  4 returned a full chapter+pages chain, 2 returned an empty list with no
  error, and 6 threw. That is a **small, network-dependent sample and not
  a measurement of catalogue health**, but it is enough to say
  `isBroken == false` is not a promise that a source works.

### The five risks, ranked

**1. `evaluateJs` is a browser, not a JS engine, and the plan budgets a
JS engine.**
`DECISIONS.md` D16 lists `org.graalvm.polyglot:js` for `evaluateJs`.
§1 shows the only overload anything calls is `(url, script)`, and
`WebViewHelper`'s two bodies prove the script runs inside that URL's
page: `window.localStorage.getItem(...)` and
`new URLSearchParams(window.location.search).get(...)`. GraalJS cannot do
that. The **good** news is the blast radius is small and now exactly
measured: reflecting over all 1270 parsers,
`MangaReaderParser.isNetShieldProtected` is true for **3 of its 259
descendants** (`AFRODITSCANS`, `MANGASUSUKU`, `NORMOYUN`), so the full
affected set is
`BATOTO(broken)`, `YURIGARDEN`, `YURIGARDEN_R18`, `KOHARU`,
`MANGALIB`, `YAOILIB`, `HENTAILIB(broken)`, `GOCTRUYENTRANHVUI`,
`AFRODITSCANS`, `MANGASUSUKU`, `NORMOYUN` - **11 of 1270 sources.**
*Early test:* implement `evaluateJs` as
`throw UnsupportedOperationException` and run those 11 through
`getList`/`getPages`. If only those 11 fail, drop GraalJS from D16 and
save the dependency. *Disproof:* if a template calls it on a path I
misread, more than 11 fail.

**2. Forgetting to install the parser as an OkHttp interceptor.**
§2 shows `MangaParserWrapper.intercept` is the *only* place
`requestHeaders` (User-Agent, Referer) get merged into a request, and
nothing in the library installs it. A host that builds one shared client
and hands it to every parser will send the wrong UA, no Referer, and
collect 403s that look like Cloudflare but are not. This is the single
easiest thing to get wrong because **everything still compiles and most
sources still work** - my own `StubCtx` did not install it and MangaDex
was perfectly happy, because MangaDex is an API that does not care.
*Early test:* run a Referer-sensitive Madara source (e.g. `ANISASCANS`)
and assert the outbound request carries the parser's UA, using an
`Interceptor` that records `chain.request().header("User-Agent")`. Two
clients, one with the parser installed and one without, is the positive
control.

**3. The cookie jar identity and mutability contract.**
28 parser classes call `getCookieJar()`, including `MadaraParser`
(550 descendants) and `MangaReaderParser` (259). They both read cookies
and *write* fabricated ones. If `getCookieJar()` returns a different
instance from the one passed to `OkHttpClient.Builder.cookieJar(...)`, or
if the jar ignores `saveFromResponse` calls that did not come from a real
response, those writes vanish silently. Nothing throws.
*Early test:* a jar that logs every `saveFromResponse`; instantiate
`MANGALIB` / a Madara source, call the login or sort-cookie path, and
assert the next `loadForRequest` returns what was written. *Disproof:*
the write is visible and the identity is shared.

**4. `redrawImageResponse` is the only member with real work in it, and
the format round-trip is where it breaks.**
`createBitmap` is trivially `BufferedImage` (§1: the whole interface is
`getWidth`/`getHeight`/`drawBitmap`). `redrawImageResponse` has to decode
the body, apply the lambda, re-encode and rebuild a `Response`. Affected
sources are exactly `EXHENTAI`, `MANGAREADERTO(broken)`, `CUUTRUYEN`,
`MIMIHENTAI`, `YURIGARDEN`, `YURIGARDEN_R18` and the 7 `MANGAFIRE_*`
constants - **13 of 1270**. The trap is format: `ImageIO` has no WebP
reader, and these are page images, which are frequently WebP.
*Early test:* fetch one real scrambled page from `CUUTRUYEN`, run it
through the implementation, write the output to a file and look at it.
Not "no exception" - *look at the image*, because a wrong `Rect`
mapping produces a valid file that is visibly shuffled.

**5. Catalogue presentation, not code.**
380 of 1270 sources (**30%**) are `isBroken == true`, and the random
live sample above suggests a meaningful share of the remaining 890 are
also dead, 403ing or returning empty. A desktop "browse the catalogue"
screen that lists 1270 entries flat will read as broken software.
*Early test:* run `getList(0, defaultSortOrder, EMPTY)` over all 890
non-broken sources with a 15 s timeout and count how many return a
non-empty list. That number is the honest size of the desktop catalogue
and it should be in `DECISIONS.md` before the UI is designed.
*Disproof of my worry:* the pass rate comes back high.

Two more that did not make the top five but will cost time:

- `CloudFlareHelper.checkResponseForProtection` calls
  `response.peekBody(Long.MAX_VALUE)` on every 403/503. Unbounded buffer.
- `requestBrowserAction`'s inherited body throws
  `UnsupportedOperationException`, not an `IOException` (§1, §4). Override
  it to throw `InteractiveActionRequiredException` or two sources will
  surface as crashes rather than source errors.

### Verdict on D1

**D1 is sound, and it is cheaper than "a few hundred lines", not more
expensive.** The evidence:

- The full abstract contract compiles in ~55 lines of Java and runs.
- All 1270 sources instantiate; all 1270 produce a domain and headers.
- A real `getList` -> `getDetails` -> `getPages` chain works against live
  sites on a plain JDK with that stub.
- Of the 8 abstract members, **one is already implemented in this repo**
  (`getConfig`, as `core/prefs/SourceSettings`, §5), **three are trivial**
  (`getDefaultUserAgent` a constant; `createBitmap` a `BufferedImage`;
  `getCookieJar` a 20-line edit of `PreferencesCookieJar`, §3), **two are
  plumbing** (`getHttpClient` is the existing `NetworkModule` stack minus
  Android, §3), and **two are genuinely hard** (`evaluateJs`,
  `redrawImageResponse`) but together affect **21 distinct sources out of
  1270**, and both fail loudly rather than silently.

Three things the decision as written gets wrong and should be corrected
in place:

1. It says "7 abstract members". It is 8 (§1).
2. It budgets GraalJS for `evaluateJs`. GraalJS cannot serve the only
   overload anyone calls (§1). Either cut the dependency and accept 11
   dead sources, or accept that `evaluateJs` needs an embedded browser -
   which is the same KCEF dependency already rejected for Cloudflare, and
   would serve both.
3. It does not mention that the host must install the `MangaParser` as an
   OkHttp interceptor (§2). That is not optional and it is not in the
   member list, so it is the part most likely to be skipped.

