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
            // JitPack serves a multi-module repo as com.github.<user>.<repo>:<artifactId>,
            // so the group has to spell out the GitHub coordinates to be resolvable.
            groupId = "com.github.azizimusa.AntooSpector"
            artifactId = "http-monitor"
            // JitPack exports the tag it is building as $VERSION; the fallback keeps
            // local publishing working. Keep it in step with the git tag.
            version = System.getenv("VERSION") ?: "1.0.0"

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
