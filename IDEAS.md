# Ideas

Feature ideas collected after 0.6.3, ordered by expected gain per effort.
None of them adds a setting; everything follows "open it and play".
Entries move to CHANGELOG.md when they ship and are deleted here.

## Medium, library

- **Add to library from a loose file.** A file opened from Finder or via
  "open with" on Android that is not in the library already plays. An
  "Add this folder to the library" line in that state would be the
  natural way to a second folder.

## Practice

- **Nudge the loop's ends.** Moving A or B by a few hundredths with the
  keyboard, for a loop that clips the first note.
- **Count-in before each pass** of the loop, or a short pause, so there is
  time to get the hands back in place.
- **Speed trainer**: the loop gets a little faster with every pass, up to
  the recording's tempo.
- **Seamless loop on Android.** The loop's jump is a seek with a short
  gap; a clipped media source repeated in the playlist would join
  without one, at the cost of reworking the queue.

## Large, but in character

- **Gapless by ear.** Not a feature, still open: listen to an album with
  live transitions in both apps.

## Decided against

- **Sleep timer, crossfade.** Both demand options.
- **Chromecast on Android.** Media3 can cast, but it pulls in Google
  Play Services; a poor fit for a GPL app that is also on GitHub.
  AirPlay on the Mac is the system's business.
- **The Java Practizer's scope**: markers, grid, snap, quantize, beat
  detection, metronome, exercise and scale editor, project files,
  SoundTouch settings. They are why it was never finished; Practice
  stays tempo, key, tuning and one loop.
- **Folder watching on the Mac.** FSEvents could make a freshly copied
  album appear by itself, but scanning stays a deliberate act: the
  user rescans when the collection changed. Decided 2026-10-14.

## Suggested next version

0.7.0, 0.8.0 and 0.9.0 shipped on 2026-10-14. Adding a loose file's
folder is the one small thing left; gapless by ear is the developer's.
