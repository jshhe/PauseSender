# PauseSender

[简体中文](README.md) | English

PauseSender turns an Android phone into a three-key Bluetooth HID keyboard for a computer. After pairing, a small overlay sends:

| Overlay button | Keyboard key | HID usage |
| --- | --- | --- |
| Backward | Left Arrow | `0x50` |
| Pause | Space | `0x2C` |
| Forward | Right Arrow | `0x4F` |

Package name: `com.pause.sender`

The interface combines Material 3 components with an open-source Liquid Glass
status surface. Android 13+ renders live refraction and dispersion; Android
9–12 uses an opaque, readable Material fallback. The floating controller uses
a lightweight glass-inspired surface without capturing content from other apps.

## Requirements

- Android 9 (API 28) or newer.
- A phone whose vendor firmware exposes Android's Bluetooth HID Device Profile.
- A computer with Bluetooth HID host support (Windows is the primary tested target).

The Android emulator cannot validate the real Bluetooth HID path. Some manufacturers disable the HID Device Profile even on recent Android versions; PauseSender reports this when the system proxy cannot be obtained.

## App language

PauseSender includes Simplified Chinese and English resources in the installed app, so changing languages works offline. Tap the language icon in the upper-right corner and choose **Follow system**, **简体中文**, or **English**. Simplified Chinese is the fallback when the system language is not supported.

Changing the language recreates only the app screen. An active HID session remains connected, while the foreground-service notification and floating-control accessibility labels refresh in the selected language.

## Privacy and permissions

PauseSender deliberately does not use an accessibility service and does not request Internet or location access.

- `BLUETOOTH_CONNECT`: connect to an already paired computer and send HID reports.
- `BLUETOOTH_ADVERTISE`: make the phone discoverable while pairing.
- `SYSTEM_ALERT_WINDOW`: show the three controls above other apps.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`: keep the HID registration alive while the overlay is in use.
- `POST_NOTIFICATIONS`: show the required foreground-service notification on Android 13+.
- Legacy `BLUETOOTH` and `BLUETOOTH_ADMIN` declarations stop at Android 11 (`maxSdkVersion=30`).

## Pairing with Windows

1. Install and open PauseSender.
2. Allow the nearby-device permission and enable Bluetooth.
3. Tap **Start pairing**. PauseSender first registers its keyboard descriptor, then asks to make the phone discoverable.
4. On Windows, open **Settings → Bluetooth & devices → Add device → Bluetooth**.
5. Select the phone's normal Bluetooth name and confirm the same pairing code on both devices.
6. During this explicit 300-second pairing window, PauseSender tries the newly bonded device automatically. If it does not connect, tap the computer under **Paired devices**.
7. After the status says the computer is connected, grant **Allow display over other apps**. The draggable Backward/Pause/Forward control appears.

Each tap is serialized as one key-down report followed by an all-zero key-up report so keys do not remain stuck.

Hiding the overlay, either in the app or from the foreground-service
notification, first stops new key input, releases any held key, and disconnects
the current Bluetooth HID host. The service remains available so the computer
can be selected and connected again. **Disconnect and stop** additionally unregisters
the HID application and ends the foreground service.

## Troubleshooting

### The computer cannot find the phone

- Confirm PauseSender says the HID keyboard is registered before opening the computer's device search.
- Tap **Make this phone discoverable again**.
- Keep the PauseSender foreground-service notification active.

### Pairing succeeds but the keyboard does not connect

Old pairing records may describe the phone as a different device type. Remove the pairing on both the phone and computer, start PauseSender's pairing flow first, and then pair again.

On Windows, remove the phone from **Bluetooth & devices**. If necessary, also uninstall its stale Bluetooth entry in Device Manager before pairing again.

### The app says HID Device Profile is unavailable

The firmware probably does not expose Android's public `BluetoothHidDevice` API. This cannot be fixed with another runtime permission, accessibility access, or rootless application code; try another supported phone or vendor firmware.

### Overlay buttons are disabled

The HID connection is currently disconnected. Reconnect the computer from the paired-device list. The overlay remains visible but intentionally stops sending until a connected callback is received.

If the overlay was deliberately hidden, it is removed and the Bluetooth HID
connection is also closed. Reconnect the computer first, then show the overlay
again.

## Build

The checked-in wrapper uses Gradle 8.11.1. The project uses Android Gradle Plugin 8.9.1, Kotlin 2.0.21, `compileSdk=35`, `targetSdk=35`, and `minSdk=28`.

On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat assembleRelease
```

Installable debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Signed release APK (preconfigured with debug signing for local testing):

```text
app/build/outputs/apk/release/app-release.apk
```

For production distribution, configure your own production keystore in `app/build.gradle.kts`.

## Verification completed in this workspace

- Unit tests cover Space, Left Arrow, Right Arrow, all-zero release reports, descriptor-range validation, GET_REPORT padding, and overlay boundary clamping.
- Target ABI is configured for `arm64-v8a`.
- Instrumentation coverage verifies the Simplified Chinese and English strings, formatted status messages, English plurals, and the Simplified Chinese fallback.
- The generated per-app language configuration contains only `zh-CN` and `en`; both language packs are retained in app bundles for offline switching.
- Android Lint reports no issues.
- `aapt2` confirms package `com.pause.sender`, min SDK 28, target SDK 35, the intended platform permissions, and AndroidX's generated app-private signature permission.
- The merged manifest contains neither Internet nor accessibility-service declarations.
- Android 15 emulator visual checks cover the Material 3 light and dark themes, edge-to-edge system-bar insets, and the live Liquid Glass status surface.
- Emulator overlay checks cover glass styling over another app, drag from `(42,472)` to `(308,720)`, persisted-position restore, and clean removal (`OK (1 test)`).
- An Android 15 emulator cold-start test covered the permission-gated first screen, HID registration, the 300-second discoverable request, the `connectedDevice` foreground service, and clean service shutdown with no fatal exception.
- On a Lenovo TB322FC/Y700 running Android 16 (API 36), the phone paired with a Windows host as a Bluetooth HID keyboard. Windows captured complete Left Arrow, Space, and Right Arrow key-down/key-up pairs from the three real overlay buttons.
- Earlier physical-device testing also covered overlay dragging, saved-position restore, clean service shutdown, and automatic reconnection. The revised hide-to-disconnect lifecycle should be repeated against a real Bluetooth host before distribution.
- An Android instrumentation test creates the real `TYPE_APPLICATION_OVERLAY`, verifies the three button mappings, rejects disabled and slide-out touches, toggles connection state, and removes the overlay cleanly after the overlay AppOp is granted (`OK (1 test)`).

## License

PauseSender is released under the [MIT License](LICENSE).
Third-party attributions are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
