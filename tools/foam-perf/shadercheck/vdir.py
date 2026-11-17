"""Make a copy of 117 HD's shader folder with patched files. Usage: vdir.py <name> <patch name>...
Patches come from patches.py: name -> list of (file, old, new) or (file, function)."""
import os, shutil, sys
from patches import P
HD = r'C:\Users\Jin\IdeaProjects\RLHD-jin\src\main\resources\rs117\hd'
here = os.path.dirname(os.path.abspath(__file__))
name = sys.argv[1]
out = os.path.join(here, 'vars', name)
if os.path.exists(out):
    shutil.rmtree(out)
for root, dirs, files in os.walk(HD):
    for f in files:
        if f.endswith('.glsl'):
            rel = os.path.relpath(os.path.join(root, f), HD)
            os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
            shutil.copy(os.path.join(root, f), os.path.join(out, rel))
for pn in sys.argv[2:]:
    for entry in P[pn]:
        path = os.path.join(out, entry[0])
        s = open(path, encoding='utf-8', newline='').read()
        crlf = '\r\n' in s
        s = s.replace('\r\n', '\n')
        if len(entry) == 2:
            s = entry[1](s)
        else:
            old, new = entry[1], entry[2]
            assert s.count(old) == 1, (pn, s.count(old), old[:80])
            s = s.replace(old, new)
        if crlf:
            s = s.replace('\n', '\r\n')
        open(path, 'w', encoding='utf-8', newline='').write(s)
print(out)
