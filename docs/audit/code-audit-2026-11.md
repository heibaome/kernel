# Code audit — November 2026

Record of the first full-tree audit of the Android/data/UI layers. Native
attack code (`src/core/`) is **out of scope**: the project requires
`cmp_disasm.py` plus on-device gates for it, and this audit did not touch
those files.

- Revision audited: `82ab9d2`
- Scope: `app/src/main/kotlin/com/ghostlock/app/{data,ota,shizuku,ghostkey,ui}`
- Out of scope: `src/core/`, `tools/extract_rs/`, `ghostkey/`

## Method

Read-only review of the data layer (profile storage, cloud pack handling,
process execution, HTTP range reads) and the UI layer (ViewModel state,
Compose dialogs). Each finding below was verified by reading the code that
produces it, not inferred from naming or convention.

Severity: **P1** = wrong behaviour in a reachable path; **P2** = defect with
limited impact or an unbounded input; **P3** = accepted risk, documented only.

## Findings and fixes

| ID | Severity | Location | Problem | Status |
| --- | --- | --- | --- | --- |
| A-01 | P1 | `GhostlockViewModel.running` | Plain `var` written on the main thread (`beginOperation`) and on `Dispatchers.IO` (`endOperation` from `finally`). Without `@Volatile` the main thread may read a stale `true` and silently ignore the execute tap. | Fixed |
| A-02 | P1 | `RiskConfirmDialog.close()` | The dialog is still clickable while its exit animation runs. Tapping **Cancel** and then **Confirm** within that window overwrites `exitAction`, so a high-risk action proceeds although the user cancelled. | Fixed |
| A-03 | P1 | `AndroidGhostlockRepository.runExploitBinary` | The `ksu-log-tailer` thread was started at creation, before the `try` that owns its only `interrupt()`. Any exception from the `ProcessBuilder` assembly in between (unwritable log dir, OOM) leaked a thread polling two files every 200 ms for the rest of the process lifetime. | Fixed |
| A-04 | P1 | `ota/HttpRangeReader` | Redirects are followed without a scheme check, so an `https → http` downgrade is accepted. Profile packs are unsigned and drive kernel offsets, making a substituted pack a panic/brick vector. | Fixed |
| A-05 | P2 | `CloudProfilePack.extract` | Only each document was bounded (4 MB), not the document count or the running total. A 32 MB deflate pack expands far beyond device memory. | Fixed + unit test |
| A-06 | P2 | `AndroidGhostlockRepository.readDocument` / `cacheDocument` | Picked documents are read/copied with no size cap; a large pick can exhaust heap or fill `filesDir`. | Documented, not fixed |
| A-07 | P2 | `UserProfileStore.list` / `documents` | Every call re-reads every stored document, and `list()` reads each file twice, so enumeration is quadratic in file count. | Documented, not fixed |
| A-08 | P2 | `CloudProfilePack.extract` | Pack entries are keyed by base name after directory stripping, so `a/x.conf` and `b/x.conf` collide and the later entry silently wins. | Documented, not fixed |
| A-09 | P3 | `AndroidGhostlockRepository.tailKsuLog` | The `pending` line buffer is unbounded until a newline arrives. Bounded in practice by the log file the tool itself writes. | Accepted |
| A-10 | P3 | `CloudProfileUpdater.update` | Integrity checking is skipped when `sha256sum.txt` is absent (HTTPS is the only guarantee). | Accepted |

## Fixes applied

- A-01: `@Volatile` on `GhostlockViewModel.running`.
- A-02: `close()` ignores calls once `visible` is false, so the exit action is
  decided by the first button that wins.
- A-03: the tailer is created without starting; `start()` moved inside the
  `try` that already guarantees `interrupt()` in its `finally`.
- A-04: the reader records whether the original URL was `https` and throws
  `IOException` on any redirect that would drop to `http`.
- A-05: `MaxDocuments = 64` and `MaxTotalBytes = 16 MiB`; both raise
  `InvalidPackException`, which `CloudProfileUpdater.update` already reports as
  `InvalidPack`. Covered by `extraction caps the document count`.

## Accepted risks and open items

- A-06: a cap needs a product decision (what to tell the user when a pick
  exceeds it). Recommended limit: refuse above 64 MB with a toast.
- A-07: only a performance concern; the store holds tens of documents, not
  thousands.
- A-08: harmless while packs are produced by the CI step that writes one
  document per release.
- A-09/A-10: both are bounded by inputs the project controls.

## Verification

- `./gradlew :app:testDebugUnitTest` — `CloudProfilePackTest` including the new
  count-cap case.
- `./gradlew :app:assembleRelease` — compile gate for the four source edits.

No file under `src/core/` was modified, so `cmp_disasm.py` and the on-device
gates do not apply to this change set.
