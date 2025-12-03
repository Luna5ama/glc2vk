group = "dev.luna5ama"
version = "0.0.1-SNAPSHOT"

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

subprojects {
    apply {
        val rootProjectPlugins = rootProject.libs.plugins
        plugin(rootProjectPlugins.kotlin.get().pluginId)
        plugin(rootProjectPlugins.kotlinxSerialization.get().pluginId)
    }
}