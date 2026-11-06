import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Cada compilación de GitHub Actions tiene un número mayor que la anterior,
// así cada APK nuevo se instala encima del anterior sin desinstalar nada.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.iacamara.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iacamara.app"
        minSdk = 34
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.4.$buildNumber"
        ndk {
            // Solo procesadores de 64 bits (S22+ y Magic 8 Lite): APK más pequeño.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Firma fija: GitHub Actions genera la clave a partir del secreto CLAVE_FIRMA
    // (scripts/derive_signing_key.py). Así todas las versiones se firman igual y se
    // instalan encima de la anterior. La clave nunca está en el repositorio.
    val keystorePath = System.getenv("IACAMARA_KEYSTORE")
    val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) {
            create("iacamara") {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = System.getenv("IACAMARA_KEYSTORE_PASSWORD")
                keyAlias = "iacamara"
                keyPassword = System.getenv("IACAMARA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sin secreto se usa una firma temporal: el APK funciona, pero para actualizar
            // hay que desinstalar la versión anterior.
            signingConfig = signingConfigs.findByName("iacamara") ?: signingConfigs.getByName("debug")
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
        jniLibs {
            // MediaPipe y ONNX Runtime podrían traer la misma librería de C++.
            pickFirsts += "**/libc++_shared.so"
            // Librerías de Qualcomm para chips que no son los de tus móviles (DSP antiguo y HTP V68).
            excludes += listOf("**/libQnnDsp*.so", "**/libQnnHtpV68*.so")
            // El chip de IA (NPU) carga su parte del controlador desde archivos normales del móvil:
            // las librerías tienen que extraerse al instalar (además, así el APK ocupa menos).
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // Los modelos de IA se leen directamente del APK: no deben ir comprimidos.
        noCompress += listOf("tflite", "task", "onnx")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)

    // IA en el móvil (modelos dentro del APK, sin internet).
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}
