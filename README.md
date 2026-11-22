# omnisound 0.2 — Android music player prototype

Native Android app built with Kotlin, Jetpack Compose and Media3/ExoPlayer. This iteration turns the previous static player mock-up into a functional **local-audio playback prototype** and improves navigation and playback feedback.

## What works in 0.2

- Import audio files from the device using Android's system file picker.
- Play and pause imported audio through Media3 ExoPlayer.
- Live playback position, duration and seek bar.
- Previous/next track navigation, shuffle ordering and a queue sheet.
- Mini-player with playback progress.
- Like list for the current app session.
- Search across the bundled discovery catalogue and imported local tracks.
- Local recommendation ranking using the existing `RecommendationEngine`.
- Animated screen transitions, mini-player appearance, and animated equalizer bars.
- Monochrome visual theme retained.

## Important: SoundCloud is not connected yet

The bundled tracks (`afterglow`, `glory box`, etc.) are metadata-only discovery examples. They do not contain audio URLs, so the app correctly reports that they cannot be played instead of pretending to play them. Live SoundCloud search, account login and streaming require an approved SoundCloud API integration and compliant OAuth configuration. Do not put a confidential client secret in an APK. The current build does not scrape SoundCloud or bypass track restrictions.

## Build without Android Studio

Push this repository to GitHub. The workflow at `.github/workflows/android.yml` builds `assembleDebug` on GitHub Actions. Open **Actions → Build omnisound APK**, then download the `omnisound-0.2-debug-apk` artifact from a successful run. Extract the ZIP and install `app-debug.apk` on Android.

The project uses JDK 17 and Gradle 8.9. GitHub-hosted Ubuntu runners provide the Android SDK.

## Current limitations

- Imported files and likes are currently held in app memory for the session; persistent library/Room wiring is a next step.
- Background playback notification/MediaSession service is not yet implemented. Treat this as an in-app playback prototype, not a production background music service.
- SoundCloud API/OAuth, live catalogue search, artwork loading and real radio stream queue are not implemented yet.
- The recommendation engine currently ranks the local metadata catalogue; it is not trained on real listening history yet.


## omnisound 0.3.0

This iteration moves playback into a Media3 `MediaSessionService`, so playback can continue independently of the Compose screen. Android's system media notification/lock-screen controls are provided by the MediaSession integration. The player screen supports swipe down on the artwork to collapse and horizontal swipes to change tracks. Screen transitions, artwork pulse, mini-player sizing, and track rows include motion.

Build with `gradle assembleDebug`. The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

Note: the current bundled catalogue still contains discovery placeholders. Real SoundCloud search/streaming requires a supported API integration and valid stream URLs; this update does not fabricate those streams.
