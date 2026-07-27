plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.published-module")
}

dependencies {
    implementation(project(":glc2vk-common"))
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation("org.lwjgl", "lwjgl")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    implementation(libs.bundles.glWrapper)
    implementation(libs.kotlinxSerializationCore)
    implementation(libs.kmogus.core)
    implementation(libs.fastutil)

    testImplementation(kotlin("test"))
    testImplementation("org.lwjgl", "lwjgl-opengl")
    testImplementation("org.lwjgl", "lwjgl-glfw")
    testRuntimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")
    testRuntimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
}

tasks.test {
    systemProperty("glc2vk.runtimeTest", providers.gradleProperty("glc2vk.runtimeTest").orElse("false").get())
}
