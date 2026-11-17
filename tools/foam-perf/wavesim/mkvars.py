"""Write bench variants of w16_wakes.glsl / w16_both.glsl. Usage: mkvars.py <base> <prefix>"""
import sys
base = open(sys.argv[1], encoding='utf-8').read()
prefix = sys.argv[2]

HEAD = '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 hull = uB.boatWakeHulls[boat];
        int count = int(hull.x);
        // Slots are filled in order
        if (count < 1)
            break;
        // Nearly all water is outside the bounds
        vec4 bounds = uB.boatWakeBounds[boat];
        if (any(lessThan(p, bounds.xy)) || any(greaterThan(p, bounds.zw)))
            continue;
'''
assert HEAD in base

MASK = '''    int inside = 0;
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 bounds = uB.boatWakeBounds[boat];
        if (all(greaterThanEqual(p, bounds.xy)) && all(lessThanEqual(p, bounds.zw)))
            inside |= 1 << boat;
    }
'''
V = {}
V['mask_lsb'] = [(HEAD, MASK + '''    while (inside != 0) {
        int boat = findLSB(inside);
        inside &= inside - 1;
        vec4 hull = uB.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
''')]
V['mask_for'] = [(HEAD, MASK + '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        if ((inside >> boat & 1) == 0)
            continue;
        vec4 hull = uB.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
''')]
# Outside test first, before the count
V['bounds_first'] = [(HEAD, '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 bounds = uB.boatWakeBounds[boat];
        if (any(lessThan(p, bounds.xy)) || any(greaterThan(p, bounds.zw)))
            continue;
        vec4 hull = uB.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
''')]
V['cullall'] = [('if (any(lessThan(p, bounds.xy)) || any(greaterThan(p, bounds.zw)))', 'if (bounds.x > -1e20)')]
V['fixed'] = [('i <= count; i++', 'i <= 2; i++')]
V['nosweep'] = [('if (sweptFade > 0.0 && (', 'if (false && (')]
V['nofoam'] = [('        if (outside)\n            continue;', '        continue;')]
V['loop1only'] = V['nosweep'] + [('        if (outside)\n            continue;', '        wake.x += side; continue;')]
V['mask_lsb_fixed'] = V['mask_lsb'] + V['fixed']
V['mask_lsb_nosweep'] = V['mask_lsb'] + V['nosweep']
V['mask_lsb_nofoam'] = V['mask_lsb'] + V['nofoam']
V['mask_lsb_loop1only'] = V['mask_lsb'] + V['loop1only']
V['mask_lsb_cullall'] = V['mask_lsb'] + [('        if (count < 1)\n            break;\n\n        vec4 size', '        if (count < 1 || hull.x > -1e20)\n            break;\n\n        vec4 size')]
for name, patches in V.items():
    s = base.replace('#version 330', '#version 420', 1)
    for old, new in patches:
        assert old in s, (name, old)
        s = s.replace(old, new)
    open('%s_%s.glsl' % (prefix, name), 'w', encoding='utf-8', newline='\n').write(s)
print(' '.join('%s_%s.glsl' % (prefix, n) for n in V))
