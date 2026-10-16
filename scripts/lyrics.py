#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

"""Lyrics for a music folder: text from lrclib.net, time stamps checked or
made with whisper.cpp.

    scripts/lyrics.py [--dry-run] [--out DIR] [--model FILE] <folder or files>...
    scripts/lyrics.py --check <folder or files>...     # only judge the lyrics files already there
    scripts/lyrics.py --replace <folder or files>...   # fetch anew for files whose own lyrics failed the check

For every audio file without lyrics (none in its tags, no .lrc/.txt
beside it or in a lyrics/ subfolder) the script asks lrclib.net. Timed
lyrics are checked against a whisper transcript of the file: the known
text is aligned to whisper's words, and the stamps count as confirmed
when most lines land within a second of the aligned time (after a
constant offset, since LRC stamps run a little ahead of the singing by
convention). Plain text gets stamps from that alignment when enough of
its words were heard, else it is kept as text. The result goes to
lyrics/<name>.lrc or .txt next to the file, which is where Crate looks.
A lyrics file that is already there is judged the same way and reported
(a lyrics downloader once filed an unrelated song under a track's
title); only --replace removes one that failed the check, when
the new lyrics are heard better, and swaps a plain .txt for a confirmed
.lrc of the same song. The app itself never goes online.

Needs ffmpeg, whisper-cli (brew install whisper-cpp) and a ggml model,
by default ~/.cache/whisper-cpp/ggml-large-v3-turbo.bin from
https://huggingface.co/ggerganov/whisper.cpp. Whisper transcripts are
cached in ~/.cache/crate-lyrics so a second run is quick.
"""

import difflib, hashlib, json, os, re, socket, statistics, subprocess, sys, tempfile, time, unicodedata, urllib.error, urllib.parse, urllib.request
from pathlib import Path

# IPv4 before IPv6: urllib tries the addresses in order and waits the full
# timeout on each, and on this network the IPv6 route to the database's
# host (Cloudflare) swallowed every SYN, which made each request a 20 s
# wait before the IPv4 fallback, every retry included.
_getaddrinfo = socket.getaddrinfo
def _ipv4_first(*args, **kwargs):
    return sorted(_getaddrinfo(*args, **kwargs), key=lambda info: info[0] != socket.AF_INET)
socket.getaddrinfo = _ipv4_first

AUDIO = {'.mp3', '.m4a', '.m4b', '.flac', '.wav', '.aif', '.aiff', '.ogg', '.opus'}
USER_AGENT = 'crate lyrics (https://github.com/egdels/crate)'
HOME = Path.home()
DEFAULT_MODEL = HOME / '.cache/whisper-cpp/ggml-large-v3-turbo.bin'
CACHE = HOME / '.cache/crate-lyrics'
STAMP = re.compile(r'\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]')
MIN_CONFIRMED = 0.7    # share of checkable lines within a second: stamps confirmed
MIN_ANCHORED = 0.7     # share of lines with a heard word: stamps good enough to write
MIN_CHECKED = 5        # lines whose first word was heard, below which a check says nothing
MIN_HEARD_OWN = 0.3    # share of lines heard, below which an existing lyrics file is the wrong song


# --- lrclib ---------------------------------------------------------------

def lrclib(path, **query):
    url = 'https://lrclib.net/api/' + path + '?' + urllib.parse.urlencode(query)
    for _ in range(15):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': USER_AGENT}), timeout=10) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404: return None
            if e.code not in (429, 503): raise
            time.sleep(0.7)   # the database answers a third of all requests with 503 whatever the pace
        except (urllib.error.URLError, TimeoutError):
            time.sleep(2)
    return None

def look_up(tags, duration):
    """Exact match first; a search when that gives no timed lyrics, so a
    timed version from another release of the recording can win."""
    artist, title = tags.get('artist') or tags.get('album_artist'), tags.get('title')
    if not artist or not title: return None
    exact = lrclib('get', artist_name=artist, track_name=title, album_name=tags.get('album', ''), duration=round(duration))
    if exact and (exact.get('instrumental') or exact.get('syncedLyrics')): return exact
    found = lrclib('search', artist_name=artist, track_name=title) or []
    close = [r for r in found if abs((r.get('duration') or -100) - duration) <= 3]
    timed = [r for r in close if r.get('syncedLyrics')]
    return timed[0] if timed else exact or next((r for r in close if r.get('plainLyrics')), None)


