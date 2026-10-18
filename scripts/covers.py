#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

"""Album covers for a music folder: from MusicBrainz and the Cover Art
Archive, with Apple's iTunes Search as the fallback.

    scripts/covers.py [--dry-run] [--accept] [--no-itunes] <folder>...

`crate-scan --covers` names the albums the app would show without a
cover (no embedded picture in their first tracks, no image in the
folder). For each the script searches MusicBrainz for releases with the
album's artist and title and compares every candidate's track list with
the folder's tracks: titles after normalising, lengths within a few
seconds. A candidate is confirmed when most of the folder's tracks are
found on it and its title matches the album's; the best confirmed one
with a front cover wins (the release group's cover when the release has
none). Albums MusicBrainz does not know, or knows without a cover, go
to the iTunes Search API, checked the same way against its track list.
The result is cover.jpg (or .png) in the album's folder, scaled down to
1200 px with sips, which is where both apps look; audio files are never
touched. A doubtful match is only reported, --accept writes it too.
Albums that share their folder with another album without a cover are
skipped, since one folder image would show for all of them. The app
picks the new covers up on its next rescan.

The JSON answers are cached in ~/.cache/crate-covers, so a second run
asks the services only about what changed. MusicBrainz allows one
request a second; the script keeps to that.
"""

import difflib, hashlib, json, os, re, socket, subprocess, sys, tempfile, time, unicodedata, urllib.error, urllib.parse, urllib.request
from pathlib import Path

# IPv4 before IPv6, as in lyrics.py: urllib waits the full timeout on an
# IPv6 route that swallows SYNs before it tries the next address.
_getaddrinfo = socket.getaddrinfo
def _ipv4_first(*args, **kwargs):
    return sorted(_getaddrinfo(*args, **kwargs), key=lambda info: info[0] != socket.AF_INET)
socket.getaddrinfo = _ipv4_first

USER_AGENT = 'crate covers/1.0 (https://github.com/egdels/crate)'
CACHE = Path.home() / '.cache/crate-covers'
REPO = Path(__file__).resolve().parent.parent
MAX_PIXELS = 1200
MIN_MATCHED = 0.8      # share of the folder's tracks found on a release: confirmed
MIN_DOUBTFUL = 0.5     # below this a candidate is not even reported
MIN_TITLE = 0.7        # album title similarity a candidate needs at all
MIN_TIMED = 0.5        # share of comparable lengths that must agree for a confirmation
MIN_BOOK_TITLE = 0.9   # an audiobook without a chapter list: title similarity needed
MIN_BOOK_AUTHOR = 0.8  # and author similarity
MIN_BONUS = 0.7        # share found that confirms when eight or more lengths all agree
CANDIDATES = 6         # releases per album whose track lists are fetched


# --- web ------------------------------------------------------------------

_last_request = {}
EMPTY_FOR = 7 * 24 * 3600

def empty(result):
    """No resource, or a search that found nothing."""
    return result is None or (isinstance(result, dict) and
                              not any(result.get(k) for k in ('releases', 'results', 'artists')) and
                              any(k in result for k in ('releases', 'results', 'artists', 'count', 'resultCount')))

def fetch(url, pace=0.0, binary=False, cache=True):
    """GET with retries on 503/429; JSON answers are cached by URL, a
    missing resource is cached as None."""
    key = CACHE / (hashlib.sha1(url.encode()).hexdigest() + '.json')
    if cache and not binary and key.exists():
        try:
            result = json.load(open(key))
            # an empty answer is asked again after a week: an album the
            # databases did not know yet may have been added since
            if not empty(result) or time.time() - key.stat().st_mtime < EMPTY_FOR:
                return result
        except ValueError: key.unlink()
    host = urllib.parse.urlparse(url).netloc
    for attempt in range(8):
        wait = _last_request.get(host, 0) + pace - time.monotonic()
        if wait > 0: time.sleep(wait)
        _last_request[host] = time.monotonic()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': USER_AGENT}), timeout=30) as r:
                data = r.read()
            result = data if binary else json.loads(data)
            break
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                result = None
                break
            if e.code not in (429, 500, 502, 503, 504): raise
            time.sleep(1 + attempt)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(2 + attempt)
    else:
        return None   # not cached: the next run tries again
    if cache and not binary:
        CACHE.mkdir(parents=True, exist_ok=True)
        part = key.with_suffix('.part')
        json.dump(result, open(part, 'w'))
        part.replace(key)
    return result

