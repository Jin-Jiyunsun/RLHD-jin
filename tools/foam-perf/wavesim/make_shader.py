"""Build a test fragment shader from 117 HD's own ocean wave code.
Usage: make_shader.py <rs117/hd folder> <out file> [gallery]
In gallery mode, one wave cell is drawn on its own, always spawning, from the start of a lifetime."""
import re
import sys

hd, out = sys.argv[1], sys.argv[2]
gallery = len(sys.argv) > 3 and sys.argv[3] == 'gallery'


def inject(src, old, new):
    if src.count(old) != 1:
        sys.exit('cannot find: ' + old)
    return src.replace(old, new)


def read(name):
    return open(hd + '/' + name, encoding='utf-8').read().replace('\r\n', '\n')


def functions(src, names):
    """Every definition of each named function, overloads included, by brace matching"""
    found = []
    for name in names:
        for m in re.finditer(r'^[a-z][a-z0-9]* ' + name + r'\([^)]*\)\s*\{', src, re.M):
            depth, i = 0, m.end() - 1
            while True:
                if src[i] == '{':
                    depth += 1
                elif src[i] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            found.append(src[m.start():i + 1])
        if not any(re.match(r'^[a-z][a-z0-9]* ' + name + r'\(', f) for f in found):
            sys.exit('missing function ' + name)
    return '\n\n'.join(found)


water = read('utils/water.glsl')
constants = read('utils/constants.glsl')
defines = [l for l in constants.split('\n') if re.match(r'#define (PI|TAU)\b', l)]
# The settings at the top of water.glsl, before its first function
defines += [l for l in water.split('\n') if l.startswith('#define ')]

wave_code = functions(water, ['noise2', 'foamBreakupNoise', 'foamLifeLeft', 'foamLasting',
                              'foamEndFade', 'oceanWaveCells', 'oceanWaves'])
if gallery:
    wave_code = inject(wave_code, 'vec2 cell = cell0 + vec2(x, y);',
                       'vec2 cell = cell0 + vec2(x, y);\n            if (any(notEqual(cell, uCell))) continue;')
    wave_code = inject(wave_code, 'float chance = saturate(OCEAN_WAVE_AMOUNT * spawnRoughness);',
                       'float chance = 1.0;')

gallery_main = '''
uniform float uLocal; // seconds into the wave's lifetime
uniform float uWindAngle;
uniform float uRoughness;
uniform vec2 uTileOrigin; // pixels
uniform float uTileSize; // pixels
out vec4 fragColor;

void main() {
    // The wave's 100th lifetime, from its start
    vec2 h = hash22(uCell + 0.31);
    uboGlobal.elapsedTime = (100.0 - h.x) * OCEAN_WAVE_LIFETIME + uLocal;
    for (int i = 0; i < WIND_HISTORY_LENGTH / 4; i++) {
        uboGlobal.windHistory[i] = vec4(uWindAngle);
        uboGlobal.roughnessHistory[i] = vec4(uRoughness);
    }
    // The wave's own cell and around it, without the drift, which only moves it about a tile over its life
    vec2 local = (gl_FragCoord.xy - uTileOrigin) / uTileSize;
    vec2 drifted = (uCell + 0.5 + (local - 0.5) * 4.4) * OCEAN_WAVE_SIZE;
    vec2 waves = oceanWaves(drifted, float(SHORE_DISTANCE_MAX), vec2(0.0), vec4(0.0, -1e9, 0.0, -1e9));
    float foam = saturate(waves.x * waves.y * OCEAN_WAVE_STRENGTH * uRoughness / 0.13);
    vec3 color = mix(vec3(0.04, 0.16, 0.22), vec3(0.92, 0.96, 0.98), foam);
    vec2 grid = abs(fract(drifted) - 0.5);
    if (max(grid.x, grid.y) > 0.5 - 4.4 * OCEAN_WAVE_SIZE / uTileSize * 0.5)
        color += 0.025;
    fragColor = vec4(color, 1.0);
}
'''

shader = '\n'.join([
    '#version 330',
    '#define SHORE_DISTANCE_MAX 12',
    '#define WIND_HISTORY_LENGTH 32',
    *defines,
    '',
    'struct UBOStub { float elapsedTime; vec4 windHistory[WIND_HISTORY_LENGTH / 4];'
    ' vec4 roughnessHistory[WIND_HISTORY_LENGTH / 4]; };',
    'UBOStub uboGlobal;',
    '',
    read('utils/hash.glsl').replace('#pragma once', ''),
    functions(read('utils/misc.glsl'), ['saturate', 'noise']),
    'uniform vec2 uCell;' if gallery else '',
    wave_code,
    gallery_main if gallery else '''
uniform float uTime;
uniform float uWindAngle;
uniform float uRoughness;
uniform vec2 uOrigin; // world tiles at the bottom left
uniform float uSpan; // tiles across
uniform vec2 uSize; // pixels
out vec4 fragColor;

void main() {
    uboGlobal.elapsedTime = uTime;
    for (int i = 0; i < WIND_HISTORY_LENGTH / 4; i++) {
        uboGlobal.windHistory[i] = vec4(uWindAngle);
        uboGlobal.roughnessHistory[i] = vec4(uRoughness);
    }
    vec2 wind = vec2(cos(uWindAngle), sin(uWindAngle));
    vec2 world = uOrigin + gl_FragCoord.xy / uSize * uSpan;
    // As the water shader drifts it, at the default wind speed
    vec2 drifted = world - wind * uTime * OCEAN_FOAM_DRIFT_SPEED;
    vec2 waves = oceanWaves(drifted, float(SHORE_DISTANCE_MAX), vec2(0.0), vec4(0.0, -1e9, 0.0, -1e9));
    // Opacity before lighting and the speck texture, as the water shader scales it, brightest at the fronts
    float foam = saturate(waves.x * waves.y * OCEAN_WAVE_STRENGTH * uRoughness / 0.13);
    vec3 water = vec3(0.04, 0.16, 0.22);
    vec3 color = mix(water, vec3(0.92, 0.96, 0.98), foam);
    // A faint tile grid
    vec2 grid = abs(fract(world) - 0.5);
    if (max(grid.x, grid.y) > 0.5 - uSpan / uSize.x * 0.5)
        color += 0.025;
    fragColor = vec4(color, 1.0);
}
'''])
open(out, 'w', encoding='utf-8', newline='\n').write(shader)
print('wrote', out)
