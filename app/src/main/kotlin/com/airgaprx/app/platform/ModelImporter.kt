package com.airgaprx.app.platform

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CancellationException

sealed interface ModelImportResult {
    data class Imported(val file: File) : ModelImportResult
    data class Rejected(val reason: String) : ModelImportResult
}

/**
 * Stages an optional model before publishing it. A successful copy proves that the selected file
 * is readable and nonempty; model-format compatibility is checked later by the inference engine.
 */
class ModelImporter(private val directory: File) {
    fun import(name: String, openInput: () -> InputStream?): ModelImportResult = synchronized(importLock) {
        if (!name.endsWith(".task")) return@synchronized ModelImportResult.Rejected("Choose a MediaPipe .task model file")
        if (name.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) {
            return@synchronized ModelImportResult.Rejected("The selected model has an unsafe filename")
        }
        val destination = File(directory, name)
        var staging: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create the model directory")
            // .part is never considered an installed model, even if a process stops during copy.
            val pending = File.createTempFile(".model-import-", ".part", directory).also { staging = it }
            val input = openInput() ?: return@synchronized ModelImportResult.Rejected(
                "Could not read the selected file. The previous model is unchanged."
            )
            input.use { source -> pending.outputStream().use { source.copyTo(it) } }
            if (pending.length() == 0L) return@synchronized ModelImportResult.Rejected(
                "The selected model is empty. The previous model is unchanged."
            )
            // Both paths are in the same private directory. If atomic replacement is unavailable,
            // reject rather than remove the working model and attempt a non-atomic fallback.
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@synchronized ModelImportResult.Rejected(
                "Could not import the selected model. The previous model is unchanged."
            )
        } finally {
            staging?.let { runCatching { it.delete() } }
        }
        // Only retire the old .task files after the replacement is completely published. Cleanup
        // failure must not turn an already committed import into a reported rejection.
        runCatching {
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".task") && it != destination }
                ?.forEach { it.delete() }
        }
        ModelImportResult.Imported(destination)
    }

    private companion object {
        // AboutViewModel creates an importer per request, so the lock spans importer instances.
        val importLock = Any()
    }
}
