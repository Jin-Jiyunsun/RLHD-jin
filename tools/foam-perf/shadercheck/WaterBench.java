import java.awt.Canvas;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.scene.BoatWakes;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Times 117 HD's real water program drawing a full 1920x1080 frame of open water, and compares the output of each
 * variant with a reference's.
 * Usage: WaterBench <rounds> <boat ubo .f32> <foam jpg> <spec>...
 * spec: name,shader dir,shore foam,lace,waves,wakes,boats[,reference name]
 */
public class WaterBench {
	static final int W = 1920, H = 1080;
	static final float SPAN = 48, ORIGIN = 3000;
	static final boolean TILES = System.getenv("BENCH_TILES") != null;
	static final int TILES_X = (int) SPAN, TILES_Z = (int) (SPAN * H / W);
	static final int VERTS = TILES ? TILES_X * TILES_Z * 6 : 3;
	static final int DRAWS = Integer.parseInt(System.getenv().getOrDefault("BENCH_DRAWS", "12"));

	static class Spec {
		String name, dir, reference;
		int[] v;
		int boats;
		int program;
		List<int[]> buffers = new ArrayList<>(); // binding, buffer
		int timeBuffer = -1, timeOffset, churnOffset = -1;
		List<Double> times = new ArrayList<>();
		float[] pixels;
	}

