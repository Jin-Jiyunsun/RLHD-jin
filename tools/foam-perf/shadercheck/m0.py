import shutil, os, re
def make(name, fn):
    d = 'vars/' + name
    if os.path.exists(d): shutil.rmtree(d)
    shutil.copytree('vars/fc_nolist', d)
    shutil.copy('vars/fc/utils/ocean_wave_cells.glsl', d + '/utils/ocean_wave_cells.glsl')
    p = d + '/utils/ocean_wave_cells.glsl'
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    s = fn(s)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
def lit(s):
    # The margin as a literal zero throughout the shared function
    a = s.index('bool oceanWaveNear('); b = s.index('// A spot\'s position in wave cells')
    body = s[a:b]
    body = body.replace('float margin, out OceanWave wave) {', 'float marginUnused, out OceanWave wave) {\n    const float margin = 0.0;')
    return s[:a] + body + s[b:]
def nofade(s):
    a = '''    if (fadedBefore >= breakEnd + leeway)
        return false;
'''
    assert a in s
    s = s.replace(a, '')
    a = '''    if (fadedBefore > 0.0 && leastBehind - ragged >= wave.travelled - fadedTravel + leeway)
        return false;
'''
    assert a in s
    return s.replace(a, '')
make('nl_same', lambda s: s)
make('nl_lit', lit)
make('nl_nofade', nofade)
make('nl_litnofade', lambda s: lit(nofade(s)))
