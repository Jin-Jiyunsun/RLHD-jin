import os, shutil, sys
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
CALL = "        wake = boatWakes(IN.position.xz / 128.0, swept);\n"
P = {
 'wk_noswept': [(CALL, CALL + "    swept = vec4(0.0, -1e9, 0.0, -1e9);\n")],
 'wk_nochurn': [("    float churned = wake.w;\n", "    float churned = 0.0;\n")],
 'wk_noamount': [("    if (wake.x > 0.0) {\n        wakeSpecks", "    if (false) {\n        wakeSpecks")],
 'wk_stub': [(CALL, "        wake = vec4(0.0);\n")],
}
P['wk_all3'] = P['wk_noswept'] + P['wk_nochurn'] + P['wk_noamount']
for name, subs in P.items():
    out = os.path.join(here, 'vars', name)
    if os.path.exists(out): shutil.rmtree(out)
    shutil.copytree(HD, out, ignore=shutil.ignore_patterns('*.json', '*.png', '*.jpg'))
    p = os.path.join(out, 'utils', 'water.glsl')
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    for old, new in subs:
        assert s.count(old) == 1, (name, old)
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
    print(name)
