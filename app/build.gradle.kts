plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Supply the original app-signing keystore through CI/local environment variables.
// Never commit the keystore or passwords to source control.
val signingStoreFile = providers.environmentVariable("VEC_DRAW_STORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("VEC_DRAW_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("VEC_DRAW_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("VEC_DRAW_KEY_PASSWORD").orNull
val hasReleaseSigning = !signingStoreFile.isNullOrBlank() && !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.topz.vecdraw"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.topz.vecdraw"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
}