	public static void main(String[] args) throws Exception {
		int rounds = Integer.parseInt(args[0]);
		ByteBuffer boatBytes = ByteBuffer.wrap(Files.readAllBytes(new File(args[1]).toPath())).order(ByteOrder.LITTLE_ENDIAN);
		float[] boatData = new float[boatBytes.remaining() / 4];
		for (int i = 0; i < boatData.length; i++)
			boatData[i] = boatBytes.getFloat();
		List<Spec> specs = new ArrayList<>();
		for (int i = 3; i < args.length; i++) {
			String[] p = args[i].split(",");
			Spec s = new Spec();
			s.name = p[0];
			s.dir = p[1];
			s.v = new int[] { Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]) };
			s.boats = Integer.parseInt(p[6]);
			s.reference = p.length > 7 ? p[7] : null;
			specs.add(s);
		}

		Frame frame = new Frame("bench");
		Canvas canvas = new Canvas();
		frame.add(canvas);
		frame.setSize(64, 64);
		frame.setLocation(-500, -500);
		frame.setVisible(true);
		try {
			AWTContext.loadNatives();
			AWTContext context;
			synchronized (canvas.getTreeLock()) {
				context = new AWTContext(canvas);
				context.configurePixelFormat(0, 0, 0);
			}
			context.createGLContext();
			GL.createCapabilities();
			glBindVertexArray(glGenVertexArrays());
			glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
			int target = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, target);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, W, H, 0, GL_RGBA, GL_FLOAT, (FloatBuffer) null);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
			glViewport(0, 0, W, H);
			textures(new File(args[2]));
			if (TILES)
				tiles();

			for (Spec s : specs)
				build(s, boatData);

			int query = glGenQueries();
			for (int round = 0; round <= rounds; round++) {
				for (Spec s : specs) {
					use(s);
					glBeginQuery(GL_TIME_ELAPSED, query);
					for (int i = 0; i < DRAWS; i++) {
						setTime(s, 100 + i * 0.37f);
						glDrawArrays(GL_TRIANGLES, 0, VERTS);
					}
					glEndQuery(GL_TIME_ELAPSED);
					long ns = glGetQueryObjectui64(query, GL_QUERY_RESULT);
					if (round > 0)
						s.times.add(ns / (double) DRAWS / 1e6);
				}
			}

			Map<String, Spec> byName = new LinkedHashMap<>();
			for (Spec s : specs) {
				byName.put(s.name, s);
				use(s);
				setTime(s, Float.parseFloat(System.getenv().getOrDefault("BENCH_TIME", "123.4")));
				glDrawArrays(GL_TRIANGLES, 0, VERTS);
				FloatBuffer pixels = BufferUtils.createFloatBuffer(W * H * 4);
				glReadPixels(0, 0, W, H, GL_RGBA, GL_FLOAT, pixels);
				s.pixels = new float[W * H * 4];
				pixels.get(s.pixels);
				BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < H; y++)
					for (int x = 0; x < W; x++) {
						int o = ((H - 1 - y) * W + x) * 4;
						int rgb = 0;
						for (int c = 0; c < 3; c++) {
							float f = s.pixels[o + c];
							int b = Float.isNaN(f) ? (c == 0 ? 255 : 0) : (int) (Math.max(0, Math.min(1, f)) * 255);
							rgb = rgb << 8 | b;
						}
						img.setRGB(x, y, rgb);
					}
				ImageIO.write(img, "png", new File(s.name + ".png"));
			}
			for (Spec s : specs) {
				double[] t = s.times.stream().mapToDouble(Double::doubleValue).sorted().toArray();
				String cmp = "";
				Spec ref = s.reference == null ? null : byName.get(s.reference);
				if (ref != null) {
					double maxDiff = 0;
					int differ = 0, nan = 0, maxAt = 0, small = 0;
					for (int i = 0; i < s.pixels.length; i++) {
						if (Float.isNaN(s.pixels[i]) != Float.isNaN(ref.pixels[i])) {
							nan++;
							continue;
						}
						double d = Math.abs(s.pixels[i] - ref.pixels[i]);
						if (d > maxDiff) {
							maxDiff = d;
							maxAt = i;
						}
						if (d > 1e-5)
							small++;
						if (d > 1 / 1024.0)
							differ++;
					}
					if (System.getenv("BENCH_DIFFLIST") != null) {
						int shown = 0;
						for (int i = 0; i < s.pixels.length && shown < 60; i += 4) {
							double d = 0;
							for (int c = 0; c < 4; c++)
								d = Math.max(d, Math.abs(s.pixels[i + c] - ref.pixels[i + c]));
							if (d > Double.parseDouble(System.getenv("BENCH_DIFFLIST"))) {
								System.out.printf("   diff x %d y %d: %.6f  (%.5f vs %.5f)%n", i / 4 % W, i / 4 / W, d, s.pixels[i], ref.pixels[i]);
								shown++;
							}
						}
					}
					if (System.getenv("BENCH_DIFF") != null) {
						BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
						for (int y = 0; y < H; y++)
							for (int x = 0; x < W; x++) {
								int o = ((H - 1 - y) * W + x) * 4;
								double d = 0;
								for (int c = 0; c < 4; c++)
									d = Math.max(d, Math.abs(s.pixels[o + c] - ref.pixels[o + c]));
								int g = (int) Math.min(255, d * 1e5);
								int base = (int) (Math.max(0, Math.min(1, ref.pixels[o + 2])) * 80);
								img.setRGB(x, y, Math.max(g, 0) << 16 | base);
							}
						try { ImageIO.write(img, "png", new File(s.name + "_diff.png")); } catch (Exception e) { throw new RuntimeException(e); }
					}
					cmp = String.format("  vs %s: max diff %.6f, %d values over 1/1024, %d nan mismatches", ref.name, maxDiff, differ, nan) + String.format(", %d over 1e-5, max at x %d y %d c %d: %.6f vs %.6f", small, maxAt / 4 % W, maxAt / 4 / W, maxAt % 4, s.pixels[maxAt], ref.pixels[maxAt]);
				}
				System.out.printf("%-26s best %.3f  p5 %.3f  p10 %.3f  p25 %.3f  median %.3f ms%s%n", s.name, t[0], t[t.length / 20], t[t.length / 10], t[t.length / 4], t[t.length / 2], cmp);
			}
		} finally {
			frame.dispose();
		}
		System.exit(0);
	}

	// Each vertex's shore data: open water as far from land as can be, or with BENCH_SHORE a shore at the first vertex,
	// with directions to it, and BENCH_SHORE's value as extra bits on the first two vertices
	static String shore() {
		String extra = System.getenv("BENCH_SHORE");
		if (extra == null)
			return "ivec3(" + SceneContext.SHORE_DISTANCE_STEPS + ")";
		int shift = SceneContext.SHORE_DIRECTION_SHIFT;
		return "ivec3(0 | 4 << " + shift + " | " + extra + ", 40 | 5 << " + shift + " | " + extra + ", "
			+ SceneContext.SHORE_DISTANCE_STEPS + ")";
	}

	static void use(Spec s) {
		glUseProgram(s.program);
		for (int[] b : s.buffers)
			glBindBufferBase(GL_UNIFORM_BUFFER, b[0], b[1]);
	}

	static void setTime(Spec s, float time) {
		if (s.timeBuffer == -1)
			return;
		glBindBuffer(GL_UNIFORM_BUFFER, s.timeBuffer);
		glBufferSubData(GL_UNIFORM_BUFFER, s.timeOffset, new float[] { time });
		if (s.churnOffset != -1)
			glBufferSubData(GL_UNIFORM_BUFFER, s.churnOffset, new float[] { time });
	}

	static void textures(File foamFile) throws Exception {
		BufferedImage foam = ImageIO.read(foamFile);
		int fw = foam.getWidth(), fh = foam.getHeight();
		ByteBuffer fb = BufferUtils.createByteBuffer(fw * fh * 4 * 4);
		for (int layer = 0; layer < 4; layer++)
			for (int y = 0; y < fh; y++)
				for (int x = 0; x < fw; x++) {
					int c = foam.getRGB(x, y);
					fb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 255);
				}
		fb.flip();
		// Unit 0: the texture array
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D_ARRAY, glGenTextures());
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, fw, fh, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, fb);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// Unit 1: a depth texture read plainly, and unit 2: one compared against
		for (int unit = 1; unit <= 2; unit++) {
			glActiveTexture(GL_TEXTURE0 + unit);
			glBindTexture(GL_TEXTURE_2D, glGenTextures());
			FloatBuffer depth = BufferUtils.createFloatBuffer(64 * 64);
			while (depth.hasRemaining())
				depth.put(1f);
			depth.flip();
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, 64, 64, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, unit == 2 ? GL_LINEAR : GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, unit == 2 ? GL_LINEAR : GL_NEAREST);
			if (unit == 2) {
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
			}
		}
		// Unit 5: a 256x256 tiling RG noise texture for lace noise experiments, filtered but without mipmaps
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, glGenTextures());
		ByteBuffer nb = BufferUtils.createByteBuffer(256 * 256 * 2);
		java.util.Random rng = new java.util.Random(117);
		while (nb.hasRemaining())
			nb.put((byte) rng.nextInt(256));
		nb.flip();
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, 256, 256, 0, GL_RG, GL_UNSIGNED_BYTE, nb);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		// Unit 3: an empty tiled lighting array
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D_ARRAY, glGenTextures());
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA16UI, 120, 68, 2, 0, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		ByteBuffer zeros = BufferUtils.createByteBuffer(120 * 68 * 2 * 8);
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, 120, 68, 2, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, zeros);
		glActiveTexture(GL_TEXTURE0);
	}

	static void build(Spec s, float[] boatData) throws Exception {
		String frag = source(new File(s.dir), s.v);
		String stub = "#version 430\n"
			+ "flat out int fWorldViewId;\nflat out ivec3 fAlphaBiasHsl;\nflat out ivec3 fMaterialData;\nflat out ivec3 fTerrainData;\n"
			+ "centroid out vec3 fTexBlend;\n"
			+ "out FragmentData { vec3 position; vec2 uv; vec3 normal; } OUT;\n"
			+ "void main() {\n"
			+ "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
			+ "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
			+ "  vec2 tiles = vec2(" + ORIGIN + ") + p * vec2(" + SPAN + ", " + (SPAN * H / W) + ");\n"
			+ "  OUT.position = vec3(tiles.x, 0.0, tiles.y) * 128.0;\n"
			+ "  OUT.uv = p;\n  OUT.normal = vec3(0.0, -1.0, 0.0);\n"
			+ "  fWorldViewId = 0;\n"
			// Open water, as far from land as can be
			+ "  fAlphaBiasHsl = " + shore() + ";\n"
			+ "  fMaterialData = ivec3(0);\n"
			// Terrain with water type 1
			+ "  fTerrainData = ivec3(1 | 1 << 3);\n"
			+ "  fTexBlend = " + (System.getenv("BENCH_SHORE") == null ? "vec3(1.0 / 3.0)" :
				"vec3(gl_VertexID == 0, gl_VertexID == 1, gl_VertexID == 2)") + ";\n"
			+ "}\n";
		String vert = TILES ? source(new File(s.dir), s.v, "scene_vert.glsl", GL_VERTEX_SHADER) : stub;
		int program = glCreateProgram();
		for (int stage = 0; stage < 2; stage++) {
			int shader = glCreateShader(stage == 0 ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
			glShaderSource(shader, stage == 0 ? vert : frag);
			glCompileShader(shader);
			if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				throw new IllegalStateException(s.name + ": " + glGetShaderInfoLog(shader));
			glAttachShader(program, shader);
		}
		glLinkProgram(program);
		if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE)
			throw new IllegalStateException(s.name + ": " + glGetProgramInfoLog(program));
		s.program = program;
		if (System.getenv("BENCH_DUMP") != null) {
			int len = glGetProgrami(program, org.lwjgl.opengl.GL41C.GL_PROGRAM_BINARY_LENGTH);
			ByteBuffer bin = BufferUtils.createByteBuffer(len);
			int[] fmt = new int[1];
			org.lwjgl.opengl.GL41C.glGetProgramBinary(program, (int[]) null, fmt, bin);
			byte[] bytes = new byte[len];
			bin.get(bytes);
			Files.write(new File(s.name + ".bin").toPath(), bytes);
		}
		glUseProgram(program);

		// Samplers by type
		int uniforms = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
		java.nio.IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
		for (int i = 0; i < uniforms; i++) {
			String name = glGetActiveUniform(program, i, size, type);
			int loc = glGetUniformLocation(program, name);
			if (loc == -1)
				continue;
			if (name.equals("laceNoise") || name.equals("waterNoise")) {
				glUniform1i(loc, 5);
				continue;
			}
			switch (type.get(0)) {
				case GL_SAMPLER_2D_ARRAY: glUniform1i(loc, 0); break;
				case GL_SAMPLER_2D: glUniform1i(loc, 1); break;
				case GL_SAMPLER_2D_SHADOW: glUniform1i(loc, 2); break;
				case GL_UNSIGNED_INT_SAMPLER_2D_ARRAY: glUniform1i(loc, 3); break;
				case 0x8DD0: glUniform1i(loc, 4); break;
				default: System.out.println("  " + s.name + ": unhandled uniform " + name + " type 0x" + Integer.toHexString(type.get(0)));
			}
		}

		// Uniform blocks, zeroed apart from what the water needs
		int blocks = glGetProgrami(program, GL_ACTIVE_UNIFORM_BLOCKS);
		ByteBuffer[] data = new ByteBuffer[blocks];
		for (int b = 0; b < blocks; b++) {
			data[b] = BufferUtils.createByteBuffer(glGetActiveUniformBlocki(program, b, GL_UNIFORM_BLOCK_DATA_SIZE));
			glUniformBlockBinding(program, b, b);
		}
		Setter set = new Setter(program, data);
		float time = 123.4f;
		if (System.getenv("BENCH_CYCLE") != null) {
			set.i("UBOSky.enabled", 1);
			set.f("UBOSky.sunDir", 0.3f, 0.8f, 0.52f);
			set.f("UBOSky.moonDir", -0.3f, -0.8f, -0.52f);
			set.f("UBOGlobal.glareIntensity", 0.5f);
		}
		set.f("UBOGlobal.gammaCorrection", 1);
		set.f("UBOGlobal.saturation", 1);
		set.f("UBOGlobal.contrast", 1);
		set.f("UBOGlobal.drawDistance", 90);
		set.f("UBOGlobal.detailDrawDistance", 90);
		set.f("UBOGlobal.shadowDrawDistance", 90);
		set.i("UBOGlobal.viewportSize", W, H);
		set.i("UBOGlobal.sceneResolution", W, H);
		set.i("UBOGlobal.tiledLightingResolution", 120, 68);
		set.f("UBOGlobal.ambientColor", 0.6f, 0.65f, 0.7f);
		set.f("UBOGlobal.ambientStrength", 1);
		set.f("UBOGlobal.lightColor", 1, 0.95f, 0.9f);
		set.f("UBOGlobal.lightStrength", 1);
		set.f("UBOGlobal.waterColorLight", 0.55f, 0.75f, 0.85f);
		set.f("UBOGlobal.waterColorMid", 0.25f, 0.45f, 0.55f);
		set.f("UBOGlobal.waterColorDark", 0.08f, 0.2f, 0.3f);
		set.f("UBOGlobal.fogColor", 0.5f, 0.6f, 0.7f);
		set.f("UBOGlobal.lightDir", 0.3f, -0.8f, 0.52f);
		set.i("UBOGlobal.castsShadows", 1);
		set.f("UBOGlobal.cameraPos", (ORIGIN + SPAN / 2) * 128, -4000, (ORIGIN - 10) * 128);
		for (String m : new String[] { "viewMatrix", "projectionMatrix", "invProjectionMatrix", "lightProjectionMatrix", "invLightProjectionMatrix" })
			set.f("UBOGlobal." + m, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
		if (TILES) {
			// Straight down onto the tiles, x across and z up the screen
			float sx = 2f / (128 * SPAN), sz = 2f / (128 * SPAN * H / W);
			set.f("UBOGlobal.projectionMatrix", sx, 0, 0, 0, 0, 0, 0, 0, 0, sz, 0, 0, -ORIGIN * 128 * sx - 1, -ORIGIN * 128 * sz - 1, 0, 1);
			set.i("UBOGlobal.orthographicProjection", 1);
		}
		set.f("UBOGlobal.elapsedTime", time);
		set.f("UBOGlobal.waterChurnTime", time);
		set.f("UBOGlobal.windDirection", (float) Math.cos(0.7), (float) Math.sin(0.7));
		set.f("UBOGlobal.waterRoughness", 1);
		set.f("UBOGlobal.waveMaxRoughness", 1);
		set.i("UBOGlobal.waveHistorySteady", 1);
		// The boat data file: wind and roughness histories, then points, bounds, headings, hulls and sizes
		int maxBoats = BoatWakes.MAX_BOATS, maxPoints = BoatWakes.MAX_POINTS;
		int o = 0;
		set.f("UBOGlobal.windHistory[0]", Arrays.copyOfRange(boatData, o, o += 32));
		set.f("UBOGlobal.roughnessHistory[0]", Arrays.copyOfRange(boatData, o, o += 32));
		float[] points = Arrays.copyOfRange(boatData, o, o += maxBoats * maxPoints * 4);
		float[] bounds = Arrays.copyOfRange(boatData, o, o += maxBoats * 4);
		float[] headings = Arrays.copyOfRange(boatData, o, o += maxBoats * 4);
		float[] hulls = Arrays.copyOfRange(boatData, o, o += maxBoats * 4);
		float[] sizes = Arrays.copyOfRange(boatData, o, o += maxBoats * 4);
		// Unused slots only have their hulls cleared, as BoatWakes leaves them
		for (int boat = s.boats; boat < maxBoats; boat++)
			for (int k = 0; k < 4; k++)
				hulls[boat * 4 + k] = 0;
		set.f("UBOGlobal.boatWakePoints[0]", points);
		set.f("UBOGlobal.boatWakeBounds[0]", bounds);
		set.f("UBOGlobal.boatWakeHeadings[0]", headings);
		set.f("UBOGlobal.boatWakeHulls[0]", hulls);
		set.f("UBOGlobal.boatWakeStates[0]", hulls);
		set.f("UBOGlobal.boatWakeSizes[0]", sizes);

		String wt = "UBOWaterTypes.Array[1].";
		set.f(wt + "specularStrength", 0.5f);
		set.f(wt + "specularGloss", 500);
		set.f(wt + "normalStrength", 0.1f);
		set.f(wt + "baseOpacity", 0.5f);
		set.i(wt + "hasFoam", 1);
		set.f(wt + "duration", 1);
		set.f(wt + "fresnelAmount", 1);
		set.f(wt + "surfaceColor", 1, 1, 1);
		set.f(wt + "foamColor", 0.9f, 0.9f, 0.9f);
		set.f(wt + "depthColor", 0.1f, 0.2f, 0.3f);
		// Overrides, as name=f:1,2;name=i:3
		String overrides = System.getenv("BENCH_SET");
		if (overrides != null) {
			for (String ov : overrides.split(";")) {
				String[] kv = ov.split("=");
				String[] vals = kv[1].substring(2).split(",");
				if (kv[1].startsWith("i:")) {
					int[] iv = new int[vals.length];
					for (int k = 0; k < vals.length; k++)
						iv[k] = Integer.parseInt(vals[k]);
					set.i(kv[0], iv);
				} else {
					float[] fv = new float[vals.length];
					for (int k = 0; k < vals.length; k++)
						fv[k] = Float.parseFloat(vals[k]);
					set.f(kv[0], fv);
				}
			}
		}

		// The camera's wave-cell position at every second of the history, for the far wave thinning: by default the
		// middle of the benchmark's view, so nothing on screen counts as far. BENCH_CAMERA=x,z sets it in wave cells.
		int[] history = set.find("UBOGlobal.waveCameraHistory[0]");
		if (history != null) {
			float camX, camZ;
			String cam = System.getenv("BENCH_CAMERA");
			if (cam != null) {
				camX = Float.parseFloat(cam.split(",")[0]);
				camZ = Float.parseFloat(cam.split(",")[1]);
			} else {
				int[] base = set.find("UBOGlobal.sceneBase");
				int[] drift = set.find("UBOGlobal.windDrift");
				int[] scroll = set.find("UBOGlobal.waveScroll");
				float tilesX = data[base[0]].getInt(base[1]) / 128f + ORIGIN + SPAN / 2;
				float tilesZ = data[base[0]].getInt(base[1] + 4) / 128f + ORIGIN + SPAN * H / W / 2;
				// Matches OCEAN_FOAM_DRIFT_SPEED and OCEAN_WAVE_SIZE in ocean_wave_cells.glsl
				camX = (tilesX - data[drift[0]].getFloat(drift[1]) * 0.08f - data[scroll[0]].getFloat(scroll[1])) / 7f;
				camZ = (tilesZ - data[drift[0]].getFloat(drift[1] + 4) * 0.08f - data[scroll[0]].getFloat(scroll[1] + 4)) / 7f;
			}
			float[] all = new float[64];
			for (int k = 0; k < 32; k++) {
				all[k * 2] = camX;
				all[k * 2 + 1] = camZ;
			}
			set.f("UBOGlobal.waveCameraHistory[0]", all);
		}

		for (int b = 0; b < blocks; b++) {
			int buffer = glGenBuffers();
			glBindBuffer(GL_UNIFORM_BUFFER, buffer);
			glBufferData(GL_UNIFORM_BUFFER, data[b], GL_DYNAMIC_DRAW);
			s.buffers.add(new int[] { b, buffer });
		}
		int[] where = set.find("UBOGlobal.elapsedTime");
		if (where != null) {
			s.timeBuffer = s.buffers.get(where[0])[1];
			s.timeOffset = where[1];
			int[] churn = set.find("UBOGlobal.waterChurnTime");
			if (churn != null)
				s.churnOffset = churn[1];
		}
	}

	static class Setter {
		final int program;
		final ByteBuffer[] data;

		Setter(int program, ByteBuffer[] data) {
			this.program = program;
			this.data = data;
			for (ByteBuffer d : data)
				d.order(ByteOrder.nativeOrder());
		}

		// Block index and offset, or null if the uniform isn't active
		int[] find(String name) {
			int index = glGetUniformIndices(program, name);
			if (index == GL_INVALID_INDEX)
				return null;
			int block = glGetActiveUniformsi(program, index, GL_UNIFORM_BLOCK_INDEX);
			if (block < 0)
				return null;
			return new int[] { block, glGetActiveUniformsi(program, index, GL_UNIFORM_OFFSET) };
		}

		void f(String name, float... values) {
			int[] where = find(name);
			if (where == null)
				return;
			for (int i = 0; i < values.length; i++)
				data[where[0]].putFloat(where[1] + i * 4, values[i]);
		}

		void i(String name, int... values) {
			int[] where = find(name);
			if (where == null)
				return;
			for (int i = 0; i < values.length; i++)
				data[where[0]].putInt(where[1] + i * 4, values[i]);
		}
	}

	// A grid of water tiles as the zone renderer draws them, two triangles each, all sharing one face's data
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
		// BENCH_FACE: each vertex's raw shore data, comma separated, overriding the above
		if (System.getenv("BENCH_FACE") != null) {
			String[] raw = System.getenv("BENCH_FACE").split(",");
			for (int i = 0; i < 3; i++)
				shore[i] = (int) Long.parseLong(raw[i].trim());
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

	static String source(File shaderDir, int[] v, String file, int shaderType) throws Exception {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("SHORE_FOAM", v[0]);
		d.put("OCEAN_LACE", v[1]);
		d.put("OCEAN_WAVES", v[2]);
		d.put("BOAT_WAKES", v[3]);
		d.put("DAYLIGHT_CYCLE", System.getenv("BENCH_CYCLE") == null ? 0 : 1);
		d.put("REDUCE_GLARE_INTENSITY", System.getenv("BENCH_CYCLE") == null ? 0 : 1);
		d.put("LEGACY_RENDERER", 0);
		d.put("ZONE_RENDERER", 1);
		d.put("WATER_PASS", 1);
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
		d.put("MAX_SIMULTANEOUS_WORLD_VIEWS", 128);
		d.put("NEBULA_CLUSTER_COUNT", 8);
		// Any defines added since, as 0
		try (var files = Files.walk(shaderDir.toPath())) {
			for (var f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".glsl"))::iterator) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("#include ([A-Z][A-Z0-9_]*)")
					.matcher(new String(Files.readAllBytes(f)));
				while (m.find())
					d.putIfAbsent(m.group(1), 0);
			}
		}
		ShaderIncludes includes = new ShaderIncludes().addIncludePath(ResourcePath.path(shaderDir));
		for (var e : d.entrySet())
			includes.define(e.getKey(), String.valueOf(e.getValue()));
		includes
			.addInclude("VERSION_HEADER", "#version 430\n")
			.addInclude("MATERIAL_CONSTANTS",
				"#define MAT_NONE getMaterial(0)\n#define MAT_CAUSTICS_MAP getMaterial(1)\n" +
				"#define MAT_WATER_FLOW_MAP getMaterial(2)\n#define MAT_WATER_FOAM getMaterial(3)\n")
			.addInclude("MATERIAL_GETTER", "#define getMaterial(i) uboMaterials.Array[i]\n")
			.addInclude("WATER_TYPE_GETTER", "#define getWaterType(i) uboWaterTypes.Array[i]\n")
			.addInclude("WORLD_VIEW_GETTER", "#define getWorldView(i) uboWorldViews.Array[i]\n")
			.addInclude("SHADER_TYPE",
				"#define VERTEX_SHADER " + GL_VERTEX_SHADER + "\n#define FRAGMENT_SHADER " + GL_FRAGMENT_SHADER +
				"\n#define GEOMETRY_SHADER " + GL_GEOMETRY_SHADER + "\n#define COMPUTE_SHADER 37305\n#define SHADER_TYPE " +
				shaderType);
		return includes.loadFile(file);
	}
}
