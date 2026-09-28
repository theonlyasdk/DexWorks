plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.asdk.tools.dexworks"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.asdk.tools.dexworks"
        minSdk = 23
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.sora.editor)
    implementation(libs.baksmali)
    implementation(libs.cfr)
    implementation(libs.dex2jar.translator)
    coreLibraryDesugaring(libs.desugar.nio)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
