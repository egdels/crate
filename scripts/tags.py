#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

"""Tag repair for a music folder: gaps filled and broken values fixed,
locally and from MusicBrainz, with a journal to undo it.

    scripts/tags.py --dry-run <folder>...   # the report: every change, old -> new
    scripts/tags.py <folder>...             # write, journal in ~/.cache/crate-tags
    scripts/tags.py --local <folder>...     # only the local repairs, no MusicBrainz or iTunes
    scripts/tags.py --no-itunes <folder>... # MusicBrainz only
    scripts/tags.py --undo <journal.json>   # put every old value back

`crate-scan --json` reads the folder the way the app does (tags, album
grouping). Two kinds of repair follow, each only where a value is
missing or provably broken; a value that is merely spelled differently
from MusicBrainz stays.

Local: an artist written several ways (ARTIST / Artist, Band /
The Band) gets its most common properly cased spelling; a title that
carries its track number or artist in front ("01 - Song Title",
"Artist - Song Title") loses them; an
album of several tracks without any album artist gets the artist the
grouping found.

MusicBrainz, then iTunes: an album confirmed as in scripts/covers.py
(80 % of its tracks found on a release, most lengths within 5 s, or 70 %
when eight or more lengths all agree) gets what it lacks from that
release: year (the release group's first release), album
artist, track and disc numbers, missing album and artist, and real
titles for placeholders ("Track-01"), missing title tags and values cut
at ID3v1's 30 characters (only when the release's value begins with
the cut one). iTunes is asked only when MusicBrainz confirms nothing;
its "(feat. ...)" suffixes go, its year is that release's own, and its
album artist is taken only when it is the album's artist.

Audio is never touched: mutagen rewrites the tag in place, ID3 stays
v2.3 unless the file had v2.4. WAV files are left alone. Writing
changes the files' modification dates, so the apps re-read them on the
next rescan and measure their loudness again. mutagen lives in a venv
under ~/.cache/crate-tags, made on the first run.
"""

import collections, json, os, re, subprocess, sys, time, unicodedata
from pathlib import Path

HOME = Path.home()
STATE = HOME / '.cache/crate-tags'
VENV = STATE / 'venv'

def ensure_mutagen():
    """Homebrew's Python refuses global packages; a venv of our own,
    made once, then the script runs again inside it."""
    try:
        import mutagen  # noqa: F401
        return
    except ImportError:
        pass
    if os.environ.get('CRATE_TAGS_VENV'):
        sys.exit('mutagen is missing even in the venv; remove ' + str(VENV) + ' and try again')
    python = VENV / 'bin/python3'
    if not python.exists():
        print('making a venv with mutagen in', VENV, file=sys.stderr)
        subprocess.run([sys.executable, '-m', 'venv', str(VENV)], check=True)
        subprocess.run([str(python), '-m', 'pip', 'install', '-q', 'mutagen'], check=True)
    os.environ['CRATE_TAGS_VENV'] = '1'
    os.execv(str(python), [str(python), str(Path(__file__).resolve())] + sys.argv[1:])

ensure_mutagen()
import mutagen
from mutagen.easyid3 import EasyID3
from mutagen.id3 import ID3, ID3NoHeaderError

sys.path.insert(0, str(Path(__file__).resolve().parent))
import covers
from covers import PLACEHOLDER, MIN_MATCHED, fold, similar, pair, match, confidence, musicbrainz, musicbrainz_candidates, itunes_candidates

FIELDS = ('title', 'artist', 'album', 'albumartist', 'tracknumber', 'discnumber', 'date')
SCAN_KEY = {'title': 'title', 'artist': 'artist', 'album': 'album', 'albumartist': 'albumArtist',
            'tracknumber': 'track', 'discnumber': 'disc', 'date': 'year'}
ID3V1 = 30   # ID3v1 field length: a value of exactly this length may be cut


# --- reading --------------------------------------------------------------

