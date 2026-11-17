"""Compare 117 HD timing snapshots taken since a given time, by which foam options were on.
Usage: snapshots.py [since, as YYYY-MM-DD_HH-MM-SS]"""
import csv
import glob
import json
import os
import statistics
import sys

folder = os.path.expanduser('~/.runelite/117hd/snapshots')
since = sys.argv[1] if len(sys.argv) > 1 else ''
FOAM = [('experimentalImprovedShoreFoam', 'shore'), ('experimentalOceanLace', 'lace'),
        ('experimentalOceanWaves', 'waves'), ('experimentalBoatWakes', 'wakes')]
# Where each run of snapshots was taken, from its first snapshot's time
LOCATIONS = [('2026-11-16_01-24', 'Port Khazard'), ('2026-11-16_01-38', 'Kastori')]


def location(stamp):
    found = ''
    for start, name in LOCATIONS:
        if stamp >= start:
            found = name
    return found


COLUMNS = ['gpu.Render scene and sky', 'gpu.Render frame', 'cpu.Draw frame']

rows = []
for path in sorted(glob.glob(os.path.join(folder, 'snapshot-*.json'))):
    stamp = os.path.basename(path)[len('snapshot-'):-len('.json')]
    if stamp < since:
        continue
    meta = json.load(open(path, encoding='utf-8'))
    settings = meta.get('settings', {})
    # Unset options are at their defaults, which are on
    on = [label for key, label in FOAM if settings.get(key, 'true') != 'false']
    with open(path[:-5] + '.csv', encoding='utf-8') as f:
        frames = list(csv.DictReader(f))
    # Skip the first second, while toggled shaders may still be compiling
    frames = frames[len(frames) // 20:]
    medians = {c: statistics.median(float(fr[c]) for fr in frames) / 1e6 for c in COLUMNS if c in frames[0]}
    rows.append((stamp, on, len(frames), medians, meta.get('gpuName', '')))

if not rows:
    sys.exit('no snapshots since ' + since)
print('GPU:', rows[-1][4])
print('%-20s %-13s %-26s %6s  %s' % ('snapshot', 'location', 'foam on', 'frames', '  '.join('%-22s' % c for c in COLUMNS)))
for stamp, on, n, med, _ in rows:
    print('%-20s %-13s %-26s %6d  %s' % (stamp, location(stamp), ','.join(on) or 'none', n,
                                   '  '.join('%-22s' % ('%.3f ms' % med[c] if c in med else '-') for c in COLUMNS)))
