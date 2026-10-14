# Ideas

Feature ideas collected after 0.6.3, ordered by expected gain per effort.
None of them adds a setting; everything follows "open it and play".
Entries move to CHANGELOG.md when they ship and are deleted here.

## Medium, library

- **Add to library from a loose file.** A file opened from Finder or via
  "open with" on Android that is not in the library already plays. An
  "Add this folder to the library" line in that state would be the
  natural way to a second folder.

## Large, but in character

- **Gapless by ear.** Not a feature, still open: listen to an album with
  live transitions in both apps.

## Decided against

- **Sleep timer, crossfade.** Both demand options.
- **Chromecast on Android.** Media3 can cast, but it pulls in Google
  Play Services; a poor fit for a GPL app that is also on GitHub.
  AirPlay on the Mac is the system's business.
- **Folder watching on the Mac.** FSEvents could make a freshly copied
  album appear by itself, but scanning stays a deliberate act: the
  user rescans when the collection changed. Decided 2026-10-14.

## Suggested next version

0.7.0 and 0.8.0 shipped on 2026-10-14. The loudness analysis is in
for 0.9; adding a loose file's folder is the small thing left.
