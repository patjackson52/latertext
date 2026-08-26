package com.patjackson.latertext.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 28
internal const val TARGET_SDK = 37

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.configureAndroidApplication() {
    extensions.configure<ApplicationExtension> {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            targetSdk = TARGET_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables.useSupportLibrary = true
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            animationsDisabled = true
            unitTests.isIncludeAndroidResources = true
        }

        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )

        lint {
            abortOnError = true
            checkDependencies = true
            checkReleaseBuilds = true
        }
    }

    configureAndroidKotlin()
    configureAndroidTestDependencies()
    configureUnitTests()
}

internal fun Project.configureAndroidLibrary() {
    extensions.configure<LibraryExtension> {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            animationsDisabled = true
            unitTests.isIncludeAndroidResources = true
        }

        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )

        lint {
            abortOnError = true
            checkReleaseBuilds = true
        }
    }

    configureAndroidKotlin()
    configureAndroidTestDependencies()
    configureUnitTests()
}

private fun Project.configureAndroidTestDependencies() {
    dependencies.add("testImplementation", libs.findLibrary("junit-jupiter").get())
    dependencies.add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    dependencies.add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    dependencies.add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    dependencies.add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
}

private fun Project.configureAndroidKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(false)
        }
    }
}

internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Hilt contributes generated test classes even when a module has no
        // handwritten tests. Gradle 9.5 otherwise treats that valid empty
        // module as a discovery failure.
        failOnNoDiscoveredTests.set(false)
    }
}
