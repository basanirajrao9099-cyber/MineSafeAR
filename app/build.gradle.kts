plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.minesafear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } // OpenCV native; v7a keeps low-end phones
        applicationId = "com.minesafear"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // AGP 9 only accepts the -optimize variant of the default file.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With built-in Kotlin, kotlin.compilerOptions.jvmTarget defaults to
    // compileOptions.targetCompatibility, so no separate kotlin block is needed.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.util.Log throws "not mocked" on the JVM by default, which
            // would fail any test that exercises FakeSyncApiService's logging.
            // Defaults make it a no-op instead of forcing a logger abstraction
            // into production code purely for tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.opencv)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose — versions come from the BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation — bottom bar destinations live in ui/navigation.
    implementation(libs.androidx.navigation.compose)

    // Room — offline storage (data/).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager — background sync (sync/).
    implementation(libs.androidx.work.runtime.ktx)

    // Retrofit & OkHttp — the SyncApiService contract and HTTP types
    implementation(libs.retrofit)
    implementation(libs.okhttp)

    // AR — ARCore plus SceneView, the maintained successor to Sceneform (ar/).
    implementation(libs.google.arcore)
    implementation(libs.sceneview.ar)

    // QR — generation for certificates, scanning for verification (certificate/).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
}
