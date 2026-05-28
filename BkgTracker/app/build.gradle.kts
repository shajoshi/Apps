import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    kotlin("plugin.serialization") version "2.0.21"
}

// Load version properties
val versionPropsFile = file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}

val versionMajor = versionProps.getProperty("VERSION_MAJOR", "1").toInt()
val versionMinor = versionProps.getProperty("VERSION_MINOR", "0").toInt()
val versionPatch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
val currentVersionCode = versionProps.getProperty("VERSION_CODE", "1").toInt()

val dateFormat = SimpleDateFormat("yyyyMMdd-HHmm")
val buildTime = dateFormat.format(Date())
val generatedVersionName = "$versionMajor.$versionMinor.$versionPatch-build$currentVersionCode-$buildTime"

android {
    namespace = "com.sj.bkgtracker"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.sj.bkgtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = currentVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"152667479080-h18un2l6n8o028ma6og4vseobcmfd94h.apps.googleusercontent.com\"")
    }

    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base.archivesName.set("BkgTracker")

tasks.withType<Test> {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            val newVersionCode = currentVersionCode + 1
            versionProps.setProperty("VERSION_CODE", newVersionCode.toString())
            versionProps.store(versionPropsFile.writer(), "Auto-incremented on successful build")
            println("Version code incremented to $newVersionCode")
        }
    }

    tasks.named("assembleRelease") {
        doLast {
            val newVersionCode = currentVersionCode + 1
            versionProps.setProperty("VERSION_CODE", newVersionCode.toString())
            versionProps.store(versionPropsFile.writer(), "Auto-incremented on successful build")
            println("Version code incremented to $newVersionCode")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.play.services.location)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(libs.junit)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
