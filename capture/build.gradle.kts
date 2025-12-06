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
    implementation(libs.bundles.glWrapper)

    testImplementation(kotlin("test"))
}