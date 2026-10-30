package com.airgaprx.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class ModelImporterTest {
    private fun modelDirectory(): File = File(Files.createTempDirectory("model-import-test").toFile(), "models").apply { mkdirs() }
    private fun oldModel(directory: File): File = File(directory, "working.task").apply { writeText("working model") }

    @Test fun unreadableSelectionRetainsTheWorkingModel() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val result = ModelImporter(directory).import("replacement.task") { null }
        assertTrue(result is ModelImportResult.Rejected)
        assertTrue("unreadable replacement must not delete the working model", previous.isFile)
        assertArrayEquals("working model".toByteArray(), previous.readBytes())
    }

    @Test fun providerExceptionIsRejectedWithoutDeletingTheWorkingModel() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val result = ModelImporter(directory).import("replacement.task") { throw IOException("provider unavailable") }
        assertTrue(result.toString(), result is ModelImportResult.Rejected)
        assertTrue(previous.isFile)
    }

    @Test fun interruptedCopyRetainsSameNamedModelAndLeavesNoSelectablePartialFile() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val result = ModelImporter(directory).import("working.task") {
            object : InputStream() {
                private var first = true
                override fun read(): Int = if (first) { first = false; 'x'.code } else throw IOException("copy interrupted")
            }
        }
        assertTrue(result.toString(), result is ModelImportResult.Rejected)
        assertArrayEquals("working model".toByteArray(), previous.readBytes())
        assertTrue(directory.listFiles().orEmpty().filter { it.name.endsWith(".task") }.single() == previous)
    }

    @Test fun emptySelectionIsRejectedAndRetainsTheWorkingModel() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val result = ModelImporter(directory).import("replacement.task") { ByteArrayInputStream(byteArrayOf()) }
        assertTrue(result.toString(), result is ModelImportResult.Rejected)
        assertTrue(previous.isFile)
    }

    @Test fun unsafeDisplayNameCannotWriteOutsideTheModelDirectory() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        for (name in listOf("../outside.task", "..\\outside.task", "C:outside.task")) {
            val result = ModelImporter(directory).import(name) { ByteArrayInputStream("new model".toByteArray()) }
            assertTrue(name, result is ModelImportResult.Rejected)
            assertTrue(previous.isFile)
        }
        assertFalse(File(directory.parentFile, "outside.task").exists())
    }

    @Test fun successfulReplacementKeepsPreviousUntilTheEntireStreamIsRead() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val replacement = "complete replacement".toByteArray()
        val result = ModelImporter(directory).import("replacement.task") {
            assertTrue("old model must exist when the provider is opened", previous.isFile)
            object : ByteArrayInputStream(replacement) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    assertTrue("old model must remain throughout copy", previous.isFile)
                    return super.read(buffer, offset, length)
                }
            }
        }
        assertTrue(result.toString(), result is ModelImportResult.Imported)
        assertArrayEquals(replacement, (result as ModelImportResult.Imported).file.readBytes())
        assertFalse(previous.exists())
        assertTrue(directory.listFiles().orEmpty().size == 1)
    }

    @Test fun successfulSameNamedReplacementPublishesTheCompleteFile() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        val replacement = "complete replacement".toByteArray()
        val result = ModelImporter(directory).import("working.task") { ByteArrayInputStream(replacement) }
        assertTrue(result.toString(), result is ModelImportResult.Imported)
        assertArrayEquals(replacement, previous.readBytes())
        assertTrue(directory.listFiles().orEmpty().size == 1)
    }

    @Test fun publicationFailureRetainsThePreviousModel() {
        val directory = modelDirectory()
        val previous = oldModel(directory)
        File(directory, "replacement.task").mkdirs() // A file cannot atomically replace a directory.
        val result = ModelImporter(directory).import("replacement.task") { ByteArrayInputStream("new model".toByteArray()) }
        assertTrue(result.toString(), result is ModelImportResult.Rejected)
        assertArrayEquals("working model".toByteArray(), previous.readBytes())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
