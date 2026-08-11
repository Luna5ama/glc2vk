package dev.vibris.core

import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.SceneContext
import dev.vibris.protocol.v2.EffectiveShaderSetting
import dev.vibris.protocol.v2.EnvironmentProvenance
import dev.vibris.protocol.v2.ResultProvenance
import dev.vibris.protocol.v2.ShaderSettingOrigin
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BenchmarkProvenance {
    @Volatile
    private var runtimeEnvironment: EnvironmentProvenance = environment()

    fun captureRuntimeEnvironment() {
        val builder = environment().toBuilder()
        reflectedStatic("net.irisshaders.iris.Iris", "getVersion")?.let(builder::setIrisVersion)
        reflectedMinecraftVersion()?.let(builder::setMinecraftVersion)
        reflectedGlString(0x1F00)?.let(builder::setGpuVendor)
        reflectedGlString(0x1F01)?.let(builder::setGpuRenderer)
        reflectedGlString(0x1F02)?.let { version ->
            builder.openglVersion = version
            builder.driverVersion = version
        }
        runtimeEnvironment = builder.build()
    }

    fun result(
        job: CoreJob,
        source: SourceRegistry.Lease,
        settings: EffectiveShaderSettings?,
        scene: SceneContext?,
        shaderLoadedAtUnixMs: Long,
        passMappingSha256: String,
    ): ResultProvenance {
        val reference = source.reference
        val builder = ResultProvenance.newBuilder()
            .setWorkspaceId(job.workspaceId)
            .setBranch(reference.branch)
            .setRequestedRevision(reference.requestedRevision)
            .setResolvedRevision(reference.resolvedRevision)
            .setStartHead(reference.startHead)
            .setCompletionHead(reference.startHead)
            .setHeadChanged(false)
            .setStale(false)
            .setShaderTreeId(reference.shaderTreeId)
            .setDirtyShaderDeltaSha256(reference.dirtyShaderDeltaSha256)
            .setSourceSnapshotSha256(source.snapshotSha256)
            .setActiveSourceUuid(source.uuid)
            .setPresetId(job.submission.presetId)
            .setPresetSha256(job.submission.presetSha256)
            .setShaderLoadedAtUnixMs(shaderLoadedAtUnixMs)
            .setEnvironment(runtimeEnvironment)
            .setPassMappingSha256(passMappingSha256)
        builder.worktreeRoot = if (reference.origin.hasWorkspace()) {
            reference.origin.workspace.worktreeRoot
        } else {
            reference.origin.commit.worktreeRoot
        }
        settings?.let {
            builder.configSha256 = it.settingsSha256
            builder.effectiveSettings = effectiveSettings(it)
        }
        scene?.let { builder.sceneSha256 = sceneHash(it) }
        return builder.build()
    }

    fun effectiveSettings(settings: EffectiveShaderSettings): dev.vibris.protocol.v2.EffectiveShaderSettings =
        dev.vibris.protocol.v2.EffectiveShaderSettings.newBuilder()
            .setSettingsSha256(settings.settingsSha256)
            .addAllSettings(settings.settings.map { setting ->
                EffectiveShaderSetting.newBuilder()
                    .setName(setting.name)
                    .setValue(setting.value)
                    .setDefaultValue(setting.defaultValue)
                    .setOrigin(setting.origin.toProtocol())
                    .setChangedFromDefault(setting.changedFromDefault())
                    .build()
            })
            .build()

    fun sceneHash(context: SceneContext): String = sha256(context(context))

    fun presetHash(presetId: String, version: String, displayName: String, context: SceneContext): String =
        sha256(buildJsonObject {
            put("preset_id", presetId)
            put("version", version)
            put("display_name", displayName)
            put("effective_context", context(context))
        })

    private fun context(value: SceneContext): JsonObject = buildJsonObject {
        put("save_id", value.saveId)
        put("dimension_id", value.dimensionId)
        put("time_preset_id", value.timePresetId)
        put("weather_preset_id", value.weatherPresetId)
        put("camera_preset_id", value.cameraPresetId)
        put("fov", value.fov)
        put("resolution", buildJsonObject {
            put("width", value.resolution.width)
            put("height", value.resolution.height)
        })
        put("settings_preset_id", value.settingsPresetId)
    }

    private fun EffectiveShaderSettings.Origin.toProtocol(): ShaderSettingOrigin = when (this) {
        EffectiveShaderSettings.Origin.DEFAULT -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_DEFAULT
        EffectiveShaderSettings.Origin.PRESERVED_CURRENT ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESERVED_CURRENT
        EffectiveShaderSettings.Origin.REQUEST_OVERRIDE ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE
        EffectiveShaderSettings.Origin.PRESET -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESET
    }

    private fun environment(): EnvironmentProvenance = EnvironmentProvenance.newBuilder()
        .setMinecraftVersion(System.getProperty("vibris.minecraft.version", ""))
        .setIrisVersion(System.getProperty("vibris.iris.version", ""))
        .setVibrisVersion(
            BenchmarkProvenance::class.java.`package`?.implementationVersion
                ?: System.getProperty("vibris.version", "development"),
        )
        .setJavaVersion(System.getProperty("java.version", ""))
        .setOperatingSystem(
            listOf(
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""),
                System.getProperty("os.arch", ""),
            ).filter(String::isNotBlank).joinToString(" "),
        )
        .setGpuVendor(System.getProperty("vibris.gpu.vendor", ""))
        .setGpuRenderer(System.getProperty("vibris.gpu.renderer", ""))
        .setOpenglVersion(System.getProperty("vibris.opengl.version", ""))
        .setDriverVersion(System.getProperty("vibris.driver.version", ""))
        .build()

    private fun reflectedStatic(className: String, methodName: String): String? = runCatching {
        Class.forName(className).getMethod(methodName).invoke(null)?.toString()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun reflectedMinecraftVersion(): String? = runCatching {
        val version = Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null)
        version.javaClass.getMethod("name").invoke(version)?.toString()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun reflectedGlString(name: Int): String? = runCatching {
        Class.forName("org.lwjgl.opengl.GL11C")
            .getMethod("glGetString", Int::class.javaPrimitiveType)
            .invoke(null, name)
            ?.toString()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun sha256(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
