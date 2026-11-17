"""Make a variant of a bench shader by exact text replacement. Usage: var.py <in> <out> <old> <new> [<old> <new>...]"""
import sys
s = open(sys.argv[1], encoding='utf-8').read()
a = sys.argv[3:]
for i in range(0, len(a), 2):
    old, new = a[i].replace('\n', '\n'), a[i + 1].replace('\n', '\n')
    assert s.count(old) >= 1, 'missing: ' + old
    s = s.replace(old, new)
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(s)
