# Atlas Codec Fix

Standalone Android app for applying HEVC codec profile fixes on rooted Android head units.

The project takes the codec-fix logic out of GInputBridge and keeps only the parts needed for this
task: ADB connection setup, profile selection, manual quick apply, and optional auto-apply after boot.

## Features

- Connects to ADB over a configurable TCP host and port; `localhost:5555` remains the default.
- Persists its private ADB key in no-backup app storage so authorization survives process restarts.
- Runs a preflight compatibility check before applying a codec profile.
- Allows bypassing the preflight compatibility check when manual override is enabled.
- Applies codec/profile/spec configs through a root shell.
- Enforces a real command deadline by closing the ADB transport when a command times out.
- Serializes profile operations process-wide inside the application.
- Provides a read-only diagnostic report with root, platform, file, preflight, and profile data.
- Shows the current Android codec list with hardware/software and audio/video filters.
- Lets codecfix and MediaWidget restart be selected independently for automatic execution.
- Schedules selected actions after a configurable boot delay as a unique persisted job with bounded
  retries.
- Can show ADB/preflight/apply errors as Android notifications.
- Provides a separate quick launcher, `Codec Fix`: in advanced mode it opens the profile popup; in
  simple mode it alternates `Min` and `Default`, then restarts MediaWidget.
- Keeps the main app focused on ADB settings and auto-apply configuration.

Preflight is deliberately small: it verifies root access, the five expected msmnile vendor files,
the QTI HEVC codec declaration, and `ro.board.platform` (falling back to `ro.soc.model`). An
unknown platform is reported as `risky` for explicit manual confirmation, not as a hard block.

## Profiles

The UI exposes five user-facing profiles:

| Profile | Description |
| --- | --- |
| `Default` | Unconditional recovery operation. Removes active bind mounts without running preflight or staging profile assets. |
| `Direwolf` | Experimental application of the stock Direwolf vendor configuration over msmnile targets. |
| `Min` | Standard config set with HEVC uncommented. |
| `Max` | Experimental cross-device aggregate. Requires confirmation for manual apply and unsafe mode for auto-apply. |
| `Ultra` | Experimental C2/QTI-first aggregate. Requires confirmation for manual apply and unsafe mode for auto-apply. |

## How It Works

When a profile is applied, the app:

1. Copies only the selected profile and root scripts from app assets into a temporary staging tree.
2. Opens an ADB shell connection to `localhost:<port>`.
3. Runs `preflight.sh` through `su root`, unless `Фикс без проверки` is enabled.
4. Stops if the target is unsupported and the check is not bypassed.
5. Through the root shell, atomically switches the staging tree into `/dev/hevc`.
6. Bind-mounts only the files listed by the profile manifest.
7. Verifies every mount and rolls the complete set back to stock if any mount fails.
8. Detects the active profile before completing the root transaction; a mismatch triggers an
   automatic `Default` restore.
9. Restarts media codec services only after a complete apply or explicit restore.

The fix is runtime-only because it uses bind mounts. After reboot Android loses these mounts. A
small non-exported receiver schedules a persisted job after `BOOT_COMPLETED`; the job waits for the
configured delay and ADB, retries transient failures up to five times, and executes the selected
codecfix/mediafix actions in that order.

`mediafix` runs through the same bounded ADB/root command path as codecfix. It resolves all current
PIDs for `com.geely.mediawidget`, sends `SIGKILL`, and treats an already stopped process as success.

## Requirements

- Android 8.0+.
- Root access with working `su`.
- ADB daemon listening on a local TCP port, usually `5555`.
- First ADB connection may require accepting the app's ADB key on the device.

This app is device/config specific. Applying wrong media codec configs can break media playback until
the profile is restored or the device is rebooted.

## Usage

1. Install the APK.
2. Open `Atlas Codec Fix`.
3. Enable `ADB helper`.
4. Set the ADB address and port, usually `localhost:5555`.
5. Tap `Подключить`.
6. Use `Запустить preflight` for the compatibility check or `Диагностика` for the complete read-only
   device report.
7. Enable `Расширенный фикс` to choose profiles manually and from the quick popup. Disable it to
   make the quick launcher alternate `Min`/`Default`; auto codecfix also uses `Min` in this mode.
