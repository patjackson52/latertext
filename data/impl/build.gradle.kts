plugins {
    id("latertext.android.library")
    id("latertext.hilt")
    id("latertext.room")
}

android {
    namespace = "com.patjackson.latertext.data.impl"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":data:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