# --- files ----------------------------------------------------------------

def probe(file):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:format_tags', '-of', 'json', str(file)],
                         capture_output=True, text=True, check=True).stdout
    fmt = json.loads(out)['format']
    tags = {k.lower(): v for k, v in fmt.get('tags', {}).items()}
    return tags, float(fmt['duration'])

def sidecar(file):
    """The file's own lyrics file beside it or in a lyrics/ subfolder, an
    .lrc before a .txt: (path, text) or None."""
    for ext in ('.lrc', '.txt'):
        for sub in ('', 'lyrics', 'Lyrics'):
            candidate = file.parent / sub / (file.stem + ext)
            if candidate.exists(): return candidate, candidate.read_text(errors='replace')
    return None

def has_lyrics(file, tags):
    if any('lyrics' in k for k in tags): return True
    base, folder = file.stem, file.parent
    for sub in ('', 'lyrics', 'Lyrics'):
        for ext in ('.lrc', '.txt'):
            if (folder / sub / (base + ext)).exists(): return True
    return False


# --- whisper --------------------------------------------------------------

def transcribe(file, model, language, prompt=None):
    """Whisper's words with DTW times, cached per file, model, language
    and prompt. Non-speech tokens are suppressed and no text context is
    carried between the 30 s windows: with both on, whisper fell into a
    caption-style loop ("Satsang with Mooji", "© BF-WATCH TV 2021") on
    the first window of a song with a quiet vocal and never left it. A
    prompt with the lyrics' opening primes it for the words, which hears
    more but also invents more; the caller uses it as a fallback."""
    stamp = f'{file.stat().st_size}:{file.stat().st_mtime}:{file}:{model.name}:{language}:v2:{prompt or ""}'
    cached = CACHE / f'{hashlib.sha1(stamp.encode()).hexdigest()}.json'
    if cached.exists():
        try: return json.load(open(cached, errors='replace'))
        except ValueError: cached.unlink()   # a run was killed while writing it
    CACHE.mkdir(parents=True, exist_ok=True)
    kind = {'large-v3-turbo': 'large.v3.turbo', 'large-v3': 'large.v3', 'medium': 'medium', 'small': 'small'}
    dtw = next((v for k, v in kind.items() if k in model.name), 'large.v3.turbo')
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / 'audio.wav'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(file), '-ac', '1', '-ar', '16000', str(wav)], check=True)
        command = ['whisper-cli', '-m', str(model), '-l', language, '-nfa', '-sow', '-ml', '1', '-dtw', dtw, '-sns', '-mc', '0',
                   '-oj', '-ojf', '-np', '-of', str(Path(tmp) / 'out'), '-f', str(wav)]
        if prompt: command += ['--prompt', prompt]
        try:
            subprocess.run(command, check=True, capture_output=True)
        except subprocess.CalledProcessError:
            # whisper-cli aborts (exit 134) on some files when DTW word times
            # and -mc 0 meet; without the context cut it gets through
            command.remove('-mc'); command.remove('0')
            subprocess.run(command, check=True, capture_output=True)
        result = json.load(open(Path(tmp) / 'out.json', errors='replace'))   # whisper's JSON may split a UTF-8 sequence
    part = cached.with_suffix('.part')
    json.dump(result, open(part, 'w'))
    part.replace(cached)
    return result

def hear(file, model, text):
    """Lyric lines aligned to the transcript, with the lyrics as prompt
    when the plain transcript heard under a third of the lines."""
    plain = STAMP.sub('', text)
    lines = lyric_lines(text)
    language = guess_language(plain) or 'auto'
    best = None
    for prompt in (None, ' '.join(plain.split())[:600]):
        try:
            words = whisper_words(transcribe(file, model, language, prompt), plain)
        except subprocess.CalledProcessError:
            if prompt is None or best is None: raise
            break   # whisper-cli aborts on some files once a prompt is given; the plain transcript stands
        aligned = align(lines, words)
        heard = sum(how in ('heard', 'partly') for _, how in aligned) / max(len(lines), 1)
        if best is None or heard > best[1]: best = (aligned, heard)
        if heard >= MIN_HEARD_OWN: break
    return lines, best[0], best[1]

def normalize(word):
    w = unicodedata.normalize('NFC', word.lower()).replace('ß', 'ss')
    return re.sub(r'[^\w]', '', w)

