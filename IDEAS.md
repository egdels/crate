# Ideas

Feature ideas collected after 0.6.3, ordered by expected gain per effort.
None of them adds a setting; everything follows "open it and play".
Entries move to CHANGELOG.md when they ship and are deleted here.

## Small, directly audible or visible

- **Recently Played / Recently Added tiles.** Two more tiles next to All
  Tracks and Favorites. Recently Added is free: the scanner knows the
  file dates. Recently Played needs a small `history.json` in the
  favorites pattern, without touching the index.

## Medium, library

- **Artist view.** A click on the artist name in the album header
  filters the grid to that artist's albums, using the sort names read
  since 0.6. No new navigation, only a pre-filled search.
- **Lyrics.** USLT in MP3, ©lyr in M4A, LYRICS as a Vorbis comment, and
  `.lrc` next to the file. Shown as a collapsible field under the
  player. Count them first with `crate-scan` before building.
- **Folder watching on the Mac.** FSEvents on the library folders, so a
  freshly copied album appears without a rescan. Unreliable over SMB,
  pleasant locally; the manual rescan stays as the fallback.
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

## Suggested next version (0.7.0)

Everything from the first group is in: Up Next, the notification
heart, resume on launch, disc separators. Release it; the lyrics count
via `crate-scan` is the groundwork for 0.8.
