plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")
    implementation("org.lwjgl", "lwjgl-zstd")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-zstd", classifier = "natives-windows")

    api(libs.kotlinxSerializationJson)

    api(libs.fastutil)
    api(libs.joml)

    api(libs.bundles.kotlinEcosystem)
    api(libs.bundles.glWrapper)
    api(libs.bundles.kmogus)

    testImplementation(kotlin("test"))
}