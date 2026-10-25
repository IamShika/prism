# Prism Music Player

A free, open-source music player for Android. No ads, no accounts, no paywall, ever.

> **Status: early development (v0.1).** The basics work; the more advanced features are on the roadmap below.

## Download

1. Open the **[Releases](../../releases)** page of this repository.
2. Download `PrismMusicPlayer.apk` from the newest release.
3. Open it on your phone. Android will ask you to allow installs from your browser or file manager ("unknown sources"). That is normal for apps that aren't on the Play Store.

Needs Android 8.0 or newer.

## Features

| | Feature | Status |
|---|---|---|
| ✅ | Basic player (play, pause, next, previous, seek) | Done in v0.1 |
| ✅ | Filter songs by length | Done in v0.1 |
| ✅ | Pitch and speed (independent of each other) | Done in v0.1 |
| ✅ | Real-time reverb | Done in v0.1 (needs testing on more phones) |
| ✅ | Accent colour themes (presets + your own colour) | Done in v0.1 |
| ⬜ | Adjustable crossfade | Planned |
| ⬜ | Create an album from picked songs (moves them into a new named folder) | Planned |
| ⬜ | BPM detector | Planned |
| ⬜ | Waveform view with real-time rewind and scrubbing | Planned |
| ⬜ | Background playback with notification and lock-screen controls | Planned |

## Publishing this on GitHub (step by step)

You don't need Android Studio. GitHub builds the APK for you.

1. Create an empty repository on GitHub (for example `prism-music-player`). Make it **Public**.
2. In this folder, run:
   ```bash
   git init
   git add .
   git commit -m "First version of Prism"
   git branch -M main
   git remote add origin https://github.com/YOUR-NAME/prism-music-player.git
   git push -u origin main
   ```
3. Open the **Actions** tab on GitHub. The "Build APK" workflow starts by itself. When it turns green, open it and download `PrismMusicPlayer-apk` from the **Artifacts** section. That's a working APK you can test on your phone.
4. To publish it for everyone, create a release by tagging a version:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   A few minutes later the APK appears on your **Releases** page.

If the first build is red, open the failed step in the Actions tab and copy the error. It is almost always a small typo or version mismatch and quick to fix.

## How the code is organised

Everything lives in `app/src/main/java/com/prism/musicplayer/`. Each file does one job:

| File | What it does |
|---|---|
| `MainActivity.kt` | The app's front door. Starts the screen. |
| `PlayerViewModel.kt` | The brain. Owns the audio player and remembers speed, pitch, reverb, filter and theme. |
| `data/SongRepository.kt` | Asks Android for the list of songs on the phone. |
| `data/Song.kt` | A tiny description of one song (title, artist, length, location). |
| `ui/Screens.kt` | Everything you see: song list, length slider, player, theme picker. |
| `ui/Theme.kt` | Turns the chosen accent colour into the app's colours. |

A good way to learn: change something small and rebuild. For example, in `ui/Screens.kt` change the text `"Song length"` to anything you like, push to GitHub, and download the new APK.

## Building on your own computer (optional)

Install [Android Studio](https://developer.android.com/studio), choose **Open**, and select this folder. It downloads everything it needs. Press the green play button to run on a phone or emulator.

## Signing

Release APKs are currently signed with a temporary debug key, so they install fine, but each GitHub build uses a different key. That means a user must **uninstall the old version before installing a newer one**. Before you tell many people about the app, create a permanent key and store it in GitHub Secrets so updates install cleanly over each other. This is a good next task.

## Roadmap

- **v0.2:** background playback service with notification, crossfade, album creator.
- **v0.3:** BPM detector, waveform view.
- **v0.4:** real-time rewind and scrubbing (the BandLab-style audio stretch). This is the hardest feature and may need a custom audio engine.

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE). Free to use, copy and modify.
