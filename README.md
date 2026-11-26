# Audify for Android

Paste (or Share) a YouTube link → yt-dlp downloads it → ffmpeg extracts the audio to MP3 → it plays in the app.
Uses [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles yt-dlp, Python and ffmpeg.

## Build the APK

**Option A: GitHub, no setup needed**
1. Create a new GitHub repo and upload the contents of this folder (keep the `.github` folder).
2. Open the **Actions** tab → wait for "Build APK" to go green (~5–10 min).
3. Click the run → download **Audify-apks** → unzip.
4. Install `app-arm64-v8a-release.apk` on your phone (almost every modern phone).
   Older 32-bit phones: `armeabi-v7a`. Emulators/Chromebooks: `x86_64`.

**Option B: Android Studio**
Open this folder in Android Studio, let it sync, then *Build → Build APK(s)*.
On the command line (Android SDK installed): `./gradlew assembleRelease` (or `gradlew.bat assembleRelease` on Windows).
APKs land in `app/build/outputs/apk/release/`.

## Features
- Download with progress and cancel; tick **Keep video** to also save the MP4
- **Share** from the YouTube app → *Audify* starts the download straight away
- Background playback with notification controls (play/pause, next, stop)
- Seek bar, previous/next, auto-advance, long-press a track to delete
- **Update yt-dlp** button. Tap it if downloads start failing (YouTube changes often)

Files are stored in `Android/data/com.example.ytplayer/files/Music/Audify` (and `Movies/Audify` for videos).
Uninstalling the app deletes them.

## Notes
- The release APK is signed with a debug key so it installs directly; you'll need to allow
  "Install unknown apps" for your browser/file manager.
- Apps like this can't go on the Play Store (against Google's policy). Sideload only.
- Only download content you have the right to save.
