package dev.vibris.core

import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Objects

internal class ArtifactOutputStream(
    private val owner: ArtifactJobTransaction,
    private val nameValue: String,
    private val pathValue: Path,
) : OutputStream() {
    private val channel = FileChannel.open(pathValue, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    private lateinit var output: OutputStream
    private lateinit var identityValue: ArtifactFiles.RegularFileIdentity
    private var bytesValue = 0L
    private var closed = false

    init {
        try {
            identityValue = ArtifactFiles.captureRegularFile(pathValue)
            output = Channels.newOutputStream(channel)
        } catch (exception: Exception) {
            channel.close()
            throw exception
        }
    }

    @Throws(IOException::class)
    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()), 0, 1)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        if (closed) {
            throw IOException("Artifact stream is closed.")
        }
        owner.write(this, bytes, offset, length)
    }

    @Throws(IOException::class)
    override fun flush() {
        output.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        synchronized(owner) {
            if (closed) {
                return
            }
            closed = true
            var failure: IOException? = null
            try {
                output.flush()
                channel.force(true)
            } catch (exception: IOException) {
                failure = exception
            }
            try {
                output.close()
            } catch (exception: IOException) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure?.addSuppressed(exception)
                }
            }
            try {
                ArtifactFiles.verifyIdentity(pathValue, identityValue)
                identityValue = ArtifactFiles.captureRegularFile(pathValue)
            } catch (exception: IOException) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure?.addSuppressed(exception)
                }
            }
            owner.closed(this, failure != null)
            failure?.let { throw it }
        }
    }

    @Throws(IOException::class)
    internal fun writeDirect(bytes: ByteArray, offset: Int, length: Int) {
        output.write(bytes, offset, length)
    }

    internal fun addBytes(length: Int) {
        bytesValue = Math.addExact(bytesValue, length.toLong())
    }

    internal fun name(): String = nameValue

    internal fun path(): Path = pathValue

    internal fun bytes(): Long = bytesValue

    internal fun identity(): ArtifactFiles.RegularFileIdentity = identityValue
}