# Antoo Spector — HTTP Monitor for Android

See every HTTP call your app makes, on the device, while you use it.

Antoo Spector captures OkHttp (and therefore Retrofit) and `HttpURLConnection` traffic and shows
it in a viewer that ships inside the library: URL, method, status, timing, full headers,
pretty-printed JSON/XML bodies, and **Copy as cURL** to replay any call in a terminal.
Optionally it also ships what it captures to an [Antoo Spector dashboard](#optional-send-traffic-to-a-dashboard),
so you can watch a tester's device from your desk.

| | |
| --- | --- |
| Artifact | `com.github.azizimusa:AntooSpector:1.2.0` (JitPack) |
| Requires | `minSdk` 24 · Java 11 · OkHttp 4.x |
| Language | Kotlin, and [fully usable from Java](#using-it-from-java) — no Kotlin plugin needed |

---

# Quick start

Four steps. Steps 1–3 are all required — **skipping step 3 is the usual reason nothing shows up.**

## Step 1 — Add the dependency

In your project's `settings.gradle.kts`, add JitPack to the repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // <- add this
    }
}
```

<details>
<summary>Groovy (<code>settings.gradle</code>)</summary>

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```
</details>

Then in your **app module's** `build.gradle.kts`:

```kotlin
dependencies {
    debugImplementation("com.github.azizimusa:AntooSpector:1.2.0")
}
```

`debugImplementation` keeps the monitor — and everything it captures — out of your release APK.

> **First build is slow.** The version is a git tag, and JitPack builds a tag the first time
> anyone asks for it. The first resolution of a new version can take a couple of minutes;
> after that it's cached.

Java-only project? Nothing extra to install, but your module must compile against Java 11 —
see [Using it from Java](#using-it-from-java).

## Step 2 — Start the monitor

Once, in your `Application.onCreate`, guarded so it never runs in release:

**Kotlin**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) return

        HttpMonitor
            .start(maxTransactions = 500, maxBodyBytes = 512 * 1024)
            .addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
    }
}
```

**Java**

```java
public class MyApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (!BuildConfig.DEBUG) return;

        HttpMonitor.INSTANCE
                .start(500, 512 * 1024)   // both arguments required from Java
                .addFilter(new HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"));
    }
}
```

What the two numbers mean:

- `maxTransactions` — how many exchanges to keep. Past this, the oldest are dropped.
- `maxBodyBytes` — how much of each body to keep. Bigger bodies are truncated for storage, but
  their real size is still reported.

Don't forget to register the class in your manifest, if it isn't already:

```xml
<application android:name=".MyApp" ... >
```

> **`start()` on its own captures nothing.** It only arms the monitor. Capture is opt-in per HTTP
> client, which is step 3.

## Step 3 — Wire it into your HTTP client

### If you use OkHttp or Retrofit

Add `HttpMonitorInterceptor` as an **application** interceptor (`addInterceptor`, *not*
`addNetworkInterceptor`), and add it **last**, so it sees the final request with all your own
headers already applied and the response body already decompressed:

**Kotlin**

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor())          // your own interceptors first
    .addInterceptor(HttpMonitorInterceptor())   // monitor last
    .build()
```

**Java (with Retrofit)**

```java
OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(new AuthInterceptor())
        .addInterceptor(new HttpMonitorInterceptor())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();

Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(Utils.getServerURL())
        .client(client)                         // Retrofit uses this client, so it's covered
        .addConverterFactory(GsonConverterFactory.create())
        .build();
```

Retrofit itself needs no changes — it monitors whatever `OkHttpClient` you hand it.

Build the client **once** and reuse it. The interceptor re-checks whether the monitor is enabled
on every call, so it's fine for a client to be created before `HttpMonitor.start()` runs.

### If you use `HttpURLConnection`

Open connections through `UrlInstrument` instead of `url.openConnection()`, and call
`disconnect()` when you're done — that, or reaching the end of the response stream, is what marks
the exchange complete:

