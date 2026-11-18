import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
OLD = """            if (bool(OCEAN_LACE) && churned < 1.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
                // Fading towards the shore in opacity, keeping its specks as dense
                float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
"""
NEW = """            // Fading towards the shore in opacity, keeping its specks as dense
            float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
            // Skipped where it couldn't change a pixel's color, as in narrow rivers
            if (bool(OCEAN_LACE) && churned < 1.0 && laceStrength >= 1.0 / 255.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
"""
P = {'rv_lacethresh': [('utils/water.glsl', OLD, NEW)]}
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
