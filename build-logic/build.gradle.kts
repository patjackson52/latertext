plugins {
    `kotlin-dsl`
}

group = "com.patjackson.latertext.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.room.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "latertext.android.application"
            implementationClass = "com.patjackson.latertext.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "latertext.android.library"
            implementationClass = "com.patjackson.latertext.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "latertext.android.compose"
            implementationClass = "com.patjackson.latertext.buildlogic.AndroidComposeConventionPlugin"
        }
        register("kotlinJvm") {
            id = "latertext.kotlin.jvm"
            implementationClass = "com.patjackson.latertext.buildlogic.KotlinJvmConventionPlugin"
        }
        register("hilt") {
            id = "latertext.hilt"
            implementationClass = "com.patjackson.latertext.buildlogic.HiltConventionPlugin"
        }
        register("room") {
            id = "latertext.room"
            implementationClass = "com.patjackson.latertext.buildlogic.RoomConventionPlugin"
        }
    }
}
