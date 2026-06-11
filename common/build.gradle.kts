plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinxSerialization)
    id("buildsrc.convention.published-module")
}

dependencies {
    api(libs.kotlinxSerializationCore)
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinx.coroutines)

    api(libs.kmogus.core)
    implementation(libs.commons.compress)
}