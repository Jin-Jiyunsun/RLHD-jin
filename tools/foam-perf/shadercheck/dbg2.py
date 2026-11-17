import shutil, os
def make(name, edits):
    d = 'vars/' + name
    if os.path.exists(d): shutil.rmtree(d)
    shutil.copytree('vars/fc_dbg1', d)
    shutil.copy('vars/fc/utils/ocean_wave_cells.glsl', d + '/utils/ocean_wave_cells.glsl')
    p = d + '/utils/ocean_wave_cells.glsl'
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for a, b in edits:
        assert s.count(a) == 1, (name, a)
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
front = ('    if (mostBehind + ragged <= -0.06 || leastBehind - ragged >= thickest)',
         '    if (mostBehind + 0.3 * ragged <= -0.06 || leastBehind - ragged >= thickest)')
make('dA', [front])
make('dB', [front, ('OCEAN_WAVE_FACE_REACH * 1.05, wave)', 'OCEAN_WAVE_FACE_REACH * 0.52, wave)')])
make('dC', [front, ('OCEAN_WAVE_FACE_REACH * 1.05, wave)', 'OCEAN_WAVE_FACE_REACH * 0.05, wave)')])
make('dD', [('OCEAN_WAVE_FACE_REACH * 1.05, wave)', 'OCEAN_WAVE_FACE_REACH * 0.05, wave)')])
