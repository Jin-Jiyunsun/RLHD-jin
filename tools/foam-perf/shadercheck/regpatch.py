s=open('ShaderCheck.java').read()
s=s.replace('public class ShaderCheck {','public class RegCheck {')
s=s.replace('import static org.lwjgl.opengl.GL33C.*;','import static org.lwjgl.opengl.GL33C.*;\nimport static org.lwjgl.opengl.GL41C.*;')
start=s.index('\t\t\tint[][] variants = {'); end=s.index('};', start)+2
s=s[:start]+'''\t\t\tint[][] variants = {
\t\t\t\t{ 1, 1, 1, 1, 0, 0, 0 },
\t\t\t\t{ 1, 1, 1, 0, 0, 0, 0 },
\t\t\t\t{ 0, 0, 0, 0, 0, 0, 0 },
\t\t\t};'''+s[end:]
old_loop='''\t\t\t\tfor (int waterPass = 1; waterPass >= 0; waterPass--)
\t\t\t\t\tfailures += compile(new File(args[0]), v, waterPass) ? 0 : 1;'''
assert s.count(old_loop)==1
s=s.replace(old_loop,'''\t\t\t\tfailures += compile(new File(args[0]), v, 1) ? 0 : 1;''')
old='''\t\tString log = glGetShaderInfoLog(shader).trim();
\t\tglDeleteShader(shader);'''
assert s.count(old)==1
new='''\t\tString log = glGetShaderInfoLog(shader).trim();
\t\tif (ok) {
\t\t\tint program = glCreateProgram();
\t\t\tglProgramParameteri(program, GL_PROGRAM_SEPARABLE, GL_TRUE);
\t\t\tglAttachShader(program, shader);
\t\t\tglLinkProgram(program);
\t\t\tif (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
\t\t\t\tSystem.out.println("  link failed: " + glGetProgramInfoLog(program));
\t\t\t} else {
\t\t\t\tint length = glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH);
\t\t\t\tjava.nio.ByteBuffer binary = org.lwjgl.BufferUtils.createByteBuffer(length);
\t\t\t\tint[] format = new int[1];
\t\t\t\tglGetProgramBinary(program, (int[]) null, format, binary);
\t\t\t\tbyte[] bytes = new byte[binary.remaining()];
\t\t\t\tbinary.get(bytes);
\t\t\t\tjava.nio.file.Files.write(java.nio.file.Paths.get(String.format("binary_wakes%d_foam%d.bin", v[3], v[0])), bytes);
\t\t\t\tSystem.out.println("  binary " + length + " bytes, format 0x" + Integer.toHexString(format[0]));
\t\t\t}
\t\t\tglDeleteProgram(program);
\t\t}
\t\tglDeleteShader(shader);'''
s=s.replace(old,new)
open('RegCheck.java','w').write(s)
print('ok')
