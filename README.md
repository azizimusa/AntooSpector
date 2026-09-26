# Antoo Spector — HTTP Monitor for Android

See every HTTP call your app makes, on the device, while you use it.

Antoo Spector captures OkHttp and `HttpURLConnection` traffic and shows it in a viewer that
ships with the library: URL, method, status, timing, full request and response headers,
pretty-printed JSON/XML bodies, and a **Copy as cURL** button to replay any call in a terminal.
Optionally it also ships what it captures to an [Antoo Spector dashboard](#send-traffic-to-a-dashboard)
so you can watch a tester's device from your desk.

- Artifact: `com.github.azizimusa:AntooSpector`
- `minSdk` 24 · Java 11 · written in Kotlin, [usable from Java](#using-it-from-a-java-project)
- Depends on OkHttp 4.x (exposed as `api`), AppCompat, RecyclerView

---

## 1. Add the dependency

In the consuming project's `settings.gradle.kts`, add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    debugImplementation("com.github.azizimusa:AntooSpector:1.0.0")
}
```

`debugImplementation` keeps the monitor — and everything it captures — out of your release APK.
See [Keeping it out of release builds](#keeping-it-out-of-release-builds).

> The version is a git tag. JitPack builds a tag the first time anyone asks for it, so the very
> first resolution of a new version can take a couple of minutes.

Other ways to consume it (Maven Local, a shared Maven repo, a composite build) are in
[Other ways to depend on it](#other-ways-to-depend-on-it).

Using this from a Java-only project? See [Using it from a Java project](#using-it-from-a-java-project) — no Kotlin plugin needed, but the module must compile against Java 11.

## 2. Start the monitor

Once, in `Application.onCreate`, guarded so it never runs in release:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            HttpMonitor
                .start(maxTransactions = 500, maxBodyBytes = 512 * 1024)
                .addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
        }
    }
}
```

- `maxTransactions` — how many exchanges to keep; the oldest are dropped past this.
- `maxBodyBytes` — how much of each body to retain. Larger bodies are truncated for storage,
  but their real size is still reported (`bodySize`, `bodyTruncated`).

Starting the monitor alone captures nothing. Capture is opt-in per HTTP client — step 3.

## 3. Instrument your HTTP client

### OkHttp

Add the interceptor as an **application** interceptor (not a network one), so bodies are seen
already decompressed and after your own interceptors have run:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor())
    .addInterceptor(HttpMonitorInterceptor())   // last, so it sees the final request
    .build()
```

Retrofit uses whatever `OkHttpClient` you hand it, so this covers Retrofit too.

### HttpURLConnection

Open connections through `UrlInstrument`, and call `disconnect()` when you're done — that,
or reaching the end of the response stream, is what marks the exchange complete:

```kotlin
val connection = UrlInstrument.openConnection(URL("https://example.com")) as HttpURLConnection
try {
    connection.inputStream.use { it.readBytes() }
} finally {
    connection.disconnect()
}
```

## 4. Open the viewer

From anywhere — a debug drawer, a long-press, a shake handler:

```kotlin
HttpMonitor.show(context)
```

The viewer's activities are declared in the library's own manifest, so your app needs no
manifest changes. Your app still needs `android.permission.INTERNET` for its own traffic.

---

## Redacting and filtering

An `HttpFilter` runs **before** anything is stored, so whatever it strips never reaches the
in-app viewer or the dashboard. Return `null` from either method to drop the whole transaction:

```kotlin
HttpMonitor.addFilter(object : HttpFilter {
    // Keep the auth endpoint out of the log entirely.
    override fun filter(request: HttpRequest): HttpRequest? =
        request.takeUnless { it.url.contains("/auth/") }

    override fun filter(response: HttpResponse): HttpResponse = response
})
```

For the common case of masking headers, use the one that's built in:

```kotlin
HttpMonitor.addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
```

Matched headers are replaced with `<redacted>`.

## Send traffic to a dashboard

`AntooReporter` ships captured traffic to an Antoo Spector dashboard, so you can watch a
device you aren't holding:

```kotlin
HttpMonitor
    .start(maxTransactions = 500)
    .addFilter(HeaderRedactingFilter("Authorization"))
    .report(
        AntooReporter(
            endpoint = BuildConfig.ANTOO_ENDPOINT,   // e.g. https://spector.example.com/api/v1/ingest
            apiKey = BuildConfig.ANTOO_KEY,
            device = DeviceInfo.from(this)
        )
    )
