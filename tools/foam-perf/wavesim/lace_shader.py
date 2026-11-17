"""Build a fragment shader that draws only 117 HD's lace, for timing and comparing it.
Usage: lace_shader.py <water.glsl> <rs117/hd folder> <out file>"""
import re
import sys

water_path, hd, out = sys.argv[1], sys.argv[2], sys.argv[3]


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
names = ['noise2', 'cellEdges', 'oceanLace']
names += [n for n in ['laceCellEdges'] if re.search(r'^[a-z][a-z0-9]* ' + n + r'\(', water, re.M)]

shader = '\n'.join([
    '#version 330',
    *defines,
    'struct UBOStub { float elapsedTime; vec3 cameraPos; float detailDrawDistance; };',
    'UBOStub uboGlobal;',
    'struct InStub { vec3 position; };',
    'InStub IN;',
    read(hd + '/utils/hash.glsl').replace('#pragma once', ''),
    functions(read(hd + '/utils/misc.glsl'), ['saturate', 'noise']),
    functions(water, names),
    '''
uniform float uTime;
uniform vec2 uSize;
uniform float uSpan;
out vec4 fragColor;

void main() {
    uboGlobal.elapsedTime = uTime;
    uboGlobal.cameraPos = vec3(0.0);
    uboGlobal.detailDrawDistance = 1000.0;
    vec2 world = vec2(3000.0) + gl_FragCoord.xy / uSize.x * uSpan;
    IN.position = vec3(0.0);
    float wa = 0.7;
    float lace = oceanLace(world, vec2(cos(wa), sin(wa)), uTime);
    fragColor = vec4(vec3(lace), 1.0);
}
'''])
open(out, 'w', encoding='utf-8', newline='\n').write(shader)
print('wrote', out)
