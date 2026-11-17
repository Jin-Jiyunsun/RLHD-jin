W = 'utils/water.glsl'
P = {}

# Diagnostics: stop the ocean wave loop after each stage
P['wv1'] = [(W, '''            if (spawnHash >= saturate(OCEAN_WAVE_AMOUNT * uboGlobal.waveMaxRoughness))
                continue;
''', '''            if (spawnHash >= saturate(OCEAN_WAVE_AMOUNT * uboGlobal.waveMaxRoughness))
                continue;
            if (spawnHash < 2.0) { result = max(result, spawnHash * 0.01); continue; }
''')]
P['wv2'] = [(W, '''            if (dot(offset, offset) > reach * reach)
                continue;
''', '''            if (dot(offset, offset) > reach * reach)
                continue;
            if (spawnHash < 2.0) { result = max(result, spawnHash * 0.01); continue; }
''')]
P['wv3'] = [(W, '''            if (life >= 1.0)
                continue;
''', '''            if (life >= 1.0)
                continue;
            if (spawnHash < 2.0) { result = max(result, life * 0.01); continue; }
''')]
P['wv4'] = [(W, '''            if (abs(u) > maxSweep || abs(v) > 1.6)
                continue;
''', '''            if (abs(u) > maxSweep || abs(v) > 1.6)
                continue;
            if (spawnHash < 2.0) { result = max(result, abs(u * v) * 0.01); continue; }
''')]
P['wv5'] = [(W, '''            if (behind + maxRagged * raggedness <= -0.06 || behind - maxRagged * raggedness >= thickness)
                continue;
''', '''            if (behind + maxRagged * raggedness <= -0.06 || behind - maxRagged * raggedness >= thickness)
                continue;
            if (spawnHash < 2.0) { result = max(result, abs(behind) * 0.01); continue; }
''')]
P['wv6'] = [(W, '''            if (cover <= 0.0)
                continue;
            if (!breakupReady) {''', '''            if (cover <= 0.0)
                continue;
            if (spawnHash < 2.0) { result = max(result, cover); continue; }
            if (!breakupReady) {''')]
# No waves at all, but the call kept
P['wv0'] = [(W, '''    vec2 cell0 = floor(q);
    float result = 0.0;
    float age = 0.0;
''', '''    vec2 cell0 = floor(q);
    float result = 0.0;
    float age = 0.0;
    if (time > -1.0) return vec2(fract(q.x) * 0.01, 0.0);
''')]

# Diagnostics: stop the lace after each stage
P['lc1'] = [(W, '''    if (laceFade <= 0.0)
        return 0.0;
''', '''    if (laceFade <= 0.0)
        return 0.0;
    if (time > -1.0) return laceFade * 0.3;
''')]
P['lc2'] = [(W, '''    float width = OCEAN_LACE_LINE_WIDTH * (1.0 + (swirl.x - 0.5) * 2.0 * OCEAN_LACE_WIDTH_VARIATION);
''', '''    float width = OCEAN_LACE_LINE_WIDTH * (1.0 + (swirl.x - 0.5) * 2.0 * OCEAN_LACE_WIDTH_VARIATION);
    if (time > -1.0) return fract(p.x + p.y) * width;
''')]
P['lc3'] = [(W, '''    if (gaps <= 0.0)
        return 0.0;
''', '''    if (gaps <= 0.0)
        return 0.0;
    if (time > -1.0) return gaps * width;
''')]
P['lc4'] = [(W, '''    if (lace <= 0.0)
        return 0.0;

    // Denser areas come and go''', '''    if (lace <= 0.0)
        return 0.0;
    if (time > -1.0) return lace;

    // Denser areas come and go''')]

# Shore diagnostics
P['sh_nowaves'] = [(W, '''    if (inward <= 0.0)
        return 0.0;
''', '''    if (inward <= 0.0)
        return 0.0;
    if (time > -1.0) return fract(inward) * 0.5;
''')]
P['sh_onetrain'] = [(W, '''    for (int k = 0; k < 3; k++) {
        float lag =''', '''    for (int k = 0; k < 1; k++) {
        float lag =''')]
P['sh_nolag'] = [(W, '''        float lag = noise(shorePoint * 0.11 + 3.7 + float(k) * vec2(17.3, 9.1)) * 1.2;''', '''        float lag = fract(shorePoint.x * 0.11 + float(k) * 0.37) * 1.2;''')]
P['sh_notex'] = [(W, '''                foamSum += weight * weight * texture(textureArray, vec3(foamUv + offset, MAT_WATER_FOAM.colorMap)).r;''', '''                foamSum += weight * weight * fract(foamUv.x + offset.x);''')]

# Shore waves: the breakup noise only once a wave needs it
P['sh_lazy'] = [(W, '''    // Which foam goes first as it breaks up, finer than the ocean waves'
    float breakup = foamBreakupNoise(world / 2.5, time);
    float wave = 0.0;
''', '''    // Which foam goes first as it breaks up, finer than the ocean waves', worked out once a wave needs it
    bool breakupReady = false;
    float breakup = 0.0;
    float wave = 0.0;
'''), (W, '''        // Breaking up where it was laid, like the ocean waves' foam, with the whole wave gone by the end of its life
        float lasting =''', '''        if (!breakupReady) {
            breakupReady = true;
            breakup = foamBreakupNoise(world / 2.5, time);
        }
        // Breaking up where it was laid, like the ocean waves' foam, with the whole wave gone by the end of its life
        float lasting =''')]