```kotlin
val connection = UrlInstrument.openConnection(URL("https://example.com")) as HttpURLConnection
try {
    connection.inputStream.use { it.readBytes() }
} finally {
    connection.disconnect()
}
```

Unlike the OkHttp interceptor, `UrlInstrument` decides at open time, so the monitor must already
be started before you open the connection.

## Step 4 — Open the viewer

From anywhere — a debug drawer, a long-press, a shake handler, a debug-only menu item:

```kotlin
HttpMonitor.show(context)          // Java: HttpMonitor.INSTANCE.show(context)
```

The viewer's activities are declared in the library's own manifest, so your app needs **no**
manifest changes for it. Your app still needs `android.permission.INTERNET` for its own traffic.

That's everything. Use your app, then open the viewer and the calls are there.

---

## Nothing is showing up?

Walk this list in order — it's almost always one of the first three:

1. **Did you add the interceptor?** `HttpMonitor.start()` alone captures nothing (step 3).
2. **Is it on the client that actually makes the call?** An app often builds more than one
   `OkHttpClient` — image loading, analytics, and the API client are frequently separate.
3. **Did `start()` run?** It's usually behind `if (BuildConfig.DEBUG)`, and `BuildConfig` there
   must be **your app's**, not the library's. Check `HttpMonitor.isEnabled` at the call site.
4. **Is your `Application` class in the manifest?** `android:name=".MyApp"`.
5. **Is a filter dropping it?** An `HttpFilter` returning `null` discards the whole transaction.
6. **Is the call made by something you don't control?** A WebView, a third-party SDK with its own
   internal client, or native code won't go through your interceptor.
7. **Release build?** With `debugImplementation`, the library isn't there at all.

---

## Optional: send traffic to a dashboard

`AntooReporter` ships captured traffic to an Antoo Spector dashboard (the Laravel companion
app) so you can watch a device you aren't holding. It needs two pieces of information:

| Required | What it is |
| --- | --- |
| `endpoint` | Full ingest URL of your dashboard, e.g. `https://spector.example.com/api/v1/ingest` |
| `apiKey` | The key that dashboard issued — sent as the `X-Antoo-Key` header |

Install it in the same chain as `start()`:

```kotlin
HttpMonitor
    .start(maxTransactions = 500, maxBodyBytes = 512 * 1024)
    .addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
    .report(
        AntooReporter(
            endpoint = BuildConfig.ANTOO_ENDPOINT,
            apiKey = BuildConfig.ANTOO_KEY,
            device = DeviceInfo.from(this)
        )
    )
```

```java
HttpMonitor.INSTANCE.report(
        new AntooReporter(
                BuildConfig.ANTOO_ENDPOINT,
                BuildConfig.ANTOO_KEY,
                DeviceInfo.from(this)));
```

`DeviceInfo.from(context)` fills in manufacturer, model, OS and app version, and mints an install
id kept in the library's own `SharedPreferences`. The dashboard groups traffic by that id.
Uninstalling the app resets it — it identifies an install, not a person.

It also reads the app's own identity off the build — package name, app label, platform — and
sends it alongside the device. One ingest key covers a whole project in the dashboard, and that
is how each app files itself under it. **You never type a package name into the dashboard.**

When one package ships as several things worth telling apart — a staging flavour, a white-label
build, a per-tester install — pass a tag and each becomes its own app in the dashboard:

```kotlin
DeviceInfo.from(this, tag = "staging")
```

```java
DeviceInfo.from(this, "staging")
```

Without a tag, the package name is the identifier, so all builds of one package are one app.

### Naming a device

Installs are told apart by an id the library mints, and shown in the dashboard as the phone's
make and model — so two testers on the same model read alike, distinguished only by the tail of
that id. Name the install to fix that:

```kotlin
DeviceInfo.from(this, tag = "staging", label = "Azizi's Pixel")
```

```java
DeviceInfo.from(this, "staging", "Azizi's Pixel");   // tag first, then label
```

