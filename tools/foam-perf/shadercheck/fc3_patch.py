def edit(p, pairs):
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for a, b in pairs:
        assert s.count(a) == 1, (p, s.count(a), a[:70])
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
edit('vars/fc/utils/ocean_wave_cells.glsl', [
('''// How much sooner or later foam fades out than its neighbours, as a share of its fading time
''', '''// How ragged the front is, relative to the rest
const float oceanWaveFrontRagged = 0.3;
// How much sooner or later foam fades out than its neighbours, as a share of its fading time
'''),
('''    // Skip spots out of reach even with the ragged edges, before working out the noise
    float ragged = oceanWaveMaxRagged * wave.raggedness + leeway;
    if (mostBehind + ragged <= -0.06 || leastBehind - ragged >= thickest)
        return false;
''', '''    // Skip spots out of reach even with the ragged edges, before working out the noise
    float ragged = oceanWaveMaxRagged * wave.raggedness;
    if (mostBehind + oceanWaveFrontRagged * ragged + leeway <= -0.06 || leastBehind - ragged - leeway >= thickest)
        return false;
'''),
('''    if (fadedBefore > 0.0 && leastBehind - ragged >= wave.travelled - fadedTravel + leeway)''',
 '''    if (fadedBefore > 0.0 && leastBehind - ragged - leeway >= wave.travelled - fadedTravel)'''),
('''                list |= 1 << (y + 2) * 5 + x + 2;''', '''                list |= 1 << ((y + 2) * 5 + x + 2);'''),
])
edit('vars/fc/utils/water.glsl', [
('''            int rows = fOceanWaveList >> first.y * 5 + first.x;
            nearby = rows & 7 | (rows >> 5 & 7) << 3 | (rows >> 10 & 7) << 6;''',
 '''            int rows = fOceanWaveList >> (first.y * 5 + first.x);
            nearby = (rows & 7) | ((rows >> 5) & 7) << 3 | ((rows >> 10) & 7) << 6;'''),
('''            if ((nearby >> (y + 1) * 3 + x + 1 & 1) == 0)''', '''            if ((nearby >> ((y + 1) * 3 + x + 1) & 1) == 0)'''),
('''behind += ragged * mix(0.3, 1.0, saturate(behind / trail)) * raggedness;''',
 '''behind += ragged * mix(oceanWaveFrontRagged, 1.0, saturate(behind / trail)) * raggedness;'''),
])
