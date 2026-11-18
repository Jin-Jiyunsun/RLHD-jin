import os, shutil, sys
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
LACE = """            if (bool(OCEAN_LACE) && churned < 1.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
                // Fading towards the shore in opacity, keeping its specks as dense
                float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
                oceanAmount = max(oceanAmount, oceanSpecks(lace, OCEAN_LACE_SOFTNESS, oceanMask) * laceStrength);
            }
"""
WAVES = """            if (bool(OCEAN_WAVES) && churned < 1.0 && oceanRoom > 0.0) {
                vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, swept);
                float amount = oceanSpecks(waves.x * oceanRoom * (1.0 - churned), OCEAN_WAVE_SOFTNESS, oceanMask);
                waveAmount = amount * waveStrength * waves.y;
                oceanAmount = max(oceanAmount, waveAmount);
            }
"""
P = {'fx_wavesfirst': [(LACE + WAVES, WAVES + LACE)]}
for name, subs in P.items():
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for old, new in subs:
        assert s.count(old) == 1, (name, old[:60])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
