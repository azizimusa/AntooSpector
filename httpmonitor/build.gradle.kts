plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "gg.padu.httpmonitor"
    resourcePrefix = "http_monitor_"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // okhttp3.Interceptor is part of the published API surface.
    api(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json implementation; the android.jar stub returns nulls in unit tests.
    testImplementation(libs.json)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // :httpmonitor is the only module that publishes, so JitPack collapses it to the
            // repo-level coordinates below and serves exactly these. (If a second module ever
            // publishes, JitPack switches to com.github.azizimusa.AntooSpector:<artifactId>.)
            groupId = "com.github.azizimusa"
            artifactId = "AntooSpector"
            // JitPack exports the tag it is building as $VERSION; the fallback keeps
            // local publishing working. Keep it in step with the git tag.
            version = System.getenv("VERSION") ?: "1.1.0"

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("HTTP Monitor")
                description.set("In-app HTTP traffic monitor for OkHttp and HttpURLConnection.")
            }
        }
    }
    repositories {
        // ./gradlew :httpmonitor:publishReleasePublicationToLocalRepoRepository
        maven {
            name = "localRepo"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