A label is what the dashboard shows wherever the device appears. Nothing enforces uniqueness —
it is a name for you to read, and the install id underneath stays the real identity.

### Keep the endpoint and key out of version control

Put them in `local.properties` (git-ignored) and expose them as `BuildConfig` fields — this
repo's sample app does exactly this, so you can copy it verbatim:

```properties
# local.properties
antoo.endpoint=https://spector.example.com/api/v1/ingest
antoo.key=your-key
```

```kotlin
// app/build.gradle.kts
import java.util.Properties

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(name: String) = local.getProperty(name).orEmpty()

android {
    defaultConfig {
        buildConfigField("String", "ANTOO_ENDPOINT", "\"${setting("antoo.endpoint")}\"")
        buildConfigField("String", "ANTOO_KEY", "\"${setting("antoo.key")}\"")
    }
    buildFeatures { buildConfig = true }
}
```

With neither set, the app simply captures locally and ships nothing — guard the `report(…)` call
with `if (BuildConfig.ANTOO_ENDPOINT.isNotEmpty() && BuildConfig.ANTOO_KEY.isNotEmpty())`.

### How the reporter behaves

- Transactions are queued as captured and posted in batches from one background daemon thread.
  **Your HTTP calls are never blocked by an upload.**
- Filters run first, so anything redacted locally is redacted on the wire too.
- Nothing about it is fatal. A queue that outruns the network drops its oldest entries; a failed
  upload is retried with exponential backoff; a batch the server permanently rejects (bad key,
  malformed payload) is dropped rather than wedging the queue behind it.
- The reporter's own uploads are never themselves captured.

Tunable constructor parameters: `batchSize` (50), `flushIntervalMs` (15 s), `queueCapacity` (500),
`maxBodyChars` (16 384), `client` if you want to supply your own `OkHttpClient`, and
`heartbeatIntervalMs` (5 s).

### Online status

The dashboard shows each device as online or offline, and it can only know that if the client
keeps saying so. With nothing queued, the reporter posts an **empty batch** — a heartbeat, a couple
of hundred bytes on the one background thread it already owns. Contact of any kind counts, so a
device sending traffic never also sends a heartbeat.

How quickly the two transitions show up:

| | when it shows | why |
|---|---|---|
| **Online** | ~1 s after launch | the reporter reports in as it starts, rather than an interval later |
| **Offline** | ~12 s after the app dies | two missed heartbeats (5 s each) plus a second of slack, aged out by the dashboard between polls |

`heartbeatIntervalMs` is the dial for the second row: **the dashboard cannot call a device gone
sooner than it expects to hear from it.** Every batch tells it the cadence, and it sizes that
device's window from what it was told — so raising the interval to save requests costs detection
time, and lowering it buys detection time at one short POST per interval. Queued traffic is
unaffected either way: it still travels in batches on `flushIntervalMs`, so heartbeats do not
make an app upload more often than you asked.

Pass `heartbeatIntervalMs = AntooReporter.HEARTBEAT_OFF` to stop them; the dashboard then judges a
device by the last traffic it captured, which reads as offline whenever the app is merely quiet.

`HttpMonitor.flushReports()` pushes what's queued right now; `HttpMonitor.report(null)` stops
reporting and releases the thread.

<details>
<summary><b>The wire format</b> — for implementing your own receiving end</summary>

`POST <endpoint>` with header `X-Antoo-Key: <apiKey>` and a JSON body:

