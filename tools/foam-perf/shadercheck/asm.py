"""Pull the NV assembly out of a program binary and summarise it. Usage: asm.py <bin>..."""
import re, sys
for f in sys.argv[1:]:
    data = open(f, 'rb').read()
    best = b''
    for m in re.finditer(rb'!!NV[a-z]+\d\.\d', data):
        end = data.find(b'\x00', m.start())
        if end - m.start() > len(best):
            best = data[m.start():end]
    text = best.decode('latin-1')
    open(f.replace('.bin', '.asm'), 'w', newline='\n').write(text)
    lines = text.split('\n')
    temps = [l for l in lines if l.startswith('TEMP R')]
    n = max(int(x) for x in re.findall(r'R(\d+)', ' '.join(temps))) + 1 if temps else 0
    print('%-34s %5d lines, %3d temps, %d REP, %d IF, %d TEX, %d LDC' % (f, len(lines), n,
        sum(l.startswith('REP') for l in lines), sum(l.startswith('IF') for l in lines),
        sum(l.startswith('TEX') or l.startswith('TXL') or l.startswith('TXD') or l.startswith('TXF') for l in lines), sum(l.startswith('LDC') for l in lines)))
