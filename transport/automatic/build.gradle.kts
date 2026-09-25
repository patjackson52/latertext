plugins {
    id("latertext.android.library")
    id("latertext.hilt")
}

android {
    namespace = "com.patjackson.latertext.transport.automatic"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":data:api"))
    implementation(project(":platform:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.klinker.android.smsmms) {
        // Only the Apache-licensed pdu_alt encoder is used. Do not pull the library's
        // obsolete networking stack; Android's system MMS service owns carrier transport.
        isTransitive = false
    }
    implementation(libs.klinker.logger)
}
