package dev.vibris.core

internal interface ShaderLink {
    @Throws(Failure::class)
    fun switchTo(source: SourceRegistry.Lease, ownership: OwnershipCheck)

    @Throws(Failure::class)
    fun detach()

    @Throws(Failure::class)
    fun retainsActiveSource(): Boolean

    fun interface OwnershipCheck {
        @Throws(Failure::class)
        fun verify()
    }

    class Failure @JvmOverloads constructor(
        message: String?,
        private val stable: Boolean,
        cause: Throwable? = null,
    ) : Exception(message, cause) {
        fun stable(): Boolean = stable
    }

    enum class Transient : ShaderLink {
        INSTANCE;

        override fun switchTo(source: SourceRegistry.Lease, ownership: OwnershipCheck) {
            ownership.verify()
        }

        override fun detach() = Unit

        override fun retainsActiveSource(): Boolean = false
    }

    companion object {
        @JvmStatic
        fun transientLink(): ShaderLink = Transient.INSTANCE
    }
}