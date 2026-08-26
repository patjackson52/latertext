plugins {
    id("latertext.android.library")
    id("latertext.hilt")
}

android {
    namespace = "com.patjackson.latertext.platform.android"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":data:api"))
    implementation(project(":platform:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.hilt.compiler)
}
