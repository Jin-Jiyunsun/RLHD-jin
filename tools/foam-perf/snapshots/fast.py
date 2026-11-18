"""Each snapshot's time from its fast frames, with the plain median for comparison. Usage: fast.py [since stamp]

Frames fall into a fast group and a slow one about 0.5 ms behind, at every setting: other GPU work landing during the
scene pass. A plain median jumps between the two as their shares change, so this takes the median of the frames
within 0.25 ms of the 1st percentile."""
import glob
import json
import os
import statistics as st
import sys

from foamkeys import foam_on

folder = os.path.expanduser('~/.runelite/117hd/snapshots')
since = sys.argv[1] if len(sys.argv) > 1 else ''
for path in sorted(glob.glob(os.path.join(folder, 'snapshot-*.json'))):
    stamp = os.path.basename(path)[len('snapshot-'):-len('.json')]
    if stamp < since:
        continue
    j = json.load(open(path))
    frames = j['frames']
    times = sorted(x['gpu']['Render scene and sky'] / 1e6 for x in frames)
    cut = times[len(times) // 100] + 0.25
    fast = [x for x in times if x <= cut]
    print('%s  %-18s fast %.3f (%2d%% of frames)  median %.3f  dynamic %s' % (
        stamp, ' '.join(foam_on(j['settings'])) or '(none)', st.median(fast), 100 * len(fast) / len(times),
        st.median(times), st.median(x['drawnDynamic'] for x in frames)))
