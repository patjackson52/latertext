package com.patjackson.latertext.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
        extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true

        val composeBom = dependencies.platform(libs.findLibrary("compose-bom").get())
        dependencies.add("implementation", composeBom)
        dependencies.add("androidTestImplementation", composeBom)
        dependencies.add("implementation", libs.findLibrary("compose-ui").get())
        dependencies.add("implementation", libs.findLibrary("compose-foundation").get())
        dependencies.add("implementation", libs.findLibrary("compose-material3").get())
        dependencies.add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
        dependencies.add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
        dependencies.add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
        dependencies.add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
        Unit
    }
}
