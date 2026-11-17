W = 'utils/water.glsl'
P = {}
HEAD = '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        // Slots are filled in order
        if (count < 1)
            break;
        // Nearly all water is outside the bounds
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (any(lessThan(p, bounds.xy)) || any(greaterThan(p, bounds.zw)))
            continue;
'''
MASK = '''    int inside = 0;
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (all(greaterThanEqual(p, bounds.xy)) && all(lessThanEqual(p, bounds.zw)))
            inside |= 1 << boat;
    }
'''
LOOP = '''    while (inside != 0) {
        int boat = findLSB(inside);
        inside &= inside - 1;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
'''
P['mask'] = [(W, HEAD, MASK + LOOP)]
P['maskguard'] = [(W, HEAD, MASK.replace('    for (int boat', '    if (uboGlobal.boatWakeHulls[0].x >= 1.0)\n    for (int boat') + LOOP)]
LOOP_FOR = '''    for (int boat = 0; inside != 0; boat++, inside >>= 1) {
        if ((inside & 1) == 0)
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
'''
LOOP_LOG = '''    while (inside != 0) {
        int lowest = inside & -inside;
        inside ^= lowest;
        int boat = int(log2(float(lowest)) + 0.5);
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
'''
LOOP_FIXED = '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        if ((inside >> boat & 1) == 0)
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
'''
P['maskfor'] = [(W, HEAD, MASK + LOOP_FOR)]
P['masklog'] = [(W, HEAD, MASK + LOOP_LOG)]
P['maskfixed'] = [(W, HEAD, MASK + LOOP_FIXED)]
P['boundsfirst'] = [(W, HEAD, '''    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (any(lessThan(p, bounds.xy)) || any(greaterThan(p, bounds.zw)))
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        if (count < 1)
            break;
''')]
CALL = '''    if (bool(BOAT_WAKES) && waterType.hasFoam != 0)
        wake = boatWakes(IN.position.xz / 128.0, swept);
'''
P['sweptconst'] = [(W, 'vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, swept);', 'vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, vec4(0.0, -1e9, 0.0, -1e9));')]
P['wakezero'] = [(W, CALL, CALL.replace('        wake = boatWakes(IN.position.xz / 128.0, swept);', '    {\n        wake = boatWakes(IN.position.xz / 128.0, swept);\n        wake = vec4(0.0);\n    }'))]
P['nograd'] = [(W, '    if (wake.x > 0.0) {\n        float specks', '    if (false) {\n        float specks')]
P['wakexzero'] = [(W, CALL, CALL.replace('        wake = boatWakes(IN.position.xz / 128.0, swept);', '    {\n        wake = boatWakes(IN.position.xz / 128.0, swept);\n        wake.xyz = vec3(0.0);\n    }'))]
P['wakewzero'] = [(W, CALL, CALL.replace('        wake = boatWakes(IN.position.xz / 128.0, swept);', '    {\n        wake = boatWakes(IN.position.xz / 128.0, swept);\n        wake.w = 0.0;\n    }'))]
TOP = '''    swept = vec4(0.0, -1e9, 0.0, -1e9);
    // In world tiles, so the noise doesn't jump when the scene's base moves at loading lines
'''
P['stub'] = [(W, TOP, '    swept = vec4(0.0, -1e9, 0.0, -1e9);\n    if (true)\n        return vec4(0.0);\n')]
P['earlyret'] = [(W, TOP, '    swept = vec4(0.0, -1e9, 0.0, -1e9);\n    if (uboGlobal.boatWakeHulls[0].x < 1.0)\n        return vec4(0.0);\n')]
P['callguard'] = [(W, CALL, '''    if (bool(BOAT_WAKES) && waterType.hasFoam != 0 && uboGlobal.boatWakeHulls[0].x >= 1.0)
        wake = boatWakes(IN.position.xz / 128.0, swept);
''')]
DECL = '''    // Worked out first, since they break up the ocean waves
    vec4 wake = vec4(0.0);
    vec4 swept = vec4(0.0, -1e9, 0.0, -1e9);
''' + CALL
START = '''vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);
'''
P['early'] = [(W, DECL, ''), (W, START, START + '\n' + DECL)]
P['nosweep'] = [(W, 'if (sweptFade > 0.0 && (', 'if (false && (')]
P['nofoam'] = [(W, '        if (outside)\n            continue;\n\n        // Scaled to the reference boat.', '        if (true)\n            continue;\n\n        // Scaled to the reference boat.')]
P['cullonly'] = [(W, '            continue;\n\n        vec4 size = uboGlobal.boatWakeSizes[boat];', '            continue;\n        if (true) { churned += hull.y; continue; }\n\n        vec4 size = uboGlobal.boatWakeSizes[boat];')]
P['nofades'] = [(W, '        if (foam <= wake.x)\n            continue;\n        if (!wakeNoise.fadeReady) {', '        if (true) { wake.x = max(wake.x, foam); continue; }\n        if (!wakeNoise.fadeReady) {')]
def bulk(n):
    body = ''.join('        bulk += foamBreakupNoise(IN.position.xz * %d.37 + bulk, uboGlobal.elapsedTime + %d.0) * noise(IN.position.zx * %d.11 - bulk);\n' % (k + 2, k, k + 3) for k in range(n))
    return [(W, '    float shoreAmount = foamAmount;\n', '    if (uboGlobal.boatWakeHulls[0].x >= 1.0) {\n        float bulk = 0.0;\n' + body + '        foamAmount += bulk;\n    }\n    float shoreAmount = foamAmount;\n')]
for n in (2, 4, 6, 8, 12):
    P['bulk%d' % n] = bulk(n)
def bulkif(n):
    body = ''.join('        if (bulk > %d.5)\n            bulk += foamBreakupNoise(IN.position.xz * %d.37 + bulk, uboGlobal.elapsedTime + %d.0);\n' % (k, k + 2, k) for k in range(n))
    return [(W, '    float shoreAmount = foamAmount;\n', '    if (uboGlobal.boatWakeHulls[0].x >= 1.0) {\n        float bulk = uboGlobal.boatWakeHulls[0].y;\n' + body + '        foamAmount += bulk;\n    }\n    float shoreAmount = foamAmount;\n')]
for n in (10, 20, 30, 40, 60):
    P['bulkif%d' % n] = bulkif(n)
def bulkloop(n):
    body = ''.join('            bulk += foamBreakupNoise(IN.position.xz * %d.37 + bulk, uboGlobal.elapsedTime + %d.0) * noise(IN.position.zx * %d.11 - bulk);\n' % (k + 2, k, k + 3) for k in range(n))
    return [(W, '    float shoreAmount = foamAmount;\n', '    {\n        float bulk = 0.0;\n        for (int k = 0; k < int(uboGlobal.boatWakeHulls[0].x); k++) {\n' + body + '        }\n        foamAmount += bulk;\n    }\n    float shoreAmount = foamAmount;\n')]
for n in (1, 2, 4, 8):
    P['bulkloop%d' % n] = bulkloop(n)
SW_NOISE = '''            if (!wakeNoise.foamReady) {
                wakeNoise.foamReady = true;
                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
            }
            firstSegment = true;'''
P['t_sweepnonoise'] = [(W, SW_NOISE, '            firstSegment = true;'), (W, 'arms, squareBow, bigShip, wakeNoise.breakup) *\n                            sweptFade * near;', 'arms, squareBow, bigShip, 0.5) *\n                            sweptFade * near;')]
P['t_noadd'] = [(W, '                        if (amount > 0.0)\n                            addSwept(swept, amount, uboGlobal.elapsedTime - segmentAge - hull.y);', '                        if (amount > 0.0)\n                            swept.x += amount;')]
P['t_noswfn'] = [(W, '''                        float amount = boatWakeSwept(dist / sideScale, mix(a.w, b.w, t) / lengthScale,
                            segmentAge + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, wakeNoise.breakup) *
                            sweptFade * near;''', '                        float amount = wakeNoise.breakup * sweptFade * near;')]
P['t_noragged'] = [(W, 'float ragged = (noise((world - outward * foamLine) * 1.3) - 0.5) * raggedness;', 'float ragged = (0.3 - 0.5) * raggedness;')]
FOAM_NOISE = '''    if (!wakeNoise.foamReady) {
        wakeNoise.foamReady = true;
        wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
    }
    float breakup = wakeNoise.breakup;'''
P['t_foamnonoise'] = [(W, FOAM_NOISE, '    float breakup = 0.5;')]
P['t_sweptout'] = [(W, '            if (!wakeNoise.foamReady) {\n                wakeNoise.foamReady = true;\n                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);\n            }\n            firstSegment = true;', '            firstSegment = true;'),
    (W, '    WakeNoise wakeNoise = WakeNoise(false, 0.0, false, 0.0, 0.0);\n', '    WakeNoise wakeNoise = WakeNoise(true, foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime), false, 0.0, 0.0);\n')]
NOISE_DECL = '    WakeNoise wakeNoise = WakeNoise(false, 0.0, false, 0.0, 0.0);\n'
EAGER = '''    if (inside == 0)
        return vec4(0.0);
    WakeNoise wakeNoise = WakeNoise(true,
        foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime), true,
        noise(world * 1.3 + 7.1),
        foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime));
'''
FOAM_LAZY = '''    if (!wakeNoise.foamReady) {
        wakeNoise.foamReady = true;
        wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
    }
'''
SWEEP_LAZY = '''            if (!wakeNoise.foamReady) {
                wakeNoise.foamReady = true;
                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
            }
'''
FADE_LAZY = '''        if (!wakeNoise.fadeReady) {
            wakeNoise.fadeReady = true;
            wakeNoise.endRagged = noise(world * 1.3 + 7.1);
            wakeNoise.stopBreakup =
                foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
        }
'''
P['eager'] = [(W, NOISE_DECL, ''), (W, HEAD, MASK + EAGER + LOOP_FOR), (W, FOAM_LAZY, ''), (W, SWEEP_LAZY, ''), (W, FADE_LAZY, '')]
# Only the breakup noise up front, the fade noise still lazy
EAGER1 = '''    if (inside == 0)
        return vec4(0.0);
    WakeNoise wakeNoise = WakeNoise(true,
        foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime), false, 0.0, 0.0);
'''
P['eager1'] = [(W, NOISE_DECL, ''), (W, HEAD, MASK + EAGER1 + LOOP_FOR), (W, FOAM_LAZY, ''), (W, SWEEP_LAZY, '')]
MASKB = '''    int inside = 0;
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        // Slots are filled in order
        if (uboGlobal.boatWakeHulls[boat].x < 1.0)
            break;
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (all(greaterThanEqual(p, bounds.xy)) && all(lessThanEqual(p, bounds.zw)))
            inside |= 1 << boat;
    }
'''
LOOP_B = '''    for (int boat = 0; inside != 0; boat++, inside >>= 1) {
        if ((inside & 1) == 0)
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
'''
P['maskb'] = [(W, HEAD, MASKB + LOOP_B)]
P['maskb_eager'] = [(W, NOISE_DECL, ''), (W, HEAD, MASKB + EAGER + LOOP_B), (W, FOAM_LAZY, ''), (W, SWEEP_LAZY, ''), (W, FADE_LAZY, '')]
P['maskb_eager1'] = [(W, NOISE_DECL, ''), (W, HEAD, MASKB + EAGER1 + LOOP_B), (W, FOAM_LAZY, ''), (W, SWEEP_LAZY, '')]
def foamfirst(s):
    start = s.index('    // Distance to land per vertex. See SceneContext for the shore data layout.\n')
    end = s.index('    float opacityFresnel = max(finalFresnel,')
    block = s[start:end]
    s = s[:start] + s[end:]
    decl = '    // The foam\'s texture wherever there\'s foam, for shading it\n    float foamMask = 0;\n'
    assert s.count(decl) == 1
    s = s.replace(decl, '')
    anchor = '    uv3 += uvFlow * flowMapStrength;\n'
    assert s.count(anchor) == 1
    return s.replace(anchor, anchor + '\n' + decl + block)
P['foamfirst'] = [(W, foamfirst)]

UVS = '''    // Each layer scrolled along with the ocean waves, on top of its own animation
    vec2 anchorUv1 = worldUvs(3).yx + waveScroll.yx / 3.0;
    vec2 anchorUv2 = worldUvs(5) + waveScroll / 5.0;
    vec2 anchorUv3 = worldUvs(7) + waveScroll / 7.0;
    vec2 uv1 = anchorUv1 - animationFrame(28 * waterType.duration);
    vec2 uv2 = anchorUv2 + animationFrame(24 * waterType.duration);
    vec2 uv3 = anchorUv3 + animationFrame(21 * waterType.duration);

'''
UVFLOW = '''    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

'''
def cut(s, text):
    assert s.count(text) == 1, text[:60]
    return s.replace(text, '')

def foamfirst2(s, wakes_first=False):
    start = s.index('    // Distance to land per vertex. See SceneContext for the shore data layout.\n')
    end = s.index('    float opacityFresnel = max(finalFresnel,')
    block = s[start:end]
    s = s[:start] + s[end:]
    decl = '    // The foam\'s texture wherever there\'s foam, for shading it\n    float foamMask = 0;\n'
    s = cut(s, decl)
    s = cut(s, UVS)
    s = cut(s, UVFLOW)
    wakes = ''
    if wakes_first:
        block = cut(block, DECL)
        wakes = '\n    // Boat wakes first, since they break up the ocean waves\n' + DECL.split('\n', 1)[1]
        top = '    WaterType waterType = getWaterType(waterTypeIndex);\n'
        i = s.index('vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {\n' + top) + len('vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {\n' + top)
        s = s[:i] + wakes + s[i:]
    anchor = '    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;\n'
    assert s.count(anchor) == 1
    return s.replace(anchor, anchor + '\n' + decl + block + '\n' + UVS + UVFLOW.rstrip('\n') + '\n')
P['ff2'] = [(W, foamfirst2)]
P['ff3'] = [(W, lambda s: foamfirst2(s, True))]

WAKE_LATE = '''    if (wake.x > 0.0) {
        float specks = smoothstep(0.1, 0.65,
            textureGrad(textureArray, vec3(speckUv, MAT_WATER_FOAM.colorMap), speckUvDx, speckUvDy).r);
        // As soft as the ocean waves' specks, and denser along the rim like their fronts
        float amount = oceanSpecks(wake.x * mix(BOAT_WAKE_COVERAGE, 1.0, wake.z), OCEAN_WAVE_SOFTNESS, specks);
        float rimBrightness = mix(1.0, BOAT_WAKE_RIM_BRIGHTNESS, wake.z);
        amount *= wake.y * waveStrength * BOAT_WAKE_STRENGTH * rimBrightness;
        // Where an ocean wave runs into an arm, their foam adds up
        oceanAmount = max(oceanAmount, amount + waveAmount * BOAT_WAKE_WAVE_MERGE);
        foamMask = max(foamMask, specks);
    }
'''
WAKE_EARLY = '''    // The wakes' own foam, worked out before the lace and waves so that less has to be kept through them. Its specks
    // are negative where there's no wake.
    float wakeAmount = 0.0;
    float wakeSpecks = -1.0;
    float churned = wake.w;
    if (wake.x > 0.0) {
        wakeSpecks = smoothstep(0.1, 0.65,
            textureGrad(textureArray, vec3(speckUv, MAT_WATER_FOAM.colorMap), speckUvDx, speckUvDy).r);
        // As soft as the ocean waves' specks, and denser along the rim like their fronts
        wakeAmount = oceanSpecks(wake.x * mix(BOAT_WAKE_COVERAGE, 1.0, wake.z), OCEAN_WAVE_SOFTNESS, wakeSpecks);
        float rimBrightness = mix(1.0, BOAT_WAKE_RIM_BRIGHTNESS, wake.z);
        wakeAmount *= wake.y * waveStrength * BOAT_WAKE_STRENGTH * rimBrightness;
    }
'''
WAKE_AFTER = '''    if (wakeSpecks >= 0.0) {
        // Where an ocean wave runs into an arm, their foam adds up
        oceanAmount = max(oceanAmount, wakeAmount + waveAmount * BOAT_WAKE_WAVE_MERGE);
        foamMask = max(foamMask, wakeSpecks);
    }
'''
def wakeearly(s):
    s = foamfirst2(s, True)
    assert s.count(WAKE_LATE) == 1
    s = s.replace(WAKE_LATE, WAKE_AFTER)
    anchor = '    float waveAmount = 0.0;\n'
    assert s.count(anchor) == 1
    s = s.replace(anchor, WAKE_EARLY + anchor)
    assert s.count('wake.w') == 5, s.count('wake.w')
    head, tail = s.split('    float churned = wake.w;\n')
    return head + '    float churned = wake.w;\n' + tail.replace('wake.w', 'churned')
P['ff4'] = [(W, wakeearly)]

SWEEP_HEAD = '''            firstSegment = true;
            a = vec4(bowPos, 0.0, 0.0);
            for (int i = 0; i <= count; i++) {
                vec4 b = stern;
                if (i > 0) {
                    b = uboGlobal.boatWakePoints[first + i - 1];
                    b.w += bowLength;
                }
                vec2 segment = b.xy - a.xy;
                float length2 = dot(segment, segment);
                if (length2 >= 1e-6) {
                    float t = saturate(dot(p - a.xy, segment) / length2);
                    float dist = length(p - (a.xy + segment * t));
'''
def second(s):
    old = '                if (dist2 < nearest) {\n                    nearest = dist2;\n'
    assert s.count(old) == 1
    s = s.replace(old, '                if (dist2 < nearest) {\n                    second = nearest;\n                    nearest = dist2;\n')
    old = '                    outside = beyond;\n                }\n                firstSegment = false;\n'
    assert s.count(old) == 1
    s = s.replace(old, '                    outside = beyond;\n                } else {\n                    second = min(second, dist2);\n                }\n                firstSegment = false;\n')
    old = '        float nearest = 1e9;\n'
    assert s.count(old) == 1
    s = s.replace(old, '        float nearest = 1e9;\n        float second = 1e9; // the next nearest segment\n')
    assert s.count(SWEEP_HEAD) == 1
    i = s.index(SWEEP_HEAD)
    endtext = '                    firstSegment = false;\n                }\n                a = b;\n            }\n        }\n        if (outside)\n'
    j = s.index(endtext, i)
    loop = s[i:j] + '                    firstSegment = false;\n                }\n                a = b;\n            }\n'
    indented = ''.join(('    ' + l if l.strip() else l) for l in loop.splitlines(True))
    new = '''            if (sqrt(second) - side >= BOAT_WAKE_SWEPT_RANGE) {
                // No other part of the path is near enough to count, as along any straight stretch
                if (!outside) {
                    float amount = boatWakeSwept(side / sideScale, fromBow / lengthScale,
                        age + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, wakeNoise.breakup) * sweptFade;
                    if (amount > 0.0)
                        addSwept(swept, amount, uboGlobal.elapsedTime - age - hull.y);
                }
            } else {
''' + indented + '            }\n        }\n        if (outside)\n'
    return s[:i] + new + s[j + len(endtext):]
P['second'] = [(W, second)]
P['farskip'] = [(W, '        float side = sqrt(nearest);\n', '        float side = sqrt(nearest);\n        if (side / sideScale >= 6.0)\n            continue;\n')]

MASK_FINAL = '''    // Nearly all water is outside every wake's bounds, so find which wakes may reach here first, as a bit each. A loop
    // of its own this small is much cheaper to get through than one holding all the work below.
    int inside = 0;
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        // Slots are filled in order
        if (uboGlobal.boatWakeHulls[boat].x < 1.0)
            break;
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (all(greaterThanEqual(p, bounds.xy)) && all(lessThanEqual(p, bounds.zw)))
            inside |= 1 << boat;
    }
    for (int boat = 0; inside != 0; boat++, inside >>= 1) {
        if ((inside & 1) == 0)
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
'''
def final(s):
    s = wakeearly(s)
    old = '\n    // Boat wakes first, since they break up the ocean waves\n'
    assert s.count(old) == 1
    s = s.replace(old, '''
    // All the foam is worked out here, before the water's normals and lighting, rather than where it's used. Whatever
    // has been worked out already and is needed again later has to be kept through the foam's loops and noise, which
    // slows the whole shader down, so as little as possible should come before it.

    // Boat wakes first, since they break up the ocean waves
''')
    assert s.count(HEAD) == 1
    s = s.replace(HEAD, MASK_FINAL)
    s = second(s)
    old = '        float second = 1e9; // the next nearest segment\n'
    assert s.count(old) == 1
    s = s.replace(old, '        // And the next nearest part of it, from another segment\n        float second = 1e9;\n')
    old = '                // No other part of the path is near enough to count, as along any straight stretch\n'
    assert s.count(old) == 1
    s = s.replace(old, '                // No other segment is near enough to add anything, as along any straight stretch, so there\'s no need\n                // to go through them all again\n')
    return s
P['final'] = [(W, final)]