def scan(folder):
    subprocess.run(['swift', 'build', '-c', 'release', '--product', 'crate-scan'], cwd=covers.REPO, check=True, capture_output=True)
    out = subprocess.run([str(covers.REPO / '.build/release/crate-scan'), str(folder), '--json', '--concurrency', '8'],
                         check=True, capture_output=True, text=True)
    sys.stderr.write(out.stderr)
    return json.loads(out.stdout)

def value(track, field):
    v = track.get(SCAN_KEY[field])
    return None if v in (None, '') else v

def file_number(track):
    """The number in front of the file name ("07_song.mp3"), or None."""
    m = re.match(r'^(\d{1,3})(?!\d)', Path(track['file']).name)
    return int(m.group(1)) if m else None

def untitled(track):
    """No title tag: the scanner fell back to the file name."""
    return track['title'] == Path(track['file']).stem


# --- local repairs --------------------------------------------------------

def artist_key(name):
    t = unicodedata.normalize('NFKC', name).casefold()
    t = re.sub(r',\s*the$', '', t)
    t = re.sub(r'^the\s+', '', t)
    t = t.replace('&', ' and ')
    return ' '.join(re.sub(r'[^\w]+', ' ', t).split())

def artist_spellings(albums):
    """{spelling: preferred spelling} for artists written several ways:
    a properly cased one before ALL CAPS or all lower case, then one
    without capitalised small words inside ("Smith & the Band", not
    "Smith and The Band"), then the most common, then the one
    with "The"."""
    counts = collections.Counter()
    for album in albums:
        for track in album['tracks']:
            for field in ('artist', 'albumartist'):
                if value(track, field): counts[value(track, field)] += 1
    groups = collections.defaultdict(list)
    for name in counts: groups[artist_key(name)].append(name)
    preferred = {}
    for names in groups.values():
        if len(names) < 2: continue
        cased = lambda n: not (n.isupper() or n.islower())
        small = lambda n: not re.search(r'\s(And|The|Of|A|An|In|On|To)\s', n)
        best = max(names, key=lambda n: (cased(n), small(n), counts[n], n.lower().startswith('the ')))
        for n in names:
            if n != best: preferred[n] = best
    return preferred

NUMBERED = re.compile(r'^(\d{1,2})\s*(?:[-.]|\s)\s*(\S.*)$')

def clean_title(track, album):
    """(title, track number or None) with leading artist, album and
    number parts removed, or None when there is nothing to remove."""
    title = track['title']
    if untitled(track): return None
    names = {fold(n) for n in (value(track, 'artist'), value(track, 'albumartist'), album['artist']) if n}
    names |= {fold(re.sub(r'^(.*), the$', r'the \1', n, flags=re.I)) for n in list(names)}
    albums = {fold(n) for n in (value(track, 'album'), album['title']) if n}
    parts = title.split(' - ')
    number, dropped, dropped_artist = None, 0, False
    while len(parts) > 1:
        head = parts[0].strip()
        bare = fold(re.sub(r',\s*the$', '', head, flags=re.I))   # "Band, The"
        if bare and bare in names:
            dropped_artist = True
        elif dropped_artist and fold(head) in albums:
            pass
        elif re.fullmatch(r'\d{1,2}', head):
            number = int(head)
        else:
            break
        parts.pop(0)
        dropped += 1
    rest = ' - '.join(parts).strip()
    m = NUMBERED.match(rest) if re.match(r'^\d{1,2}\s*[-.]\s', rest) else None
    if m:
        number, rest = int(m.group(1)), m.group(2).strip()
        dropped += 1
    if not dropped or not rest: return None
    if number is not None and rest[0].isdigit(): return None   # "2 - 4 - 6 Song"
    known = value(track, 'tracknumber')
    if number is not None and known is not None and number != known:
        return None   # a number that is not the track's: part of the title, leave it
    return rest, number

