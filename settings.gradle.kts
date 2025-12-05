rootProject.name = "glc2vk"

pluginManagement {
    repositories {
        maven("https://maven.luna5ama.dev")
        gradlePluginPortal()
    }

    plugins {
        id("dev.luna5ama.jar-optimizer") version "1.2-SNAPSHOT"
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

listOf(
    "common",
    "capture",
    "replay",
).map {
    "${rootProject.name}-$it" to file(it)
}.forEach { (name, dir) ->
    include(name)
    project(":$name").projectDir = dir
}