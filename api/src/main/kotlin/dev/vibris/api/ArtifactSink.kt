package dev.vibris.api

import java.io.IOException
import java.io.OutputStream

fun interface ArtifactSink {
    @Throws(IOException::class)
    fun open(artifactName: String): OutputStream
}