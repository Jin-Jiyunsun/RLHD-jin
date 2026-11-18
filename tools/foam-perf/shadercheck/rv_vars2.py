import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
WC = "                vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, swept);\n"
P = {'rv_stubwaves': [('utils/water.glsl', WC, "                vec2 waves = vec2(oceanRoom * 0.5);\n")]}
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
