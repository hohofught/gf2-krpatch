plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hoho.snqxkr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hoho.snqxkr"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    // 번역 엔진으로 두 가지를 만든다.
    //  kotlin: 앱 코드(Kotlin) 엔진만. 모든 기기
    //  native: C++ 엔진(libsnqx.so, armv8-a)을 넣은 판. arm64-v8a 기기만. .so 를 못 올리면 Kotlin 엔진을 쓴다
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    flavorDimensions += "engine"
    productFlavors {
        create("kotlin") {
            dimension = "engine"
            buildConfigField("boolean", "NATIVE_ENGINE", "false")
            // CMake 는 설정만 하고 아무것도 빌드하지 않는다 (.so 없음). 인자를 달리해 native 판과 빌드 폴더를 나눈다.
            // (ndk.abiFilters 를 쓰면 라이브러리들의 다른 CPU 용 .so 까지 빠지므로 CMake 쪽만 줄인다)
            externalNativeBuild {
                cmake {
                    targets += "snqx_none"
                    arguments += "-DSNQX_ENGINE=none"
                    abiFilters += "arm64-v8a"
                }
            }
        }
        create("native") {
            dimension = "engine"
            versionNameSuffix = "-native"
            buildConfigField("boolean", "NATIVE_ENGINE", "true")
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild { cmake { targets += "snqx" } }
        }
    }

    // RELEASE_KEYSTORE 환경변수가 있으면 그 키로, 없으면 디버그 키로 서명한다.
    // (CI 시크릿을 넣지 않아도 빌드가 되게 하되, 넣으면 업데이트 설치가 가능한 고정 키로 서명)
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    val hasReleaseKey = !releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // 배포본(서명 다름)과 나란히 설치해서 시험할 수 있게 패키지 이름을 나눈다
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "4g"
            // 실제 LangPackageTable 샘플 폴더 (./gradlew testDebugUnitTest -PsnqxSamples=...). 없으면 건너뛴다
            it.systemProperty("snqx.samples", (project.findProperty("snqxSamples") as String?).orEmpty())
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
