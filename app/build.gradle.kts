plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.ruuh.airtaskplay"
    compileSdk = 34
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "app.ruuh.airtaskplay"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    buildFeatures { prefab = true }
    signingConfigs { create("main") { storeFile = file("airtaskplay.jks"); storePassword = "airtaskplay"; keyAlias = "airtaskplay"; keyPassword = "airtaskplay" } }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") { signingConfig = signingConfigs.getByName("main"); isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.oboe:oboe:1.9.3")
}
