package com.ghostlock.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ghostlock.app.data.ota.HttpRangeReader
import com.ghostlock.app.domain.model.CloudProfileUpdateResult
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One release of a downloaded pack plus the document that carries it. */
internal data class PackRelease(val release: String, val document: String)

/** A downloaded pack failed structural or parse validation. */
internal class InvalidPackException(reason: String) : Exception(reason)

/**
 * Pure inspection of a downloaded profile pack (the CI-uploaded
 * GhostLock-kernel-profiles.zip): zip extraction, checksum handling and
 * per-document validation through the regular profile parser. No Android or
 * store dependencies, so the rules are unit-testable on the JVM.
 */
internal object CloudProfilePack {
    const val IndexFileName = "index.conf"
    const val PackFileName = "GhostLock-kernel-profiles.zip"
    const val ChecksumFileName = "sha256sum.txt"

    /** Upper bound for one HOCON document inside the pack. */
    private const val MaxDocumentBytes = 4L * 1024 * 1024

    /**
     * Every HOCON document in the zip, keyed by base name (the pack is flat
     * once directories are stripped). Anything else is ignored.
     */
    fun extract(zipBytes: ByteArray): Map<String, String> {
        val documents = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && name.endsWith(".conf")) {
                    documents[name.substringAfterLast('/')] = readEntry(zip, name)
                }
                zip.closeEntry()
            }
        }
        return documents
    }

    /** Bounded entry read (readNBytes is API 33+; minSdk is 31). */
    private fun readEntry(zip: ZipInputStream, name: String): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = zip.read(chunk)
            if (read == -1) break
            total += read
            if (total > MaxDocumentBytes) throw InvalidPackException("document too large: $name")
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Expected digest for [fileName] inside a sha256sum.txt document
     * ("<hex>  <name>" lines, binary marker accepted); null when absent.
     */
    fun expectedChecksum(checksumText: String, fileName: String): String? {
        for (line in checksumText.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            if (!parts[0].matches(Regex("[0-9a-fA-F]{64}"))) continue
            if (parts[1].removePrefix("*") == fileName) return parts[0].lowercase()
        }
        return null
    }

    /**
     * Validates the pack shape and every document: index.conf must be there,
     * each release-bearing entry needs a `release` and the current layout,
     * and includes resolve through [parse] exactly like a manual import.
     * Returns the releases the pack provides.
     */
    fun validate(
        documents: Map<String, String>,
        parse: (text: String, extraDocuments: Map<String, String>) -> List<ValueMap>,
    ): List<PackRelease> {
        if (IndexFileName !in documents) {
            throw InvalidPackException("missing $IndexFileName")
        }
        val pack = linkedMapOf<String, PackRelease>()
        for ((name, text) in documents) {
            val entries = try {
                parse(text, documents)
            } catch (error: UserProfileStore.MissingIncludes) {
                throw error
            } catch (error: Exception) {
                throw InvalidPackException("unparsable document: $name (${error.message})")
            }
            for (entry in entries) {
                val release = (entry["release"] as? String).orEmpty()
                if (release.isEmpty()) throw InvalidPackException("release missing in $name")
                /* Cloud packs are current-layout only (v2); legacy documents
                 * must go through the explicit v1 -> v2 conversion first. */
                if (isLegacyProfileEntry(entry)) {
                    throw InvalidPackException("legacy layout document: $name")
                }
                pack[release] = PackRelease(release, name)
            }
        }
        if (pack.isEmpty()) throw InvalidPackException("no profile releases found")
        return pack.values.toList()
    }
}

/**
 * Manual cloud profile update. Downloads the pack from the configured source,
 * verifies the checksum when the release ships one, validates every document
 * and installs the result into the user profile store (which already shadows
 * the bundled assets). The cloud layer replaces itself as a whole; documents
 * the user imported by hand are never touched.
 */
