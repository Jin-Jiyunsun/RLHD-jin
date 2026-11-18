import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
SIG = "vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {\n"
COND = "    if (bool(BOAT_WAKES) && waterType.hasFoam != 0)\n        wake = boatWakes(IN.position.xz / 128.0, swept);\n"
def split(s):
    s = s.replace(SIG, "vec4 sampleWaterWith(int waterTypeIndex, vec3 viewDir, bool wakes) {\n")
    s = s.replace(COND, "    if (bool(BOAT_WAKES) && wakes && waterType.hasFoam != 0)\n        wake = boatWakes(IN.position.xz / 128.0, swept);\n")
    s += """
// With no boat wakes anywhere, a copy of the water without them, so their checks don't slow the waves
vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    if (bool(BOAT_WAKES) && uboGlobal.boatWakeStates[0].x >= 1.0)
        return sampleWaterWith(waterTypeIndex, viewDir, true);
    return sampleWaterWith(waterTypeIndex, viewDir, false);
}
"""
    return s
for name in ['fx_split']:
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    assert s.count(SIG) == 1 and s.count(COND) == 1
    s = split(s)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
