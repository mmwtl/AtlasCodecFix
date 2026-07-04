# Atlas Codec Fix

Standalone Android app for applying HEVC codec profile fixes on rooted Android head units.

The project takes the codec-fix logic out of GInputBridge and keeps only the parts needed for this
task: ADB connection setup, profile selection, manual quick apply, and optional auto-apply after boot.

## Features

- Connects to local ADB over TCP from the device itself.
- Applies codec/profile/spec configs through a root shell.
- Supports auto-apply after boot and after app update.
- Provides a separate quick launcher, `Codec Profiles`, for applying a profile from a small popup.
- Keeps the main app focused on ADB settings and auto-apply configuration.

## Profiles

The UI exposes three user-facing profiles:

| Profile | Description |
| --- | --- |
| `Default` | Standard `msmnile` profile. Removes active bind mounts and returns to the stock msmnile config set. |
| `Min` | Standard config set with HEVC uncommented. |
| `Max` | Config set assembled from the best available performance numbers in the bundled files. |

The repository also contains additional internal/experimental profile assets copied from the source
codec-fix set, but the app UI intentionally exposes only `Default`, `Min`, and `Max`.

## How It Works

When a profile is applied, the app:

1. Copies the required profile files and `codecfix.sh` from app assets.
2. Opens an ADB shell connection to `localhost:<port>`.
3. Runs the script through `su root`.
4. Copies the selected files to `/dev/hevc`.
5. Bind-mounts codec config files over the target files in `/vendor/etc`.
6. Restarts media codec services.

The fix is runtime-only because it uses bind mounts. After reboot, Android loses these mounts, so the
app can re-apply the selected profile automatically if auto-apply is enabled.

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
4. Set the ADB port, usually `5555`.
5. Tap `Подключить`.
6. Select the profile for auto-apply.
7. Enable `Применять после загрузки` if the fix should be restored automatically after boot.

For one-off manual activation, open the separate launcher entry `Codec Profiles` and choose the
profile to apply. Tapping outside the popup closes it.

## Project Structure

```text
app/src/main/java/com/mmwtl/atlascodecfix/
  AdbClient.kt                 ADB connection and shell execution
  HevcCodecFixRepository.kt    Profile staging, apply, and detection logic
  HevcCodecFixVariant.kt       Profile definitions
  MainActivity.kt              Main settings UI
  QuickApplyActivity.kt        Popup UI for manual profile activation
  AutoApplyReceiver.kt         Boot/package-update auto-apply receiver

hevc/
  codecfix.sh                  Root-side bind-mount script
  default/                     Stock/default source configs
  min/                         Minimal HEVC-enabled profile
  max/                         Maximum performance-number profile
  ultra/                       Experimental bundled profile assets
```

## Build

Install Android Studio or an Android SDK with the required build tools, then run:

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

`assembleRelease` always produces an installable APK. If no private signing config is present, the
release build falls back to the Android debug key, which is suitable for local testing only.

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

## Safety Notes

- The app executes root commands.
- It bind-mounts files over `/vendor/etc` media codec configs.
- The fix is not persistent by itself; rebooting clears the bind mounts.
- Use `Default` or reboot the device to return to the stock runtime state.

## License

No license file is included yet.
