import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key)?.trim().orEmpty().ifEmpty { default }

android {
    namespace = "com.munux.books"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.munux.books"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }

        // Configuração de tradução via local.properties (espécie de ".env" do projeto).
        // Em ordem de prioridade na 1ª instalação:
        //   1. TRANSLATOR_* (genérico, escolhe explicitamente o provider)
        //   2. DEEPSEEK_API_KEY (atalho — configura OPENAI_COMPATIBLE c/ api.deepseek.com)
        //   3. OPENAI_API_KEY  (atalho — OPENAI_COMPATIBLE c/ api.openai.com)
        //   4. ANTHROPIC_API_KEY (atalho — provider ANTHROPIC)
        //   5. GEMINI_API_KEY  (legado — provider GEMINI)
        buildConfigField("String", "TRANSLATOR_PROVIDER", "\"${localProp("TRANSLATOR_PROVIDER")}\"")
        buildConfigField("String", "TRANSLATOR_API_KEY", "\"${localProp("TRANSLATOR_API_KEY")}\"")
        buildConfigField("String", "TRANSLATOR_BASE_URL", "\"${localProp("TRANSLATOR_BASE_URL")}\"")
        buildConfigField("String", "TRANSLATOR_MODEL", "\"${localProp("TRANSLATOR_MODEL")}\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${localProp("DEEPSEEK_API_KEY")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProp("OPENAI_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProp("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProp("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${localProp("GEMINI_MODEL", "gemini-2.5-flash")}\"")
        buildConfigField("String", "TRANSLATOR_SOURCE_LANG", "\"${localProp("TRANSLATOR_SOURCE_LANG", localProp("GEMINI_SOURCE_LANG", "en"))}\"")
        buildConfigField("String", "TRANSLATOR_TARGET_LANG", "\"${localProp("TRANSLATOR_TARGET_LANG", localProp("GEMINI_TARGET_LANG", "pt-BR"))}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.pdfbox.android)

    debugImplementation(libs.androidx.ui.tooling)
}