internal class CloudProfileUpdater(
    private val store: UserProfileStore,
    private val preferences: SharedPreferences,
) {
    private companion object {
        /* The pre-release asset the CI uploads; used when no custom source is
         * configured. */
        const val DefaultSource =
            "https://github.com/heibaome/kernel/releases/download/pre-release/GhostLock-kernel-profiles.zip"
        const val PrefCloudProfileSource = "cloud_profile_source"
        const val PrefCloudProfileFiles = "cloud_profile_files"
        const val MaxPackBytes = 32L * 1024 * 1024
        const val MaxChecksumBytes = 64 * 1024
    }

    /** Configured pack URL; a blank preference falls back to the default. */
    fun source(): String =
        preferences.getString(PrefCloudProfileSource, null)
            ?.takeIf { it.isNotBlank() } ?: DefaultSource

    fun setSource(url: String) {
        preferences.edit { putString(PrefCloudProfileSource, url.trim()) }
    }

    /** Stored document names installed by a previous cloud update. */
    fun managedNames(): Set<String> =
        preferences.getStringSet(PrefCloudProfileFiles, emptySet()).orEmpty()

    private fun setManagedNames(names: Set<String>) {
        preferences.edit { putStringSet(PrefCloudProfileFiles, names) }
    }

    fun isManaged(name: String): Boolean = name in managedNames()

    /** A cloud-installed document was deleted; stop tracking it. */
    fun forget(name: String) {
        val names = managedNames()
        if (name in names) setManagedNames(names - name)
    }

    /** A cloud-installed document was renamed; keep tracking it. */
    fun adopt(oldName: String, newName: String) {
        val names = managedNames()
        if (oldName in names) setManagedNames(names - oldName + newName)
    }

    suspend fun update(): CloudProfileUpdateResult = withContext(Dispatchers.IO) {
        try {
            val source = source()
            val reader = HttpRangeReader()
            val length = reader.fileLength(source)
                ?: return@withContext CloudProfileUpdateResult.Failed("cannot reach $source")
            if (length <= 0 || length > MaxPackBytes) {
                return@withContext CloudProfileUpdateResult.Failed("pack size out of range: $length")
            }
            val zipBytes = reader.read(source, 0, length.toInt())
                ?: return@withContext CloudProfileUpdateResult.Failed("download failed: $source")

            /* The checksum file sits next to the pack in the same release;
             * a missing one only skips verification. */
            val checksumUrl = source.replaceAfterLast('/', CloudProfilePack.ChecksumFileName)
            val checksumText = fetchText(reader, checksumUrl, MaxChecksumBytes)
            if (checksumText != null) {
                val expected = CloudProfilePack.expectedChecksum(
                    checksumText, CloudProfilePack.PackFileName,
                )
                if (expected != null && expected != CloudProfilePack.sha256Hex(zipBytes)) {
                    return@withContext CloudProfileUpdateResult.ChecksumFailed(expected)
                }
            }

            val documents = CloudProfilePack.extract(zipBytes)
            val pack = try {
                CloudProfilePack.validate(documents) { text, docs ->
                    store.parseEntries(text, docs)
                }
            } catch (error: UserProfileStore.MissingIncludes) {
                return@withContext CloudProfileUpdateResult.InvalidPack(
                    "missing includes: ${error.files.joinToString()}",
                )
            } catch (error: InvalidPackException) {
                return@withContext CloudProfileUpdateResult.InvalidPack(
                    error.message ?: "invalid pack",
                )
            }

            /* Diff before touching the store: byte-identical documents count
             * their releases as unchanged. */
            val installedReleases = store.list().flatMap { it.releases }.toSet()
            val added = pack.filter { it.release !in installedReleases }
            val changed = pack.filter {
                it.release in installedReleases &&
                    store.rawText(it.document) != documents[it.document]
            }
            val unchanged = pack.size - added.size - changed.size

            managedNames().forEach(store::delete)
            val installed = documents.map { (name, text) -> store.save(name, text) }.toSet()
            setManagedNames(installed)

            CloudProfileUpdateResult.Success(
                added = added.map { it.release },
                updated = changed.map { it.release },
                unchanged = unchanged,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CloudProfileUpdateResult.Failed(error.message ?: "update failed")
        }
    }

    private suspend fun fetchText(reader: HttpRangeReader, url: String, maxBytes: Int): String? {
        val length = reader.fileLength(url) ?: return null
        if (length <= 0 || length > maxBytes) return null
        return reader.read(url, 0, length.toInt())?.toString(Charsets.UTF_8)
    }
}
