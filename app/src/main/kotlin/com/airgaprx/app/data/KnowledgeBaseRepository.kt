package com.airgaprx.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.airgaprx.core.kb.InstallResult
import com.airgaprx.core.kb.KbCheck
import com.airgaprx.core.kb.KbInstaller
import com.airgaprx.core.kb.KbPackage
import com.airgaprx.core.kb.KbVerifier
import com.airgaprx.core.kb.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface KbState {
    data object Loading : KbState
    data class Ready(val kb: KnowledgeBase) : KbState
    data class Failed(val reason: String) : KbState
}

/**
 * Single access path to the knowledge base (M1 KnowledgeBaseRepository): nothing can read
 * interaction data without passing signature and checksum verification (R4).
 *
 * The bundled package in the APK and an offline sideloaded update (a .zip chosen with the system
 * file picker) both go through the same [KbInstaller], so there is exactly one trust path.
 */
class KnowledgeBaseRepository(private val context: Context) {
    private val verifier = KbVerifier(
        KbVerifier.pemToDer(context.assets.open("kb/kb_public_key.pem").bufferedReader().use { it.readText() })
    )
    private val installer = KbInstaller(File(context.noBackupFilesDir, "kb"), verifier)
    private val mutex = Mutex()
    private var open: SqliteKnowledgeBase? = null

    private val _state = MutableStateFlow<KbState>(KbState.Loading)
    val state: StateFlow<KbState> = _state

    /** Verifies the installed copy, (re)installing the bundled package if it is missing, stale or tampered with. */
    suspend fun load(): KbState = withContext(Dispatchers.IO) {
        mutex.withLock {
            var check = installer.current()
            val bundled = bundledReleaseNewer(check)
            if (check is KbCheck.Invalid || bundled) {
                if (check is KbCheck.Invalid) Log.w(TAG, "Installed KB invalid (${check.reason}); installing bundled package")
                val result = context.assets.open(BUNDLED).use { installer.install(it, allowDowngrade = check is KbCheck.Invalid) }
                if (result is InstallResult.Rejected) return@withLock fail("Bundled knowledge base rejected: ${result.reason}")
                check = installer.current()
            }
            when (val verified = check) {
                is KbCheck.Invalid -> fail(verified.reason)
                is KbCheck.Valid -> {
                    open?.close()
                    val kb = SqliteKnowledgeBase(File(installer.currentDir, KbPackage.DB), verified.version)
                    open = kb
                    KbState.Ready(kb).also { _state.value = it }
                }
            }
        }
    }

    /** Installs an offline update package. The previous knowledge base stays live if anything fails. */
    suspend fun installFromUri(uri: Uri): InstallResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withLock InstallResult.Rejected("Could not open the selected file")
            input.use { installer.install(it) }
        }
        if (result is InstallResult.Installed) load()
        result
    }

    private fun bundledReleaseNewer(current: KbCheck): Boolean {
        if (current !is KbCheck.Valid) return false
        // Cheap check: compare the bundled manifest's compile date without unpacking the database.
        val bundledCompiledAt = runCatching {
            java.util.zip.ZipInputStream(context.assets.open(BUNDLED)).use { z ->
                generateSequence { z.nextEntry }.first { it.name == KbPackage.MANIFEST }
                java.util.Properties().apply { load(z) }.getProperty("compiled_at")
            }
        }.getOrNull() ?: return false
        return bundledCompiledAt > current.version.compiledAt
    }

    private fun fail(reason: String): KbState = KbState.Failed(reason).also { _state.value = it }

    companion object {
        private const val TAG = "AirGapRx.KB"
        const val BUNDLED = "kb/airgaprx-kb.zip"
    }
}
