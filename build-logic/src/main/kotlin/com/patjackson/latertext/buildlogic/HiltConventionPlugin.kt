package com.patjackson.latertext.buildlogic

import dagger.hilt.android.plugin.HiltExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        extensions.configure<HiltExtension> {
            enableAggregatingTask = true
        }

        dependencies.add("implementation", libs.findLibrary("hilt-android").get())
        dependencies.add("ksp", libs.findLibrary("hilt-compiler").get())
        Unit
    }
}
