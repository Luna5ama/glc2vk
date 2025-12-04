plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(project(":glc2vk-common"))
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation("org.lwjgl", "lwjgl")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
}