def musicbrainz(path, **query):
    query['fmt'] = 'json'
    return fetch('https://musicbrainz.org/ws/2/' + path + '?' + urllib.parse.urlencode(query), pace=1.1)

def itunes(path, **query):
    return fetch('https://itunes.apple.com/' + path + '?' + urllib.parse.urlencode(query), pace=3.0)


# --- comparing ------------------------------------------------------------

NOISE = re.compile(r'\s*[\(\[][^)\]]*\b(disc|disk|dis|cd|retail|bonus|remaster(ed)?|explicit|edition|version|'
                   r'feat|narrator|ungekürzt|gekürzt|unabridged|abridged)\b[^)\]]*([\)\]]|$)', re.I)
DISC = re.compile(r'\s*[-,:]?\s*\b(cd|disc|disk)\s*#?\s*\d+\b.*$', re.I)

def clean_album(title):
    """The album title without disc numbers and edition notes, which the
    databases keep apart from the title or spell differently."""
    t = NOISE.sub('', title)
    t = DISC.sub('', t)
    t = re.sub(r'^\d{1,2}[_.\s-]+(?=\D)', '', t)        # a folder name: "04_Album Title 2024"
    t = re.sub(r'\s*[\(\[]?(19|20)\d\d[\)\]]?$', '', t)
    t = t.replace('_', ' ')
    return t.strip(' -,:') or title

def fold(text):
    t = unicodedata.normalize('NFKD', text.lower()).replace('ß', 'ss')
    t = ''.join(c for c in t if not unicodedata.combining(c))
    t = re.sub(r'\b(feat|ft)\.?\b.*$', '', t)   # guest credits sit in the title in some tags only
    t = re.sub(r'[\(\[].*?[\)\]]', ' ', t)       # "(Live)", "[Remastered]"
    t = t.replace('&', ' and ')
    t = re.sub(r'^\d{1,2}\s*[-.]\s+', '', t)   # "01 - Song Title"
    t = re.sub(r'^the\s+', '', t)
    return ' '.join(re.sub(r'[^\w]+', ' ', t).split())

def similar(a, b):
    a, b = fold(a or ''), fold(b or '')
    if not a or not b: return 0.0
    if a == b: return 1.0
    if a in b or b in a:
        # "Album" in "Album, Volume 2", but not a two-word title inside a podcast episode's long one
        short, long = sorted((a, b), key=len)
        if len(short) < 4: return 0.5
        return 0.9 if len(short) >= len(long) / 2 else 0.7
    return difflib.SequenceMatcher(None, a, b).ratio()

def album_similarity(local, remote):
    return max(similar(local, remote), similar(clean_album(local), clean_album(remote)))

PLACEHOLDER = re.compile(r'^(track|titel|title|piste|audiotrack|unknown|untitled)?[\s_-]*\d+$', re.I)

def track_similarity(local, remote):
    """Also with the artist cut off the front, as compilations tag
    "Artist - Title"."""
    best = similar(local, remote)
    if ' - ' in local: best = max(best, similar(local.split(' - ', 1)[1], remote))
    return best

def position(track):
    """The track number, or the number in front of a title that is only
    the file name ("03_song.mp3")."""
    if track.get('track'): return track['track']
    m = re.match(r'^(\d{1,2})(?!\d)', track['title'].strip())
    return int(m.group(1)) if m else None

def pair(local_tracks, remote_tracks):
    """For each of the folder's tracks the index of its track on the
    release, or None: a title that matches, a title that roughly matches
    at the same length, or, for a placeholder title such as "Track-01"
    or a file without a title tag, the same position at the same length.
    remote_tracks holds (title, seconds, disc, number)."""
    pairs, used = [], set()
    for track in local_tracks:
        placeholder = bool(PLACEHOLDER.match(track['title'].strip()))
        untitled = 'file' in track and track['title'] == Path(track['file']).stem
        place = position(track)
        best, best_score = None, 0.0
        for i, (title, length, disc, number) in enumerate(remote_tracks):
            if i in used: continue
            # a far longer or shorter track is another piece whatever its title:
            # a whole episode in one file is not the release's "Part 1"
            if length and track['duration'] and \
                    abs(length - track['duration']) > max(30, 0.2 * max(length, track['duration'])): continue
            close = length and track['duration'] and abs(length - track['duration']) <= 5
            same_place = place is not None and number == place and disc == (track.get('disc') or 1) \
                and length and track['duration'] and abs(length - track['duration']) <= 3
            if placeholder:
                sim = 1.0 if same_place else 0.0
            else:
                sim = track_similarity(track['title'], title)
                if untitled and same_place: sim = 1.0
            score = sim + (0.3 if close else 0)
            if (sim >= 0.8 or (sim >= 0.5 and close)) and score > best_score:
                best, best_score = i, score
        if best is not None: used.add(best)
        pairs.append(best)
    return pairs

