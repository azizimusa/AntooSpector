# HTTP Monitor

In-app HTTP traffic monitor for Android: captures OkHttp and `HttpURLConnection` exchanges and
shows them in a built-in viewer (request/response headers, formatted JSON/XML bodies, timings,
copy-as-cURL).

- Group / artifact: `com.github.azizimusa:AntooSpector:1.0.0`
- `minSdk` 24, Java 11, Kotlin
- Depends on OkHttp 4.x (`api`), AppCompat and RecyclerView

## Add it to another app

### Option A — JitPack (published releases)

Add the JitPack repository in the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

and in the app module:

```kotlin
dependencies {
    debugImplementation("com.github.azizimusa:AntooSpector:1.0.0")
}
```

The version is the git tag. JitPack builds the tag on first request, so the very first
resolution of a new version can take a couple of minutes.

### Option B — Maven Local (while iterating)

Publish from this project:

```bash
./gradlew :httpmonitor:publishToMavenLocal
```

Then add `mavenLocal()` to the consuming project's repositories and depend on the same
coordinates as above.

### Option C — a shared/checked-in Maven repository

```bash
./gradlew :httpmonitor:publishReleasePublicationToLocalRepoRepository
# artifacts land in build/repo/com/github/azizimusa/AntooSpector/1.0.0/
```

Copy that directory anywhere (a shared drive, another repo, an S3 bucket, an internal Maven
server) and point the consumer at it:

```kotlin
repositories {
    maven { url = uri("/path/to/repo") }
}
```

### Option D — composite build (no publishing at all)

In the consuming project's `settings.gradle.kts`:

```kotlin
includeBuild("/Users/you/AndroidStudioProjects/Paduke") {
    dependencySubstitution {
        substitute(module("com.github.azizimusa:AntooSpector")).using(project(":httpmonitor"))
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

Run `./gradlew :httpmonitor:testDebugUnitTest :httpmonitor:lintDebug` first, then:

```bash
git tag 1.1.0 && git push origin 1.1.0
```

JitPack builds the tag on the first request for that version — there is nothing to upload.
The published version is the tag name (JitPack passes it to the build as `$VERSION`); keep the
fallback `version` in `httpmonitor/build.gradle.kts` in step with it so local publishing matches.
Build logs for a tag are at
<https://jitpack.io/com/github/azizimusa/AntooSpector/1.1.0/build.log>.
