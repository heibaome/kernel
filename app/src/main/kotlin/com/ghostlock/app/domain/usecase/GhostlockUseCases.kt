package com.ghostlock.app.domain.usecase

import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.ExploitMode
import com.ghostlock.app.domain.repository.GhostlockRepository

class LoadKernelSnapshotUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke() = repository.snapshot()
}

class DetectExploitModeUseCase(private val repository: GhostlockRepository) {

    /**
     * Per-engine support verdict for the current kernel. The default mode is
     * GhostKey only when GhostKey alone matches (both matching stays GhostLock).
     */
    data class Result(
        val ghostlockMatched: Boolean,
        val ghostkeyMatched: Boolean,
        val ghostkeyRelease: String?,
    ) {
        val defaultMode: ExploitMode
            get() = if (!ghostlockMatched && ghostkeyMatched) ExploitMode.GHOSTKEY else ExploitMode.GHOSTLOCK
    }

    /**
     * Combines the GhostLock verdict from [GhostlockRepository.snapshot] (which
     * already covers builtin and imported profiles) with the GhostKey probe.
     * Each engine is matched against the release source it actually resolves:
     * GhostLock via the snapshot's kernel release, GhostKey via its own probe.
     */
    suspend operator fun invoke(): Result {
        val snapshot = repository.snapshot()
        val ghostkeyRelease = repository.ghostKeyMatchedRelease()
        return Result(
            ghostlockMatched = snapshot.kernelSupported,
            ghostkeyMatched = ghostkeyRelease != null,
            ghostkeyRelease = ghostkeyRelease,
        )
    }
}

class SelectCpuPairUseCase(private val repository: GhostlockRepository) {
    operator fun invoke(index: Int) = repository.selectCpuPair(index)
}

class ImportOffsetsUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(documents: Map<String, String>) = repository.importOffsets(documents)
    suspend fun overwrite(documents: Map<String, String>) = repository.confirmImport(documents)
}

class UpdateCloudProfilesUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke() = repository.updateCloudProfiles()
}

class ParseSourceUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(
        input: String,
        xblPath: String? = null,
        uefiPath: String? = null,
        vendorBootPath: String? = null,
        overwrite: Boolean = false,
        onLog: (String) -> Unit = {},
    ) = repository.parseSource(input, xblPath, uefiPath, vendorBootPath, overwrite, onLog)
}

class RunExploitUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(pair: CpuPair, useShizuku: Boolean, onLog: (String) -> Unit) =
        if (useShizuku) repository.runExploitWithShizuku(pair, onLog)
        else repository.runExploit(pair, onLog)
}

class ReadDocumentUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(uri: String) = repository.readDocument(uri)
    suspend fun cache(uri: String, fileName: String) = repository.cacheDocument(uri, fileName)
}
