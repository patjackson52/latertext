plugins {
    id("latertext.kotlin.jvm")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":data:api"))
    api(project(":platform:api"))
    api(libs.kotlinx.coroutines.test)
}
