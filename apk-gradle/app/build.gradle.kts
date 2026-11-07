plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/* Local builds sign with the author's keystore; CI either passes
 * -Pic.keystore=<path> (from a repository secret) or gets an unsigned APK. */
val keystoreFile = file(
    (project.findProperty("ic.keystore") as String?)
        ?: "D:/kwork/icv11/apk/debug.keystore"
)

android {
    namespace = "dev.ic.timerlpe"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ic.timerlpe"
        minSdk = 33
        targetSdk = 37
        versionCode = 170
        versionName = "0.17.0"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    signingConfigs {
        create("local") {
            storeFile = keystoreFile
            storePassword = (project.findProperty("ic.storePassword") as String?) ?: "android"
            keyAlias = (project.findProperty("ic.keyAlias") as String?) ?: "ic"
            keyPassword = (project.findProperty("ic.keyPassword") as String?) ?: "android"
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
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("local")
            } else {
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("io.airlift:aircompressor:0.27")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("io.github.suqi8.coui.kmp:coui-ui-android:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-icons-android:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-preference-android:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-blur-android:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
