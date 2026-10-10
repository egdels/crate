# Google Play listing

This directory holds the Play Store listing in the layout `fastlane supply`
and the Play Console's bulk upload understand: one folder per locale with
`title.txt` (30 characters max), `short_description.txt` (80) and
`full_description.txt` (4,000), and the graphics under `images/`. Nothing
here is uploaded automatically; it is the source of what gets pasted or
uploaded into the Play Console by hand.

The listing has one language, `en-US`, because the app's own UI is
English only.

Release notes for Play go into `changelogs/<versionCode>.txt` (500
characters max; the versionCode is the one Gradle derives from
`appVersion`, 0.3.0 -> 300). They are written for users of the store,
not from the CHANGELOG, which is for readers of the repository.

## Regenerating the graphics

```sh
scripts/store-assets.sh
```

writes `icon.png` (512 x 512) and `featureGraphic.png` (1024 x 500) from
`scripts/icon.swift`, and frames the raw screenshots in `build/store/raw/`
(not committed) with the captions from the table in `store-assets.sh`:

| raw file            | device                       | canvas      |
|---------------------|------------------------------|-------------|
| `phone-1..6.png`    | Pixel 9a emulator, 1080x2424 | 1080 x 1920 |
| `tablet7-1..3.png`  | 7" emulator, 1920x1200       | 1920 x 1080 |
| `tablet10-1..3.png` | 10" emulator, 2560x1600      | 2560 x 1440 |

The phone shots are: album grid, album page, playing with the player bar,
lock screen with the media card, search, empty state. The tablet shots
are: grid and track list side by side, playing, search. Before capturing,
put the status bar into demo mode so that every shot shows 10:00, full
battery and Wi-Fi:

```sh
adb shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 1000" "battery -e level 100 -e plugged false" \
         "network -e wifi show -e level 4 -e fully true" "network -e mobile hide" \
         "notifications -e visible false"; do
    adb shell am broadcast -a com.android.systemui.demo -e command $c
done
adb exec-out screencap -p > build/store/raw/phone-1.png
```

The lock screen needs `adb shell locksettings set-disabled false`, then
`input keyevent KEYCODE_SLEEP` and `KEYCODE_WAKEUP`; the clock on it is the
real one, demo mode does not reach it.

## Play Console answers

Values for the forms that are not part of the listing text:

- **Category**: Music & Audio. **Tags**: music player, offline, FLAC.
- **Contact e-mail**: the developer's; **website**: the GitHub repository.
- **Privacy policy URL**: https://github.com/egdels/crate/blob/main/PRIVACY.md
  (the same text is shown inside the app under Privacy Policy in the
  menu, as the User Data policy asks).
- **App access**: all functionality is available without special access
  (no login, no restricted features).
- **Ads**: no, the app contains no ads.
- **Content rating** (IARC questionnaire): "Utility, productivity,
  communication or other"; no violence, sexual content, language,
  controlled substances, gambling, user interaction, personal data or
  location sharing. Expected rating: Everyone / PEGI 3 / USK 0.
- **Target audience**: 18 and over (the simplest answer that avoids the
  families policy; the app has no content for children either way).
- **Data safety**: "Does your app collect or share any of the required
  user data types?" -> No. The app has no INTERNET permission, so the
  form's "data is encrypted in transit" and "users can request deletion"
  questions do not apply; answer that no data is collected.
  Playback runs in a foreground service of type `mediaPlayback`, which
  Play asks about separately (foreground service permissions
  declaration): purpose "media playback", the user-facing feature is the
  player itself and its notification.
- **Government apps / financial features / health**: no.
- **News app**: no. **COVID-19 contact tracing**: no.
- **Open source**: mention the GPL-3.0 licence and the repository in the
  full description (already there); Play has no separate field.

## What to watch for

- The screenshots show album covers from the developer's own collection.
  Play's policy allows screenshots of the app in use; if a review ever
  objects to third-party artwork, replace the test folder with
  Creative-Commons releases and run the capture again.
- Every release needs a new `versionCode`; the Gradle build derives it
  from `appVersion` in `android/gradle.properties`, so bumping the version
  is enough.
- Play takes App Bundles, not APKs. Every `v*` tag attaches
  `Crate-<version>.aab` to the GitHub release, built and signed by
  `release.yml` with the same key as the APK; that is the file to upload.
  The bundle's signer must be the key Play knows as the upload key;
  `apksigner verify --print-certs` on the release APK and
  `keytool -printcert -jarfile` on the AAB show the certificate digests
  to compare.
