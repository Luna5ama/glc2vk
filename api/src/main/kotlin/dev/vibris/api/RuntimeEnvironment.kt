package dev.vibris.api

@JvmRecord
data class RuntimeEnvironment(
    val minecraftVersion: String,
    val irisVersion: String,
    val vibrisVersion: String,
    val javaVersion: String,
    val operatingSystem: String,
    val gpuVendor: String,
    val gpuRenderer: String,
    val openglVersion: String,
    val driverVersion: String,
) {
    init {
        require(minecraftVersion.isNotBlank()) { "minecraftVersion must not be blank" }
        require(irisVersion.isNotBlank()) { "irisVersion must not be blank" }
        require(vibrisVersion.isNotBlank()) { "vibrisVersion must not be blank" }
        require(javaVersion.isNotBlank()) { "javaVersion must not be blank" }
        require(operatingSystem.isNotBlank()) { "operatingSystem must not be blank" }
        require(gpuVendor.isNotBlank()) { "gpuVendor must not be blank" }
        require(gpuRenderer.isNotBlank()) { "gpuRenderer must not be blank" }
        require(openglVersion.isNotBlank()) { "openglVersion must not be blank" }
        require(driverVersion.isNotBlank()) { "driverVersion must not be blank" }
    }
}
