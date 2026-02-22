import java.io.FileInputStream
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val buildTime = SimpleDateFormat("yyyyMMdd.HHmm").format(Date())

android {
    namespace = "net.remotetx.hamradio"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.remotetx.hamradio"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.$buildTime"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["release.keyAlias"] as String? ?: "key0"
            keyPassword = keystoreProperties["release.keyPassword"] as String? ?: "android"
            storeFile = file("release.jks")
            storePassword = keystoreProperties["release.storePassword"] as String? ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Reference the AAR by its file name using the flatDir from settings.gradle.kts
    implementation(group = "", name = "blueparrottsdk-release", ext = "aar")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.webkit:webkit:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
