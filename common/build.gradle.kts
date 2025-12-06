plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinxSerialization)
    id("buildsrc.convention.published-module")
}

dependencies {
    api(libs.kotlinxSerializationCore)
    implementation(libs.kotlinxSerializationJson)

    api(libs.kmogus.core)
    implementation(libs.commons.compress)
}