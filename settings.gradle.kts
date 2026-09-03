rootProject.name = "vibris"

pluginManagement {
    repositories {
        maven("https://maven.luna5ama.dev")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }

    plugins {
        id("dev.luna5ama.jar-optimizer") version "1.2-SNAPSHOT"
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        id("fabric-loom") version "1.14.4"
    }
}

includeBuild("../Iris") {
    dependencySubstitution {
        substitute(module("net.irisshaders:common")).using(project(":common"))
        substitute(module("net.irisshaders:fabric")).using(project(":fabric"))
    }
}

listOf(
    "api",
    "common",
    "capture",
    "core",
    "integration-tests",
    "protocol-java",
    "replay-vk",
    "replay-gl",
    "test-runtime",
    "mod-common",
    "mod-fabric",
).map {
    "${rootProject.name}-$it" to file(it)
}.forEach { (name, dir) ->
    include(name)
    project(":$name").projectDir = dir
}