```

How it behaves:

- Transactions are queued as they're captured and posted in batches from one background daemon
  thread. Your HTTP calls are never blocked by an upload.
- Filters run first, so anything redacted locally is redacted on the wire too.
- Nothing about it is fatal. A queue that outruns the network drops its oldest entries; a failed
  upload is retried with exponential backoff; a batch the server permanently rejects (a bad key,
  a malformed payload) is dropped rather than wedging the queue behind it.
- The reporter's own uploads are never themselves captured.

Tunable constructor parameters: `batchSize` (50), `flushIntervalMs` (15 s), `queueCapacity` (500),
`maxBodyChars` (16 384), and `client` if you want to supply your own `OkHttpClient`.

`DeviceInfo.from(context)` reads the manufacturer, model, OS and app version, and mints an
install id kept in the library's own `SharedPreferences`. The dashboard groups traffic by that
id. Uninstalling the app resets it — it identifies an install, not a person.

Call `HttpMonitor.flushReports()` to push what's queued right now, or
`HttpMonitor.report(null)` to stop reporting and release the thread.

### Keeping credentials out of version control

This repo's sample app reads them from `local.properties` (git-ignored) and exposes them as
`BuildConfig` fields — copy the pattern:

```properties
# local.properties
antoo.endpoint=https://spector.example.com/api/v1/ingest
antoo.key=your-key
```

```kotlin
// app/build.gradle.kts
buildConfigField("String", "ANTOO_ENDPOINT", "\"${setting("antoo.endpoint")}\"")
buildConfigField("String", "ANTOO_KEY", "\"${setting("antoo.key")}\"")
```

With neither set, the app simply captures locally and ships nothing.

### The wire format

`POST <endpoint>` with header `X-Antoo-Key: <apiKey>` and a JSON body:

```json
{
  "device": { "uid": "…", "manufacturer": "…", "model": "…", "os_version": "…",
              "app_version": "…", "app_build": "…" },
  "transactions": [
    {
      "id": 1727330000000001,
      "source": "okhttp",
      "duration_ms": 142,
      "request":  { "method": "GET", "url": "…", "headers": { "accept": ["*/*"] },
                    "body_size": 0, "body_truncated": false, "started_at": 1727330000000 },
      "response": { "status_code": 200, "status_message": "OK", "headers": { … },
                    "body": "…", "body_size": 1024, "body_truncated": false,
                    "received_at": 1727330000142 }
    }
  ]
}
```

Headers are multi-value (`name -> [values]`). Binary bodies are described rather than sent
(`"<binary body, 12.4 kB>"`). A 2xx means accepted; 429 and 5xx are retried; anything else
drops the batch.

To send traffic somewhere else entirely, implement `TransactionReporter` yourself — its `report`
is called on the thread that made the HTTP call, so it must only hand the transaction off:
never block, never throw.

## Keeping it out of release builds

Two things together do it:

1. Depend on the library with `debugImplementation`.
2. Guard `HttpMonitor.start()` behind `BuildConfig.DEBUG`.

If the only code that touches `HttpMonitor` / `HttpMonitorInterceptor` lives in debug-only
sources, the whole thing compiles away in release.

## Using it from a Java project

The library is written in Kotlin, but it ships as an ordinary AAR — a Java-only Android
project can use it **without adding the Kotlin Gradle plugin**. The Kotlin standard library
is declared as a dependency of the artifact, so Gradle pulls it in for you.

Two requirements on the consuming module:

```kotlin
android {
    defaultConfig {
        minSdk = 24                                       // or higher
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11      // the AAR is Java 11 bytecode
        targetCompatibility = JavaVersion.VERSION_11
    }
}
```

Java 8 will not work; the library targets 11.

### Calling it from Java

Kotlin `object`s are reached through their `INSTANCE` field, and Kotlin's default arguments
don't exist in Java — so `start(…)` takes both parameters explicitly:

```java
import gg.padu.httpmonitor.HeaderRedactingFilter;
import gg.padu.httpmonitor.HttpMonitor;

public class MyApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (!BuildConfig.DEBUG) return;

        HttpMonitor.INSTANCE
                .start(500, 512 * 1024)   // maxTransactions, maxBodyBytes — both required
                .addFilter(new HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"));
    }
}
```

Instrumenting a client, and opening the viewer:

```java
import gg.padu.httpmonitor.okhttp.HttpMonitorInterceptor;
import gg.padu.httpmonitor.urlconnection.UrlInstrument;

OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(new HttpMonitorInterceptor())
        .build();

HttpURLConnection connection =
        (HttpURLConnection) UrlInstrument.openConnection(new URL("https://example.com"));

