# ChopLab iOS preview

This directory contains the iOS companion MVP for ChopLab / おとひろい.

The app is a native SwiftUI + AVFoundation implementation with:

- user-selected audio copied into the app's private Application Support directory;
- 16 PAD playback with per-PAD normalized chop ranges;
- source playback, recording, `ALL STOP`, and a local BPM control;
- no bundled user/third-party audio, credentials, signing keys, or network upload.

## Build locally on macOS

Install [XcodeGen](https://github.com/yonaskolb/XcodeGen), then run:

```bash
cd ios
xcodegen generate --spec project.yml
cd ..
xcodebuild -project ios/ChopLab.xcodeproj \
  -scheme ChopLab \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/build \
  CODE_SIGNING_ALLOWED=NO \
  build

SIMULATOR_NAME="$(xcrun simctl list devices available | awk -F '[()]' '/iPhone/{print $1; exit}' | xargs)"
xcodebuild -project ios/ChopLab.xcodeproj \
  -scheme ChopLab \
  -destination "platform=iOS Simulator,name=${SIMULATOR_NAME}" \
  CODE_SIGNING_ALLOWED=NO \
  test
```

The public preview workflow builds an unsigned iOS Simulator `.app` and publishes it as a zip with a SHA-256 file. It is not an installable signed iPhone/iPad IPA. A signed device build requires the user's Apple Developer team, provisioning profile, and signing credentials; those must never be committed or placed in public GitHub Actions logs.

User audio is intentionally not part of the repository or release assets. Test the app with audio selected on the local device or Simulator only.
