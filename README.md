# Atlas Codec Fix

## Release signing

`assembleRelease` always produces an installable APK.

By default the release build is signed with the Android debug key. This is convenient for local
testing, but not suitable as a long-term release key.

For a real release key:

1. Copy `app/_secure.signing.gradle` to `secure.signing.gradle` in the project root.
2. Fill in `storePassword`, `keyAlias`, and `keyPassword`.
3. Put the keystore at `atlascodecfix-release.jks`, or change `storeFile`.
4. Run:

```bash
ANDROID_HOME=/Users/wital/Library/Android/sdk sh gradlew :app:assembleRelease
```

`secure.signing.gradle` is ignored by git and overrides the debug-key fallback.
