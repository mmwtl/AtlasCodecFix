# AtlasCodecFix Repository Guide

## Scope

These instructions apply to the entire repository.

## Project purpose

AtlasCodecFix is a small Android application for rooted, device-specific Android head units. It
connects to the device's local `adbd`, stages media codec configuration files, bind-mounts selected
files over `/vendor/etc`, and restarts media services. Treat every apply-path change as a safety-
critical change: a partial configuration can break media playback until reboot.

## Required safety invariants

1. `Default`/`msmnile` is an unconditional recovery operation. It must never be blocked by
   compatibility checks and must not depend on staging profile assets.
2. Profile application is serialized process-wide. Never allow two staging/apply/detect sequences
   to overlap.
3. A non-default profile is successful only when every requested bind mount succeeds and the final
   detected variant matches. On any mount failure, remove mounts created by that attempt and leave
   the device in the stock/default runtime state.
4. Restart media services only after the complete mount set has been verified, or after an explicit
   restore operation.
5. Every ADB connect and command operation must have a real finite timeout that closes the
   underlying socket/stream. Do not rely on coroutine cancellation around blocking library calls.
6. ADB command APIs return structured results. Never infer success by searching human-readable
   output for error strings.
7. Persist the generated ADB key pair in private no-backup app storage. Never log or bundle private
   key material.
8. Automatic application must be scheduled as unique background work with bounded retries. A
   broadcast receiver may enqueue work but must not perform the root operation itself.
9. Export as few components as possible. Receivers must reject unexpected actions even when they
   are not exported.
10. `Max` and other cross-device aggregate profiles are experimental. Do not enable their automatic
    application without an explicit unsafe override and clear user-facing warning.
11. Root operations follow `ADB transport -> su root sh -c -> command`. Keep remote staging under
    `/dev/hevc`; treat `/vendor/etc` as read-only apart from runtime bind mounts and unmounts over
    the existing files.

## Source layout

- `app/src/main/java/com/mmwtl/atlascodecfix/`: Android/Kotlin application code.
- `hevc/preflight.sh`: compatibility and device guard.
- `hevc/codecfix.sh`: root-side restore/apply transaction.
- `hevc/default/`: untouched reference files copied from target/vendor sources.
- `hevc/min/`, `hevc/max/`, `hevc/ultra/`: derived profiles.

Keep reference vendor files unchanged. Derived profile changes should be minimal, reviewable diffs
from the relevant reference file and documented in tests or README.

## Architecture guidelines

- Keep Android framework code at the edges. Business rules belong in small testable classes.
- Depend on an `AdbCommandExecutor` abstraction rather than a concrete socket client.
- Keep UI state in a `ViewModel`; composables should render immutable state and emit events.
- Avoid adding a dependency-injection framework for this project size. Explicit application-scoped
  construction is preferred.
- Put user-visible text in Android string resources.
- Use Material components with accessibility semantics; avoid custom controls unless required by
  the head-unit form factor.

## Profile and script rules

- Bind only files that differ from stock for that profile.
- Shell scripts must be compatible with `/system/bin/sh`; do not assume Bash.
- Quote paths and variables, check every destructive/mount command, and make cleanup idempotent.
- The vendor `video_system_specs*.json` files are JSON-with-comments. Validate them with a
  JSONC-aware check rather than strict `jq` alone.
- XML files must remain well-formed, and active codec names/aliases must not be duplicated across
  the effective include tree.

## Build and verification

Use the repository wrapper. The common local SDK path is shown only as a convenience:

```bash
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" ./gradlew \
  verify :app:assembleDebug
```

When private release signing is configured, also run `./gradlew :app:assembleRelease` and verify
the resulting APK signature. Release packaging intentionally fails without signing material.

For shell-only checks:

```bash
sh -n hevc/preflight.sh
sh -n hevc/codecfix.sh
```

Before handing off a change:

- run unit tests and Android lint;
- build both debug and release variants when build/signing changes are involved;
- validate XML, JSONC, and shell assets;
- verify that `git status` contains only intended changes;
- state explicitly when device-level root/ADB behavior could not be exercised on representative
  hardware.

## Tests

Add regression tests for every changed parser or safety rule. At minimum, preserve coverage for:

- typed ADB success/failure/timeout handling;
- preflight output parsing and compatibility decisions;
- unconditional default restore;
- variant detection;
- profile serialization;
- shell apply success, partial-mount rollback, and restore idempotency;
- profile file manifests and expected reference-file diffs.

Do not make unit tests depend on a running `adbd`, root, or the developer's signing files.

## Repository hygiene

- `gradlew` and `*.sh` are executable; XML/JSON/image/source files are not.
- Never commit `secure.signing.gradle`, keystores, generated APKs, private ADB keys, SDK paths, or
  Gradle caches.
- A distributable release must fail closed when release signing material is absent. If a locally
  installable debug-signed optimized build is useful, give it a distinct build type/name.
- Do not add an open-source license without confirming that redistribution of bundled Qualcomm and
  vendor configuration files is permitted.
