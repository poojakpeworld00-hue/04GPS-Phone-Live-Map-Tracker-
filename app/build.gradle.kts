import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.googleservices)
    alias(libs.plugins.firebase.crashlytics)
}

val lhProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val lhApiKey: String = lhProps.getProperty("lighthouse.apiKey", "")
val lhBaseUrl: String = lhProps.getProperty("lighthouse.baseUrl", "")
// Number-lookup API (contact-saver) key; the base URL comes from Remote Config `contacts_base_url`.
val lookupApiKey: String = lhProps.getProperty("lookup.apiKey", "")

fun xorByteArrayLiteral(value: String, key: Int = 0x5A): String {
    if (value.isEmpty()) return "new byte[]{}"
    val parts = value.toByteArray(Charsets.UTF_8)
        .map { (it.toInt() xor key) and 0xFF }
        .map { if (it >= 0x80) it - 0x100 else it }   // Java byte is signed
        .joinToString(",")
    return "new byte[]{$parts}"
}

android {
    namespace = "com.find.my.phone.gps.location.tracker"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.find.my.phone.gps.location.tracker"
        // org.fossify:commons (the launcher's UI/theming base) declares minSdkVersion 26,
        // and the launcher leans on API 25/26 LauncherApps shortcut + pinned-item APIs.
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LightHouse credentials → obfuscated BuildConfig byte[] (decoded at runtime by Scrambled.s).
        buildConfigField("byte[]", "LH_API_KEY", xorByteArrayLiteral(lhApiKey))
        buildConfigField("byte[]", "LH_BASE_URL", xorByteArrayLiteral(lhBaseUrl))
        buildConfigField("byte[]", "LOOKUP_API_KEY", xorByteArrayLiteral(lookupApiKey))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // `-PdebugSignRelease=true` signs a release build with the debug key so the minified
            // build can be installed and smoke-tested on a device; never set for a store build.
            if (project.findProperty("debugSignRelease") == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // 17, not 11: org.fossify:commons is compiled against Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

base {
    val appName = "GPSPhoneLocationTracker"
    val formattedDate: String =
        SimpleDateFormat("MMM.dd.yyyy", Locale.getDefault()).format(Date())
    val config = android.defaultConfig
    archivesName.set(
        "${appName}_${config.applicationId}_v${config.versionName}(${config.versionCode})_$formattedDate"
    )
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.glide)
    implementation(libs.intuit.sdp)
    implementation(libs.intuit.ssp)
    implementation(libs.lottie)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.shimmer)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // IP geo lookup (ads country gate + language suggestions) is a single OkHttp call.
    implementation(libs.okhttp)
    // QR generation for the SIM Tools "Share + QR code" screen (encode only, no scanner).
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.gson)
    // Reminders: schedules each reminder's alert (survives app kill and reboot).
    implementation(libs.androidx.work.runtime.ktx)

    // ── Number lookup ──────────────────────────────────────────────────────────
    // contact-saver API, offline number facts (region / carrier / area), and the OSM map.
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    // Chucker: HTTP inspector on debug builds only; release links the no-op with the same API.
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.noop)
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.libphonenumber.carrier)
    implementation(libs.osmdroid)

    // Premium (paywall 9c): weekly / monthly subscriptions and a lifetime purchase.
    implementation(libs.billing)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // ── Ads module (adcast) ────────────────────────────────────────────────────
    implementation(libs.gms.play.services.ads)
    implementation(libs.firebase.config)
    implementation(libs.installreferrer)
    implementation(libs.firebase.analytics)
    implementation(libs.google.firebase.crashlytics)
    implementation(libs.facebook.android.sdk)
    implementation(libs.audience.network.sdk)
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)

    // LightHouse push SDK (audience attribution + data disclosure on the splash).
    implementation(libs.lighthouse)
    implementation(libs.lighthouse.extended)

    // ── Home-screen launcher ──────────────────────────────────────────────────
    // Home grid, drawer, side panels, widgets. Brings org.fossify:commons in as `api`.
    implementation(project(":launcher"))
}

// org.fossify:commons (via :launcher) drags in patternLockView, which still depends on the
// pre-AndroidX support library; it collides class-for-class with androidx. AGP 9 has no Jetifier.
configurations.configureEach {
    exclude(group = "com.android.support")
}
