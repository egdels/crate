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

- **Compute loudness ourselves.** The developer's collection has no
  ReplayGain tags, only Sound Check in ~300 files. Crate could run EBU
  R128 over the files during a scan and keep the result in a
  `loudness.json` (favorites pattern, nothing written into the files),
  giving every album a real album gain instead of the Sound Check mean.
  Cost: an R128 implementation in Swift and Kotlin, and scan time (one
  pass over ~18,000 tracks takes hours and must run in the background,
  interruptible). The biggest audible gain on the list, and the most
  expensive.
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

## Suggested next version (0.7.0)

0.7.0 shipped with the first group. Lyrics, the artist view and the
two recent tiles are in for 0.8; that is a release.
