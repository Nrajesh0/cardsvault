<img src="docs/icon.png" alt="CardVault icon" width="80">

# CardVault

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](#)
[![Offline Only](https://img.shields.io/badge/Network-Strictly%20Offline-1D2249)](#)
[![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)](LICENSE)

A strictly offline Android card wallet (`com.cardssrj.app`). Store credit, debit, and prepaid cards, get due-date reminders, and import local PDF statements — with zero internet permission, no accounts, and no cloud.

## Features

- Scan cards with the camera, tap via NFC, or type manually (offline BIN detection).
- Card numbers, expiry, CVV, and holder names encrypted with Android Keystore and protected by biometric/PIN lock.
- Checkout autofill, customizable due-date reminders, and local PDF statement import with category spending tracking.
- Password-encrypted local backups, `FLAG_SECURE` screenshot blocking, and cloud backup disabled.

## Install

Download `CardVault_<version>.apk` from **Releases** and install on Android 8.0 or newer.

## Building

Requires JDK 17, Android SDK (platform 36), and Gradle 8.14.3.

```sh
gradle :app:assembleDebug        # debug (com.cardssrj.app.debug)
gradle :app:assembleRelease      # release (com.cardssrj.app)
gradle :app:testReleaseUnitTest  # unit tests
```

Signing secrets for the GitHub release workflow are documented in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Credits & License

- [GPL-3.0](LICENSE). Modified from [CardKeep](https://github.com/santdas36/CardKeep) (2026: removed email sync/network permissions, local-only PDF imports, package `com.cardssrj.app`).
- BIN data: [binlist-data](https://github.com/iannuttall/binlist-data) & [bin-list-data](https://github.com/venelinkochev/bin-list-data) (CC BY 4.0, see [`third_party/`](third_party/)). Inter font (SIL OFL 1.1).
