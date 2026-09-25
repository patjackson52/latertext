buildscript {
    providers.gradleProperty("shipyardDeployPluginRepository").orNull?.let { repository ->
        repositories {
            maven { url = uri(repository); content { includeGroup("works.sloop.shipyard") } }
            mavenCentral()
        }
        dependencies { classpath("works.sloop.shipyard:shipyard-deploy-gradle-plugin:0.1.0-latertext.0597dd4") }
    }
}

plugins {
    id("latertext.android.application")
    // Explicit application marker also lets Shipyard Deploy discover this convention-based module.
    id("com.android.application") version libs.versions.agp.get()
    id("latertext.android.compose")
    id("latertext.hilt")
}

if (providers.gradleProperty("shipyardDeployPluginRepository").isPresent) {
    apply(plugin = "works.sloop.shipyard.deploy")
}

android {
    namespace = "com.patjackson.latertext"
    buildFeatures { buildConfig = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true }

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
        create("development") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = ""
            matchingFallbacks += "debug"
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
    sourceSets.getByName("development").kotlin.srcDir("src/debug/java")
}

configurations.named("developmentImplementation") { extendsFrom(configurations.getByName("debugImplementation")) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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
    debugImplementation("works.sloop.swip:swip-core:0.1.12")
    debugImplementation("works.sloop.swip:swip-debug:0.1.1")
    debugImplementation("com.sloopworks.debugdrawer:debugdrawer:0.1.1")
    debugImplementation("com.sloopworks.debugdrawer:debugdrawer-swip:0.1.1")

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
