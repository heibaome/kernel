package com.ghostlock.app.data

import com.ghostlock.app.data.CloudProfilePack.IndexFileName
import com.ghostlock.app.data.CloudProfilePack.PackFileName
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the cloud profile pack inspection rules (no Android deps). */
class CloudProfilePackTest {

    /* A minimal current-layout (v2) document the converter leaves untouched. */
    private val v2Document = """
        release = "9.9.9-test"
        schema_version = 1
        kernel_major = 6
        route {
          tcp_zerocopy {
            compact_waiter = 1
          }
        }
        fallback {
          to = "none"
        }
        kernelsnitch {
          collisions = 4
        }
    """.trimIndent()

    /* The same document without the v2 markers: the converter rewrites it. */
    private val legacyDocument = """
        release = "9.9.9-legacy"
        kernel_major = 6
        compact_waiter = 1
    """.trimIndent()

    private fun parseLikeStore(text: String, documents: Map<String, String>): List<ValueMap> {
        require(documents.isNotEmpty())
        val value = HoconSupport.parseValue(text)
        val map = value.asValueMap() ?: return emptyList()
        return if (map.containsKey("release")) listOf(map) else emptyList()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `sha256 matches known vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CloudProfilePack.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CloudProfilePack.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `checksum file parsing finds the pack line`() {
        val text = """
            0e1f2a3b4c5d6e7f00112233445566778899aabbccddeeff0011223344556677  GhostLock.exe
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  GhostLock-kernel-profiles.zip
        """.trimIndent()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CloudProfilePack.expectedChecksum(text, CloudProfilePack.PackFileName),
        )
    }

    @Test
    fun `checksum file parsing accepts the binary marker`() {
        val text = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 *GhostLock-kernel-profiles.zip"
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CloudProfilePack.expectedChecksum(text, CloudProfilePack.PackFileName),
        )
    }

    @Test
    fun `checksum file without the pack entry is treated as absent`() {
        assertNull(CloudProfilePack.expectedChecksum("deadbeef  other.zip", CloudProfilePack.PackFileName))
        assertNull(CloudProfilePack.expectedChecksum("not a checksum file", CloudProfilePack.PackFileName))
    }

    @Test
    fun `extraction keeps only HOCON documents by base name`() {
        val bytes = zipOf(
            "index.conf" to "profiles = []",
            "kernel_profiles/9.9.9-test.conf" to v2Document,
            "notes.txt" to "ignored",
            "sub/readme.md" to "ignored",
        )
        val documents = CloudProfilePack.extract(bytes)
        assertEquals(setOf(IndexFileName, "9.9.9-test.conf"), documents.keys)
        assertEquals(v2Document, documents["9.9.9-test.conf"])
    }

    @Test
    fun `validation accepts a current-layout pack`() {
        val documents = mapOf(
            IndexFileName to "profiles = []",
            "9.9.9-test.conf" to v2Document,
        )
        val pack = CloudProfilePack.validate(documents, ::parseLikeStore)
        assertEquals(listOf(PackRelease("9.9.9-test", "9.9.9-test.conf")), pack)
    }

    @Test
    fun `validation rejects a legacy-layout document`() {
        val documents = mapOf(
            IndexFileName to "profiles = []",
            "legacy.conf" to legacyDocument,
        )
        try {
            CloudProfilePack.validate(documents, ::parseLikeStore)
            throw AssertionError("expected InvalidPackException")
        } catch (expected: InvalidPackException) {
            assertTrue(expected.message!!.contains("legacy"))
        }
    }

    @Test
    fun `validation requires the index document`() {
        val documents = mapOf("9.9.9-test.conf" to v2Document)
        try {
            CloudProfilePack.validate(documents, ::parseLikeStore)
            throw AssertionError("expected InvalidPackException")
        } catch (expected: InvalidPackException) {
            assertTrue(expected.message!!.contains(IndexFileName))
        }
    }

    @Test
    fun `validation rejects a pack with no releases`() {
        val documents = mapOf(IndexFileName to "profiles = []")
        try {
            CloudProfilePack.validate(documents, ::parseLikeStore)
            throw AssertionError("expected InvalidPackException")
        } catch (expected: InvalidPackException) {
            assertTrue(expected.message!!.contains("no profile releases"))
        }
    }

    @Test
    fun `validation rejects an empty release name`() {
        val documents = mapOf(
            IndexFileName to "profiles = []",
            "broken.conf" to "release = \"\"\nschema_version = 1",
        )
        try {
            CloudProfilePack.validate(documents, ::parseLikeStore)
            throw AssertionError("expected InvalidPackException")
        } catch (expected: InvalidPackException) {
            assertTrue(expected.message!!.contains("release missing"))
        }
    }
}
