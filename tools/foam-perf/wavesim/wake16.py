"""Build 16-boat wake benches from 117 HD's water.glsl, plus the uniform block data.
Usage: wake16.py <rs117/hd folder> <out prefix> [water.glsl to use instead]"""
import math, re, struct, subprocess, sys
hd, prefix = sys.argv[1], sys.argv[2]
water = sys.argv[3] if len(sys.argv) > 3 else None
here = sys.path[0]
tmp = prefix + '_base.glsl'
if water:
    # wake_shader.py reads utils/water.glsl from the folder, so point it at a copy
    import os, shutil
    alt = prefix + '_hd'
    if os.path.exists(alt): shutil.rmtree(alt)
    shutil.copytree(hd, alt)
    shutil.copy(water, alt + '/utils/water.glsl')
    hd = alt
subprocess.check_call([sys.executable, here + '/wake_shader.py', hd, tmp])
subprocess.check_call([sys.executable, here + '/ubo_wake.py', tmp, tmp])
s = open(tmp, encoding='utf-8').read()
main = s.index('uniform float uTime;')
s = s[:main] + '''uniform float uTime;
uniform vec2 uOrigin;
uniform float uSpan;
uniform vec2 uSize;
out vec4 fragColor;

void main() {
    uboGlobal.elapsedTime = uTime;
    uboGlobal.waveMaxRoughness = 1.0;
    uboGlobal.waveHistorySteady = 1;
    uboGlobal.sceneBase = ivec2(0);
    vec2 p = uOrigin + gl_FragCoord.xy / uSize * uSpan;
    vec4 swept = vec4(0.0, -1e9, 0.0, -1e9);
    vec4 wake = vec4(0.0);
#if DO_WAKES
    wake = boatWakes(p, swept);
#endif
    float waves = 0.0;
#if DO_WAVES
    if (wake.w < 1.0) {
        vec2 w = oceanWaves(p, float(SHORE_DISTANCE_MAX), vec2(0.0), swept);
        waves = w.x * (1.0 - wake.w) * w.y;
    }
#endif
    fragColor = vec4(waves * 0.5 + wake.x * wake.y * 0.3 + wake.z * 0.05 + wake.w * 0.1 + swept.x * 0.05, 0.0, 0.0, 1.0);
}
'''
for name, wakes, waves in [('both', 1, 1), ('wakes', 1, 0), ('waves', 0, 1), ('wavesnowake', 0, 1)]:
    v = s.replace('#version 330', '#version 330\n#define DO_WAKES %d\n#define DO_WAVES %d' % (wakes, waves), 1)
    if name == 'wavesnowake':
        # As the game compiles it with wakes off: no swept check at all
        v = v.replace('float unswept = 1.0 - max(spawnTime < swept.y ? swept.x : 0.0, spawnTime < swept.w ? swept.z : 0.0);', 'float unswept = 1.0;')
        assert 'unswept = 1.0;' in v
    open('%s_%s.glsl' % (prefix, name), 'w', encoding='utf-8', newline='\n').write(v)

# Uniform block: wind and roughness history, then 16 test boats as BoatWakes.placeTestWakes lays them out
W, H, SPAN, ORIGIN = 1920, 1080, 48.0, 3000.0
cx, cz = ORIGIN + SPAN / 2, ORIGIN + SPAN * H / W / 2
vec = [(0.7,) * 4] * 8 + [(1.0,) * 4] * 8
points, bounds, heads, hulls, sizes = [], [], [], [], []
tan7 = math.tan(math.radians(7))
for i in range(16):
    col, row = i % 4, i // 4
    sx = cx + (col - 1.5 + (row % 2) * 0.5) * 7
    sz = cz + (row - 1.5) * 12
    pts = [(sx, sz - 0.25 * 3, 0.25, 0.75), (sx, sz - 4.5 * 3, 4.5, 13.5)]
    points += pts + [(0.0,) * 4] * (40 - len(pts))
    heads.append((0.0, 1.0, sx, sz))
    hulls.append((len(pts), 0.0, 100.0, 0.0))
    sizes.append((10.0, 3.0, 0.0, 0.0))
    xs = [sx] + [p[0] for p in pts]; zs = [sz] + [p[1] for p in pts] + [sz + 5.25]
    band = 1.2 + (5.25 * tan7 + 2 * 0.6) + 0.5 * (0.35 + 5 * 0.08 + 2 * 0.5)
    reach = max(band, 1.5 + 4.5 * 0.5) + 0.5 * 0.35 * (0.3 + 5 * 0.15 + 4.5)
    bounds.append((min(xs) - reach, min(zs) - reach, max(xs) + reach, max(zs) + reach))
data = vec + points + bounds + heads + hulls + sizes
open(prefix + '_ubo.f32', 'wb').write(b''.join(struct.pack('<4f', *v) for v in data))
print('built', prefix)
