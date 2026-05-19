import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val spotifyClientId = localProperties.getProperty("spotify.clientId", "")

val signingKeystoreFile = localProperties.getProperty("signing.keystoreFile", "")
val signingKeystorePassword = localProperties.getProperty("signing.keystorePassword", "")
val signingKeyAlias = localProperties.getProperty("signing.keyAlias", "")
val signingKeyPassword = localProperties.getProperty("signing.keyPassword", "")

android {
    namespace = "com.ambient.tvclock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ambient.tvclock"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }

    flavorDimensions += "platform"
    productFlavors {
        create("firetv") {
            dimension = "platform"
            applicationIdSuffix = ".firetv"
            minSdk = 25
            versionNameSuffix = "-firetv"
        }
        create("googletv") {
            dimension = "platform"
            applicationIdSuffix = ".googletv"
            minSdk = 29
            versionNameSuffix = "-googletv"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingKeystoreFile.isNotEmpty()) {
            create("release") {
                storeFile = file(signingKeystoreFile)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    ndkVersion = "26.1.10909125"

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    implementation("com.wireguard.android:tunnel:1.0.20260102")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
