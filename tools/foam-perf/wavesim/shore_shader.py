"""Build a fragment shader that draws only 117 HD's shore foam and shore waves, along straight shores repeating every
SPACING tiles, so every pixel is near a shore. Usage: shore_shader.py <water.glsl> <rs117/hd folder> <out file>"""
import re
import sys

water_path, hd, out = sys.argv[1], sys.argv[2], sys.argv[3]
SPACING = 3.0


def read(path):
    return open(path, encoding='utf-8').read().replace('\r\n', '\n')


def block(src, start):
    i = src.index('{', start)
    depth = 0
    while True:
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
        i += 1


def functions(src, names):
    found = []
    for n in names:
        starts = [m.start() for m in re.finditer(r'^[a-z][a-z0-9]* ' + n + r'\(', src, re.M)]
        if not starts:
            sys.exit('missing ' + n)
        found += [block(src, i) for i in starts]
    return '\n\n'.join(found)


water = read(water_path)
constants = read(hd + '/utils/constants.glsl')
defines = [l for l in constants.split('\n') if re.match(r'#define (PI|TAU)\b', l)]
defines += [l for l in water.split('\n') if l.startswith('#define ')]
consts = re.findall(r'^const float shoreWave[^;]*;', water, re.M | re.S)

edge_start = water.index('        // Hide tile edges in the foam line')
edge = block(water, water.index('if (nearShore)', edge_start))
shore_start = water.index('        // Shore foam and waves')
shore = block(water, water.index('if (nearShore)', shore_start))
shore = shore.replace('texture(textureArray, vec3(foamUv + offset, MAT_WATER_FOAM.colorMap))', 'texture(uFoam, foamUv + offset)')
shore = shore.replace('-worldUvs(1.0), foamMask', 'gWorld, foamMask')
assert 'textureArray' not in shore

shader = '\n'.join([
    '#version 330',
    *defines,
    'struct UBOStub { float elapsedTime; };',
    'UBOStub uboGlobal;',
    read(hd + '/utils/hash.glsl').replace('#pragma once', ''),
    functions(read(hd + '/utils/misc.glsl'), ['saturate', 'noise']),
    *consts,
    'vec2 gWorld;',
    'vec2 worldUvs(float scale) { return -gWorld / scale; }',
    functions(water, ['waveTravelDistance', 'noise2', 'foamBreakupNoise', 'foamLifeLeft', 'foamLasting', 'foamEndFade',
                      'shoreWaves'] + (['shoreWavesCheap'] if 'shoreWavesCheap(' in water else [])),
    '''
uniform float uTime;
uniform vec2 uSize;
uniform float uSpan;
uniform sampler2D uFoam;
out vec4 fragColor;

void main() {
    uboGlobal.elapsedTime = uTime;
    gWorld = vec2(3000.0) + gl_FragCoord.xy / uSize.x * uSpan;
    // Straight shores every SPACING tiles, with land to the west of each
    float shoreDist = mod(gWorld.x, %s);
    vec2 towardsShore = vec2(-1.0, 0.0);
    vec2 shoreDir = towardsShore;
    bool nearShore = true;
    float roomForWaves = 1.0;
    vec2 uvFlow = vec2(0.0);
    float flowMapStrength = 0.025;
    float foamMask = 0.0;
    float foamAmount = 0.0;
    const float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    %s
    %s
    fragColor = vec4(vec3(foamAmount / SHORE_FOAM_STRENGTH), 1.0);
}
''' % (SPACING, edge, shore)])
open(out, 'w', encoding='utf-8', newline='\n').write(shader)
print('wrote', out)
