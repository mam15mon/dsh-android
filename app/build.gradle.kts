import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

val clientFile = rootProject.layout.projectDirectory.file("client.json")
val client = JsonSlurper().parseText(providers.fileContents(clientFile).asText.get()) as Map<*, *>
val releaseKeystore = providers.environmentVariable("DSH_ANDROID_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("DSH_ANDROID_STORE_PASSWORD").orNull
check(releaseKeystore.isNullOrBlank() == releasePassword.isNullOrBlank()) {
    "Provide both DSH_ANDROID_KEYSTORE and DSH_ANDROID_STORE_PASSWORD for release signing."
}

android {
    namespace = "io.github.jackwener.dsh"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = client["package"] as String
        testInstrumentationRunner = "io.github.jackwener.dsh.nativeextra.PhoneBackendTest"
        minSdk = 29
        targetSdk = 36
        versionCode = client["versionCode"] as Int
        versionName = client["versionName"] as String
        ndk { abiFilters += "arm64-v8a" }
        // Metadata for the bundled APK in src/main/assets/shizuku.
        buildConfigField("String", "SHIZUKU_VERSION", "\"13.6.0\"")
        buildConfigField("long", "SHIZUKU_VERSION_CODE", "1086L")
    }

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = releasePassword
            keyAlias = "dsh_android"
            keyPassword = releasePassword
            // minSdk 29 makes AGP drop v1 (JAR) signing, but some vendor installers, file managers and app stores
            // only look for META-INF signature files and reject the APK as unsigned. Ship v1+v2+v3.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            // R8 stays off until a minified build has been verified on a device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reaches into java.io.FileDescriptor on JDK 17+.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Package the verified native inputs byte for byte, including on hosts without the NDK.
            keepDebugSymbols += "**/*.so"
            // require-builtin is replaced by --expose-internals (js/patches/02); never ship the old addon.
            excludes += "**/libdsh_require_builtin.so"
        }
    }
}

kotlin { jvmToolchain(21) }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // 用浏览器登录 (#49): the sign-in flow in a Custom Tab when Google refuses the in-app WebView.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.coil.compose)
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    // Back-stack tests: the real nav graph on a TestNavHostController (Robolectric for the Android framework bits).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.navigation.testing)
    // Room migration tests on the JVM: the real SQLite engine runs the migration SQL against the exported schemas.
    testImplementation(libs.sqlite.jdbc)
    // Geofences on devices with Play services (background-FGS-start exempt); LocationManager otherwise.
    implementation(libs.play.services.location)
    // PDF text for file_read (Apache-2.0, ~3 MB). BouncyCastle is only needed for encrypted PDFs, which
    // file_read reports as unreadable instead (saves ~8 MB).
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    // Offline text recognition (Chinese + Latin, bundled model) for custom-drawn screens without readable nodes.
    implementation(libs.mlkit.text.chinese)
}
