"""The 16 test boats as wake16.py lays them out, but all heading at an angle. Usage: diag_ubo.py <degrees> <out.f32>"""
import math, struct, sys
ang = math.radians(float(sys.argv[1]))
hx, hz = math.sin(ang), math.cos(ang)
W, H, SPAN, ORIGIN = 1920, 1080, 48.0, 3000.0
cx, cz = ORIGIN + SPAN / 2, ORIGIN + SPAN * H / W / 2
vec = [(0.7,) * 4] * 8 + [(1.0,) * 4] * 8
points, bounds, heads, hulls, sizes = [], [], [], [], []
tan7 = math.tan(math.radians(7))
for i in range(16):
    col, row = i % 4, i // 4
    sx = cx + (col - 1.5 + (row % 2) * 0.5) * 7
    sz = cz + (row - 1.5) * 12
    pts = [(sx - hx * 0.75, sz - hz * 0.75, 0.25, 0.75), (sx - hx * 13.5, sz - hz * 13.5, 4.5, 13.5)]
    points += pts + [(0.0,) * 4] * (40 - len(pts))
    heads.append((hx, hz, sx, sz))
    hulls.append((len(pts), 0.0, 100.0, 0.0))
    sizes.append((10.0, 3.0, 0.0, 0.0))
    xs = [sx, sx + hx * 5.25] + [p[0] for p in pts]; zs = [sz, sz + hz * 5.25] + [p[1] for p in pts]
    band = 1.2 + (5.25 * tan7 + 2 * 0.6) + 0.5 * (0.35 + 5 * 0.08 + 2 * 0.5)
    reach = max(band, 1.5 + 4.5 * 0.5) + 0.5 * 0.35 * (0.3 + 5 * 0.15 + 4.5)
    bounds.append((min(xs) - reach, min(zs) - reach, max(xs) + reach, max(zs) + reach))
data = vec + points + bounds + heads + hulls + sizes
open(sys.argv[2], 'wb').write(b''.join(struct.pack('<4f', *v) for v in data))
