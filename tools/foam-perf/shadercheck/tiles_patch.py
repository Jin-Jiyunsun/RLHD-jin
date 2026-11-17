p = 'WaterBench.java'
s = open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
s = s.replace('\r\n', '\n')


def rep(a, b, count=1):
    global s
    assert s.count(a) == count, (s.count(a), a)
    s = s.replace(a, b)


rep(r'''	static final int DRAWS =''', r'''	static final boolean TILES = System.getenv("BENCH_TILES") != null;
	static final int TILES_X = (int) SPAN, TILES_Z = (int) (SPAN * H / W);
	static final int VERTS = TILES ? TILES_X * TILES_Z * 6 : 3;
	static final int DRAWS =''')
rep(r'glDrawArrays(GL_TRIANGLES, 0, 3);', r'glDrawArrays(GL_TRIANGLES, 0, VERTS);', 2)
rep(r'''			textures(new File(args[2]));
''', r'''			textures(new File(args[2]));
			if (TILES)
				tiles();
''')
rep(r'''		String vert = "#version 430\n"''', r'''		String stub = "#version 430\n"''')
rep(r'''			+ "}\n";
		int program = glCreateProgram();''', r'''			+ "}\n";
		String vert = TILES ? source(new File(s.dir), s.v, "scene_vert.glsl", GL_VERTEX_SHADER) : stub;
		int program = glCreateProgram();''')
rep(r'''				case GL_UNSIGNED_INT_SAMPLER_2D_ARRAY: glUniform1i(loc, 3); break;''',
    r'''				case GL_UNSIGNED_INT_SAMPLER_2D_ARRAY: glUniform1i(loc, 3); break;
				case 0x8DD0: glUniform1i(loc, 4); break;''')
rep(r'''		set.f("UBOGlobal.elapsedTime", time);
		set.f("UBOGlobal.waterChurnTime", time);''', r'''		if (TILES) {
			// Straight down onto the tiles, x across and z up the screen
			float sx = 2f / (128 * SPAN), sz = 2f / (128 * SPAN * H / W);
			set.f("UBOGlobal.projectionMatrix", sx, 0, 0, 0, 0, 0, 0, 0, 0, sz, 0, 0, -ORIGIN * 128 * sx - 1, -ORIGIN * 128 * sz - 1, 0, 1);
			set.i("UBOGlobal.orthographicProjection", 1);
		}
		set.f("UBOGlobal.elapsedTime", time);
		set.f("UBOGlobal.waterChurnTime", time);''')
rep(r'''	static String source(File shaderDir, int[] v) throws Exception {''', r'''	// A grid of water tiles as the zone renderer draws them, two triangles each, all sharing one face's data
	static void tiles() {
		FloatBuffer positions = BufferUtils.createFloatBuffer(VERTS * 3);
		int[][] corners = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 }, { 0, 1 }, { 1, 0 } };
		for (int z = 0; z < TILES_Z; z++)
			for (int x = 0; x < TILES_X; x++)
				for (int[] c : corners)
					positions.put((ORIGIN + x + c[0]) * 128).put(0).put((ORIGIN + z + c[1]) * 128);
		positions.flip();
		glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
		glBufferData(GL_ARRAY_BUFFER, positions, GL_STATIC_DRAW);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
		glEnableVertexAttribArray(0);
		glVertexAttrib4f(1, 0, 0, 0, 0);
		glVertexAttrib4f(2, 0, -1, 0, 0);
		glVertexAttribI1i(3, 0);
		glVertexAttribI1i(6, -1);
		glVertexAttribI2i(7, 0, 0);
		// The face's shore data, material data and terrain data, each per vertex
		String extra = System.getenv("BENCH_SHORE");
		int steps = SceneContext.SHORE_DISTANCE_STEPS, shift = SceneContext.SHORE_DIRECTION_SHIFT;
		int[] shore = { steps, steps, steps };
		if (extra != null) {
			int bits = Integer.decode(extra);
			String[] far = System.getenv().getOrDefault("BENCH_SHORE_STEPS", "40," + steps).split(",");
			shore = new int[] { 0 | 4 << shift | bits, Integer.parseInt(far[0]) | 5 << shift | bits, Integer.parseInt(far[1]) };
		}
		int terrain = 1 | 1 << 3;
		int[] face = { shore[0], shore[1], shore[2], 0, 0, 0, 0, 0, terrain, terrain, terrain, 0 };
		int buffer = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, buffer);
		glBufferData(GL_TEXTURE_BUFFER, face, GL_STATIC_DRAW);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_BUFFER, glGenTextures());
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32I, buffer);
		glActiveTexture(GL_TEXTURE0);
	}

	static String source(File shaderDir, int[] v) throws Exception {
		return source(shaderDir, v, "scene_frag.glsl", GL_FRAGMENT_SHADER);
	}

	static String source(File shaderDir, int[] v, String file, int shaderType) throws Exception {''')
rep(r'''				"\n#define GEOMETRY_SHADER " + GL_GEOMETRY_SHADER + "\n#define COMPUTE_SHADER 37305\n#define SHADER_TYPE " +
				GL_FRAGMENT_SHADER);
		return includes.loadFile("scene_frag.glsl");''', r'''				"\n#define GEOMETRY_SHADER " + GL_GEOMETRY_SHADER + "\n#define COMPUTE_SHADER 37305\n#define SHADER_TYPE " +
				shaderType);
		return includes.loadFile(file);''')
if crlf:
    s = s.replace('\n', '\r\n')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('patched')
