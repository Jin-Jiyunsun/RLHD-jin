import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
OLD = """                fOceanWaveList = oceanWaveList(fOceanWaveOrigin);
"""
NEW = """                // Only where a vertex has room for waves, as the pixels need. See SHORE_WAVE_MIN_ROOM.
                ivec3 enclosure = fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
                if (any(lessThan(enclosure, ivec3(int(ceil((1.0 - 5.0 / SHORE_DISTANCE_MAX) * WATER_ENCLOSURE_STEPS))))))
                    fOceanWaveList = oceanWaveList(fOceanWaveOrigin);
"""
P = {'rv_vskip': [('scene_vert.glsl', OLD, NEW)],
     'rv_vnone': [('scene_vert.glsl', OLD, "")]}
for name, subs in P.items():
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    for f, old, new in subs:
        p = os.path.join(out, f)
        s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
        assert s.count(old) == 1, (name, old[:60])
        s = s.replace(old, new)
        open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
