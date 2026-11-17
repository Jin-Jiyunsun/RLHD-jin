import sys
p = sys.argv[1] + '/utils/water.glsl'
s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
def rep(a, b, count=1):
    global s
    assert s.count(a) == count, (s.count(a), a[:80])
    s = s.replace(a, b)
rep('min(0.55 * OCEAN_WAVE_RAGGEDNESS, 0.8)', 'foamBreakupSpread', 4)
rep('''#define OCEAN_WAVE_WIGGLE 1.0              // how wiggly the fronts are
#define OCEAN_WAVE_RAGGEDNESS 1.5          // how ragged the edges and breakup are
''', '')
rep('''    // In wave radii
    const float maxSweep = oceanWaveMaxSweep;
    // The furthest the ragged edges can move the foam, in wave radii, from the sizes of their four noises below
    const float maxRagged = 0.5 * (0.3 + 0.18 + 0.14 + 0.1) * OCEAN_WAVE_RAGGEDNESS;
''', '')
a = s.index('            vec2 center = wave.center;\n')
b = s.index('            if (!raggedReady) {\n')
s = s[:a] + '''            float life = wave.life;
            float sweep = wave.sweep;
            float travelled = wave.travelled;
            float trail = wave.trail;
            float u = wave.u;
            float v = wave.v;
            float behind = wave.behind;
            float thickness = wave.thickness;
            float raggedness = wave.raggedness;

''' + s[b:]
rep('// From broad lumps to fine tears. maxRagged must cover these.', '// From broad lumps to fine tears. oceanWaveMaxRagged must cover these.')
rep('''* breakEnd;
            float foamLife = max(life - passed, 0.0);
            // Faster waves fade sooner, so their trails don't stretch too far
            float fadeTime = mix(0.3, 0.55, r.w) * (maxSweep * 0.6 / sweep);
            float lasting = foamLifeLeft(foamLife / fadeTime, breakup);''', '''* oceanWaveBreakEnd;
            float foamLife = max(life - passed, 0.0);
            // The longest it can last is worked into oceanWaveNear, so change that with this
            float lasting = foamLifeLeft(foamLife / wave.fadeTime, breakup);''')
open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
