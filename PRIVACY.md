# Privacy Policy

*Crate for Android and macOS. Effective 2026-10-10. The Android app shows
this text under Privacy Policy in its menu.*

## Who is responsible

Crate is free software developed and published by Christian Kierdorf as a
private, non-commercial project. The source code is at
https://github.com/egdels/crate. Questions about this policy go to the
project's issue tracker at https://github.com/egdels/crate/issues or to
the contact e-mail address shown on the app's Google Play listing.

## The short version

Crate does not collect, store, transmit, sell or share any personal data.
There is no account, no analytics, no crash reporting, no advertising, no
tracking and no telemetry. The app never opens a network connection of
its own. It only reads the music files in the folders you point it at.

## What the app accesses on your device

- **Music files in folders you choose.** On Android you pick a folder with
  the system's folder picker; Crate keeps the access grant for that folder
  and reads the audio files in it to show titles, artists, albums,
  durations and covers, and to play them. It never requests broad storage
  access, never reads other folders and never modifies, copies, moves or
  uploads a file. On macOS the same applies to folders you open or drop on
  the app.
- **Files you open with Crate.** If you choose Crate in "Open with" for an
  audio file, the app reads that file to play it.

## What the app stores on your device

- **The library index**: file paths, tags (title, artist, album, year,
  track number, genre) and durations of your music files, so that the app
  does not have to read every file again on each launch. It is one JSON
  file in the app's private storage (Android) or in
  `~/Library/Application Support/Crate/library.json` (macOS).
- **Cover thumbnails**: scaled-down copies of the album covers embedded in
  your files or lying next to them, in the app's cache directory (Android,
  the system may clear it at any time) or in
  `~/Library/Application Support/Crate/Artwork/` (macOS).
- **Folder grants (Android)**: the folders you picked stay readable to
  Crate through the system's persisted permissions until you remove the
  folder in the app or uninstall it.

All of this stays on your device and in the app's own storage. Nothing is
backed up by Crate itself; the Android system may include the app's data
in a device backup you have set up with Google, under the terms of that
backup service, not of Crate.

## What the system sees

Crate hands the title, artist, album and cover of the track that is
playing to the operating system so that the media notification, lock
screen, Bluetooth and headset controls (Android) or the media keys,
Control Center and the Now Playing widget (macOS) work. That information
stays on your device and is handled by the system the same way as for
any other player.

## Android permissions

Crate declares these permissions and nothing else:

- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: playback
  continues while the screen is off or another app is in front; Android
  requires a foreground service of type "media playback" for that.
- `WAKE_LOCK`: keeps the processor awake while audio plays with the
  screen off.
- `POST_NOTIFICATIONS`: shows the media notification with cover and
  transport controls.

There is no `INTERNET` permission: the app cannot send anything anywhere.
There is no storage, location, camera, microphone, contacts or phone
permission.

## Third parties

Crate contains no third-party SDKs for advertising, analytics or crash
reporting. On Android it uses the AndroidX and Media3 libraries from the
Android Open Source Project, which run locally and do not contact any
server. No data is shared with anyone.

## Children

Crate has no content or features aimed at children and collects no data
from anyone, of any age.

## Deleting your data

Uninstalling the app removes the library index, the cover thumbnails and
the folder grants. "Remove folder" in the app drops that folder's entries
from the index. On macOS, deleting
`~/Library/Application Support/Crate/` removes everything Crate has ever
written. Your music files are never touched.

## Changes

This policy changes only if the app's behaviour changes. Each change is
noted in `CHANGELOG.md` and the effective date above is updated. The
current version is always at https://github.com/egdels/crate/blob/main/PRIVACY.md.
