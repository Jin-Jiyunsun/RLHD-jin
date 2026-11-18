import java.awt.Canvas;
import java.awt.Frame;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.scene.BoatWakes;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Compiles 117 HD's scene fragment shader as its water pass, through the plugin's own include handling, for a few
 * combinations of the foam settings. Usage: ShaderCheck <shader folder>
 */
public class ShaderCheck {
	public static void main(String[] args) throws Exception {
		Frame frame = new Frame("shader check");
		Canvas canvas = new Canvas();
		frame.add(canvas);
		frame.setSize(64, 64);
		frame.setLocation(-500, -500);
		frame.setVisible(true);
		int failures = 0;
		try {
			AWTContext.loadNatives();
			AWTContext context;
			synchronized (canvas.getTreeLock()) {
				context = new AWTContext(canvas);
				context.configurePixelFormat(0, 0, 0);
			}
			context.createGLContext();
			GL.createCapabilities();
			System.out.println(glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));

			// shore foam, lace, waves, wakes, daylight cycle, glare reduction, legacy renderer
			int[][] variants = {
				{ 1, 1, 1, 1, 1, 1, 0 },
				{ 0, 0, 0, 0, 1, 1, 0 },
				{ 1, 0, 0, 0, 0, 0, 0 },
				{ 0, 1, 0, 0, 1, 0, 0 },
				{ 0, 0, 1, 0, 0, 1, 0 },
				{ 0, 0, 0, 1, 1, 0, 0 },
				{ 1, 1, 1, 1, 0, 0, 1 },
				{ 0, 0, 0, 0, 0, 0, 1 },
			};
			for (int[] v : variants)
				for (int waterPass = 1; waterPass >= 0; waterPass--)
					failures += compile(new File(args[0]), v, waterPass) ? 0 : 1;
		} finally {
			frame.dispose();
		}
		System.out.println(failures == 0 ? "ALL COMPILED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	static boolean compile(File shaderDir, int[] v, int waterPass) throws Exception {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("SHORE_FOAM", v[0]);
		d.put("OCEAN_LACE", v[1]);
		d.put("OCEAN_WAVES", v[2]);
		d.put("BOAT_WAKES", v[3]);
		d.put("DAYLIGHT_CYCLE", v[4]);
		d.put("REDUCE_GLARE_INTENSITY", v[5]);
		d.put("LEGACY_RENDERER", v[6]);
		d.put("ZONE_RENDERER", 1 - v[6]);
		d.put("WATER_PASS", waterPass);
		d.put("DITHER_FADE", 1);
		d.put("SHORE_DISTANCE_MAX", SceneContext.SHORE_DISTANCE_MAX);
		d.put("SHORE_DISTANCE_STEPS", SceneContext.SHORE_DISTANCE_STEPS);
		d.put("SHORE_DIRECTION_SHIFT", SceneContext.SHORE_DIRECTION_SHIFT);
		d.put("SHORE_DIRECTION_STEPS", SceneContext.SHORE_DIRECTION_STEPS);
		d.put("SHORE_SIDES_SHIFT", SceneContext.SHORE_SIDES_SHIFT);
		d.put("WATER_SQUARE_TILE_BIT", SceneContext.WATER_SQUARE_TILE_BIT);
		d.put("WATER_ENCLOSURE_SHIFT", SceneContext.WATER_ENCLOSURE_SHIFT);
		d.put("WATER_ENCLOSURE_STEPS", SceneContext.WATER_ENCLOSURE_STEPS);
		d.put("WIND_HISTORY_LENGTH", 32);
		d.put("BOAT_WAKE_MAX_BOATS", BoatWakes.MAX_BOATS);
		d.put("BOAT_WAKE_MAX_POINTS", BoatWakes.MAX_POINTS);
		d.put("WATER_NOISE_SIZE", 256);
		for (String zero : new String[] {
			"UI_SCALING_MODE", "COLOR_BLINDNESS", "APPLY_COLOR_FILTER", "DECOUPLE_WATER_FROM_SKY_COLOR", "TERRAIN_ONLY_PASS",
			"VANILLA_COLOR_BANDING", "LEGACY_GREY_COLORS", "DISABLE_DIRECTIONAL_SHADING", "FLAT_SHADING", "WIREFRAME",
			"WINDOWS_HDR_CORRECTION", "LEGACY_BOAT_SILHOUETTES", "STAR_MODE"
		})
			d.put(zero, 0);
		for (String one : new String[] {
			"WATER_SPECULAR_REFLECTIONS", "DYNAMIC_LIGHTS", "TILED_LIGHTING", "NORMAL_MAPPING", "PARALLAX_OCCLUSION_MAPPING",
			"TERRAIN_SHADOWS", "SHADOW_TRANSPARENCY", "POINT_SPRITES", "NEBULAE", "UNDO_VANILLA_SHADING", "WIND_DISPLACEMENT",
			"CHARACTER_DISPLACEMENT", "CENTROID_QUALIFIER", "SHADOW_FILTERING"
		})
			d.put(one, 1);
		d.put("MATERIAL_COUNT", 200);
		d.put("WATER_TYPE_COUNT", 30);
		d.put("TILED_LIGHTING_LAYER_COUNT", 2);
		d.put("TILED_LIGHTING_TILE_SIZE", 16);
		d.put("MAX_LIGHT_COUNT", 100);
		d.put("SHADOW_MODE", 2);
		d.put("SHADOW_FILTERING_KERNEL", 3);
		d.put("WIND_DISPLACEMENT_NOISE_RESOLUTION", 128);
		d.put("MAX_CHARACTER_POSITION_COUNT", 50);
		d.put("MAX_SIMULTANEOUS_WORLD_VIEWS", v[6] == 1 ? 0 : 128);
		d.put("NEBULA_CLUSTER_COUNT", 8);

		// Any defines added since, as 0
		try (var files = java.nio.file.Files.walk(shaderDir.toPath())) {
			for (var f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".glsl"))::iterator) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("#include ([A-Z][A-Z0-9_]*)")
					.matcher(new String(java.nio.file.Files.readAllBytes(f)));
				while (m.find())
					d.putIfAbsent(m.group(1), 0);
			}
		}

		ShaderIncludes includes = new ShaderIncludes().addIncludePath(ResourcePath.path(shaderDir));
		for (var e : d.entrySet())
			includes.define(e.getKey(), String.valueOf(e.getValue()));
		String label = String.format("foam %d lace %d waves %d wakes %d cycle %d glare %d legacy %d water pass %d",
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

	static String includeNames(ShaderIncludes includes) throws Exception {
		var f = ShaderIncludes.class.getDeclaredField("includeList");
		f.setAccessible(true);
		var list = (java.util.List<?>) f.get(includes);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++)
			sb.append(i).append('=').append(list.get(i)).append(' ');
		return sb.toString();
	}
}
