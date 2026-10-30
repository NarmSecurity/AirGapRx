package com.airgaprx.core.kb

import com.airgaprx.core.model.KbVersion
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * A knowledge-base package is a zip with exactly three files:
 *  - `kb.sqlite`               the read-only interaction database
 *  - `kb.manifest.properties`  release id, compile date, SHA-256 of the database, sources, counts
 *  - `kb.sig`                  ECDSA P-256 / SHA-256 signature over the manifest bytes
 *
 * The same package format is used for the copy bundled in the APK and for offline sideloaded
 * updates, so both go through identical verification (R1 + R4, M1 Section 6.3).
 */
object KbPackage {
    const val DB = "kb.sqlite"
    const val MANIFEST = "kb.manifest.properties"
    const val SIGNATURE = "kb.sig"
    val FILES = setOf(DB, MANIFEST, SIGNATURE)
    const val FORMAT_VERSION = "1"
}

sealed interface KbCheck {
    data class Valid(val version: KbVersion) : KbCheck
    data class Invalid(val reason: String) : KbCheck
}

/** Verifies signature, database checksum and format version. Pure JVM so it is unit-testable. */
class KbVerifier(publicKeyDer: ByteArray) {
    private val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))

    fun verify(dir: File): KbCheck {
        val manifest = File(dir, KbPackage.MANIFEST)
        val sig = File(dir, KbPackage.SIGNATURE)
        val db = File(dir, KbPackage.DB)
        if (!manifest.isFile || !sig.isFile || !db.isFile) return KbCheck.Invalid("Package is missing a required file")
        val manifestBytes = manifest.readBytes()

        val signatureOk = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey); update(manifestBytes); verify(sig.readBytes())
            }
        } catch (e: Exception) { false }
        if (!signatureOk) return KbCheck.Invalid("Signature does not match the trusted publisher key")

        val props = Properties().apply { load(ByteArrayInputStream(manifestBytes)) }
        if (props.getProperty("format_version") != KbPackage.FORMAT_VERSION) {
            return KbCheck.Invalid("Unsupported package format ${props.getProperty("format_version")}")
        }
        val expected = props.getProperty("db_sha256")?.lowercase() ?: return KbCheck.Invalid("Manifest has no checksum")
        val actual = sha256(db)
        if (actual != expected) return KbCheck.Invalid("Database checksum mismatch")

        return KbCheck.Valid(
            KbVersion(
                releaseId = props.getProperty("release_id", "unknown"),
                compiledAt = props.getProperty("compiled_at", "unknown"),
                dbSha256 = actual,
                sources = props.getProperty("sources", "").split("|").map { it.trim() }.filter { it.isNotEmpty() },
                ingredientCount = props.getProperty("ingredient_count", "0").toIntOrNull() ?: 0,
                interactionCount = props.getProperty("interaction_count", "0").toIntOrNull() ?: 0,
                dataStatus = props.getProperty("data_status", "development"),
                // Missing field = old/unknown package: treat as unreviewed.
                unreviewedRows = props.getProperty("unreviewed_rows")?.toIntOrNull() ?: Int.MAX_VALUE,
                ddiSource = props.getProperty("ddi_source", "unknown"),
                ddiSourceDetail = props.getProperty("ddi_source_detail", ""),
            )
        )
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Parses a PEM "PUBLIC KEY" block into DER bytes. */
        fun pemToDer(pem: String): ByteArray {
            val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
            return java.util.Base64.getDecoder().decode(body)
        }
    }
}

sealed interface InstallResult {
    data class Installed(val version: KbVersion) : InstallResult
    data class Rejected(val reason: String) : InstallResult
}

/**
 * What this build is willing to load. Release builds set [requireReleaseGrade] so development or
 * unreviewed data is refused even when correctly signed (the signature proves authenticity,
 * not clinical review).
 */
data class KbPolicy(val requireReleaseGrade: Boolean = false)

/**
 * Installs verified packages with an atomic directory swap. On any failure the previously
 * installed knowledge base is left untouched (evaluation criterion 6).
 *
 * Layout under [root]: `current/` (live), `staging/` (being verified), `previous/` (transient).
 */
class KbInstaller(
    private val root: File,
    private val verifier: KbVerifier,
    private val policy: KbPolicy = KbPolicy(),
) {
    val currentDir: File get() = File(root, "current")
    private val stagingDir get() = File(root, "staging")
    private val previousDir get() = File(root, "previous")

    /** Re-verifies the live copy, detecting on-disk tampering since install. */
    fun current(): KbCheck {
        if (!currentDir.isDirectory) return KbCheck.Invalid("Not installed")
        return applyPolicy(verifier.verify(currentDir))
    }

    private fun applyPolicy(check: KbCheck): KbCheck {
        if (check !is KbCheck.Valid || !policy.requireReleaseGrade || check.version.isReleaseGrade) return check
        val v = check.version
        return KbCheck.Invalid(
            "This build only accepts a reviewed knowledge base; package ${v.releaseId} is ${v.dataStatus} data " +
                "(${if (v.unreviewedRows == Int.MAX_VALUE) "review status unknown" else "${v.unreviewedRows} unreviewed rows"}, " +
                "drug-drug source ${v.ddiSource})"
        )
    }

    fun install(zip: InputStream, allowDowngrade: Boolean = false): InstallResult {
        root.mkdirs()
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            unzip(zip, stagingDir)?.let { return InstallResult.Rejected(it) }
            val staged = applyPolicy(verifier.verify(stagingDir))
            if (staged is KbCheck.Invalid) return InstallResult.Rejected(staged.reason)
            val newVersion = (staged as KbCheck.Valid).version

            val live = current()
            if (!allowDowngrade && live is KbCheck.Valid && newVersion.compiledAt < live.version.compiledAt) {
                return InstallResult.Rejected("Package is older than the installed knowledge base (${live.version.releaseId})")
            }

            previousDir.deleteRecursively()
            if (currentDir.exists() && !currentDir.renameTo(previousDir)) {
                return InstallResult.Rejected("Could not move the existing knowledge base aside")
            }
            if (!stagingDir.renameTo(currentDir)) {
                previousDir.renameTo(currentDir) // roll back
                return InstallResult.Rejected("Could not activate the new knowledge base")
            }
            previousDir.deleteRecursively()
            return InstallResult.Installed(newVersion)
        } catch (e: IOException) {
            // Truncated ZIP entries, unreadable streams and verification reads are rejected
            // before activation, just like invalid signatures or checksums.
            return InstallResult.Rejected("Could not read the knowledge-base package; it may be truncated or corrupt")
        } catch (e: IllegalArgumentException) {
            // Properties.load rejects malformed escape sequences even in a signed manifest.
            return InstallResult.Rejected("Package manifest is malformed")
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Extracts only the three expected names; anything else (including path traversal) is rejected. */
    private fun unzip(input: InputStream, dest: File): String? {
        val seen = HashSet<String>()
        ZipInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name !in KbPackage.FILES) return "Unexpected file in package: $name"
                if (!seen.add(name)) return "Duplicate file in package: $name"
                File(dest, name).outputStream().use { out -> zin.copyTo(out) }
            }
        }
        val missing = KbPackage.FILES - seen
        return if (missing.isEmpty()) null else "Package is missing: ${missing.joinToString()}"
    }
}