def whisper_words(result, lyric_text=''):
    """[(seconds, normalized word)]; a word's time is its first token's
    DTW time. Whisper's hallucinations in instrumental passages are
    dropped: a short phrase looping three times or more (unless the
    lyrics repeat it that way too, as a refrain does), seven or more
    words inside one second, three words on the very same instant."""
    lyric_words = ' '.join(normalize(w) for w in lyric_text.split() if normalize(w))
    words = []
    for seg in result['transcription']:
        toks = [t for t in seg['tokens'] if not t['text'].startswith('[') and t.get('t_dtw', -1) >= 0]
        text = normalize(seg['text'])
        if toks and text: words.append((toks[0]['t_dtw'] / 100, text))
    keep = [True] * len(words)
    for n in range(1, 6):
        i = 0
        while i + n <= len(words):
            phrase = [w for _, w in words[i:i + n]]
            reps = 1
            while [w for _, w in words[i + reps * n:i + (reps + 1) * n]] == phrase: reps += 1
            if reps >= 3 and ' '.join(phrase * 3) not in lyric_words:
                for k in range(i, i + reps * n): keep[k] = False
                i += reps * n
            else: i += 1
    i = 0
    while i < len(words):
        j = i
        while j < len(words) and words[j][0] - words[i][0] <= 1.0: j += 1
        same = i
        while same < len(words) and words[same][0] == words[i][0]: same += 1
        end = max(j if j - i >= 7 else i, same if same - i >= 3 else i)
        if end > i:
            for k in range(i, end): keep[k] = False
            i = end
        else: i += 1
    return [w for w, k in zip(words, keep) if k]


# --- alignment ------------------------------------------------------------

def lyric_lines(text):
    """[(stamp seconds or None, text)]; LRC header tags and blank lines dropped."""
    lines = []
    for raw in text.splitlines():
        if re.match(r'^\[[a-z]+:', raw): continue
        stamps = STAMP.findall(raw)
        body = STAMP.sub('', raw).strip()
        if not body: continue
        t = None
        if stamps:
            m, s, frac = stamps[0]
            t = int(m) * 60 + int(s) + (int(frac) / (100 if len(frac) <= 2 else 1000) if frac else 0)
        lines.append((t, body))
    return lines

def similarity(a, b):
    if a == b: return 2.0
    if min(len(a), len(b)) < 4: return -1.0
    return 1.0 if difflib.SequenceMatcher(None, a, b).ratio() >= 0.8 else -1.0

def align(lines, words):
    """Per line: (time, how) with how = 'heard' (first word matched),
    'partly' (a later word matched, the start stepped back at the track's
    word rate), 'between' (interpolated between heard words) or 'guessed' (across a
    gap of over ten seconds without heard words, or outside them). A
    global alignment (Needleman-Wunsch) over the two word sequences, so a
    repeated chorus is matched where it falls, not where the longest
    identical run happens to be."""
    lyric = [(i, normalize(w)) for i, (_, text) in enumerate(lines) for w in text.split() if normalize(w)]
    a, b = [w for _, w in lyric], [w for _, w in words]
    n, m, gap = len(a), len(b), -0.6
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    back = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1): score[i][0], back[i][0] = i * gap, 1
    for j in range(1, m + 1): score[0][j], back[0][j] = j * gap, 2
    for i in range(1, n + 1):
        ai, row, prev, brow = a[i - 1], score[i], score[i - 1], back[i]
        for j in range(1, m + 1):
            diag, up, left = prev[j - 1] + similarity(ai, b[j - 1]), prev[j] + gap, row[j - 1] + gap
            if diag >= up and diag >= left: row[j], brow[j] = diag, 0
            elif up >= left: row[j], brow[j] = up, 1
            else: row[j], brow[j] = left, 2
    anchor = {}
    i, j = n, m
    while i > 0 or j > 0:
        d = back[i][j]
        if d == 0:
            if similarity(a[i - 1], b[j - 1]) > 0: anchor[i - 1] = words[j - 1][0]
            i, j = i - 1, j - 1
        elif d == 1: i -= 1
        else: j -= 1
    keys = sorted(anchor)
    for k in range(1, len(keys) - 1):   # a time running backwards is a whisper glitch, not an anchor
        if not anchor[keys[k - 1]] <= anchor[keys[k]] <= anchor[keys[k + 1]]: anchor.pop(keys[k])
    starts = {}
    for idx, (line, _) in enumerate(lyric): starts.setdefault(line, idx)
    keys = sorted(anchor)
    rates = [(anchor[b] - anchor[a]) / (b - a) for a, b in zip(keys, keys[1:]) if 0 < anchor[b] - anchor[a] < 3]
    rate = statistics.median(rates) if rates else 0.4   # seconds per sung word
    first = {}
    for idx, (line, _) in enumerate(lyric):
        if idx in anchor and line not in first:
            first[line] = (anchor[idx] - (idx - starts[line]) * rate, 'heard' if idx == starts[line] else 'partly')
    result = []
    for line in range(len(lines)):
        if line in first: result.append(first[line]); continue
        start = starts.get(line)
        before = [(i, t) for i, t in anchor.items() if start is not None and i < start]
        after = [(i, t) for i, t in anchor.items() if start is not None and i > start]
        if before and after:
            (i0, t0), (i1, t1) = max(before), min(after)
            result.append((t0 + (t1 - t0) * (start - i0) / (i1 - i0), 'guessed' if t1 - t0 > 10 else 'between'))
        elif before: result.append((max(before)[1], 'guessed'))
        elif after: result.append((min(after)[1], 'guessed'))
        else: result.append((None, 'guessed'))
    return result