def local_changes(albums, propose):
    spellings = artist_spellings(albums)
    for album in albums:
        for track in album['tracks']:
            for field in ('artist', 'albumartist'):
                old = value(track, field)
                if old in spellings:
                    propose(track, field, old, spellings[old], 'spelling used elsewhere')
            cleaned = clean_title(track, album)
            if cleaned:
                title, number = cleaned
                propose(track, 'title', track['title'], title, 'number/artist in front')
                if number is not None and value(track, 'tracknumber') is None:
                    propose(track, 'tracknumber', None, number, 'number from the title')
        # only an artist every track's tag names: the grouping's "Various
        # Artists" or a folder name for untagged files is no tag to write
        artists = {artist_key(value(t, 'artist') or '') for t in album['tracks']}
        if len(album['tracks']) > 1 and len(artists) == 1 and '' not in artists \
                and artist_key(album['artist']) in artists \
                and not any(value(t, 'albumartist') for t in album['tracks']):
            artist = spellings.get(album['artist'], album['artist'])
            for track in album['tracks']:
                propose(track, 'albumartist', None, artist, 'artist the album agrees on', weak=True)


# --- MusicBrainz and iTunes ----------------------------------------------

def credit(credits):
    return ''.join(c.get('name', '') + c.get('joinphrase', '') for c in credits or []) or None

def cut(local, remote):
    """local is remote cut at ID3v1's length."""
    if not local or not remote or not ID3V1 - 2 <= len(local) <= ID3V1: return False
    squash = lambda s: re.sub(r'[^\w]', '', unicodedata.normalize('NFKC', s).casefold())
    # the release's value must hold more than the cut one, not only other punctuation
    return len(squash(remote)) > len(squash(local)) and squash(remote).startswith(squash(local))

FEAT = re.compile(r'\s*[\(\[](feat|ft|with)\.?\s[^)\]]*[\)\]]', re.I)

def release_from_musicbrainz(album):
    """The best confirmed MusicBrainz release as a dict of what the
    repairs need, or None."""
    best = None
    for candidate in musicbrainz_candidates(album):
        full = musicbrainz(f'release/{candidate["id"]}', inc='recordings artist-credits release-groups')
        if not full: continue
        remote, artists = [], []
        for medium in full.get('media', []):
            for t in medium.get('tracks') or []:
                remote.append((t.get('title', ''), (t.get('length') or 0) / 1000, medium.get('position') or 1, t.get('position')))
                artists.append(credit(t.get('artist-credit') or (t.get('recording') or {}).get('artist-credit')))
        share, timed, comparable, total = match(album['tracks'], remote)
        share = confidence(share, timed, comparable)
        key = (share, timed, -abs(total - len(album['tracks'])))
        if best is None or key > best[0]:
            group = full.get('release-group') or {}
            best = (key, share, {
                'source': 'MusicBrainz', 'what': f'MusicBrainz {full["id"]} "{full.get("title")}"',
                'title': full.get('title'), 'year': (group.get('first-release-date') or full.get('date') or '')[:4],
                'year_reason': 'MusicBrainz, first release', 'album_artist': credit(full.get('artist-credit')),
                'several_discs': len(full.get('media', [])) > 1, 'remote': remote, 'artists': artists})
        if share == 1 and timed == comparable == len(album['tracks']): break
    if not best or best[1] < MIN_MATCHED: return None
    return dict(best[2], share=best[1])

