# HTTP Monitor

In-app HTTP traffic monitor for Android: captures OkHttp and `HttpURLConnection` exchanges and
shows them in a built-in viewer (request/response headers, formatted JSON/XML bodies, timings,
copy-as-cURL).

- Group / artifact: `gg.padu:http-monitor:1.0.0`
- `minSdk` 24, Java 11, Kotlin
- Depends on OkHttp 4.x (`api`), AppCompat and RecyclerView

## Add it to another app

### Option A — Maven Local (quickest)

Publish from this project:

```bash
./gradlew :httpmonitor:publishToMavenLocal
```

Then in the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

and in the app module:

```kotlin
dependencies {
    debugImplementation("gg.padu:http-monitor:1.0.0")
}
```

### Option B — a shared/checked-in Maven repository

```bash
./gradlew :httpmonitor:publishReleasePublicationToLocalRepoRepository
# artifacts land in build/repo/gg/padu/http-monitor/1.0.0/
```

Copy that directory anywhere (a shared drive, another repo, an S3 bucket, an internal Maven
server) and point the consumer at it:

```kotlin
repositories {
    maven { url = uri("/path/to/repo") }
}
```

### Option C — composite build (no publishing while iterating)

In the consuming project's `settings.gradle.kts`:

```kotlin
includeBuild("/Users/you/AndroidStudioProjects/Paduke") {
    dependencySubstitution {
        substitute(module("gg.padu:http-monitor")).using(project(":httpmonitor"))
    }
}
```

## Use it

Start the monitor once, typically in `Application.onCreate` and only for debug builds:

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

Capture is opt-in per client. For OkHttp add the interceptor as an **application** interceptor so
bodies are seen decompressed:

```kotlin
OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor())
    .addInterceptor(HttpMonitorInterceptor())
    .build()
```

For `HttpURLConnection`, open connections through `UrlInstrument` and call `disconnect()` when
done — that, or the end of the response stream, marks the exchange complete:

```kotlin
val connection = UrlInstrument.openConnection(URL("https://example.com")) as HttpURLConnection
try {
    connection.inputStream.use { it.readBytes() }
} finally {
    connection.disconnect()
}
```

Open the viewer from anywhere (a debug drawer, a long-press, a shake handler):

```kotlin
HttpMonitor.show(context)
```

The viewer activities are declared in the library manifest, so the consuming app needs no manifest
changes. The app still needs `android.permission.INTERNET` for its own traffic.

## Filtering

`HttpFilter` adjusts or drops traffic before it is stored. Returning `null` from either method
skips the whole transaction:

```kotlin
HttpMonitor.addFilter(object : HttpFilter {
    override fun filter(request: HttpRequest): HttpRequest? =
        request.takeUnless { it.url.contains("/auth/") }

    override fun filter(response: HttpResponse): HttpResponse = response
})
```

`HeaderRedactingFilter(vararg names)` is included for the common case of masking headers.

## Other API

| Call | Purpose |
| --- | --- |
| `HttpMonitor.stop()` / `start()` | Pause and resume capture; the stored list is kept |
| `HttpMonitor.isEnabled` | Whether capture is currently on |
| `HttpMonitor.transactions()` | Snapshot of what was captured, newest first |
| `HttpMonitor.find(id)` | One transaction by id |
| `HttpMonitor.clear()` | Drop everything captured so far |
| `HttpMonitor.addListener(...)` | Observe the list (delivered on the main thread) |
| `Bodies.asCurl(transaction)` | Reproduce a request as a cURL command |

Bodies larger than `maxBodyBytes` are truncated for storage but their real size is still reported
(`bodySize`, `bodyTruncated`).

## Keeping it out of release builds

Depend on it with `debugImplementation` and guard `HttpMonitor.start()` behind `BuildConfig.DEBUG`.
Code that only calls `HttpMonitor`/`HttpMonitorInterceptor` from debug-only sources then compiles
away entirely in release.

## Releasing a new version

Bump `version` in `httpmonitor/build.gradle.kts`, then publish with one of the commands above.
Run `./gradlew :httpmonitor:testDebugUnitTest :httpmonitor:lintDebug` first.
