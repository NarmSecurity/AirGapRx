package com.airgaprx.core

import com.airgaprx.core.kb.InstallResult
import com.airgaprx.core.kb.KbCheck
import com.airgaprx.core.kb.KbInstaller
import com.airgaprx.core.kb.KbPackage
import com.airgaprx.core.kb.KbVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Evaluation criterion 6: corrupted or untrusted packages are rejected and the prior KB is retained. */
class KbPackageTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val otherKeys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val verifier = KbVerifier(keys.public.encoded)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun pkg(
        release: String,
        compiledAt: String,
        db: ByteArray = "db-$release".toByteArray(),
        signer: KeyPair = keys,
        tamperDbAfterSigning: Boolean = false,
        extra: String? = null,
        status: String = "release",
        unreviewed: Int = 0,
        ddi: String = "DDInter",
    ): ByteArray {
        val manifest = """
            format_version=1
            release_id=$release
            compiled_at=$compiledAt
            db_sha256=${hex(MessageDigest.getInstance("SHA-256").digest(db))}
            sources=TEST
            ingredient_count=1
            interaction_count=1
            data_status=$status
            unreviewed_rows=$unreviewed
            ddi_source=$ddi
        """.trimIndent().toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(manifest); sign() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put(KbPackage.MANIFEST, manifest)
            put(KbPackage.SIGNATURE, sig)
            put(KbPackage.DB, if (tamperDbAfterSigning) db + 1 else db)
            if (extra != null) put(extra, byteArrayOf(1))
        }
        return out.toByteArray()
    }

    private fun installer(requireRelease: Boolean = false): KbInstaller =
        KbInstaller(Files.createTempDirectory("kb").toFile(), verifier, com.airgaprx.core.kb.KbPolicy(requireRelease))
    private fun KbInstaller.releaseId() = (current() as KbCheck.Valid).version.releaseId

    @Test fun validPackageInstalls() {
        val i = installer()
        val r = i.install(ByteArrayInputStream(pkg("2026.10", "2026-10-01T00:00:00Z")))
        assertTrue(r.toString(), r is InstallResult.Installed)
        assertEquals("2026.10", i.releaseId())
    }

    @Test fun corruptedChecksumIsRejectedAndPriorRetained() {
        val i = installer()
        i.install(ByteArrayInputStream(pkg("2026.10", "2026-10-01T00:00:00Z")))
        val r = i.install(ByteArrayInputStream(pkg("2026.11", "2026-11-01T00:00:00Z", tamperDbAfterSigning = true)))
        assertTrue(r is InstallResult.Rejected)
        assertTrue((r as InstallResult.Rejected).reason.contains("checksum"))
        assertEquals("2026.10", i.releaseId())
    }

    @Test fun untrustedSignerIsRejected() {
        val i = installer()
        val r = i.install(ByteArrayInputStream(pkg("evil", "2027-01-01T00:00:00Z", signer = otherKeys)))
        assertTrue(r is InstallResult.Rejected)
    }

    @Test fun unexpectedFileIsRejected() {
        val r = installer().install(ByteArrayInputStream(pkg("x", "2026-10-01T00:00:00Z", extra = "../../evil.so")))
        assertTrue(r is InstallResult.Rejected)
    }

    @Test fun downgradeIsRejected() {
        val i = installer()
        i.install(ByteArrayInputStream(pkg("2026.11", "2026-11-01T00:00:00Z")))
        val r = i.install(ByteArrayInputStream(pkg("2026.10", "2026-10-01T00:00:00Z")))
        assertTrue(r is InstallResult.Rejected)
        assertEquals("2026.11", i.releaseId())
    }

    @Test fun onDiskTamperingIsDetected() {
        val i = installer()
        i.install(ByteArrayInputStream(pkg("2026.10", "2026-10-01T00:00:00Z")))
        File(i.currentDir, KbPackage.DB).appendBytes(byteArrayOf(0))
        assertTrue(i.current() is KbCheck.Invalid)
    }

    /** Review finding 2: a correctly signed development KB must be refused by a release build. */
    @Test fun releasePolicyRejectsSignedDevelopmentData() {
        val i = installer(requireRelease = true)
        val dev = i.install(ByteArrayInputStream(pkg("dev", "2026-10-01T00:00:00Z", status = "development", unreviewed = 37, ddi = "DEV-FIXTURE")))
        assertTrue(dev.toString(), dev is InstallResult.Rejected)
        val unreviewed = i.install(ByteArrayInputStream(pkg("half", "2026-10-02T00:00:00Z", unreviewed = 3)))
        assertTrue(unreviewed is InstallResult.Rejected)
        val ok = i.install(ByteArrayInputStream(pkg("rel", "2026-10-03T00:00:00Z")))
        assertTrue(ok.toString(), ok is InstallResult.Installed)
    }

    @Test fun developmentDataStillLoadsInDebugPolicy() {
        val r = installer(requireRelease = false).install(
            ByteArrayInputStream(pkg("dev", "2026-10-01T00:00:00Z", status = "development", unreviewed = 37, ddi = "DEV-FIXTURE"))
        )
        assertTrue(r is InstallResult.Installed)
        assertTrue(!(r as InstallResult.Installed).version.isReleaseGrade)
    }
}