def release_from_itunes(album):
    """The best confirmed iTunes album the same way. Its names carry
    guests as "(feat. ...)", which goes; its album artist is used only
    when it is the album's own artist (an album credited to "Other
    feat. Artist" stays under Artist)."""
    best = None
    for result, songs, remote in itunes_candidates(album):
        share, timed, comparable, total = match(album['tracks'], remote)
        share = confidence(share, timed, comparable)
        key = (share, timed)
        if best is None or key > best[0]:
            artist = result.get('artistName')
            title = re.sub(r'\s+-\s+(EP|Single)$', '', FEAT.sub('', result.get('collectionName') or ''))
            best = (key, share, {
                'source': 'iTunes', 'what': f'iTunes {result["collectionId"]} "{result.get("collectionName")}"',
                'title': title, 'year': (result.get('releaseDate') or '')[:4], 'year_reason': 'iTunes, release',
                'album_artist': artist if artist and similar(album['artist'], artist) >= 0.6 else None,
                'several_discs': (result.get('discCount') or 1) > 1 if 'discCount' in result else
                                 len({r[2] for r in remote}) > 1,
                'remote': [(FEAT.sub('', t), length, disc, number) for t, length, disc, number in remote],
                'artists': [s.get('artistName') for s in songs]})
        if share == 1: break
    if not best or best[1] < MIN_MATCHED: return None
    return dict(best[2], share=best[1])

def release_changes(album, propose, use_itunes=True):
    """Gaps and broken values filled from the album's confirmed release,
    MusicBrainz first, iTunes when MusicBrainz confirms none."""
    release = release_from_musicbrainz(album) or (release_from_itunes(album) if use_itunes else None)
    if not release: return None
    source, remote = release['source'], release['remote']
    pairs = pair(album['tracks'], remote)
    # the release's numbering only for a folder that is part of it: one
    # with tracks the release lacks has its own order
    numbered = None not in pairs
    for track, index in zip(album['tracks'], pairs):
        old_album = value(track, 'album')
        if old_album is None:
            propose(track, 'album', None, release['title'], source)
        elif cut(old_album, release['title']):
            propose(track, 'album', old_album, release['title'], 'cut at 30 characters')
        if value(track, 'albumartist') is None and release['album_artist']:
            propose(track, 'albumartist', None, release['album_artist'], source)
        if value(track, 'date') is None and release['year'].isdigit():
            propose(track, 'date', None, int(release['year']), release['year_reason'])
        if index is None: continue
        title, _, disc, number = remote[index]
        if PLACEHOLDER.match(track['title'].strip()):
            propose(track, 'title', track['title'], title, 'placeholder title')
        elif untitled(track) and track['title'] != title:
            propose(track, 'title', None, title, 'no title tag')
        elif cut(track['title'], title):
            propose(track, 'title', track['title'], title, 'cut at 30 characters')
        artist = release['artists'][index]
        old_artist = value(track, 'artist')
        if old_artist is None and artist:
            propose(track, 'artist', None, artist, source)
        elif cut(old_artist, artist):
            propose(track, 'artist', old_artist, artist, 'cut at 30 characters')
        named = file_number(track)
        if value(track, 'tracknumber') is None and number and numbered and named in (None, number):
            propose(track, 'tracknumber', None, number, source)
        if value(track, 'discnumber') is None and release['several_discs']:
            propose(track, 'discnumber', None, disc, source)
    return f'{release["what"]}, {release["share"]:.0%} of tracks'


# --- writing --------------------------------------------------------------

LEFT_ALONE = ('.wav', '.aif', '.aiff')
# MusicBrainz writes typographic quotes ("Can’t"); the collections use
# the plain ones, and the apps' search folds case and accents, not these
QUOTES = str.maketrans({'’': "'", '‘': "'", '“': '"', '”': '"'})

def disguised(path):
    """An MP4 named .mp3: mutagen tags it as MP4, which the apps, going
    by the name, never read."""
    if Path(path).suffix.lower() != '.mp3': return False
    with open(path, 'rb') as f: head = f.read(12)
    return head[4:8] == b'ftyp'

def open_tags(path):
    """mutagen's easy interface, or None for a format left alone."""
    if Path(path).suffix.lower() in LEFT_ALONE: return None
    audio = mutagen.File(path, easy=True)
    if audio is None: return None
    if audio.tags is None: audio.add_tags()
    return audio

def save(audio):
    if isinstance(audio.tags, EasyID3):
        # keep v2.4 where it is, else v2.3, which every player reads; the
        # easy interface hides the version, the file on disk still has it
        try:
            version = ID3(audio.filename).version
        except ID3NoHeaderError:
            version = (2, 3, 0)
        audio.save(v2_version=4 if version >= (2, 4, 0) else 3)
    else:
        audio.save()

