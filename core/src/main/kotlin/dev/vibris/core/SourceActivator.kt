package dev.vibris.core

import dev.vibris.protocol.v1.ErrorCode

internal class SourceActivator(
    private val sources: SourceRegistry,
    private val link: ShaderLink,
) : AutoCloseable {
    private var ready = true
    private var closed = false

    @Synchronized
    @Throws(Failure::class)
    fun begin(next: SourceRegistry.Lease): Activation {
        requireReady()
        val activation: SourceRegistry.Activation
        try {
            activation = sources.beginActivation(next)
            link.switchTo(next) { requireOwned(next) }
        } catch (failure: SourceRegistry.Failure) {
            sources.failActivation(next)
            throw Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.message, failure)
        } catch (failure: ShaderLink.Failure) {
            throw fail(next, ErrorCode.SYMLINK_SWITCH_FAILED, failure.message, failure.stable(), failure)
        }
        return Activation(activation)
    }

    @Synchronized
    @Throws(Failure::class)
    fun commit(activation: Activation) {
        try {
            sources.requireOwned(activation.state.next)
            sources.commitActivation(activation.state)
        } catch (failure: SourceRegistry.Failure) {
            throw Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.message, failure)
        }
    }

    @Synchronized
    fun rollback(activation: Activation): Boolean {
        val previous = activation.state.previous
        return try {
            if (previous == null) {
                link.detach()
            } else {
                link.switchTo(previous) { requireOwned(previous) }
            }
            sources.retryActivation(activation.state)
            true
        } catch (_: ShaderLink.Failure) {
            ready = false
            false
        }
    }

    @Synchronized
    fun fail(activation: Activation) {
        sources.failActivation(activation.state)
    }

    @Synchronized
    fun release(leases: List<SourceRegistry.Lease>) {
        var retainActive = false
        try {
            retainActive = link.retainsActiveSource()
        } catch (_: ShaderLink.Failure) {
            ready = false
        }
        sources.release(leases, retainActive)
    }

    @Synchronized
    @Throws(Failure::class)
    fun verifyActiveSource() {
        try {
            link.retainsActiveSource()
            sources.requireActiveOwned()
        } catch (failure: SourceRegistry.Failure) {
            ready = false
            throw Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.message, failure)
        } catch (failure: ShaderLink.Failure) {
            ready = false
            throw Failure(ErrorCode.SYMLINK_SWITCH_FAILED, failure.message, failure)
        }
    }

    @Synchronized
    fun ready(): Boolean = ready && !closed

    @Synchronized
    fun markNotReady() {
        ready = false
    }

    @Synchronized
    fun isActive(source: SourceRegistry.Lease): Boolean = sources.isActive(source)

    @Synchronized
    @Throws(Failure::class)
    fun retainActive(): SourceRegistry.Lease? = try {
        sources.retainActive()
    } catch (failure: SourceRegistry.Failure) {
        ready = false
        throw Failure(failure.code, failure.message, failure)
    }

    @Synchronized
    fun releaseRetained(source: SourceRegistry.Lease) {
        sources.releaseRetained(source)
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            link.detach()
        } catch (_: ShaderLink.Failure) {
            ready = false
        }
        sources.close()
    }

    @Throws(ShaderLink.Failure::class)
    private fun requireOwned(source: SourceRegistry.Lease) {
        try {
            sources.requireOwned(source)
        } catch (failure: SourceRegistry.Failure) {
            throw ShaderLink.Failure(failure.message, true, failure)
        }
    }

    private fun fail(
        source: SourceRegistry.Lease,
        code: ErrorCode,
        message: String?,
        stable: Boolean,
        cause: Exception,
    ): Failure {
        if (stable) {
            sources.retryActivation(source)
        } else {
            sources.failActivation(source)
        }
        if (!stable) {
            ready = false
        }
        return Failure(code, message, cause)
    }

    @Throws(Failure::class)
    private fun requireReady() {
        if (!ready || closed) {
            throw Failure(ErrorCode.SERVER_NOT_READY, "Source activation is not ready.")
        }
    }

    data class Activation(val state: SourceRegistry.Activation) {
        fun state(): SourceRegistry.Activation = state

        fun previous(): SourceRegistry.Lease? = state.previous
    }

    class Failure @JvmOverloads constructor(
        @JvmField val code: ErrorCode,
        message: String?,
        cause: Throwable? = null,
    ) : Exception(message, cause)
}