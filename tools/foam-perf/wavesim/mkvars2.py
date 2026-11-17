"""Variants on top of the bitmask version. Usage: mkvars2.py <base> <prefix>"""
import sys
base = open(sys.argv[1], encoding='utf-8').read()
prefix = sys.argv[2]
V = {}
V['base'] = []
V['guard'] = [('    int inside = 0;\n', '    int inside = 0;\n    if (uB.boatWakeHulls[0].x >= 1.0)\n')]
V['noiseconst'] = [('wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uB_TIME);', 'wakeNoise.breakup = 0.5;')]
V['noadd'] = [('                        if (amount > 0.0)\n                            addSwept(', '                        if (amount > 1e9)\n                            addSwept(')]
SWEEP_OPEN = '''        if (sweptFade > 0.0 && (!arms || side / sideScale < armReach)) {
            if (!wakeNoise.foamReady) {
                wakeNoise.foamReady = true;
                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
            }
            firstSegment = true;'''
assert SWEEP_OPEN in base, 'sweep'
V['second'] = [
    ('                if (dist2 < nearest) {\n                    nearest = dist2;', '                if (dist2 >= nearest) {\n                    second = min(second, dist2);\n                } else {\n                    second = nearest;\n                    nearest = dist2;'),
    ('        float nearest = 1e9;\n', '        float nearest = 1e9;\n        float second = 1e9;\n'),
    (SWEEP_OPEN, '''        if (sweptFade > 0.0 && (!arms || side / sideScale < armReach)) {
            if (!wakeNoise.foamReady) {
                wakeNoise.foamReady = true;
                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
            }
            if (sqrt(second) - side >= BOAT_WAKE_SWEPT_RANGE) {
                if (!outside) {
                    float amount = boatWakeSwept(side / sideScale, fromBow / lengthScale,
                        age + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, wakeNoise.breakup) * sweptFade;
                    if (amount > 0.0)
                        addSwept(swept, amount, uboGlobal.elapsedTime - age - hull.y);
                }
                count = -1;
            }
            firstSegment = true;'''),
    ('            a = vec4(bowPos, 0.0, 0.0);\n            for (int i = 0; i <= count; i++) {', '            vec4 last = a;\n            a = vec4(bowPos, 0.0, 0.0);\n            for (int i = 0; i <= count; i++) {'),
    ('                    firstSegment = false;\n                }\n                a = b;\n            }\n        }\n        if (outside)', '                    firstSegment = false;\n                }\n                a = b;\n            }\n            a = last;\n        }\n        if (outside)'),
]
for name, patches in V.items():
    s = base
    for old, new in patches:
        old = old.replace('uboGlobal.elapsedTime', 'uboGlobal.elapsedTime').replace('uB_TIME', 'uboGlobal.elapsedTime')
        assert old in s, (name, old)
        s = s.replace(old, new)
    open('%s_%s.glsl' % (prefix, name), 'w', encoding='utf-8', newline='\n').write(s)
print(' '.join('%s_%s.glsl' % (prefix, n) for n in V))
