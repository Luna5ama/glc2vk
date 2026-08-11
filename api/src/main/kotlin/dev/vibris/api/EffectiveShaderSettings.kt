package dev.vibris.api

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Complete resolved settings for one successful shader reload.
 * The canonical hash covers names, values, and defaults; origin records how the same resolved state was selected.
 */
@JvmRecord
data class EffectiveShaderSettings(
    @field:DefensiveSnapshot val settings: List<Setting>,
    val settingsSha256: String,
) {
    init {
        require(settings.zipWithNext().all { (left, right) -> left.name < right.name }) {
            "settings must be uniquely ordered by name"
        }
        require(settingsSha256 == stableHash(settings)) {
            "settingsSha256 must match the canonical effective settings"
        }
    }

    fun values(): Map<String, String> = settings.associate { setting -> setting.name to setting.value }

    fun changedFromDefault(): List<Setting> = settings.filter(Setting::changedFromDefault)

    fun hasSameResolvedState(other: EffectiveShaderSettings): Boolean =
        settings.size == other.settings.size && settingsSha256 == other.settingsSha256 &&
            settings.zip(other.settings).all { (left, right) ->
                left.name == right.name && left.value == right.value && left.defaultValue == right.defaultValue
            }

    @JvmRecord
    data class Setting(
        val name: String,
        val value: String,
        val defaultValue: String,
        val origin: Origin,
    ) {
        init {
            require(name.isNotBlank()) { "setting name must not be blank" }
        }

        fun changedFromDefault(): Boolean = value != defaultValue
    }

    enum class Origin {
        DEFAULT,
        PRESERVED_CURRENT,
        REQUEST_OVERRIDE,
        PRESET,
    }

    companion object {
        private val HASH_DOMAIN = "vibris-effective-shader-settings-v2".toByteArray(Charsets.UTF_8)
        private val EMPTY = of(emptyList())

        @JvmStatic
        fun of(settings: Collection<Setting>): EffectiveShaderSettings {
            val ordered = settings.sortedBy(Setting::name)
            require(ordered.zipWithNext().all { (left, right) -> left.name != right.name }) {
                "setting names must be unique"
            }
            return EffectiveShaderSettings(ordered, stableHash(ordered))
        }

        @JvmStatic
        fun empty(): EffectiveShaderSettings = EMPTY

        private fun stableHash(settings: List<Setting>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(HASH_DOMAIN)
            digest.update(0.toByte())
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(settings.size).array())
            settings.forEach { setting ->
                digest.updateField(setting.name)
                digest.updateField(setting.value)
                digest.updateField(setting.defaultValue)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun MessageDigest.updateField(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
    }
}