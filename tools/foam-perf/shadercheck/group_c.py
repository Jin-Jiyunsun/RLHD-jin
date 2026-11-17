import os

ROOT = r'C:\Users\Jin\IdeaProjects\RLHD-jin-shore-foam'
W = 'src/main/resources/rs117/hd/utils/water.glsl'
C = 'src/main/resources/rs117/hd/utils/constants.glsl'
G = 'src/main/resources/rs117/hd/uniforms/global.glsl'
P = 'src/main/java/rs117/hd/HdPlugin.java'

text = open(os.path.join(ROOT, W), encoding='utf-8', newline='').read().replace('\r\n', '\n')

# The foam lighting, without its old-foam alternative, one level shallower
start = text.index('        #if IMPROVED_SHORE_FOAM\n            vec3 foamColor = vec3(0);\n')
end_marker = ('        #else\n            vec3 foamColor = waterType.foamColor * foamMask * compositeLight;\n'
              '            float litFoamAmount = foamAmount * foamColor.r;\n        #endif\n')
end = text.index(end_marker, start) + len(end_marker)
lighting_old = text[start:end]
inner = lighting_old[len('        #if IMPROVED_SHORE_FOAM\n'):-len(end_marker)]
lighting_new = ''.join(line[4:] if line.startswith('    ') else line for line in inner.splitlines(keepends=True))