8. Select the profile for auto-apply when advanced mode is enabled.
9. Enable `Codecfix после загрузки`, `Mediafix после загрузки`, or both, and set the boot delay.
10. Tap `Посмотреть кодеки` to rebuild and view the current codec list.
11. Enable `Небезопасный режим` only if the compatibility guard blocks a known-good target. This is
   also the explicit override required for automatic application of experimental profiles.
12. Enable `Ошибки уведомлениями` if errors should also appear as Android notifications.

For one-off manual activation in advanced mode, open `Codec Fix` and choose a profile or run
Mediafix directly. Tapping outside the popup closes it. In simple mode the launcher has no menu: it
detects the active profile, applies `Min` unless `Min` is already active (then restores `Default`),
and runs Mediafix last. Mediafix is not run if codecfix/restore fails.

## Project Structure

```text
app/src/main/java/com/mmwtl/atlascodecfix/
  AdbClient.kt                 ADB connection and shell execution
  AdbCommandDeadline.kt        Physical ADB transport timeout watchdog
  AdbCommandResult.kt          Typed command success/failure model
  AdbKeyStore.kt               Persistent private ADB key storage
  CodecFixViewModel.kt         Main-screen state and actions
  HevcCodecFixRepository.kt    Profile staging, apply, and detection logic
  HevcCodecFixVariant.kt       Profile definitions
  MainActivity.kt              Main settings UI
  QuickApplyActivity.kt        Advanced popup and simple one-tap Min/Default toggle
  AutoApplyReceiver.kt         Validates boot/update broadcasts and schedules work
  AutoApplyJobService.kt       Bounded background auto-apply execution
  AutoApplyScheduler.kt        Unique persisted JobScheduler registration

hevc/
  preflight.sh                 Root-side compatibility guard
  codecfix.sh                  Root-side bind-mount script
  default/                     Stock/default source configs
  min/                         Minimal HEVC-enabled profile
  max/                         Maximum performance-number profile
  ultra/                       Experimental bundled profile assets
  tests/                       Host-side transaction and asset validation tests
```

## Build

Install JDK 17 and the Android 16 (API 36) SDK, then run:

```bash
./gradlew :app:assembleDebug
```

Release build:

```bash
./gradlew :app:assembleRelease
```

The release APK is written to:

```text
app/build/outputs/apk/release/
```

## Release Signing

`assembleRelease` fails when private release signing is not configured. This prevents an artifact
intended for distribution from being silently signed with the Android debug key. Use
`:app:assembleDebug` for local testing without private signing material.

For a real release key:

1. Copy `app/_secure.signing.gradle` to `secure.signing.gradle` in the project root.
2. Fill in `storePassword`, `keyAlias`, and `keyPassword`.
3. Put the keystore at `atlascodecfix-release.jks`, or change `storeFile` in `secure.signing.gradle`.
4. Run `./gradlew :app:assembleRelease`.

`secure.signing.gradle`, `*.jks`, and `*.keystore` are ignored by git.

You can verify a built APK with:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/*.apk
```

## Verification

Run all host-side checks and Android lint/unit tests:

```bash
./gradlew verify
```

Build verification used by CI:

```bash
./gradlew verify :app:assembleDebug
```

The shell transaction test uses fake mount commands to verify `Min`, idempotent restore, and
rollback after a deliberately failed second bind mount. Device-level behavior still needs a rooted
representative head unit because an emulator cannot reproduce the vendor codec stack and mount
namespace.

## Safety Notes

- The app executes root commands.
- It bind-mounts files over `/vendor/etc` media codec configs.
- The fix is not persistent by itself; rebooting clears the bind mounts.
- Use `Default` or reboot the device to return to the stock runtime state.
- `Небезопасный режим` bypasses the compatibility guard and can apply the fix to unsupported targets.
- `Max`, `Direwolf`, and `Ultra` are experimental cross-device profiles.

## License

No project-wide open-source license is granted yet. See [NOTICE.md](NOTICE.md) before redistribution;
the bundled Qualcomm/vendor configuration files retain their original notices.