def match(local_tracks, remote_tracks):
    """Share of the folder's tracks found on the release, the number of
    matched tracks whose lengths agree within 5 s, the number of matched
    tracks with a length on both sides, and the release's track count.
    A folder of one file that lasts as long as the whole release (an
    audio play ripped as a single track) counts as found."""
    if len(local_tracks) == 1 and len(remote_tracks) > 1 and all(r[1] for r in remote_tracks):
        total, length = sum(r[1] for r in remote_tracks), local_tracks[0]['duration']
        if length and abs(total - length) <= max(10, total * 0.01): return 1.0, 1, 1, len(remote_tracks)
    found, timed, comparable = 0, 0, 0
    for track, best in zip(local_tracks, pair(local_tracks, remote_tracks)):
        if best is None: continue
        found += 1
        length = remote_tracks[best][1]
        if length and track['duration']:
            comparable += 1
            if abs(length - track['duration']) <= 5: timed += 1
    return found / max(len(local_tracks), 1), timed, comparable, len(remote_tracks)

def confidence(share, timed, comparable):
    """The share of tracks found, capped below a confirmation when the
    lengths that can be compared mostly disagree: the same song titles
    on another release (a single, a compilation, a podcast) are not the
    album. Raised to a confirmation when at least eight tracks were
    found, 70 % of the folder, all at their release's length: a folder
    with a few bonus tracks."""
    if comparable and timed < comparable * MIN_TIMED: return min(share, MIN_MATCHED - 0.01)
    if share >= MIN_BONUS and comparable >= 8 and timed == comparable: return max(share, MIN_MATCHED)
    return share


# --- MusicBrainz ----------------------------------------------------------

def lucene(text):
    return re.sub(r'([+\-&|!(){}\[\]^"~*?:\\/])', r'\\\1', text)

def musicbrainz_candidates(album):
    title, artist = clean_album(album['title']), album['artist']
    terms = [f'release:"{lucene(title)}"']
    if artist not in ('Unknown Artist',):
        terms.append(f'artist:"{lucene(artist)}"')
    found = musicbrainz('release', query=' AND '.join(terms), limit=25) or {}
    releases = found.get('releases') or []
    if not releases and artist != 'Unknown Artist':
        # a looser query: the words without phrase quotes
        found = musicbrainz('release', query=f'release:({lucene(title)}) AND artist:({lucene(artist)})', limit=25) or {}
        releases = found.get('releases') or []
    count = len(album['tracks'])
    releases = [r for r in releases if album_similarity(album['title'], r.get('title', '')) >= MIN_TITLE]
    # a release with far fewer tracks than the folder cannot be it (a few
    # bonus tracks are fine); among the rest prefer the search's score,
    # then albums over singles (a folder with one track of an album found
    # the single first), then official releases, then a track count close
    # to the folder's
    releases = [r for r in releases if (r.get('track-count') or 0) >= count * MIN_BONUS]
    def rank(r):
        kind = (r.get('release-group') or {}).get('primary-type')
        return (-(r.get('score') or 0) // 10, kind != 'Album', r.get('status') != 'Official',
                abs((r.get('track-count') or 0) - count))
    releases.sort(key=rank)
    return releases[:CANDIDATES]

