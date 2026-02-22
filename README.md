# RemoteTX HAM Radio Android App

Android app for HAM radio operators to access the RemoteTX web interface with high-quality background audio and hardware PTT support.

## Features

- **Configurable Callsign**: Automatically loads `https://<callsign>.remotetx.net`.
- **Hardware PTT Support**:
    - **BlueParrott / Jabra**: Native integration via BlueParrott SDK for the Parrott Button.
    - **Generic BLE PTT**: Support for standard GATT PTT buttons (e.g., PTT-Z, CTR2).
- **Auto-Reconnect**: Automatically remembers and reconnects to the last used Bluetooth device on startup.
- **Background Operation**: Uses a Foreground Service and Partial Wake Lock to maintain audio and PTT connectivity while the screen is off or the app is in the background.
- **Optimized Audio**: Bidirectional Bluetooth SCO support and `MODE_IN_COMMUNICATION` routing for reliable headset use.
- **Diagnostic Logging**: Built-in "Session Log" for troubleshooting connection and PTT events.

## Building

1. Open the project in Android Studio.
2. Ensure you have the BlueParrott SDK AAR in the `app/libs` folder (or configured via flatDir).
3. Sync Gradle files.
4. Build and run on a device (Minimum API 24, Target API 33).

## Permissions

The app requires the following permissions for full functionality:
- **Microphone**: For voice transmission to the radio.
- **Bluetooth Connect/Scan**: To find and interact with PTT buttons and headsets (Android 12+).
- **Notifications**: For the foreground service status (Android 13+).
- **Battery Optimization Exemption**: To prevent the system from killing the background audio service.
- **Accessibility Service** (Optional): Can be enabled to intercept additional hardware media keys for PTT mapping.

## Usage

1. **Initial Setup**:
    - Enter your HAM radio callsign and tap "Connect".
    - Grant all requested permissions.
2. **Connecting PTT**:
    - Tap the menu (top right) and select **Connect Bluetooth PTT**.
    - Select your device from the list. 
    - The app will identify if it is a BlueParrott SDK device or a standard BLE button.
3. **PTT Operation**:
    - Pressing your hardware button will inject a backslash (`\`) key event into the WebView to trigger the RemoteTX PTT logic.
    - The PTT state is logged in the internal log for verification.

## Diagnostics

If you encounter issues with audio or PTT:
1. Tap the **Menu** (three dots).
2. Triple-tap the **Version Number** at the bottom of the menu.
3. Select **View Log**.
4. This log shows detailed information about GATT connections, SDK status, and PTT trigger events.
