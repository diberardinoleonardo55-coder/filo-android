plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La chiave di firma arriva dai segreti della repo, non da qui dentro.
//
// Serve che sia SEMPRE LA STESSA: Android rifiuta di installare un
// aggiornamento firmato con una chiave diversa da quella dell'app gia'
// installata ("App non installata"), e l'unica via d'uscita e' disinstallare
// perdendo le impostazioni. Le build di debug in CI firmavano con una chiave
// generata al momento sul runner, cioe' diversa a ogni compilazione.
val chiaveFile: String? = System.getenv("CHIAVE_FILE")
val chiavePassword: String? = System.getenv("CHIAVE_PASSWORD")

android {
    namespace = "it.leo.filo"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.leo.filo"
        // 26: da qui in su ci sono i canali di notifica e i servizi in primo
        // piano come li usa Filo.
        minSdk = 26
        targetSdk = 34
        // Il numero della build di GitHub, cosi' cresce da solo: un APK con un
        // versionCode piu' basso di quello installato non si installa.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.1"
    }

    signingConfigs {
        create("nostra") {
            if (chiaveFile != null) {
                storeFile = file(chiaveFile)
                storePassword = chiavePassword
                keyAlias = "filo"
                keyPassword = chiavePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Senza chiave (una compilazione in locale) resta non firmato: e'
            // meglio di un APK firmato con una chiave qualsiasi.
            signingConfig = if (chiaveFile != null) signingConfigs.getByName("nostra") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
