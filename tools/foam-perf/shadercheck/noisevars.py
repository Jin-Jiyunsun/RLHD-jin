"""Water noise texture variants from the repo's shaders. Usage: noisevars.py -> vars/nz_<name>"""
import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))

T1 = '''
// As noise, but read from waterNoise like waterNoise2
float waterNoise1(vec2 st) {
    return waterNoise2(st).x;
}

// Distance to the nearest edge'''

EDITS = {
    'waves': [
        ('ragged = (noise(q * 2.2', 'ragged = (waterNoise1(q * 2.2'),
        ('ragged += (noise(q * 5.1', 'ragged += (waterNoise1(q * 5.1'),
        ('ragged += (noise(q * 11.3', 'ragged += (waterNoise1(q * 11.3'),
        ('ragged += (noise(q * 23.7', 'ragged += (waterNoise1(q * 23.7'),
        ('float streaks = noise(vec2(u * 1.6', 'float streaks = waterNoise1(vec2(u * 1.6'),
    ],
    'breakup': [
        ('    return noise(q * 3.7 + time * vec2(-0.09, -0.05) + 21.7);', '    return waterNoise1(q * 3.7 + time * vec2(-0.09, -0.05) + 21.7);'),
    ],
    'shore': [
        ('float lag = noise(shorePoint * 0.11', 'float lag = waterNoise1(shorePoint * 0.11'),
        ('vec2 r = noise2(shorePoint / SHORE_WAVE_PIECE_SIZE', 'vec2 r = waterNoise2(shorePoint / SHORE_WAVE_PIECE_SIZE'),
        ('float sets = noise(vec2(n * SHORE_WAVE_INTERVAL', 'float sets = waterNoise1(vec2(n * SHORE_WAVE_INTERVAL'),
        ('float stopNoise = noise(shorePoint * 0.2', 'float stopNoise = waterNoise1(shorePoint * 0.2'),
        ('float edgeNoise = noise(worldUvs(0.4)', 'float edgeNoise = waterNoise1(worldUvs(0.4)'),
        ('vec2 foamWarp = noise2(worldUvs(1.5)', 'vec2 foamWarp = waterNoise2(worldUvs(1.5)'),
    ],
    'wakes': [
        ('float ragged = (noise((world - outward * foamLine) * 1.3)', 'float ragged = (waterNoise1((world - outward * foamLine) * 1.3)'),
        ('wakeNoise.endRagged = noise(world * 1.3 + 7.1);', 'wakeNoise.endRagged = waterNoise1(world * 1.3 + 7.1);'),
    ],
}
VARIANTS = {'base': [], 'waves': ['waves'], 'breakup': ['breakup'], 'shore': ['shore'], 'wakes': ['wakes'],
            'all': ['waves', 'breakup', 'shore', 'wakes']}

for name, groups in VARIANTS.items():
    out = os.path.join(here, 'vars', 'nz_' + name)
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=lambda d, fs: [f for f in fs if not (f.endswith('.glsl') or os.path.isdir(os.path.join(d, f)))])
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    if groups:
        a = '\n// Distance to the nearest edge'
        assert s.count(a) == 1
        s = s.replace(a, T1, 1)
    for g in groups:
        for old, new in EDITS[g]:
            assert s.count(old) == 1, (g, old)
            s = s.replace(old, new)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print(name)
