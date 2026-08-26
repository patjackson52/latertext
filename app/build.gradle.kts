plugins {
    id("latertext.android.application")
    id("latertext.android.compose")
    id("latertext.hilt")
}

android {
    namespace = "com.patjackson.latertext"

    defaultConfig {
        applicationId = "com.patjackson.latertext"
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:api"))
    implementation(project(":data:impl"))
    implementation(project(":platform:api"))
    implementation(project(":platform:android"))
    implementation(project(":transport:automatic"))
    implementation(project(":transport:assisted"))
    implementation(project(":feature:composer"))
    implementation(project(":feature:schedules"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
