plugins {
    id("latertext.android.library")
    id("latertext.android.compose")
    id("latertext.hilt")
}

android {
    namespace = "com.patjackson.latertext.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:api"))
    implementation(project(":platform:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
}
