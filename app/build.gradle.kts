plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Reads a Gradle property, then an environment variable, then falls back to a placeholder.
fun configValue(name: String, fallback: String): String {
    val fromProperty = providers.gradleProperty(name).orNull
    val fromEnv = System.getenv(name)
    return listOf(fromProperty, fromEnv).firstOrNull { !it.isNullOrBlank() } ?: fallback
}

android {
    namespace = "com.westly.nbms"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.westly.nbms"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val supabaseUrl = configValue("SUPABASE_URL", "https://placeholder.supabase.co")
        val supabaseAnonKey = configValue("SUPABASE_ANON_KEY", "placeholder-anon-key")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    // Permanent signing key. GitHub Actions supplies it through environment variables.
    // Without them (for example a local build) the app falls back to the debug key.
    val nbmsKeystorePath: String? = System.getenv("NBMS_KEYSTORE_PATH")
    val nbmsKeystorePassword: String? = System.getenv("NBMS_KEYSTORE_PASSWORD")
    val hasNbmsKey = !nbmsKeystorePath.isNullOrBlank() &&
        !nbmsKeystorePassword.isNullOrBlank() &&
        file(nbmsKeystorePath).exists()

    signingConfigs {
        if (hasNbmsKey) {
            create("nbms") {
                storeFile = file(nbmsKeystorePath!!)
                storePassword = nbmsKeystorePassword
                keyAlias = "nbms"
                keyPassword = nbmsKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same permanent key, so each new build installs over the old one.
            if (hasNbmsKey) signingConfig = signingConfigs.getByName("nbms")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasNbmsKey) {
                signingConfigs.getByName("nbms")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Hilt (dependency injection)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Kotlin libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Supabase + Ktor
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)

    // Storage, security, background work, splash, QR
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.zxing.core)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