HttpMonitor.INSTANCE.show(context);
```

`HttpFilter` compiles to real JVM default methods, so an anonymous class only has to override
the method it cares about. Returning `null` still drops the transaction:

```java
import gg.padu.httpmonitor.HttpFilter;
import gg.padu.httpmonitor.HttpRequest;

HttpMonitor.INSTANCE.addFilter(new HttpFilter() {
    @Override public HttpRequest filter(HttpRequest request) {
        return request.getUrl().contains("/auth/") ? null : request;
    }
});
```

Dashboard reporting works the same way — `DeviceInfo.from(…)` is a static method and
`AntooReporter`'s optional parameters have Java overloads:

```java
import gg.padu.httpmonitor.report.AntooReporter;
import gg.padu.httpmonitor.report.DeviceInfo;

HttpMonitor.INSTANCE.report(
        new AntooReporter(
                BuildConfig.ANTOO_ENDPOINT,
                BuildConfig.ANTOO_KEY,
                DeviceInfo.from(this)));
```

`TransactionStore.Listener` is a single-method interface, so a Java lambda works:

```java
HttpMonitor.INSTANCE.addListener(transactions ->
        Log.d("Monitor", transactions.size() + " captured"));
```

### Java quick reference

| Kotlin | Java |
| --- | --- |
| `HttpMonitor.start(500)` | `HttpMonitor.INSTANCE.start(500, 512 * 1024)` |
| `HttpMonitor.show(context)` | `HttpMonitor.INSTANCE.show(context)` |
| `Bodies.asCurl(transaction)` | `Bodies.INSTANCE.asCurl(transaction)` |
| `UrlInstrument.openConnection(url)` | `UrlInstrument.openConnection(url)` (static) |
| `DeviceInfo.from(context)` | `DeviceInfo.from(context)` (static) |
| `transaction.request.url` | `transaction.getRequest().getUrl()` |

Everything else — `HttpMonitorInterceptor`, `AntooReporter`, `DeviceInfo`, `HeaderRedactingFilter` —
is constructed with `new` as usual.

## API reference

| Call | Purpose |
| --- | --- |
| `HttpMonitor.start(maxTransactions, maxBodyBytes)` | Begin capturing; also resets the store |
| `HttpMonitor.stop()` | Pause capture; whatever was stored is kept |
| `HttpMonitor.isEnabled` | Whether capture is currently on |
| `HttpMonitor.show(context)` | Open the built-in viewer |
| `HttpMonitor.transactions()` | Snapshot of what was captured, newest first |
| `HttpMonitor.find(id)` | One transaction by id |
| `HttpMonitor.clear()` | Drop everything captured so far |
| `HttpMonitor.addListener(…)` / `removeListener(…)` | Observe the list (delivered on the main thread) |
| `HttpMonitor.addFilter(…)` / `removeFilter(…)` | Install or remove an `HttpFilter` |
| `HttpMonitor.report(reporter)` | Install a `TransactionReporter`, or `null` to stop |
| `HttpMonitor.flushReports()` | Send what the reporter has queued now |
| `Bodies.asCurl(transaction)` | Reproduce a request as a cURL command |

## Other ways to depend on it

**Maven Local**, while iterating on the library:

```bash
./gradlew :httpmonitor:publishToMavenLocal
```

Add `mavenLocal()` to the consumer's repositories and use the same coordinates.

**A shared or checked-in Maven repository:**

```bash
./gradlew :httpmonitor:publishReleasePublicationToLocalRepoRepository
# artifacts land in build/repo/com/github/azizimusa/AntooSpector/<version>/
```

Copy that directory anywhere (a shared drive, another repo, an S3 bucket, an internal Maven
server) and point the consumer at it with `maven { url = uri("/path/to/repo") }`.

**A composite build**, with no publishing at all — in the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/AntooSpector") {
    dependencySubstitution {
        substitute(module("com.github.azizimusa:AntooSpector")).using(project(":httpmonitor"))
    }
}
```

---

## Working on this repo

```
app/           sample app that uses the library
httpmonitor/   the library itself
```

```bash
./gradlew :httpmonitor:testDebugUnitTest :httpmonitor:lintDebug   # before every release
./gradlew :app:installDebug                                       # try it in the sample app
```

### Releasing a new version

Run the tests and lint above, then push a tag:

```bash
git tag 1.1.0 && git push origin 1.1.0
```

JitPack builds the tag on the first request for that version — there is nothing to upload.
The published version is the tag name (JitPack passes it to the build as `$VERSION`); keep the
fallback `version` in `httpmonitor/build.gradle.kts` in step with it so local publishing matches.

Build logs for a tag are at
<https://jitpack.io/com/github/azizimusa/AntooSpector/1.1.0/build.log>.
