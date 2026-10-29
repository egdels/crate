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
- Tap the artist's name on an album page to see that artist's albums:
  the search field takes `artist:Name`, which you can type as well.
  Clear the field to see everything again.
- **Recently Added** lists the newest files by their date, an album
  copied in one go together; **Recently Played** what was played last,
  newest first, kept on this device next to the library index. Both
  tiles play their list, and the search narrows them.
- The heart on a track, in the player bar or on an album page marks a
  favorite. **Favorites** in the grid lists them; the play button plays
  them. Favorites are kept on this device, in a file next to the library
  index.
- **Shuffle** and **Repeat** are in the menu. Albums play gapless.
- The list icon in the player bar shows **Up Next**, what plays after
  this track. A tap plays an entry now, the × takes it out. Press and
  hold a track or an album for Play Next and Play Last.
- Albums come out equally loud: files tagged with ReplayGain play at
  the level the tags describe, and files without are measured by Crate
  while the app is open, after a scan (EBU R 128, the way ReplayGain
  2.0 asks for it; the line under the grid counts along, the results
  are kept next to the library index, the files are not touched). Until
  a file is measured, its iTunes Sound Check tag counts, or it plays as
  it is. The system volume is on top of that. Playing an album, every
  track gets the same correction, so a quiet intro stays quiet and the
  record keeps its dynamics; in Shuffle and All Tracks each track is
  levelled on its own.
- The notification, the lock screen and headphone buttons control
  playback, and the notification has the heart too; the music keeps
  playing when the app is in the background.
- Audiobooks, radio plays, podcasts and tracks over 20 minutes get
  jumps beside the slider: back 15 seconds, forward 30. The
  notification shows them in place of previous and next. A track counts
  as spoken when it is an `.m4b` file or its genre says so (Audiobook,
  Hörbuch, Spoken Word, Podcast…).
- Audiobooks go on where you left them, also after other music in
  between: Crate keeps the position of each such file, next to the
  library index. Playing it again starts there, playing its album
  starts at the file heard last; a file heard to its end starts from
  the top next time.
- Crate remembers where you stopped: the next launch comes up with the
  same queue, at the same spot, paused.
- Lyrics: when a track has them, an icon appears next to the slider in
  the player bar. Crate looks for an `.lrc` or `.txt` file with the
  track's name beside it or in a `lyrics` folder, then in the track's
  tags. A timed `.lrc` follows the playback.

## Practice

- For playing along: the sliders icon in the player bar opens
  **Practice** for the track playing. The icon is coloured while the
  track does not play as recorded: another tempo, key or tuning, or a
  loop that is on.
- The wave at the top shows the track; tap it to jump there, drag
  across it to loop that passage. The round arrows under it jump 5
  seconds back or on; inside a loop that is on, they stay in the loop.
- Tempo from 50 to 200 % (the key stays), key up to an octave down or
  up in semitones (the tempo stays), and tuning up to 50 cents either
  way, for a recording that is not at A = 440 Hz or an instrument that
  cannot follow it; the A it amounts to is shown. **Original** sets
  tempo, key and tuning back; the loop stays.
- **A** and **B** mark the loop's start and end at the spot playing;
  from then on that passage repeats and shows as a band on the wave.
  With a loop set, A and B move its ends. The repeat symbol turns the
  loop off and on again without losing A and B; it is coloured while
  the loop plays. The × clears it.
- Everything is remembered for that file and comes back the next time
  it plays, also when it is reached with the notification's buttons. It
  is kept on this device next to the library index; the music files are
  not touched.

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
