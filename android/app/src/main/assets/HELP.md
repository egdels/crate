# Crate Help

Crate opens and plays. It reads music where it lies; nothing is copied,
uploaded or changed.

## Library

- **Add Folder…** in the menu picks a folder with the system's file
  picker. Subfolders are included. The folder can be on the phone, an SD
  card or a USB drive.
- **Rescan** picks up new, changed and removed files. Unchanged files
  are not read again.
- **Remove** takes a folder out of the library. Nothing on the device
  changes.
- Files the device cannot decode count as unreadable in the line under
  the grid.

## Playing

- Tap an album to open it, then tap a track to play from there through
  the album. On a wide screen the album opens next to the grid.
- Albums are sorted by artist, ignoring a leading "The" and following
  the sort names in the tags when the files have them, then by year.
- The play button in the bar plays the open album, or every track when
  All Tracks is open.
- **All Tracks** lists everything; a tap plays from that row to the end
  of the list.
- The search icon narrows albums and tracks by title, artist, album,
  composer and genre. Back closes the search.
- The heart on a track, in the player bar or on an album page marks a
  favorite. **Favorites** in the grid lists them; the play button plays
  them. Favorites are kept on this device, in a file next to the library
  index.
- **Shuffle** and **Repeat** are in the menu. Albums play gapless.
- The list icon in the player bar shows **Up Next**, what plays after
  this track. A tap plays an entry now, the × takes it out. Press and
  hold a track or an album for Play Next and Play Last.
- Files tagged with ReplayGain or iTunes Sound Check play at the level
  the tags describe, so albums come out equally loud; the system volume
  is on top of that. Untagged files play as they are. Playing an album,
  every track gets the same correction, so a quiet intro stays quiet and
  the record keeps its dynamics; in Shuffle and All Tracks each track is
  levelled on its own.
- The notification, the lock screen and headphone buttons control
  playback, and the notification has the heart too; the music keeps
  playing when the app is in the background.

## Opening files

A music file opened from a file manager with Crate plays at once. A file
from the library plays with its album queued; any other file plays as it
is.

## Files and formats

- MP3, AAC and M4A (including M4B audiobooks), FLAC, Ogg Vorbis, Opus,
  WAV and AIFF, as far as the device decodes them.
- Covers come from the files or from an image next to them, such as
  `cover.jpg` or `folder.png`.
- The library index lives in the app's private storage, the cover
  thumbnails in its cache. "Clear storage" in the system's app info
  starts over; the music itself is never touched.

## Source and bug reports

Crate is free software under the GPL. Code, releases and the issue
tracker are at github.com/egdels/crate. The privacy policy is in the
menu.
