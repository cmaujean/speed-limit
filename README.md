# Speed Limit

An Android app that tracks your location and displays the current speed limit as a small, draggable floating overlay bubble, similar to the Uber driver app's minimized view.

## How it works

- A foreground location service continuously tracks GPS location using high-accuracy fused location
- Road data (with speed limits, one-way direction, and geometry) is bulk-fetched from OpenStreetMap via the Overpass API in a 500m radius around your position
- On every GPS tick (~1s), the app matches your position and travel bearing against cached road segments locally with no network call needed, so street changes are picked up immediately
- Turning onto an untagged road clears stale speed limits back to `--` within 3 seconds
- The cache automatically refreshes when you approach the edge of the fetched area, with automatic mirror fallback and exponential retry backoff
- A partial wake lock and stall detection keep tracking alive even when another app is in front or the phone is locked

## The overlay bubble

- Appears as a floating speed limit sign over all other apps (choose between a red-bordered Circle sign or a US rectangular `SPEED LIMIT` sign)
- Drag it anywhere on screen (clamped to screen bounds and remembered across launches)
- Tap it to open the main app
- Flashes red when you exceed the speed limit by more than the configured allowance (default: 5 mph or km/h)

## Main app

The main app is a launcher and settings screen:

- **Launch / Stop Bubble**: launches or dismisses the floating overlay and previews the live detected speed limit
- **Over-limit allowance**: configure how many mph or km/h over the limit before the red flash warning triggers
- **Speed units**: switch between `mph` and `km/h` (automatically converts tagged road speeds)
- **Sign style**: switch between `Circle` and `US Sign` styles
- All settings and the bubble's screen position are saved automatically
- Handles requesting location and overlay permissions on first launch

## Permissions

- Fine location (GPS road matching while the overlay is running)
- Display over other apps (floating bubble)
- Foreground service (`location` type, keeps tracking alive when switching apps)
- Wake lock (prevents CPU sleep while screen is off)
- Internet (Overpass API queries)

## Data source

Speed limit data comes from OpenStreetMap via the [Overpass API](https://overpass-api.de/). No API key required. Coverage depends on OSM contributor data for your area.

## Building

Requires Flutter SDK and Android SDK.

```bash
flutter build apk --release
```

The release APK will be at `build/app/outputs/flutter-apk/app-release.apk` (or use `flutter build apk --debug` for `app-debug.apk`).

## Support

If you find this app useful, [buy me a coffee](https://buymeacoffee.com/4dvu1r9nsh).
