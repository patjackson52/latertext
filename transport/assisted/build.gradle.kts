plugins {
    id("latertext.android.library")
    id("latertext.hilt")
}

android {
    namespace = "com.patjackson.latertext.transport.assisted"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":data:api"))
    implementation(project(":platform:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
