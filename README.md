# Speed Limit

An Android app that tracks your location and displays the current speed limit as a small, draggable floating overlay bubble -- similar to the Uber driver app's minimized view.

## How it works

- A foreground service continuously tracks GPS location using high-accuracy fused location
- Road data (with speed limits and geometry) is bulk-fetched from OpenStreetMap via the Overpass API in a 500m radius around your position
- On every GPS tick (~1s), the app matches your position and travel bearing against cached road segments locally -- no network call needed, so street changes are picked up instantly
- The cache automatically refreshes when you approach the edge of the fetched area
- A partial wake lock and stall detection keep tracking alive even when the phone is locked

## The overlay bubble

- Appears as a small round speed limit sign floating over all other apps
- Drag it anywhere on screen
- Tap it to open the main app
- Flashes red when you exceed the speed limit by more than the configured allowance (default: 5 mph)

## Main app

The main app is a simple launcher and settings screen:

- **Start/Stop Bubble** -- launches or dismisses the floating overlay
- **Over-limit allowance** -- configure how many mph over the limit before the red flash warning triggers
- Handles requesting location and overlay permissions on first launch

## Permissions

- Fine location + background location (GPS tracking)
- Display over other apps (floating bubble)
- Foreground service (keeps tracking alive in background)
- Wake lock (prevents CPU sleep while screen is off)
- Internet (Overpass API queries)

## Data source

Speed limit data comes from OpenStreetMap via the [Overpass API](https://overpass-api.de/). No API key required. Coverage depends on OSM contributor data for your area.

## Building

Requires Flutter SDK and Android SDK.

```
flutter build apk --debug
```

The APK will be at `build/app/outputs/flutter-apk/app-debug.apk`.

## Support

If you find this app useful, [buy me a coffee](https://buymeacoffee.com/4dvu1r9nsh).
