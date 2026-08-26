plugins {
    id("latertext.android.library")
    id("latertext.android.compose")
}

android {
    namespace = "com.patjackson.latertext.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}
