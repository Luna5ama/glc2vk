plugins {
    id("buildsrc.convention.kotlin-jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    mavenLocal()
}

dependencies {
    implementation(project(":glc2vk-common"))

    implementation(libs.kotlinxSerializationCore)
    implementation(libs.fastutil)
    implementation(libs.kmogus.core)

    implementation("net.echonolix:caelum-core:1.0-SNAPSHOT")
    implementation("net.echonolix:caelum-vulkan:1.0-SNAPSHOT")
    implementation("net.echonolix:caelum-glfw-vulkan:1.0-SNAPSHOT")
}