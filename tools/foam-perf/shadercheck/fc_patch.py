import sys
d = sys.argv[1]
def edit(path, pairs):
    s = open(path, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    for a, b in pairs:
        assert s.count(a) == 1, (path, s.count(a), a[:70])
        s = s.replace(a, b)
    s = s.replace('\n', '\r\n')
    open(path, 'w', encoding='utf-8', newline='').write(s)

edit(d + '/utils/water.glsl', [
('''#include <utils/misc.glsl>

#if WATER_PASS || LEGACY_RENDERER
''', '''#include <utils/misc.glsl>
#include <utils/ocean_wave_cells.glsl>

#if WATER_PASS || LEGACY_RENDERER
'''),
('''// Open water foam, shared by the lace and ocean waves
#define OCEAN_FOAM_DRIFT_SPEED 0.08        // in tiles per second, scaled by the wind speed
''', '''// Open water foam, shared by the lace and ocean waves. OCEAN_FOAM_DRIFT_SPEED is in ocean_wave_cells.glsl.
'''),
('''// Ocean waves, breaking out at sea
''', '''// Ocean waves, breaking out at sea. The settings for where and when they form are in ocean_wave_cells.glsl.
'''),
('''#define OCEAN_WAVE_STRETCH 1.5             // along the wind, 1 for none
#define OCEAN_WAVE_SIZE 7.0                // spacing, in tiles
#define OCEAN_WAVE_AMOUNT 0.55             // chance of each spot having a wave each lifetime, from 0 to 1
#define OCEAN_WAVE_LIFETIME 14.0           // longest life, in seconds
#define OCEAN_WAVE_RATE_RANDOMNESS 0.4     // how much faster some waves live, from 0 to 1
''', ''),
('''// Ocean wave coverage and foam age, from 0 to 1.''', '''// Whether the vertex shader lists the waves near each water face, as only the zone renderer's does
#define OCEAN_WAVE_FACE_LIST (OCEAN_WAVES && ZONE_RENDERER && WATER_PASS)
#if OCEAN_WAVE_FACE_LIST
    // Position of the face's last vertex in wave cells, and the waves near it, from oceanWaveList
    flat in vec2 fOceanWaveOrigin;
    flat in int fOceanWaveList;
#endif

// Ocean wave coverage and foam age, from 0 to 1.'''),
])
edit(d + '/scene_vert.glsl', [
('''#include <utils/uvs.glsl>
''', '''#include <utils/uvs.glsl>

#include WATER_PASS
'''),
('''    out FragmentData {
        vec3 position;
        vec2 uv;
        vec3 normal;
    } OUT;

    void main() {
        int vertex = gl_VertexID % 3;''', '''    out FragmentData {
        vec3 position;
        vec2 uv;
        vec3 normal;
    } OUT;

    #if WATER_PASS && OCEAN_WAVES
        #include <utils/ocean_wave_cells.glsl>

        // The ocean waves near each water face, for its pixels to search rather than every cell around them
        flat out vec2 fOceanWaveOrigin;
        flat out int fOceanWaveList;
    #endif

    void main() {
        int vertex = gl_VertexID % 3;'''),
('''        fTexBlend = vec3(0);
        fTexBlend[vertex] = 1.0;

        OUT.position = worldPosition;''', '''        fTexBlend = vec3(0);
        fTexBlend[vertex] = 1.0;

        #if WATER_PASS && OCEAN_WAVES
            fOceanWaveOrigin = vec2(0);
            fOceanWaveList = 0;
            if (isProvoking) {
                fOceanWaveOrigin = oceanWaveCellPosition((uboGlobal.sceneBase + worldPosition.xz) / 128.0);
                fOceanWaveList = oceanWaveList(fOceanWaveOrigin);
            }
        #endif

        OUT.position = worldPosition;'''),
])
p = d + '/utils/ocean_wave_cells.glsl'
s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n').replace('\n', '\r\n')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