edits = {
    W: [
        (lighting_old, lighting_new),
        ('''    #if IMPROVED_SHORE_FOAM
        float foamMask = 0;
    #else
        vec2 uvFoam = IN.uv + uvFlow * flowMapStrength;
        float foamMask = texture(textureArray, vec3(uvFoam, MAT_WATER_FOAM.colorMap)).r;
    #endif
''',
         '''    // The foam's texture wherever there's foam, for shading it
    float foamMask = 0;
'''),
        ('''    #if IMPROVED_SHORE_FOAM
    float foamAmount = 0;
    // Skip faces neither near the shore nor on open water. Uniform per face, so texture derivatives stay valid. Cast,
    // since max is ambiguous with whole number settings.
    float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * SHORE_DISTANCE_STEPS));
    ivec3 enclosureSteps = fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
    const int openWaterSteps = int(ceil((1.0 - float(SHORE_WAVE_MIN_ROOM) / WATER_ROOM_MAX) * WATER_ENCLOSURE_STEPS));
''',
         '''    float foamAmount = 0;
    // Skip faces neither near the shore nor on open water. Uniform per face, so texture derivatives stay valid. Cast,
    // since max is ambiguous with whole number settings.
    const float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    const int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * SHORE_DISTANCE_STEPS));
    ivec3 enclosureSteps = fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
    const int openWaterSteps = int(ceil((1.0 - float(SHORE_WAVE_MIN_ROOM) / SHORE_DISTANCE_MAX) * WATER_ENCLOSURE_STEPS));
'''),
        ('''    // Rougher water, from the wind strength, has more and stronger waves, more visible lace and faster churn. Normal
    // until the roughness is first set.
    float roughness = uboGlobal.waterRoughness > 0.0 ? uboGlobal.waterRoughness : 1.0;
    float waveStrength = OCEAN_WAVE_STRENGTH * pow(roughness, OCEAN_WAVE_ROUGHNESS_STRENGTH);
''',
         '''    // Rougher water, from the wind strength, has more and stronger waves, more visible lace and faster churn. The
    // waves are stronger in proportion to it.
    float roughness = uboGlobal.waterRoughness;
    float waveStrength = OCEAN_WAVE_STRENGTH * roughness;
'''),
        ('''        // Direction to land, where known. Blending vectors avoids wrapping, and weakens where vertices disagree.
        vec2 shoreDir = vec2(0);
        vec2 towardsShore = vec2(0);
        if (any(lessThan(shoreDistances, ivec3(SHORE_DISTANCE_STEPS)))) {
''',
         '''        // Direction to land, where known, for the shore foam and ocean waves. Blending vectors avoids wrapping, and
        // weakens where vertices disagree.
        vec2 shoreDir = vec2(0);
        vec2 towardsShore = vec2(0);
        if ((nearShore || bool(OCEAN_WAVES)) && any(lessThan(shoreDistances, ivec3(SHORE_DISTANCE_STEPS)))) {
'''),
        ('''        // Nearest point on the shore, in world tiles, which stays put as waves come in
        vec2 shorePoint = -worldUvs(1.0) + towardsShore * shoreDist;

''', ''),
        ('''        float waterRoom = (1.0 - dot(fTexBlend, vec3(enclosureSteps)) / WATER_ENCLOSURE_STEPS) * WATER_ROOM_MAX;
''',
         '''        float waterRoom = (1.0 - dot(fTexBlend, vec3(enclosureSteps)) / WATER_ENCLOSURE_STEPS) * SHORE_DISTANCE_MAX;
'''),
        ('''            sway *= 1.0 - smoothstep(foamReach, 4.0, shoreDist);
''',
         '''            sway *= 1.0 - smoothstep(foamReach, SHORE_FOAM_SWAY_REACH, shoreDist);
'''),
        ('''#define SHORE_FOAM_SWAY 0.08               // how far the foam sways in and out, in tiles
''',
         '''#define SHORE_FOAM_SWAY 0.08               // how far the foam sways in and out, in tiles
#define SHORE_FOAM_SWAY_REACH 4.0          // distance from the shore the sway has faded out by, in tiles
'''),
        ('''            float wave = 0.0;
            if (roomForWaves > 0.0 && waveOpacity > 0.0)
                wave = shoreWaves(shoreDist, shorePoint, foamMask, roomForWaves);
''',
         '''            float wave = 0.0;
            if (roomForWaves > 0.0 && waveOpacity > 0.0) {
                // Nearest point on the shore, in world tiles, which stays put as waves come in
                vec2 shorePoint = -worldUvs(1.0) + towardsShore * shoreDist;
                wave = shoreWaves(shoreDist, shorePoint, foamMask, roomForWaves);
            }
'''),
        ('''            vec2 wind = oceanWind();
            float churnTime = uboGlobal.waterRoughness > 0.0 ? uboGlobal.waterChurnTime : uboGlobal.elapsedTime;
            if (bool(OCEAN_LACE)) {
                float laceRoughness = pow(roughness, OCEAN_LACE_ROUGHNESS);
                // Denser as well as stronger, up to solid line cores
                float lace = saturate(oceanLace(drifted, wind, churnTime) * sqrt(laceRoughness)) * oceanRoom;
                float laceStrength = OCEAN_LACE_STRENGTH * laceRoughness;
                foamAmount = max(foamAmount, oceanSpecks(lace, OCEAN_LACE_SOFTNESS, oceanMask) * laceStrength);
''',
         '''            if (bool(OCEAN_LACE)) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * oceanRoom;
                float laceStrength = OCEAN_LACE_STRENGTH * roughness;
                foamAmount = max(foamAmount, oceanSpecks(lace, OCEAN_LACE_SOFTNESS, oceanMask) * laceStrength);
'''),
        ('''    #else
        // The original foam only checks whether each vertex is on the shore
        float shoreLineMask = 1 - dot(fTexBlend, vec3(notEqual(shoreDistances, ivec3(0))));
        float foamAmount = min(shoreLineMask, 0.8);
        foamAmount = clamp(pow(1.0 - ((1.0 - foamAmount) / 0.7), 3), 0.0, 1.0) * waterType.hasFoam;
    #endif
''', ''),
        ('''    // With the new shore foam off, shores keep the original fade along tile edges, lit the original way below
    float originalFoam = 0.0;
    float originalMask = 0.0;
    if (!bool(SHORE_FOAM)) {
        float shoreLineMask''',
         '''    // With the new shore foam off, shores keep the original fade along tile edges, which only checks whether each
    // vertex is on the shore, lit the original way below
    float originalFoam = 0.0;
    float originalMask = 0.0;
    if (!bool(SHORE_FOAM)) {
        float shoreLineMask'''),
        ('''    #if IMPROVED_SHORE_FOAM
    // How much more opaque foam is under a low sun''',
         '''    // How much more opaque foam is under a low sun'''),
        ('''        foamLowSun = (1.0 - smoothstep(0.0, 0.5, sunHeight)) * smoothstep(0.0, 0.2, sunHeight);
    }
    #endif
''',
         '''        foamLowSun = (1.0 - smoothstep(0.0, 0.5, sunHeight)) * smoothstep(0.0, 0.2, sunHeight);
    }
'''),
        ('''// Wind direction along local X and Z, east if unset
vec2 oceanWind() {
    vec2 wind = uboGlobal.windDirection;
    return dot(wind, wind) > 0.25 ? normalize(wind) : vec2(1.0, 0.0);
}

''', ''),
        ('''#define OCEAN_LACE_ROUGHNESS 1.0           // how much rougher water strengthens the lace, as a power of the roughness
''', ''),
        ('''#define OCEAN_WAVE_ROUGHNESS_STRENGTH 1.0  // how much rougher water strengthens them, as a power of the roughness
''', ''),
        ('''#define SHORE_WAVE_FULL_ROOM 6.0           // and for full strength, up to WATER_ROOM_MAX
''',
         '''#define SHORE_WAVE_FULL_ROOM 6.0           // and for full strength, up to SHORE_DISTANCE_MAX
'''),
        # Readable names inside the travel distance again
        ('''float waveTravelDistance(float shoreDist) {
    if (shoreWaveEndSpeed >= 0.999)
        return shoreDist;
    // Full speed beyond the reach, and stopping short of zero speed inside the shore
    float d = clamp(shoreDist, -0.9 * shoreWaveEndSpeed / (1.0 - shoreWaveEndSpeed) * shoreWaveReach, shoreWaveReach);
    float travel = shoreWaveReach / (1.0 - shoreWaveEndSpeed) * log((shoreWaveEndSpeed + (1.0 - shoreWaveEndSpeed) * d / shoreWaveReach) / shoreWaveEndSpeed);
    return travel + max(shoreDist - shoreWaveReach, 0.0);
}
''',
         '''float waveTravelDistance(float shoreDist) {
    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
    if (endSpeed >= 0.999)
        return shoreDist;
    // Full speed beyond the reach, and stopping short of zero speed inside the shore
    float d = clamp(shoreDist, -0.9 * endSpeed / (1.0 - endSpeed) * reach, reach);
    float travel = reach / (1.0 - endSpeed) * log((endSpeed + (1.0 - endSpeed) * d / reach) / endSpeed);
    return travel + max(shoreDist - reach, 0.0);
}
'''),
    ],
    C: [
        ('#include IMPROVED_SHORE_FOAM\n', ''),
        ('#include WATER_ROOM_MAX\n', ''),
    ],
    G: [
        ('    vec2 windDirection; // along local X and Z\n', '    vec2 windDirection; // along local X and Z, unit length\n'),
        ('    float detailDrawDistance; // in tiles, 0 until first set\n', '    float detailDrawDistance; // in tiles\n'),
    ],
    P: [
        ('''\t\t\t.define("IMPROVED_SHORE_FOAM",
\t\t\t\tconfig.improvedShoreFoam() || config.oceanLace() || config.oceanWaves() || config.boatWakes())
''', ''),
        ('''\t\t\t.define("WATER_ROOM_MAX", SceneContext.SHORE_DISTANCE_MAX)
''', ''),
    ],
}
