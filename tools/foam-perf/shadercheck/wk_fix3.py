import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
CALL = "        wake = boatWakes(IN.position.xz / 128.0, swept);\n"
ADD = """void addSwept(inout vec4 swept, float amount, float time) {
    if (amount > swept.x || (amount == swept.x && time > swept.y))
        swept.xy = vec2(amount, time);
    if (time > swept.w || (time == swept.w && amount > swept.z))
        swept.zw = vec2(amount, time);
}"""
OPAQUE = "    swept = uboGlobal.elapsedTime < -1.0 ? vec4(0.5, 1.0, 0.5, 1.0) : vec4(0.0, -1e9, 0.0, -1e9);\n"
P = {
 # boatWakes keeps no sweep, but the waves still test an unknown one
 'sw_wavesonly': [(ADD, "void addSwept(inout vec4 swept, float amount, float time) {}"), (CALL, CALL + OPAQUE)],
 # boatWakes sweeps as now, but the waves ignore it
 'sw_wakesonly': [(CALL, CALL + "    swept += 0.0 * swept.yxwz;\n    if (swept.x > 2.0) wake.x += 0.001;\n    swept = vec4(0.0, -1e9, 0.0, -1e9);\n")],
}
for name, subs in P.items():
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for old, new in subs:
        assert s.count(old) == 1, (name, old[:60])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
