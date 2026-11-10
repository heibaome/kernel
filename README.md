# GhostSuite

[中文](README_ZH.md)

GhostSuite is a dual-engine Android root exploit suite that hands off to KernelSU.

- **GhostLock** — CVE-2026-43499 (PI-futex), W1/W2/W3 chain, driven from the app process with an exported kernel profile.
- **GhostKey** — CVE-2026-98260 (fput UAF), Shizuku + NDK pipeline: calibrate, inject, verify, hand off. Requires Shizuku.

Both engines share one UI, one execution sheet and one log stream; the engine card picks the payload. Do not run both exploits concurrently on the same device (see [docs/MERGE.md](docs/MERGE.md)).

## Use the app

1. Install a root manager APK from [KernelSU](https://github.com/tiann/KernelSU), [BakaSU/ReSukiSU](https://github.com/Baka-SU/BakaSU), or [KowSU](https://github.com/KOWX712/KernelSU).
2. Install the GhostSuite APK (`com.kernel.ghostsuite`) and open it. Upgrading from an old `com.ghostlock.app` build requires uninstalling first — the package id changed and data is not migrated.
3. Pick an engine on the main screen. The app defaults to the engine your kernel supports.

**GhostLock** requires an exact `uname -r` profile match; check the [supported devices](docs/kernel_profiles/SUPPORTED_DEVICES.md). **GhostKey** reports kernel support by version range, but its profile must match your exact build — import your `boot.img` from the engine card to generate one.

If Shizuku is enabled (mandatory for GhostKey), start it with ADB first and tap the status card to grant access; the card refreshes as soon as the grant lands.

Any root manager that ships `libksud.so` provides `ksud` for module loading. The built-in list (KernelSU, ReSukiSU, BakaSU, KowSU) is auto-detected, and other managers can be picked in **Settings → Root manager**. Without `ksud`, W1/W2 still grant uid 0 but no module is loaded.

### Coexisting with the original GhostLock / GhostKey apps

Shizuku authorization does **not** conflict: grants are per-package, each app binds its own UserService process, and the only shared component is the Shizuku server itself (one per device — restarting it affects every client app, which then simply shows `NOT_RUNNING` until restarted). **Never run exploits from two apps at the same time**: each app's execution lock only sees itself, and two concurrent heap-grooming attacks on one kernel are a guaranteed crash, not a permission question.

## Get kernel profiles

- **In-app import (GhostLock)**: **Parse OTA link** accepts a full OTA ZIP URL; **Parse local image** accepts `boot.img` plus optional `xbl_config.img`; **Import offsets.conf (v2)** accepts a HOCON profile (older JSON via **Import offsets.json (v1)**).
- **boot.img import (GhostKey)**: the engine card parses a boot image and builds the offset profile for your exact kernel.
- **Cloud update (GhostLock)**: **Advanced → Load configuration → Check cloud profile updates** pulls the CI-uploaded profile pack; the source URL is configurable in the same screen.

Extractor output is an unverified candidate. The app checks missing or invalid fields before execution. Generating a profile does not prove device support. See the [porting guide](docs/kernel_profiles/README.md) for validation and built-in registration, and the [MediaTek guide](docs/kernel_profiles/MEDIATEK.md) for image-based address recovery.

## Command-line tools

<details>
<summary>Run the native binary with an exported profile</summary>

Requires the Android NDK, `make`, Gradle, and ADB. An adb shell run skips the app's seccomp bypass stage.

```sh
make -C src ghostlock
./gradlew exportKernelProfiles
adb push build/native/ghostlock /data/local/tmp/ghostlock
adb push "build/kernel-profiles/<release>.bin" /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

</details>

<details>
<summary>Build and run the offset extractor</summary>

Requires Rust. The extractor accepts a boot image, a full OTA ZIP, or an HTTP(S) URL.

```sh
(cd tools/extract_rs && cargo build --release)
build/extract/release/ghostlock-extract boot.img --xbl-config xbl_config.img --format conf --out profile.conf
build/extract/release/ghostlock-extract OTA.zip --format conf --out profile.conf
```

Import `profile.conf` in the app. Omit `--xbl-config` if you do not have that image. The extractor rejects a patched `remove_waiter()` with exit code `6`.

</details>

## Reference

- [Profile schema](docs/kernel_profiles/PROFILE_SCHEMA.md) and [execution defaults](docs/kernel_profiles/defaults.md)
- [Native core](src/core/README.md)
- [Engine fusion record](docs/MERGE.md)
- [Add a component](docs/development/adding-a-component.md), [engineering rules](docs/development/engineering-standards.md), and [writing rules](docs/development/documentation-standards.md)
- [Code audit (2026-11)](docs/audit/code-audit-2026-11.md) — Android data/UI findings and their fixes

## Disclaimer

This tool performs kernel privilege escalation against known vulnerabilities, for security research and authorized modification of devices you own. Profiles are device- and kernel-specific: applying a mismatched profile can panic the kernel or corrupt memory. Profiles generated on-device from a `boot.img` or imported `.conf` files are **not** verified on real hardware unless explicitly marked supported — use at your own risk. Test fixtures in this repository are synthetic and encode no real device data. The authors are not responsible for bricked devices, data loss, or misuse.

## Credits and license

Licensed under [Apache License 2.0](LICENSE). Based on:

- [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia)
- [JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus)
- [x-spy/CVE-2026-43499-popsicle](https://github.com/x-spy/CVE-2026-43499-popsicle)
