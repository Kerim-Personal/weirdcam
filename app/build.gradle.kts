plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.weirdcam" //
    compileSdk = 35 // Android 15 (U) - Geliştirme için, stabilite için 34'e çekilebilir

    defaultConfig {
        applicationId = "com.example.weirdcam" //
        minSdk = 21 //
        targetSdk = 34 // Android 14 (T) - compileSdk ile uyumlu olması iyi olur (örn: 35)
        versionCode = 1 //
        versionName = "1.0" //

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" //
    }

    buildTypes {
        release {
            isMinifyEnabled = false //
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), //
                "proguard-rules.pro" //
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11 //
        targetCompatibility = JavaVersion.VERSION_11 //
    }
    kotlinOptions {
        jvmTarget = "11" //
    }
    buildFeatures {
        compose = true //
    }
}

dependencies {
    implementation(libs.androidx.core.ktx) //
    implementation(libs.androidx.core) // Titreşim için
    implementation(libs.androidx.lifecycle.runtime.ktx) //
    implementation(libs.androidx.activity.compose) //
    implementation(platform(libs.androidx.compose.bom)) //
    implementation(libs.androidx.ui) //
    implementation(libs.androidx.ui.graphics) //
    implementation(libs.androidx.ui.tooling.preview) //
    implementation(libs.androidx.material3) //

    implementation(libs.androidx.camera.core) //
    implementation(libs.androidx.camera.camera2) //
    implementation(libs.androidx.camera.lifecycle) //
    implementation(libs.androidx.camera.view) //
    implementation("androidx.camera:camera-video:1.3.2") // veya libs.androidx.camera.video

    implementation("androidx.compose.material:material-icons-core:1.6.7") //
    implementation("androidx.compose.material:material-icons-extended:1.6.7") //

    testImplementation(libs.junit) //
    androidTestImplementation(libs.androidx.junit) //
    androidTestImplementation(libs.androidx.espresso.core) //
    androidTestImplementation(platform(libs.androidx.compose.bom)) //
    androidTestImplementation(libs.androidx.ui.test.junit4) //
    debugImplementation(libs.androidx.ui.tooling) //
    debugImplementation(libs.androidx.ui.test.manifest) //
    implementation("io.coil-kt:coil-compose:2.6.0") // En son sürümü kontrol edin
}