def check(lines, aligned):
    """How the file's own stamps compare with the aligned times."""
    deltas = [t - s for (s, _), (t, how) in zip(lines, aligned) if s is not None and t is not None and how == 'heard']
    if len(deltas) < MIN_CHECKED: return None
    offset = statistics.median(deltas)
    within = sum(abs(d - offset) < 1 for d in deltas)
    return {'offset': offset, 'within': within, 'checked': len(deltas), 'confirmed': within >= MIN_CONFIRMED * len(deltas)}

def stamp(t):
    return f'[{int(t // 60):02d}:{t % 60:05.2f}]'


# --- main -----------------------------------------------------------------

def main(argv):
    dry = '--dry-run' in argv
    check_only = '--check' in argv
    replace = '--replace' in argv
    out_dir = Path(argv[argv.index('--out') + 1]) if '--out' in argv else None
    model = Path(argv[argv.index('--model') + 1]) if '--model' in argv else DEFAULT_MODEL
    skip = {'--dry-run', '--out', '--model', '--check', '--replace'}
    paths, i = [], 0
    while i < len(argv):
        if argv[i] in ('--out', '--model'): i += 2; continue
        if argv[i] not in skip: paths.append(Path(argv[i]))
        i += 1
    files = sorted(f for p in paths for f in ([p] if p.is_file() else p.rglob('*')) if f.suffix.lower() in AUDIO and f.is_file())
    if not files: print('nothing to do', file=sys.stderr); return 2
    counts = {}
    for file in files:
        try:
            verdict = handle(file, model, out_dir, dry, check_only, replace)
        except KeyboardInterrupt:
            raise
        except subprocess.CalledProcessError as error:   # a file ffmpeg or whisper cannot read
            tail = (error.stderr or b'').decode(errors='replace').strip().splitlines()[-1:] if isinstance(error.stderr, bytes) else []
            verdict = f'failed: {Path(error.cmd[0]).name} exit {error.returncode}: {" ".join(tail)[:160]}'
        except Exception as error:   # an odd answer from the database, a file that vanished
            verdict = f'failed: {type(error).__name__}: {str(error).strip()[:120]}'
        print(f'{file.name}: {verdict}')
        counts[verdict.split(' (')[0].split(': ')[0]] = counts.get(verdict.split(' (')[0].split(': ')[0], 0) + 1
    print()
    for k, v in sorted(counts.items(), key=lambda kv: -kv[1]): print(f'{v:5d}  {k}')
    return 0

