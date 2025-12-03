plugins {
    id("buildsrc.convention.kotlin-jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = "natives-windows")

    implementation(libs.kotlinxSerializationJson)

    implementation(libs.fastutil)
    implementation(libs.joml)

    implementation(libs.bundles.kotlinEcosystem)
    implementation(libs.bundles.glWrapper)
    implementation(libs.bundles.kmogus)
}