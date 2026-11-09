import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (not in git). Without it, release falls back to debug signing.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.svitlograf"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.svitlograf"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "dist"
    productFlavors {
        // Sideloaded APK with self-updates from the Mac mini.
        create("github") {
            dimension = "dist"
            buildConfigField("String", "UPDATES_URL", "\"https://beta.decadance.me/svitlograf\"")
            buildConfigField("boolean", "DIRECT_BATTERY_REQUEST", "true")
        }
        // Google Play: no self-updates, no restricted permissions.
        create("play") {
            dimension = "dist"
            buildConfigField("String", "UPDATES_URL", "\"\"")
            buildConfigField("boolean", "DIRECT_BATTERY_REQUEST", "false")
        }
    }

    androidResources {
        localeFilters += listOf("en", "uk")
    }


    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.11.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    systemProperty("robolectric.dependency.repo.id", "gcs-central")
}
