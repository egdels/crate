#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

"""Compare two copies of a music folder, and bring one up to the other.

    scripts/sync.py <source> <target>          # what differs: only in one, changed, newer in the target
    scripts/sync.py --copy <source> <target>   # copy what is new or changed in the source

Made for a collection kept on a local disk and on a network share. File
names are compared after Unicode normalisation: macOS writes "ü" as u
plus a combining mark, an SMB share may hand back the composed form,
and a byte-wise tool (the openrsync macOS ships) takes the two for
different files, deletes one and copies the other under a second name.

--copy never deletes and never overwrites a file that is newer in the
target; it reports those. A file goes to a temporary name first and is
renamed into place, with its modification date kept, so a later
comparison finds the two equal. New files go into the target's own
spelling of an existing folder. A target that is missing or empty while
the source is not (a share that dropped its connection) stops the run;
a first copy into an empty folder is a job for cp or the Finder.
Files a share lists but cannot open are reported, not copied.
"""

import os, shutil, sys, unicodedata

SKIP_DIRS = {'#recycle', '@eaDir', '.Trashes', '.Spotlight-V100', '.fseventsd'}
TMP = '.crate-tmp'


def nfc(text):
    return unicodedata.normalize('NFC', text)

def walk(root):
    """{normalised relative path: (path, size, mtime)}; size -1 for a file
    listed but not openable."""
    files = {}
    for folder, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
        for name in names:
            if name == '.DS_Store' or name.startswith('._') or name.endswith(TMP): continue
            path = os.path.join(folder, name)
            try:
                st = os.stat(path)
                files[nfc(os.path.relpath(path, root))] = (path, st.st_size, st.st_mtime)
            except OSError:
                files[nfc(os.path.relpath(path, root))] = (path, -1, 0)
    return files

def compare(source, target):
    a, b = walk(source), walk(target)
    if a and not b:
        sys.exit(f'{target} has no files while {source} has {len(a)}: is the share connected?')
    only_source = sorted(set(a) - set(b))
    only_target = sorted(set(b) - set(a))
    both = set(a) & set(b)
    unreadable = sorted(k for k in set(a) | set(b) if (k in a and a[k][1] == -1) or (k in b and b[k][1] == -1))
    # two seconds of slack: SMB and FAT round modification times
    changed = sorted(k for k in both if a[k][1] != -1 and b[k][1] != -1
                     and (a[k][1] != b[k][1] or abs(a[k][2] - b[k][2]) > 2))
    newer = [k for k in changed if b[k][2] > a[k][2] + 2]
    return a, b, only_source, only_target, changed, newer, unreadable

def report(label, keys, limit=20):
    print(f'{label}: {len(keys)}')
    for k in keys[:limit]: print('   ', k)
    if len(keys) > limit: print(f'    ... and {len(keys) - limit} more')

def target_folders(target):
    """Normalised relative folder -> the target's own spelling of it."""
    folders = {}
    for folder, dirs, _ in os.walk(target):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
        folders[nfc(os.path.relpath(folder, target))] = folder
    return folders

def copy(a, b, keys, source, target):
    folders = target_folders(target)
    done = failed = 0
    for k in keys:
        src = a[k][0]
        if k in b:
            dst = b[k][0]
        else:
            rel_folder = os.path.dirname(k) or '.'
            folder = folders.get(rel_folder)
            if folder is None:
                folder = os.path.join(target, os.path.relpath(os.path.dirname(src), source))
                os.makedirs(folder, exist_ok=True)
                folders[rel_folder] = folder
            dst = os.path.join(folder, os.path.basename(src))
        tmp = dst + TMP
        try:
            shutil.copy2(src, tmp)
            os.replace(tmp, dst)
            done += 1
        except OSError as e:
            print(f'failed: {k}: {e}')
            failed += 1
            if os.path.exists(tmp): os.remove(tmp)
        if done and done % 200 == 0: print(f'{done} of {len(keys)}', flush=True)
    return done, failed


def main():
    args = sys.argv[1:]
    do_copy = '--copy' in args
    paths = [a for a in args if not a.startswith('--')]
    if len(paths) != 2:
        print(__doc__)
        sys.exit(2)
    source, target = (os.path.abspath(p) for p in paths)
    for p in (source, target):
        if not os.path.isdir(p): sys.exit(f'{p} is not a folder (is the share connected?)')
    sys.stdout.reconfigure(line_buffering=True)
    a, b, only_source, only_target, changed, newer, unreadable = compare(source, target)
    print(f'source {len(a)} files, target {len(b)} files')
    report('only in the source', only_source)
    report('only in the target (left alone)', only_target)
    report('changed', changed)
    report('newer in the target (left alone)', newer)
    report('listed but not openable (left alone)', unreadable)
    if not do_copy: return
    keys = [k for k in only_source + changed if k not in newer and a[k][1] != -1]
    done, failed = copy(a, b, keys, source, target)
    print(f'copied {done}, failed {failed}')
    _, _, only_source, _, changed, _, _ = compare(source, target)
    print(f'afterwards: {len(only_source)} only in the source, {len(changed)} changed')


if __name__ == '__main__':
    main()
