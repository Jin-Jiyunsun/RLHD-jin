import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
WC = "                vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, swept);\n"
US = """            float unswept = 1.0 - max(wave.spawnTime < swept.y ? swept.x : 0.0, wave.spawnTime < swept.w ? swept.z : 0.0);
            if (unswept <= 0.0)
                continue;
"""
P = {
 'fx_branch': [(WC, """                vec2 waves = swept.x > 0.0 ?
                    oceanWaves(drifted, shoreDist, towardsShore, swept) :
                    oceanWaves(drifted, shoreDist, towardsShore, vec4(0.0, -1e9, 0.0, -1e9));
""")],
 'fx_inloop': [(US, """            float unswept = 1.0;
            if (swept.x > 0.0) {
                unswept -= max(wave.spawnTime < swept.y ? swept.x : 0.0, wave.spawnTime < swept.w ? swept.z : 0.0);
                if (unswept <= 0.0)
                    continue;
            }
""")],
}
for name, subs in P.items():
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for old, new in subs:
        assert s.count(old) == 1, (name, old)
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
