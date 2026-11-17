"""Build a test fragment shader for one boat wake over the ocean waves, from 117 HD's own code.
Usage: wake_shader.py <rs117/hd folder> <out file>"""
import re
import sys

hd, out = sys.argv[1], sys.argv[2]


def read(name):
    return open(hd + '/' + name, encoding='utf-8').read().replace('\r\n', '\n')


def block(src, start_pattern):
    """A definition starting with the pattern, through its matching closing brace"""
    m = re.search(start_pattern, src, re.M)
    if not m:
        sys.exit('missing ' + start_pattern)
    i = src.index('{', m.start())
    depth = 0
    while True:
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i + 1
    if src[end] == ';':
        end += 1
    return src[m.start():end]


def functions(src, names):
    found = []
    for n in names:
        starts = [m.start() for m in re.finditer(r'^[a-z][a-z0-9]* ' + n + r'\(', src, re.M)]
        if not starts:
            sys.exit('missing ' + n)
        found += [block(src[i:], r'^') for i in starts]
    return '\n\n'.join(found)


water = read('utils/water.glsl')
constants = read('utils/constants.glsl')
defines = [l for l in constants.split('\n') if re.match(r'#define (PI|TAU)\b', l)]
defines += [l for l in water.split('\n') if l.startswith('#define ')]

code = functions(water, ['noise2', 'foamBreakupNoise', 'foamLifeLeft', 'foamLasting',
                         'foamEndFade', 'oceanWaveCells', 'oceanWaves'])
code += '\n\n' + block(water, r'^struct WakeNoise')
code += '\n\n' + functions(water, ['boatWakeFoam', 'boatWakeSwept', 'addSwept', 'boatWakes', 'oceanSpecks'])

main = '''
uniform float uTime;
uniform vec2 uOrigin; // tiles at the bottom left
uniform float uSpan; // tiles across
uniform vec2 uSize; // pixels
uniform vec4 uPoints[BOAT_WAKE_MAX_POINTS];
uniform vec4 uBounds, uHeading, uHull, uBoatSize;
uniform int uShowSwept;
out vec4 fragColor;

void main() {
    uboGlobal.elapsedTime = uTime;
    uboGlobal.sceneBase = ivec2(0);
    for (int i = 0; i < WIND_HISTORY_LENGTH / 4; i++) {
        uboGlobal.windHistory[i] = vec4(0.0);
        uboGlobal.roughnessHistory[i] = vec4(1.0);
    }
    for (int i = 0; i < BOAT_WAKE_MAX_POINTS; i++)
        uboGlobal.boatWakePoints[i] = uPoints[i];
    uboGlobal.boatWakeBounds[0] = uBounds;
    uboGlobal.boatWakeHeadings[0] = uHeading;
    uboGlobal.boatWakeHulls[0] = uHull;
    uboGlobal.boatWakeSizes[0] = uBoatSize;

    vec2 p = uOrigin + gl_FragCoord.xy / uSize * uSpan;
    vec4 swept;
    vec4 wake = boatWakes(p, swept);
    // Waves, as sampleWater draws them, without the speck texture
    float waves = 0.0;
    if (wake.w < 1.0) {
        vec2 w = oceanWaves(p, float(SHORE_DISTANCE_MAX), vec2(0.0), swept);
        waves = saturate(w.x * (1.0 - wake.w) * w.y * OCEAN_WAVE_STRENGTH / 0.13);
    }
    float foam = saturate(wake.x * mix(BOAT_WAKE_COVERAGE, 1.0, wake.z) * wake.y * BOAT_WAKE_STRENGTH);
    vec3 color = vec3(0.04, 0.16, 0.22);
    // Swept area tinted red, churned area tinted blue
    if (uShowSwept != 0) {
        color = mix(color, vec3(0.55, 0.08, 0.08), swept.x * 0.6);
        color = mix(color, vec3(0.1, 0.3, 0.6), wake.w * 0.5);
    }
    color = mix(color, vec3(0.92, 0.96, 0.98), max(waves, foam));
    // The hull outline
    vec2 h = uHeading.xy;
    vec2 rel = p - uHeading.zw;
    float along = dot(rel, h), across = abs(dot(rel, vec2(-h.y, h.x)));
    if (along > 0.0 && along < BOAT_WAKE_BOW_LENGTH && across < BOAT_HULL_WIDTH * 0.5 * sqrt(saturate((BOAT_WAKE_BOW_LENGTH - along) / BOAT_HULL_BOW_LENGTH)))
        color = mix(color, vec3(0.45, 0.3, 0.15), 0.8);
    fragColor = vec4(color, 1.0);
}
'''

shader = '\n'.join([
    '#version 330',
    '#define SHORE_DISTANCE_MAX 12',
    '#define WIND_HISTORY_LENGTH 32',
    '#define BOAT_WAKE_MAX_BOATS 1',
    '#define BOAT_WAKE_MAX_POINTS 40',
    *defines,
    '',
    'struct UBOStub { float elapsedTime; ivec2 sceneBase; vec4 windHistory[WIND_HISTORY_LENGTH / 4];'
    ' vec4 roughnessHistory[WIND_HISTORY_LENGTH / 4]; vec4 boatWakePoints[BOAT_WAKE_MAX_POINTS];'
    ' vec4 boatWakeBounds[1]; vec4 boatWakeHeadings[1]; vec4 boatWakeHulls[1]; vec4 boatWakeSizes[1]; };',
    'UBOStub uboGlobal;',
    '',
    read('utils/hash.glsl').replace('#pragma once', ''),
    functions(read('utils/misc.glsl'), ['saturate', 'noise']),
    code,
    main])
open(out, 'w', encoding='utf-8', newline='\n').write(shader)
print('wrote', out)
