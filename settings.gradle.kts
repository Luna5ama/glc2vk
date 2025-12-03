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

includeBuild("../gl-wrapper") {
    dependencySubstitution {
        substitute(module("dev.luna5ama:gl-wrapper-base")).using(project(":base"))
        substitute(module("dev.luna5ama:gl-wrapper-core")).using(project(":shared"))
        substitute(module("dev.luna5ama:gl-wrapper-lwjgl-3")).using(project(":lwjgl-3"))
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