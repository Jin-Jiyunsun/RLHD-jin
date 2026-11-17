p = 'ShaderCheck.java'
s = open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
s = s.replace('\r\n', '\n')
a = s.index('		includes\n			.addInclude("VERSION_HEADER"')
b = s.index('	static String includeNames(')
new = r'''		String label = String.format("foam %d lace %d waves %d wakes %d cycle %d glare %d legacy %d water pass %d",
			v[0], v[1], v[2], v[3], v[4], v[5], v[6], waterPass);
		// The whole program, as SceneShaderProgram builds it: vertex, geometry for the legacy renderer, and fragment
		int[] types = v[6] == 1 ?
			new int[] { GL_VERTEX_SHADER, GL_GEOMETRY_SHADER, GL_FRAGMENT_SHADER } :
			new int[] { GL_VERTEX_SHADER, GL_FRAGMENT_SHADER };
		String[] files = v[6] == 1 ?
			new String[] { "scene_vert.glsl", "scene_geom.glsl", "scene_frag.glsl" } :
			new String[] { "scene_vert.glsl", "scene_frag.glsl" };
		int program = glCreateProgram();
		boolean ok = true;
		StringBuilder problems = new StringBuilder();
		for (int i = 0; i < types.length; i++) {
			ShaderIncludes stage = includes.copy()
				.addInclude("VERSION_HEADER", "#version 430\n")
				.addInclude("MATERIAL_CONSTANTS",
					"#define MAT_NONE getMaterial(0)\n#define MAT_CAUSTICS_MAP getMaterial(1)\n" +
					"#define MAT_WATER_FLOW_MAP getMaterial(2)\n#define MAT_WATER_FOAM getMaterial(3)\n")
				.addInclude("MATERIAL_GETTER", "#define getMaterial(i) uboMaterials.Array[i]\n")
				.addInclude("WATER_TYPE_GETTER", "#define getWaterType(i) uboWaterTypes.Array[i]\n")
				.addInclude("WORLD_VIEW_GETTER", v[6] == 1 ? "" : "#define getWorldView(i) uboWorldViews.Array[i]\n")
				.addInclude("SHADER_TYPE",
					"#define VERTEX_SHADER " + GL_VERTEX_SHADER + "\n#define FRAGMENT_SHADER " + GL_FRAGMENT_SHADER +
					"\n#define GEOMETRY_SHADER " + GL_GEOMETRY_SHADER + "\n#define COMPUTE_SHADER 37305\n#define SHADER_TYPE " +
					types[i]);
			String source;
			try {
				source = stage.loadFile(files[i]);
			} catch (Exception ex) {
				System.out.println("FAIL  " + label + "\n  " + files[i] + ": " + ex.getMessage());
				return false;
			}
			int shader = glCreateShader(types[i]);
			glShaderSource(shader, source);
			glCompileShader(shader);
			if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
				ok = false;
				problems.append("  ").append(files[i]).append(":\n");
				for (String line : glGetShaderInfoLog(shader).trim().split("\n"))
					problems.append("    ").append(line.trim()).append("\n");
				problems.append("    files: ").append(includeNames(stage)).append("\n");
			}
			glAttachShader(program, shader);
			glDeleteShader(shader);
		}
		if (ok) {
			glLinkProgram(program);
			if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
				ok = false;
				problems.append("  link: ").append(glGetProgramInfoLog(program).trim()).append("\n");
			}
		}
		glDeleteProgram(program);
		System.out.println((ok ? "ok    " : "FAIL  ") + label);
		if (!ok)
			System.out.print(problems);
		return ok;
	}

'''
s = s[:a] + new + s[b:]
if crlf:
    s = s.replace('\n', '\r\n')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('patched')
