import os, shutil
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
US = """            // Waves a boat swept over stay broken up for the rest of their lives
            float unswept = 1.0 - max(wave.spawnTime < swept.y ? swept.x : 0.0, wave.spawnTime < swept.w ? swept.z : 0.0);
            if (unswept <= 0.0)
                continue;
"""
END = "            cover *= fade * unswept;\n"
P = {
 'fx_nocont': [(US, US.replace("            if (unswept <= 0.0)\n                continue;\n", ""))],
 'fx_late': [(US, ""), (END, """            // Waves a boat swept over stay broken up for the rest of their lives
            cover *= fade * (1.0 - max(wave.spawnTime < swept.y ? swept.x : 0.0, wave.spawnTime < swept.w ? swept.z : 0.0));
""")],
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
