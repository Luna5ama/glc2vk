package dev.luna5ama.vibris.common

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureDataLoadTest {
    @Test
    fun `entry completed before await is delivered`() = runBlocking {
        val entries = CaptureEntries<String>()

        entries.complete("entry", "value")

        assertEquals("value", entries.await("entry"))
    }

    @Test
    fun `entry completed after await is delivered`() = runBlocking {
        val entries = CaptureEntries<String>()
        val value = async { entries.await("entry") }

        entries.complete("entry", "value")

        assertEquals("value", value.await())
    }
}
