import json, os, sys, glob, statistics as st
from foamkeys import foam_on
d = os.path.expanduser('~/.runelite/117hd/snapshots')
n = int(sys.argv[1]) if len(sys.argv) > 1 else 2
for f in sorted(glob.glob(d + '/*.json'))[-n:]:
    j = json.load(open(f)); fr = j['frames']; s = j['settings']; t0 = fr[0]['timestamp']
    on = foam_on(s)
    q = [st.median([x['gpu']['Render scene and sky'] / 1e6 for x in fr if a * 1000 <= x['timestamp'] - t0 < (a + 5) * 1000]) for a in (0, 5, 10, 15)]
    print(os.path.basename(f)[9:-5], s.get('sceneResolutionScale'), ' '.join(on) or '(none)',
          'median %.3f' % (st.median(x['gpu']['Render scene and sky'] for x in fr) / 1e6),
          'quarters', ' '.join('%.2f' % v for v in q), 'dyn', st.median(x['drawnDynamic'] for x in fr))