def as_tag(field, v):
    return str(v)

def write(changes, journal_path):
    journal = []
    written = 0
    for path, fields in changes.items():
        try:
            audio = open_tags(path)
            if audio is None:
                print(f'skipped (format left alone): {path}')
                continue
            before = {f: (list(audio[f]) if f in audio else None) for f in fields}
            for field, (old, new, reason) in fields.items():
                audio[field] = [as_tag(field, new)]
            save(audio)
            journal.append({'file': path, 'before': before, 'after': {f: [as_tag(f, n)] for f, (_, n, _) in fields.items()}})
            written += 1
        except Exception as e:   # one damaged file must not stop the run
            print(f'could not write {path}: {e}')
            continue
        if written % 50 == 0: dump(journal, journal_path)
    dump(journal, journal_path)
    return written

def dump(journal, path):
    part = path.with_suffix('.part')
    json.dump(journal, open(part, 'w'), ensure_ascii=False, indent=1)
    part.replace(path)

def undo(journal_path):
    journal = json.load(open(journal_path))
    restored = 0
    for entry in reversed(journal):
        try:
            audio = open_tags(entry['file'])
            for field, old in entry['before'].items():
                if old is None:
                    if field in audio: del audio[field]
                else:
                    audio[field] = old
            save(audio)
            restored += 1
        except Exception as e:
            print(f'could not restore {entry["file"]}: {e}')
    print(f'restored {restored} of {len(journal)} files')


# --- main -----------------------------------------------------------------

def main():
    sys.stdout.reconfigure(line_buffering=True)
    args = sys.argv[1:]
    if '--undo' in args:
        undo(Path(args[args.index('--undo') + 1]))
        return
    dry = '--dry-run' in args
    local_only = '--local' in args
    use_itunes = '--no-itunes' not in args
    folders = [Path(a) for a in args if not a.startswith('--')]
    if not folders:
        print(__doc__)
        sys.exit(2)
    changes = {}          # file -> {field: (old, new, reason)}
    tally = collections.Counter()
    for root in folders:
        albums = scan(root)

        def propose(track, field, old, new, reason, weak=False):
            if isinstance(new, str): new = new.translate(QUOTES)
            if new is None or old == new: return
            if Path(track['file']).suffix.lower() in LEFT_ALONE or disguised(track['file']): return
            fields = changes.setdefault(track['file'], {})
            if weak and field in fields: return
            if field in fields and not weak and fields[field][2] == 'artist the album agrees on':
                pass   # MusicBrainz's album artist beats the local guess
            elif field in fields:
                return
            fields[field] = (old, new, reason)

        local_changes(albums, propose)
        for n, album in enumerate(albums, 1):
            source = None
            if not local_only:
                source = release_changes(album, propose, use_itunes)
            lines = []
            for track in album['tracks']:
                for field, (old, new, reason) in changes.get(track['file'], {}).items():
                    lines.append(f'    {Path(track["file"]).name}: {field}: {old if old is not None else "–"} → {new}  ({reason})')
                    tally[f'{field} ({reason})'] += 1
            if lines:
                print(f'[{n}/{len(albums)}] {album["artist"]} — {album["title"]}' + (f'  [{source}]' if source else ''))
                print('\n'.join(lines))
    print()
    print(f'{sum(len(f) for f in changes.values())} changes in {len(changes)} files:')
    for what, count in tally.most_common(): print(f'  {count:5}  {what}')
    if dry or not changes:
        if dry: print('(dry run, nothing written)')
        return
    STATE.mkdir(parents=True, exist_ok=True)
    journal = STATE / time.strftime('journal-%Y%m%d-%H%M%S.json')
    written = write(changes, journal)
    print(f'wrote {written} files; to undo: scripts/tags.py --undo {journal}')


if __name__ == '__main__':
    main()
