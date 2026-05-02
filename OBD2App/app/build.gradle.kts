plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date

// Load version properties
val versionPropsFile = file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}

// Get current version values
val versionMajor = versionProps.getProperty("VERSION_MAJOR", "1").toInt()
val versionMinor = versionProps.getProperty("VERSION_MINOR", "0").toInt()
val versionPatch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
val currentVersionCode = versionProps.getProperty("VERSION_CODE", "1").toInt()

// Use current version code for the build (increment only after successful build)
val buildVersionCode = currentVersionCode

// Generate version name with build number and timestamp
val dateFormat = SimpleDateFormat("yyyyMMdd-HHmm")
val buildTime = dateFormat.format(Date())
val generatedVersionName = "$versionMajor.$versionMinor.$versionPatch-build$buildVersionCode-$buildTime"

android {
    namespace = "com.sj.obd2app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.sj.obd2app"
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    
    packaging {
        resources {
            jniLibs {
                useLegacyPackaging = false
            }
        }
    }
}

base.archivesName.set("OBD2Viewer")

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // GPS Geoid correction + layout persistence
    implementation(libs.play.services.location)
    implementation(libs.gson)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            // Increment only when this task completes successfully
            val newVersionCode = currentVersionCode + 1
            versionProps.setProperty("VERSION_CODE", newVersionCode.toString())
            versionProps.store(versionPropsFile.writer(), "Auto-incremented on successful build")
            println("Version code incremented to $newVersionCode")
        }
    }

    tasks.named("assembleRelease") {
        doLast {
            // Increment only when this task completes successfully
            val newVersionCode = currentVersionCode + 1
            versionProps.setProperty("VERSION_CODE", newVersionCode.toString())
            versionProps.store(versionPropsFile.writer(), "Auto-incremented on successful build")
            println("Version code incremented to $newVersionCode")
        }
    }
}