def musicbrainz_cover(album):
    """(confidence, description, image URLs to try) of the best release."""
    best = None
    for release in musicbrainz_candidates(album):
        full = musicbrainz(f'release/{release["id"]}', inc='recordings')
        if not full: continue
        remote = [(t.get('title', ''), (t.get('length') or 0) / 1000, medium.get('position') or 1, t.get('position'))
                  for medium in full.get('media', []) for t in medium.get('tracks') or []]
        share, timed, comparable, total = match(album['tracks'], remote)
        share = confidence(share, timed, comparable)
        art = full.get('cover-art-archive') or {}
        group = (full.get('release-group') or release.get('release-group') or {}).get('id')
        urls = []
        if art.get('front'):
            urls += [f'https://coverartarchive.org/release/{release["id"]}/front-{s}' for s in (1200, 500)]
            urls.append(f'https://coverartarchive.org/release/{release["id"]}/front')
        elif group:
            urls += [f'https://coverartarchive.org/release-group/{group}/front-{s}' for s in (1200, 500)]
            urls.append(f'https://coverartarchive.org/release-group/{group}/front')
        key = (share, bool(art.get('front')), timed, -abs(total - len(album['tracks'])))
        what = f'MusicBrainz {release["id"]} "{full.get("title")}" ({full.get("date", "?")}, {full.get("country", "?")}), ' \
               f'{share:.0%} of tracks, {timed} same length, {total} on release'
        if best is None or key > best[0]:
            best = (key, share, what, urls)
        if share == 1 and art.get('front') and timed == len(album['tracks']): break
    return (best[1], best[2], best[3]) if best else None


# --- iTunes ---------------------------------------------------------------

def itunes_candidates(album):
    """(album result, its songs, remote tracks) for the iTunes albums
    whose title and artist fit; the songs carry track names, lengths and
    numbers."""
    term = clean_album(album['title'])
    if album['artist'] not in ('Unknown Artist', 'Various Artists'): term = album['artist'] + ' ' + term
    found = (itunes('search', term=term, media='music', entity='album', limit=10) or {}).get('results') or []
    for result in found:
        if album_similarity(album['title'], result.get('collectionName', '')) < MIN_TITLE: continue
        if album['artist'] not in ('Unknown Artist', 'Various Artists') and \
                similar(album['artist'], result.get('artistName', '')) < 0.6 and \
                album['artist'].lower() not in result.get('collectionName', '').lower():   # "Album (feat. Artist)"
            continue
        if (result.get('trackCount') or 0) < len(album['tracks']) * MIN_BONUS: continue
        songs = (itunes('lookup', id=result['collectionId'], entity='song') or {}).get('results') or []
        songs = [s for s in songs if s.get('wrapperType') == 'track']
        remote = [(s.get('trackName', ''), (s.get('trackTimeMillis') or 0) / 1000, s.get('discNumber') or 1, s.get('trackNumber'))
                  for s in songs]
        yield result, songs, remote

def itunes_cover(album):
    best = None
    for result, songs, remote in itunes_candidates(album):
        if not result.get('artworkUrl100'): continue
        share, timed, comparable, total = match(album['tracks'], remote)
        share = confidence(share, timed, comparable)
        art = result['artworkUrl100']
        urls = [art.replace('100x100bb', f'{MAX_PIXELS}x{MAX_PIXELS}bb'), art.replace('100x100bb', '600x600bb')]
        what = f'iTunes {result["collectionId"]} "{result.get("collectionName")}" ({(result.get("releaseDate") or "?")[:4]}), ' \
               f'{share:.0%} of tracks, {timed} same length, {total} on release'
        key = (share, timed)
        if best is None or key > best[0]:
            best = (key, share, what, urls)
        if share == 1: break
    return (best[1], best[2], best[3]) if best else None


def itunes_audiobook_cover(album):
    """An audiobook from iTunes' audiobook catalogue. It lists no chapters,
    so the check is the title and the author alone, both strictly."""
    if album['artist'] in ('Unknown Artist', 'Various Artists'): return None
    term = album['artist'] + ' ' + clean_album(album['title'])
    for country in ('DE', 'US'):
        found = (itunes('search', term=term, media='audiobook', entity='audiobook', limit=10, country=country) or {}).get('results') or []
        for result in found:
            if not result.get('artworkUrl100'): continue
            name = clean_album(result.get('collectionName', ''))
            # "Title - Series, Band 1": the title alone counts too
            if max(album_similarity(album['title'], name), album_similarity(album['title'], name.split(' - ')[0])) < MIN_BOOK_TITLE:
                continue
            if similar(album['artist'], result.get('artistName', '')) < MIN_BOOK_AUTHOR: continue
            art = result['artworkUrl100']
            urls = [art.replace('100x100bb', f'{MAX_PIXELS}x{MAX_PIXELS}bb'), art.replace('100x100bb', '600x600bb')]
            what = f'iTunes audiobook {result["collectionId"]} "{result.get("collectionName")}" by {result.get("artistName")} ' \
                   f'(title and author only)'
            return 1.0, what, urls
    return None


