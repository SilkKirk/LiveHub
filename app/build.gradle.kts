import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystoreFile.exists().also { exists ->
    if (exists) keystoreFile.inputStream().use(keystoreProperties::load)
}

/**
 * Version, overridable from the command line.
 *
 * `-PversionName=v0.9.1` / `-PversionCode=901` is what the release workflow passes, derived
 * from the git tag. The defaults keep an ordinary `assembleDebug` working without them, but
 * a store build must never fall back to them: `versionCode` has to increase on every
 * release or an update is rejected as a downgrade, and forgetting to bump it by hand is
 * exactly the kind of thing that only shows up when a user cannot install the new build.
 */
val releaseVersionName: String = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
    ?: "0.1.0"
val releaseVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: 1

android {
    namespace = "com.livehub.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.livehub.app"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.create("release") {
                    storeFile = file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            } else {
                // Fall back to the debug key so the *minified* build is still installable.
                // Without this the release APK is unsigned and no CI job can launch it, which
                // is exactly how a startup crash caused only by R8 reached a user: every
                // automated check exercised the unminified debug build instead.
                // The release workflow refuses to publish a debug-signed release, so this
                // cannot leak into a real release.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        /**
         * The 48dp touch minimum has been broken twice in this project, both times in a way that
         * looked fixed: `size(32.dp)` on an `IconButton` (round 61), and then
         * `size(48.dp).padding(8.dp)` — a 48dp semantics node around a 32dp clickable area,
         * because `IconButton` applies Material's minimum *inside* the caller's modifier
         * (round 65). Neither was caught by a test; both were caught by reading the code.
         *
         * Compose's own lint check knows this rule, so the third occurrence should be caught by
         * CI instead of by whoever happens to miss the button.
         */
        error += "TouchTargetSizeCheck"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":core:designsystem"))

    implementation(project(":platform-api"))
    implementation(project(":platform:douyu"))
    implementation(project(":platform:huya"))
    implementation(project(":platform:douyin"))
    implementation(project(":platform:bilibili"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    // Douyu signs playback requests by running the site's own JS; the signature
    // algorithm changes without notice, so the script is evaluated rather than
    // reimplemented.
    implementation("org.mozilla:rhino:1.7.14")
    implementation(libs.androidx.work.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // HLS only. DASH was never wired up — `LivePlayer` picks between `HlsMediaSource` and
    // `ProgressiveMediaSource`, and no platform adapter serves a DASH manifest — so the module
    // was pure APK weight. The OkHttp data source is absent for the same reason: playback uses
    // `DefaultHttpDataSource`, because the CDNs need custom `Referer` headers and cross-protocol
    // redirects that the OkHttp one handles less predictably.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    // Deliberately absent:
    //  * `navigation-compose` — navigation is the `Screen` enum in `UiState`, driven by the
    //    ViewModel; a NavHost would be a second source of truth.
    //  * `jmdns` — LAN discovery goes through Android's own `NsdManager`
    //    (`NsdPeerDiscovery`), so the library was never referenced (its `-keep` rule in
    //    proguard-rules.pro went with it).
    //  * `bouncycastle` — every signature in this app is hand-written (MD5/RC4/WBI/a_bogus);
    //    no `Security.addProvider`, `Cipher` or `X509` call exists anywhere.

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    // Compose UI assertions under Robolectric, so overlay behaviour is covered on the JVM
    // instead of only on an emulator. The debug manifest it needs is already declared above.
    testImplementation(libs.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

/**
 * Robolectric's Android runtime jar, fetched by Gradle instead of by Robolectric.
 *
 * By default Robolectric downloads `org.robolectric:android-all-instrumented` (151 MB for
 * SDK 34) from Maven Central **inside the test JVM**, on every clean runner, with no shared
 * cache. One slow or failed fetch then fails every Robolectric test with
 * `initializationError: Failed to fetch maven artifact`, which reads exactly like a code
 * failure and turned three CI runs red for no reason.
 *
 * Gradle already resolves and caches dependencies reliably, so the jar is resolved here and
 * Robolectric is pointed at the directory in offline mode below. `gradle/actions/setup-gradle`
 * persists the Gradle cache between runs, so this is a one-time download rather than a
 * per-run gamble.
 */
val robolectricRuntime: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    robolectricRuntime(libs.robolectric.android.all.instrumented)
}

val robolectricDependencyDir = layout.buildDirectory.dir("robolectric-runtime")

val copyRobolectricRuntime by tasks.registering(Copy::class) {
    group = "verification"
    description = "Stages Robolectric's Android runtime so tests never fetch it at runtime."
    from(robolectricRuntime)
    into(robolectricDependencyDir)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Always staged and always offline: if the artifact cannot be resolved the build fails
    // with an ordinary Gradle resolution error naming the coordinate, which is far easier to
    // act on than Robolectric's `initializationError` from inside a forked test JVM.
    dependsOn(copyRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDependencyDir.get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
