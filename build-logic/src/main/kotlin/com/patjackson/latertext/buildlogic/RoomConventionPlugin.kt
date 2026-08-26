package com.patjackson.latertext.buildlogic

import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")

        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }

        dependencies.add("implementation", libs.findLibrary("room-runtime").get())
        dependencies.add("implementation", libs.findLibrary("room-ktx").get())
        dependencies.add("ksp", libs.findLibrary("room-compiler").get())
        dependencies.add("testImplementation", libs.findLibrary("room-testing").get())
        dependencies.add("androidTestImplementation", libs.findLibrary("room-testing").get())
        Unit
    }
}