# --- files ----------------------------------------------------------------

def missing_covers(folder):
    subprocess.run(['swift', 'build', '-c', 'release', '--product', 'crate-scan'], cwd=REPO, check=True, capture_output=True)
    out = subprocess.run([str(REPO / '.build/release/crate-scan'), str(folder), '--covers', '--concurrency', '8'],
                         check=True, capture_output=True, text=True)
    sys.stderr.write(out.stderr)
    return json.loads(out.stdout)

def save(image, folder):
    """cover.jpg or cover.png in the folder, no bigger than MAX_PIXELS."""
    ext = '.png' if image[:8] == b'\x89PNG\r\n\x1a\n' else '.jpg'
    if ext == '.jpg' and image[:3] != b'\xff\xd8\xff': raise ValueError('neither JPEG nor PNG')
    target = Path(folder) / ('cover' + ext)
    if target.exists(): raise ValueError(f'{target.name} is already there')
    with tempfile.TemporaryDirectory() as tmp:
        part = Path(tmp) / ('cover' + ext)
        part.write_bytes(image)
        subprocess.run(['sips', '-Z', str(MAX_PIXELS), str(part)], check=True, capture_output=True)
        # sips grows nothing: -Z only scales an image down to the bound
        target.write_bytes(part.read_bytes())
    return target

def download(urls):
    for url in urls:
        image = fetch(url, binary=True, cache=False)
        if image and len(image) > 1000: return image
    return None


def main():
    sys.stdout.reconfigure(line_buffering=True)   # progress shows in a log file too
    args = sys.argv[1:]
    dry = '--dry-run' in args
    accept = '--accept' in args
    use_itunes = '--no-itunes' not in args
    folders = [Path(a) for a in args if not a.startswith('--')]
    if not folders:
        print(__doc__)
        sys.exit(2)
    counts = {}
    def tally(kind): counts[kind] = counts.get(kind, 0) + 1
    for root in folders:
        albums = missing_covers(root)
        per_folder = {}
        for album in albums: per_folder[album['folder']] = per_folder.get(album['folder'], 0) + 1
        for n, album in enumerate(albums, 1):
            name = f'[{n}/{len(albums)}] {album["artist"]} — {album["title"]} ({len(album["tracks"])} tracks)'
            if per_folder[album['folder']] > 1:
                print(f'{name}: shares its folder with another album without a cover, skipped')
                tally('shared folder')
                continue
            found = musicbrainz_cover(album)
            if use_itunes and (not found or found[0] < MIN_MATCHED or not found[2]):
                other = itunes_cover(album)
                if other and (not found or other[0] > found[0] or not found[2]): found = other
            if use_itunes and (not found or found[0] < MIN_DOUBTFUL or not found[2]):
                found = itunes_audiobook_cover(album) or found
            if not found or found[0] < MIN_DOUBTFUL or not found[2]:
                print(f'{name}: not found' + (f' (best: {found[1]})' if found else ''))
                tally('not found')
                continue
            share, what, urls = found
            confirmed = share >= MIN_MATCHED
            if not confirmed and not accept:
                print(f'{name}: doubtful, not written: {what}')
                tally('doubtful')
                continue
            if dry:
                print(f'{name}: {"confirmed" if confirmed else "doubtful, accepted"}: {what}')
                tally('confirmed' if confirmed else 'accepted')
                continue
            image = download(urls)
            if not image and use_itunes and what.startswith('MusicBrainz'):
                # neither the release nor its group has a cover in the archive
                other = itunes_cover(album)
                if other and other[2] and (other[0] >= MIN_MATCHED or accept):
                    share, what, urls = other
                    confirmed = share >= MIN_MATCHED
                    image = download(urls)
            if not image:
                print(f'{name}: matched, but no cover to download (Cover Art Archive, iTunes): {what}')
                tally('download failed')
                continue
            try:
                target = save(image, album['folder'])
            except (ValueError, OSError, subprocess.CalledProcessError) as e:
                print(f'{name}: could not save the image ({e})')
                tally('save failed')
                continue
            print(f'{name}: {target.name} written, {"confirmed" if confirmed else "doubtful, accepted"}: {what}')
            tally('confirmed' if confirmed else 'accepted')
    print()
    print(', '.join(f'{v} {k}' for k, v in sorted(counts.items(), key=lambda kv: -kv[1])) + (' (dry run)' if dry else ''))


if __name__ == '__main__':
    main()
