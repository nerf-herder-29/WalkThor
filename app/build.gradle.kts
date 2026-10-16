plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nerfherder.walkthor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nerfherder.walkthor"
        // AYN Thor ships with Android 13 (API 33); 26 keeps it usable on other handhelds too.
        minSdk = 26
        targetSdk = 35
        versionCode = 52
        versionName = "1.25.0"
    }

    // One permanent key for every release, so new APKs install as updates over old ones.
    // CI decodes it from the WALKTHOR_KEYSTORE secret; without it, release falls back to the debug key.
    val releaseKeystore = System.getenv("WALKTHOR_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WALKTHOR_KEY_PASSWORD")
                keyAlias = "walkthor"
                keyPassword = System.getenv("WALKTHOR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrink and optimise: less code to load means less memory taken from the game.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Screenshot tests (StoreScreenshots) render real resources and write PNGs.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "2g"
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    testImplementation(composeBom)
    debugImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // PDF text extraction (for search + match highlighting). Rendering uses the platform PdfRenderer.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Cover artwork thumbnails (content:// URIs from the Cocoon folder, and local files).
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Screenshot rendering on the JVM (no device needed); not part of the app.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
