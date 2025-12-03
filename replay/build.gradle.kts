plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":glc2vk-common"))

    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))

    implementation("org.lwjgl", "lwjgl-glfw")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
}