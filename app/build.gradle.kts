plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.intercom.video.twoway"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.intercom.video.twoway"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `./gradlew functionalTest` runs only tests annotated @FunctionalTest.
        if (gradle.startParameter.taskNames.any { it.lowercase().endsWith("functionaltest") }) {
            testInstrumentationRunnerArguments["annotation"] =
                "com.intercom.video.twoway.functional.FunctionalTest"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // The 30-minute soak test only runs on request: ./gradlew :app:testDebugUnitTest -Psoak [-Psoak.minutes=N]
            it.useJUnitPlatform {
                if (project.hasProperty("soak")) includeTags("soak") else excludeTags("soak")
            }
            it.systemProperty("soak.minutes", (project.findProperty("soak.minutes") ?: "30").toString())
        }
        unitTests.isReturnDefaultValues = true
        managedDevices {
            localDevices {
                create("pixel6api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.concentus)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Runs every @FunctionalTest instrumented test headless on the managed emulator (CI).
tasks.register("functionalTest") {
    group = "verification"
    description = "Runs @FunctionalTest instrumented tests on the pixel6api34 managed device."
    dependsOn("pixel6api34DebugAndroidTest")
}

// Same tests on an emulator or phone that is already running (adb device).
tasks.register("connectedFunctionalTest") {
    group = "verification"
    description = "Runs @FunctionalTest instrumented tests on the connected device/emulator."
    dependsOn("connectedDebugAndroidTest")
}
