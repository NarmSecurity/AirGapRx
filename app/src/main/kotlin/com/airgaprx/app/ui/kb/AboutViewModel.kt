package com.airgaprx.app.ui.kb

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airgaprx.app.AppContainer
import com.airgaprx.app.data.KbState
import com.airgaprx.app.platform.MediaPipeLlmParser
import com.airgaprx.core.kb.InstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AboutViewModel(private val c: AppContainer) : ViewModel() {
    val kb: StateFlow<KbState> = c.kbRepository.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _model = MutableStateFlow(c.modelFile())
    val model: StateFlow<File?> = _model

    val lastTimings get() = c.lastTimings

    /** Offline update: the user picks a signed .zip from local storage. */
    fun installKb(uri: Uri) = viewModelScope.launch {
        _message.value = when (val r = c.kbRepository.installFromUri(uri)) {
            is InstallResult.Installed -> "Installed knowledge base ${r.version.releaseId}"
            is InstallResult.Rejected -> "Update rejected: ${r.reason}. The previous knowledge base is still in use."
        }
    }

    /** Copies a MediaPipe .task model into app-private storage. */
    fun importModel(context: Context, uri: Uri) = viewModelScope.launch {
        _message.value = withContext(Dispatchers.IO) {
            val name = displayName(context, uri) ?: "model.task"
            if (!name.endsWith(".task")) return@withContext "Choose a MediaPipe .task model file"
            val dir = MediaPipeLlmParser.modelDir(context)
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                ?: return@withContext "Could not read the selected file"
            "Imported $name (${dest.length() / (1024 * 1024)} MB)"
        }
        c.onModelChanged()
        _model.value = c.modelFile()
    }

    fun removeModel(context: Context) {
        MediaPipeLlmParser.modelDir(context).listFiles()?.forEach { it.delete() }
        c.onModelChanged()
        _model.value = null
        _message.value = "Language model removed; the deterministic parser is used on its own."
    }

    fun clearMessage() { _message.value = null }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
