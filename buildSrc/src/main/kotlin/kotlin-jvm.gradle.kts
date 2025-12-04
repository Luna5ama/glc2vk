package buildsrc.convention

import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("buildsrc.convention.java")
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.contracts.ExperimentalContracts")
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xbackend-threads=0",
            "-Xconsistent-data-class-copy-visibility"
        )
    }
}