import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
MASK = """        if (openWater) {
            // Scrolled with the waves and lace, unlike the boat wakes' specks
            vec2 oceanSpeckUv = speckUv + (waveScroll + regionOffset) / OCEAN_FOAM_TEXTURE_SCALE;
            oceanMask = smoothstep(0.1, 0.65, texture(textureArray, vec3(oceanSpeckUv, MAT_WATER_FOAM.colorMap)).r);
            foamMask = oceanMask;
        }
"""
ROOMS = """        // Lace and ocean waves, fading out near the shore
        float oceanRoom = roomForWaves * smoothstep(OCEAN_FOAM_SHORE_GAP, OCEAN_FOAM_SHORE_FADE, shoreDist);
        float laceRoom = smoothstep(OCEAN_LACE_MIN_ROOM, OCEAN_LACE_FULL_ROOM, waterRoom) *
            smoothstep(OCEAN_LACE_SHORE_GAP, OCEAN_LACE_SHORE_FADE, shoreDist);
"""
LACE = """            if (bool(OCEAN_LACE) && churned < 1.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
                // Fading towards the shore in opacity, keeping its specks as dense
                float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
"""
NEWMASK = ROOMS + """        // Fading towards the shore in opacity, keeping its specks as dense. Skipped where too faint to change a pixel's
        // color, as across narrow rivers.
        float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
        bool lacing = bool(OCEAN_LACE) && laceStrength >= 1.0 / 255.0;
        // Only where something uses them. Explicit derivatives, since this is decided per pixel.
        if (openWater && (lacing || (bool(OCEAN_WAVES) && oceanRoom > 0.0) || wakeSpecks >= 0.0)) {
            // Scrolled with the waves and lace, unlike the boat wakes' specks
            vec2 oceanSpeckUv = speckUv + (waveScroll + regionOffset) / OCEAN_FOAM_TEXTURE_SCALE;
            oceanMask = smoothstep(0.1, 0.65,
                textureGrad(textureArray, vec3(oceanSpeckUv, MAT_WATER_FOAM.colorMap), speckUvDx, speckUvDy).r);
            foamMask = oceanMask;
        }
"""
NEWLACE = """            if (lacing && churned < 1.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
"""
def both(s):
    for old, new in ((ROOMS, ""), (MASK, NEWMASK), (LACE, NEWLACE)):
        assert s.count(old) == 1, old[:60]
        s = s.replace(old, new, 1)
    return s
def maskonly(s):
    # The mask gate alone, lace as now
    new = NEWMASK.replace("bool lacing = bool(OCEAN_LACE) && laceStrength >= 1.0 / 255.0;", "bool lacing = bool(OCEAN_LACE) && laceRoom > 0.0;")
    for old, nw in ((ROOMS, ""), (MASK, new), (LACE, NEWLACE)):
        assert s.count(old) == 1, old[:60]
        s = s.replace(old, nw, 1)
    return s
for name, fn in (('rv_both', both), ('rv_maskonly', maskonly)):
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    s = fn(s)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
