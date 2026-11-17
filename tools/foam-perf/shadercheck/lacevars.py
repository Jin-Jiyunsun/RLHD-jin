"""Lace noise texture variants. Usage: lacevars.py  -> vars/ln_base, ln_tex, ln_texcells"""
import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))

TEXFN = '''
uniform sampler2D laceNoise;

// As noise2, from a 256x256 tiling texture of random values. Filtering blends the texels; moving the lookup by the
// smoothstep curve's offset within each cell gives noise2's smooth blend with one read.
vec2 laceNoise2(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return textureLod(laceNoise, (i + u + 0.5) / 256.0, 0.0).xy;
}

// Distance to the nearest edge'''

def patch(s, cells):
    s = s.replace('\r\n', '\n')
    a = '\n// Distance to the nearest edge'
    assert s.count(a) == 1
    s = s.replace(a, TEXFN, 1)
    for old in ['vec2 swirl = noise2(', 'vec2 curl = noise2(', 'gapStart + 0.12, noise2(']:
        assert s.count(old) == 1, old
        s = s.replace(old, old.replace('noise2(', 'laceNoise2('))
    old = 'float dense = noise(drifted / OCEAN_LACE_DENSE_SIZE + vec2(wt, -0.7 * wt));'
    assert s.count(old) == 1
    s = s.replace(old, 'float dense = laceNoise2(drifted / OCEAN_LACE_DENSE_SIZE + vec2(wt, -0.7 * wt)).y;')
    if cells:
        old = '            vec2 h = hash22(i + offset);'
        assert s.count(old) == 1
        s = s.replace(old, '            vec2 h = texelFetch(laceNoise, ivec2(i + offset) & 255, 0).xy;')
    return s

for name, mode in [('ln_base', None), ('ln_tex', False), ('ln_texcells', True)]:
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=lambda d, fs: [f for f in fs if not (f.endswith('.glsl') or os.path.isdir(os.path.join(d, f)))])
    if mode is not None:
        p = os.path.join(out, 'utils', 'water.glsl')
        s = open(p, encoding='utf-8', newline='').read()
        open(p, 'w', encoding='utf-8', newline='').write(patch(s, mode))
    print(out)