def handle(file, model, out_dir, dry, check_only, replace):
    """One file's verdict; writes its lyrics file unless dry or check_only."""
    if True:
        tags, duration = probe(file)
        own = sidecar(file)
        if any('lyrics' in k for k in tags) and not own:
            verdict = 'has lyrics in its tags'
        elif own and not replace:
            # the file's own lyrics: judged against the audio, kept either way
            lines, aligned, heard = hear(file, model, own[1])
            c = check(lines, aligned) if own[0].suffix == '.lrc' else None
            if heard < MIN_HEARD_OWN:
                verdict = f'own {own[0].suffix} does not match the audio ({heard:.0%} of the lines heard); --replace fetches a new one'
            elif c and not c['confirmed']:
                verdict = f"own .lrc doubtful ({c['within']}/{c['checked']} lines within 1 s); --replace fetches a new one"
            elif c:
                verdict = f"own .lrc confirmed ({c['within']}/{c['checked']} lines within 1 s, offset {c['offset']:+.1f} s)"
            else:
                verdict = f'own {own[0].suffix} matches the audio ({heard:.0%} of the lines heard)'
        elif check_only:
            verdict = 'no lyrics file of its own'
        else:
            record = look_up(tags, duration)
            if not record: verdict = 'not found'
            elif record.get('instrumental'): verdict = 'instrumental'
            else:
                text = record.get('syncedLyrics') or record.get('plainLyrics') or ''
                lines, aligned, heard = hear(file, model, text)
                if record.get('syncedLyrics'):
                    c = check(lines, aligned)
                    if c and c['confirmed']:
                        verdict = f"timed, confirmed ({c['within']}/{c['checked']} lines within 1 s, offset {c['offset']:+.1f} s)"
                        output, ext = text, '.lrc'
                    elif c:
                        verdict = f"timed, doubtful ({c['within']}/{c['checked']} lines within 1 s), kept as text"
                        output, ext = record.get('plainLyrics') or STAMP.sub('', text), '.txt'
                    else:
                        verdict = 'timed, unchecked (too little heard), kept as text'
                        output, ext = record.get('plainLyrics') or STAMP.sub('', text), '.txt'
                elif heard >= MIN_ANCHORED and all(t is not None for t, _ in aligned):
                    verdict = f'text, timed by whisper ({heard:.0%} of the lines heard)'
                    output, ext = ''.join(f'{stamp(t)}{body}\n' for (t, _), (_, body) in zip(aligned, lines)), '.lrc'
                else:
                    verdict = f'text, too little heard for stamps ({heard:.0%}), kept as text'
                    output, ext = text, '.txt'
                if own and replace:
                    own_heard = hear(file, model, own[1])[2]
                    own_ok = own_heard >= MIN_HEARD_OWN and (own[0].suffix == '.txt' or (check(*hear(file, model, own[1])[:2]) or {}).get('confirmed'))
                    if own_ok and not (own[0].suffix == '.txt' and ext == '.lrc'):
                        verdict = f'own {own[0].suffix} kept: it passed the check'
                    elif heard <= own_heard:
                        verdict = f'own {own[0].suffix} kept: the new lyrics are heard no better ({heard:.0%} vs {own_heard:.0%})'
                    else:
                        verdict = f'own {own[0].suffix} replaced: ' + verdict
                if not dry and 'kept:' not in verdict:
                    folder = out_dir or file.parent / 'lyrics'
                    folder.mkdir(parents=True, exist_ok=True)
                    target = folder / (file.stem + ext)
                    if own and replace:
                        target.write_text(output.rstrip('\n') + '\n')
                        if own[0] != target: own[0].unlink()
                    elif not target.exists(): target.write_text(output.rstrip('\n') + '\n')
        return verdict

STOPWORDS = {
    'de': 'und der die das ich du nicht ist ein eine es wir ihr sie mich dich mir dir wie was auf mit von zu den dem im wenn aber nur noch',
    'en': 'the and you i to a of in it my me is that your on we be for this with all not just are was',
    'fr': 'le la les et je tu de un une est que pas ne dans pour des mon ma tes vous nous sur au qui',
    'es': 'el la los las y que de un una es no en mi te me se por con para tu yo del al como',
    'it': 'il la le e che di un una non è in mi ti ci per con del della sono ma come se io',
    'pt': 'o a os as e que de um uma não é em meu minha você eu se por com para do da no na',
    'nl': 'de het en een ik je niet is van dat in op met voor zijn ze we maar als nog wel',
}

def guess_language(text):
    """The lyrics' language by stop words; whisper's own detection guesses
    from the first seconds, which in a song are often an intro without
    words, and then transcribes the wrong language (seen: Russian for a
    German song). None when the words give no clear answer."""
    words = [normalize(w) for w in text.split()]
    best = max(STOPWORDS, key=lambda lang: sum(w in STOPWORDS[lang].split() for w in words))
    hits = sum(w in STOPWORDS[best].split() for w in words)
    return best if hits >= max(5, len(words) * 0.08) else None

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
