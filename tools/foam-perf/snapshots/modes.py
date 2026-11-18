"""Split one snapshot's frames into the fast and slow group and show when slow frames happen and what else differs.
Usage: modes.py <stamp> [threshold ms]"""
import json, os, sys, statistics as st
d = os.path.expanduser('~/.runelite/117hd/snapshots')
j = json.load(open(os.path.join(d, 'snapshot-%s.json' % sys.argv[1])))
fr = j['frames']; t0 = fr[0]['timestamp']
v = [x['gpu']['Render scene and sky'] / 1e6 for x in fr]
cut = float(sys.argv[2]) if len(sys.argv) > 2 else (sorted(v)[len(v) // 100] + 0.25)
slow = [x > cut for x in v]
# Runs of slow and fast frames
runs = []; cur = slow[0]; n = 0
for s in slow:
    if s == cur: n += 1
    else: runs.append((cur, n)); cur = s; n = 1
runs.append((cur, n))
print('cut %.3f  slow %.1f%%  runs: %d' % (cut, 100 * sum(slow) / len(slow), len(runs)))
print('first runs (S=slow,F=fast):', ' '.join(('S' if a else 'F') + str(b) for a, b in runs[:40]))
# Per second share of slow frames
secs = {}
for x, s in zip(fr, slow):
    secs.setdefault(int((x['timestamp'] - t0) / 1000), []).append(s)
print('slow share per second:', ' '.join('%d' % (100 * sum(a) / len(a)) for k, a in sorted(secs.items())))
# Frame intervals
dt = [(fr[i + 1]['timestamp'] - fr[i]['timestamp']) for i in range(len(fr) - 1)]
print('frame interval ms median %.2f' % st.median(dt))
for group in ('gpu', 'cpu'):
    for k in fr[0][group]:
        a = [x[group][k] / 1e6 for x, s in zip(fr, slow) if s]
        b = [x[group][k] / 1e6 for x, s in zip(fr, slow) if not s]
        if a and b and abs(st.median(a) - st.median(b)) > 0.02:
            print('%s.%-28s slow %.3f fast %.3f' % (group, k, st.median(a), st.median(b)))
for k in ('drawnTiles', 'drawnStatic', 'drawnDynamic', 'memoryUsed'):
    a = [x[k] for x, s in zip(fr, slow) if s]; b = [x[k] for x, s in zip(fr, slow) if not s]
    print('%-14s slow %s fast %s' % (k, st.median(a), st.median(b)))
