# FlClash Android On-Demand Fork

[**简体中文**](README_zh_CN.md)

[![License](https://img.shields.io/github/license/moreoronce/FlClash?style=flat-square)](LICENSE)
[![Upstream](https://img.shields.io/badge/upstream-chen08209%2FFlClash-blue?style=flat-square)](https://github.com/chen08209/FlClash)

This repository is a focused fork of [chen08209/FlClash](https://github.com/chen08209/FlClash). The upstream project is a multi-platform ClashMeta/mihomo client for Android, Windows, macOS, and Linux. This fork keeps that base, but the active branch is tuned for one main use case: more reliable Android on-demand VPN behavior around Wi-Fi SSID changes.

Use upstream FlClash if you want the general-purpose release channel. Use this fork if you are testing or developing the Android on-demand VPN changes described below.

## What This Fork Changes

- Improves Android on-demand VPN handling when Wi-Fi SSID changes. The Android service observes network changes natively, deduplicates SSID transitions, and only suspends VPN after the excluded SSID is connected and validated.
- Keeps `VpnService` running as a foreground service while on-demand mode is suspended, so VPN can resume after leaving the excluded SSID without reopening the app.
- Restores VPN when the TUN state drifts away from the expected suspended or active state.
- Adds Android on-demand diagnostics to make SSID, network, and VPN state easier to inspect while debugging.
- Reduces background work when the UI is not in the foreground by pausing traffic, logs, and connection refreshes, throttling suspended-state notifications, and deduplicating DNS updates.
- Updates the bundled mihomo core through the `core/Clash.Meta` submodule to a branch based on upstream `v1.19.27`.
- Narrows local Android build output toward `android-arm64` for faster Android-only development and testing.

## Inherited From Upstream

- ClashMeta/mihomo-based proxy core.
- Android, Windows, macOS, and Linux app targets.
- Material You design with a Surfboard-like UI.
- Subscription import, rule/profile management, dark mode, and WebDAV sync.
- Desktop process-mode core integration and Android FFI/lib-mode core integration.

Desktop platforms are still present in the source tree, but this fork branch has not been optimized or validated as a desktop release branch.

## Preview

Desktop:

<p align="center">
  <img alt="FlClash desktop preview" src="snapshots/desktop.gif">
</p>

Mobile:

<p align="center">
  <img alt="FlClash mobile preview" src="snapshots/mobile.gif">
</p>

## Current Validation

The Android-focused branch has been validated with:

```bash
go test ./...
plugins/setup/buildkit/run_build_tool.cmd android --arch arm64
cd android && ./gradlew.bat :app:assembleDebug
```

The debug APK was installed on a real Android device for a smoke test. The app process, remote process, and foreground `VpnService` started successfully, with no crash or ANR observed in logcat or Android exit-info during that smoke test.

## Download

For normal end-user releases, prefer upstream FlClash:

<a href="https://chen08209.github.io/FlClash-fdroid-repo/repo?fingerprint=789D6D32668712EF7672F9E58DEEB15FBD6DCEEC5AE7A4371EA72F2AAE8A12FD"><img alt="Get it on F-Droid" src="snapshots/get-it-on-fdroid.svg" width="200px"/></a>
<a href="https://github.com/chen08209/FlClash/releases"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" width="200px"/></a>

This fork branch is primarily for source builds and Android behavior testing unless a fork-specific release is published.

## Build From Source

Initialize submodules first:

```bash
git submodule update --init --recursive
```

Install the project toolchains:

- Flutter matching the project constraints. FVM is recommended; Flutter `3.35.7` is the known-good version documented for this checkout.
- Go for the ClashMeta/mihomo core.
- Android SDK and Android NDK for Android builds.
- GCC and Inno Setup for Windows packaging.
- `appdmg` for macOS DMG packaging.

Fetch Flutter dependencies:

```bash
fvm flutter pub get
```

Build the Android core and debug APK:

```bash
plugins/setup/buildkit/run_build_tool.cmd android --arch arm64
cd android
./gradlew.bat :app:assembleDebug
```

Run a full package build through the project setup script:

```bash
dart setup.dart android
dart setup.dart windows
dart setup.dart linux
dart setup.dart macos
```

On Linux, install these desktop dependencies if they are not already available:

```bash
sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
```

## Android Integration Actions

The Android app supports these external actions:

```text
com.follow.clash.action.START
com.follow.clash.action.STOP
com.follow.clash.action.TOGGLE
```

## Development Notes

- Run `flutter test`, not `dart test`, for root package tests because some models pull in Flutter types.
- After changing models, providers, or Drift database schema, regenerate code:

```bash
dart run build_runner build --delete-conflicting-outputs
```

- Root `flutter test` discovers the root package tests only. Plugin tests under `plugins/` need to be run by path or from the plugin package directory.

## Credits

This fork builds on [FlClash](https://github.com/chen08209/FlClash), ClashMeta/mihomo, Flutter, and the related local plugins bundled in this repository. Upstream project license terms remain in [LICENSE](LICENSE).
