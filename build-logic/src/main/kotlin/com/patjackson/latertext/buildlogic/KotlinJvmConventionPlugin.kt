package com.patjackson.latertext.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(false)
            }
        }

        dependencies.add("testImplementation", libs.findLibrary("junit-jupiter").get())
        dependencies.add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
        dependencies.add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            failOnNoDiscoveredTests.set(false)
        }
    }
}
