"""Move a wake bench shader's arrays from the local stub struct into a real uniform block. Usage: ubo_wake.py <in> <out>"""
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = s.replace('#define BOAT_WAKE_MAX_BOATS 1', '#define BOAT_WAKE_MAX_BOATS 16')
s = re.sub(r'^struct UBOStub \{.*\};$', 'struct UBOStub { float elapsedTime; ivec2 sceneBase; float waveMaxRoughness; int waveHistorySteady; };\n'
    'layout(std140) uniform HistoryBlock { vec4 windHistory[WIND_HISTORY_LENGTH / 4]; vec4 roughnessHistory[WIND_HISTORY_LENGTH / 4];'
    ' vec4 boatWakePoints[BOAT_WAKE_MAX_BOATS * BOAT_WAKE_MAX_POINTS]; vec4 boatWakeBounds[BOAT_WAKE_MAX_BOATS];'
    ' vec4 boatWakeHeadings[BOAT_WAKE_MAX_BOATS]; vec4 boatWakeHulls[BOAT_WAKE_MAX_BOATS]; vec4 boatWakeSizes[BOAT_WAKE_MAX_BOATS]; } uB;', s, flags=re.M)
s = re.sub(r'uboGlobal\.(windHistory|roughnessHistory|boatWake\w+)', r'uB.\1', s)
# Drop main's writes to them
main = s.index('void main()')
body = s[main:]
body = re.sub(r'\n    for \(int i = 0; i < WIND_HISTORY_LENGTH / 4; i\+\+\) \{[^}]*\}', '', body)
body = re.sub(r'\n    for \(int i = 0; i < BOAT_WAKE_MAX_POINTS; i\+\+\)\n[^\n]*', '', body)
body = re.sub(r'\n    uB\.[^\n]*', '', body)
s = s[:main] + body
assert 'uB.' not in body.split('vec2 p =')[0], body[:600]
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(s)
