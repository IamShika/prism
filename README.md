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
| ✅ | Real-time reverb with MAIN / TUNE panel and 5 presets | Done in v0.2 (our own reverb engine, works on every phone) |
| ✅ | Accent colour themes (presets + your own colour) | Done in v0.1 |
| ✅ | Adjustable crossfade (1 to 12 s, 3 fade curves, on manual skip and on auto-advance) | Done in v0.3 |
| ✅ | Create an album: press and hold songs to pick them, name it, and they are moved into `Music/<name>/` | Done in v0.4 |
| ⬜ | BPM detector | Planned |
| ⬜ | Waveform view with real-time rewind and scrubbing | Planned |
| ✅ | Background playback with notification, lock-screen and Bluetooth controls | Done in v0.3 |
| ✅ | "Open with Prism" from a file manager: a small player pops up over the file manager and plays the file right there | Done in v0.3 |

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
| `ui/ReverbPanel.kt` | The reverb screen: knobs, vertical sliders, preset menu. |
| `ui/CrossfadePanel.kt` | The crossfade settings: switch, curve graph, duration. |
| `ui/OverlayPlayer.kt` | The small player card that pops up over other apps. |
| `OverlayPlayerActivity.kt` | The window that holds that card. Android starts it when you open a music file with Prism. |
| `PlaybackService.kt` | Keeps the music playing in the background and shows it in the notification and media controls. |
| `audio/AudioEngine.kt` | Owns the audio players. One engine for the whole app, shared by the screen, the overlay and the notification. |
| `audio/CrossfadeLogic.kt` | Decides when and how to blend two songs. The helper player starts silently ahead of time so the old song never drops out. Pure maths, fully tested. |
| `audio/CrossfadeController.kt` | Carries out those decisions on the real players. |
| `data/AlbumNaming.kt` | The rules for album folder names (what is allowed, what happens to bad characters). Fully tested. |
| `data/AlbumMover.kt` | Moves the picked songs into the album folder. |
| `ui/AlbumDialog.kt` | The "New album" name box. |
| `data/ExternalAudio.kt` | Figures out what to play when another app hands Prism a file. |
| `audio/ReverbEngine.kt` | The reverb itself: pure maths, no Android parts. |
| `audio/ReverbAudioProcessor.kt` | Plugs the reverb into the player's sound pipeline. |
| `audio/ReverbPresets.kt` | Small Room, Studio, Concert Hall, Cathedral, Dreamy. |
| `ui/Theme.kt` | Turns the chosen accent colour into the app's colours. |

A good way to learn: change something small and rebuild. For example, in `ui/Screens.kt` change the text `"Song length"` to anything you like, push to GitHub, and download the new APK.

## Automatic tests

The reverb engine and the crossfade timing have automatic checks. For the reverb: does the tail last as long as the
Decay knob says, does it stay stable with any knob settings. For the crossfade: does the blend start at the right
moment, survive pausing, skipping and seeking. They need only Java and
[Kotlin](https://kotlinlang.org/docs/command-line.html), not Android:

```bash
tools/reverb-test/run.sh
tools/crossfade-test/run.sh
tools/album-test/run.sh
```

## Building on your own computer (optional)

Install [Android Studio](https://developer.android.com/studio), choose **Open**, and select this folder. It downloads everything it needs. Press the green play button to run on a phone or emulator.

## Signing

Android only lets a new version install over an old one if both were signed with the **same key**. Without your own key, each GitHub build uses a different temporary key, and users would have to uninstall the app before updating.

**One-time setup (do this before your first public release):**

1. Create the key on your own computer. It never goes into the repo:
   ```bash
   mkdir -p ~/prism-signing && cd ~/prism-signing
   keytool -genkeypair -v -keystore prism-release.jks -alias prism \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Pick a strong password and remember it. If it asks for a separate key password, press Enter to reuse the same one.
2. **Back up `prism-release.jks` and the password somewhere safe** (for example a password manager). If you lose them, nobody can update the app without uninstalling it first. Never upload the file to GitHub.
3. On GitHub open the repo, then **Settings > Secrets and variables > Actions > New repository secret**, and add four secrets:

   | Secret name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the output of `base64 -w0 prism-release.jks` (one very long line) |
   | `KEYSTORE_PASSWORD` | the password you chose |
   | `KEY_ALIAS` | `prism` |
   | `KEY_PASSWORD` | the same password |

4. Push any change. In the build log, the "Set up signing key" step should say *Using the permanent release key*.

From then on every release APK uses your key, and every GitHub build gets a higher version number automatically, so users can simply install new versions over old ones.

## Roadmap

- **v0.4:** ~~album creator~~ (done), BPM detector (then the reverb "Tempo" sync can follow the song's beat).
- **v0.5:** waveform view.
- **v0.4:** real-time rewind and scrubbing (the BandLab-style audio stretch). This is the hardest feature and may need a custom audio engine.

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE). Free to use, copy and modify.
