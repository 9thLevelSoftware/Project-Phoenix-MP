plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Read Supabase config from local.properties, falling back to environment variables for CI/CD
val localPropsFile = rootProject.file("local.properties")
val localPropsMap: Map<String, String> = if (localPropsFile.exists()) {
    localPropsFile.readLines()
        .filter { it.contains("=") && !it.trimStart().startsWith("#") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim()
        }
} else {
    emptyMap()
}

// Priority: local.properties > environment variables
val supabaseUrl: String = localPropsMap["supabase.url"]?.takeIf { it.isNotBlank() }
    ?: System.getenv("SUPABASE_URL")
    ?: ""
val supabaseAnonKey: String = localPropsMap["supabase.anon.key"]?.takeIf { it.isNotBlank() }
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: ""

// Validate Supabase credentials unless explicitly skipped (e.g., CI builds that only run tests)
val skipSupabaseCheck = providers.gradleProperty("skip.supabase.check").orNull?.toBoolean() ?: false
if (!skipSupabaseCheck && (supabaseUrl.isBlank() || supabaseAnonKey.isBlank())) {
    throw GradleException(
        "Missing Supabase credentials. Provide via local.properties (supabase.url, supabase.anon.key) " +
            "or environment variables (SUPABASE_URL, SUPABASE_ANON_KEY), " +
            "or pass -Pskip.supabase.check=true for test-only builds.",
    )
}

val injectedVersionCode = providers.gradleProperty("version.code").orNull?.let { raw ->
    val parsed = raw.toIntOrNull()
        ?: throw GradleException(
            "Invalid -Pversion.code='$raw'. Expected an integer between 1 and 2100000000.",
        )

    if (parsed !in 1..2_100_000_000) {
        throw GradleException(
            "Invalid -Pversion.code='$raw'. Expected an integer between 1 and 2100000000.",
        )
    }

    parsed
}

android {
    namespace = "com.devil.phoenixproject"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devil.phoenixproject"
        minSdk = 26
        targetSdk = 36
        // Fail fast if CI injects an invalid version code instead of silently shipping a default.
        versionCode = injectedVersionCode ?: 5
        versionName = "0.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Supabase config injected from local.properties
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        getByName("debug") {
            val keystorePath = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (keystorePath.exists()) {
                storeFile = keystorePath
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Release signing is handled by CI (GitHub Actions) via keystore secrets.
            // Local release builds use debug signing for testing only.
            // See .github/workflows/ for the production signing pipeline.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared module
    implementation(project(":shared"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)

    // Compose Tooling
    debugImplementation(libs.compose.ui.tooling)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging
    implementation(libs.kermit)

    // Image Loading - Coil 3
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.ktor.client.okhttp)

    // Testing - Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)

    // Testing - Instrumented/E2E Tests
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.multiplatform.settings.test)
    androidTestImplementation(libs.multiplatform.settings)
    debugImplementation(libs.compose.ui.test.manifest)
}