```json
{
  "app": { "platform": "android", "package_name": "gg.padu.ke", "label": "Paduke",
           "tag": "staging", "version": "…", "build": "…" },
  "device": { "uid": "…", "manufacturer": "…", "model": "…", "os_version": "…",
              "app_version": "…", "app_build": "…", "report_interval_ms": 5000 },
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
(`"<binary body, 12.4 kB>"`). An empty `transactions` array is a heartbeat, and
`device.report_interval_ms` is how often the client promises to send one — a receiving end uses it
to decide how much silence means the app is gone. A 2xx means accepted; 429 and 5xx are retried;
anything else drops the batch.

To send traffic somewhere else entirely, implement `TransactionReporter` yourself. Its `report`
is called on the thread that made the HTTP call, so it must only hand the transaction off:
never block, never throw.
</details>

---

## Redacting and filtering

An `HttpFilter` runs **before** anything is stored, so whatever it strips never reaches the in-app
viewer or the dashboard. For the common case — masking sensitive headers — use the built-in one:

```kotlin
HttpMonitor.addFilter(HeaderRedactingFilter("Authorization", "Cookie", "Set-Cookie"))
```

Matched headers are replaced with `<redacted>`.

Return `null` from either method to drop the whole transaction:

```kotlin
HttpMonitor.addFilter(object : HttpFilter {
    // Keep the auth endpoint out of the log entirely.
    override fun filter(request: HttpRequest): HttpRequest? =
        request.takeUnless { it.url.contains("/auth/") }
})
```

Filters apply to everything captured from the moment you add them.

## Keeping it out of release builds

Two things together do it:

1. Depend on the library with `debugImplementation`.
2. Guard `HttpMonitor.start()` behind `BuildConfig.DEBUG`.

If the only code that touches `HttpMonitor` / `HttpMonitorInterceptor` lives in debug-only
sources, the whole thing compiles away in release.

---

## Using it from Java

The library is Kotlin, but it ships as an ordinary AAR — a Java-only Android project can use it
**without adding the Kotlin Gradle plugin**. The Kotlin standard library comes in as a transitive
dependency.

One requirement on the consuming module — **Java 8 will not work**:

```kotlin
android {
    defaultConfig { minSdk = 24 }                     // or higher
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11  // the AAR is Java 11 bytecode
        targetCompatibility = JavaVersion.VERSION_11
    }
}
```

Two Kotlin-isms to know: an `object` is reached through its `INSTANCE` field, and Kotlin's default
arguments don't exist in Java, so `start(…)` takes both parameters explicitly.

| Kotlin | Java |
| --- | --- |
| `HttpMonitor.start(500)` | `HttpMonitor.INSTANCE.start(500, 512 * 1024)` |
| `HttpMonitor.show(context)` | `HttpMonitor.INSTANCE.show(context)` |
| `Bodies.asCurl(transaction)` | `Bodies.INSTANCE.asCurl(transaction)` |
| `UrlInstrument.openConnection(url)` | `UrlInstrument.openConnection(url)` (static) |
| `DeviceInfo.from(context, tag = "staging")` | `DeviceInfo.from(context, "staging")` (static) |
| `transaction.request.url` | `transaction.getRequest().getUrl()` |

Everything else — `HttpMonitorInterceptor`, `AntooReporter`, `DeviceInfo`, `HeaderRedactingFilter` —
is constructed with `new` as usual.

`HttpFilter` compiles to real JVM default methods, so an anonymous class overrides only the method
it cares about:

```java
HttpMonitor.INSTANCE.addFilter(new HttpFilter() {
    @Override public HttpRequest filter(HttpRequest request) {
        return request.getUrl().contains("/auth/") ? null : request;
    }
});
```

`TransactionStore.Listener` is a single-method interface, so a Java lambda works:

```java
HttpMonitor.INSTANCE.addListener(transactions ->
        Log.d("Monitor", transactions.size() + " captured"));
```

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

<details>
<summary>Maven Local, a shared Maven repo, or a composite build</summary>

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
</details>

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
git tag 1.3.0 && git push origin 1.3.0
```

JitPack builds the tag on the first request for that version — there is nothing to upload. The
published version is the tag name (JitPack passes it to the build as `$VERSION`); keep the
fallback `version` in `httpmonitor/build.gradle.kts` in step with it so local publishing matches.

Build logs for a tag are at
<https://jitpack.io/com/github/azizimusa/AntooSpector/1.3.0/build.log>.
