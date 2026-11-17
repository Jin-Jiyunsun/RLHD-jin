for k, expr in ((1, 'nearby != 0'), (2, '(nearby & nearby - 1) != 0'), (3, None)):
    p = 'vars/fc_dbg%d/utils/water.glsl' % k
    s = open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
    a = '''            if (nearby == 0)
                return vec2(0.0);
        }
'''
    assert s.count(a) == 1
    if expr:
        b = a + '        if (%s) return vec2(1.0, 0.0); else return vec2(0.0);\n' % expr
    else:
        b = a + '        else return vec2(1.0, 0.0);\n        return vec2(0.0);\n'
    s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n'))
