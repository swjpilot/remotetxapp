# RemoteTX HAM Radio Android App

Android app for HAM radio operators to access RemoteTX web interface with background audio support.

## Features

- Configurable callsign input (loads https://<callsign>.remotetx.net)
- Background operation with foreground service
- Microphone and Bluetooth audio support
- Battery optimization bypass
- WebView with full JavaScript and audio permissions

## Building

1. Open project in Android Studio
2. Sync Gradle files
3. Build and run on device (API 24+)

## Permissions

The app requests:
- Internet access
- Microphone access
- Bluetooth connectivity
- Battery optimization exemption
- Foreground service for background operation

## Usage

1. Enter your HAM radio callsign
2. Tap "Connect"
3. Grant requested permissions
4. The app will load your RemoteTX interface and run in background
