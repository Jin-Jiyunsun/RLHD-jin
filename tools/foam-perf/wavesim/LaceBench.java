import java.awt.Canvas;
import java.awt.Frame;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Times fragment shaders drawing a full 1920x1080 frame, and compares their output with the first's.
 * Usage: LaceBench <span tiles> <shader>...
 */
public class LaceBench {
	public static void main(String[] args) throws Exception {
		int w = 1920, h = 1080;
		float span = Float.parseFloat(args[0]);
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
			int tex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, tex);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, w, h, 0, GL_RED, GL_FLOAT, (FloatBuffer) null);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
			glViewport(0, 0, w, h);
			int query = glGenQueries();
			float[] reference = null;
			for (int s = 1; s < args.length; s++) {
				String frag = new String(Files.readAllBytes(new File(args[s]).toPath()), "UTF-8");
				int program = link("#version 330" + "\n" + "void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
					+ " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }", frag);
				glUseProgram(program);
				if (System.getenv("BENCH_DUMP") != null) {
					int len = glGetProgrami(program, org.lwjgl.opengl.GL41C.GL_PROGRAM_BINARY_LENGTH);
					java.nio.ByteBuffer bin = BufferUtils.createByteBuffer(len);
					int[] fmt = new int[1];
					org.lwjgl.opengl.GL41C.glGetProgramBinary(program, (int[]) null, fmt, bin);
					byte[] bytes = new byte[len];
					bin.get(bytes);
					Files.write(new File(args[s].replace(".glsl", ".bin")).toPath(), bytes);
				}
				glUniform2f(glGetUniformLocation(program, "uSize"), w, h);
				glUniform1f(glGetUniformLocation(program, "uSpan"), span);
				int timeLoc = glGetUniformLocation(program, "uTime");
				// For the wave shaders, which ignore the ones they don't have
				glUniform1f(glGetUniformLocation(program, "uRoughness"), 1);
				glUniform1f(glGetUniformLocation(program, "uWindAngle"), 0.7f);
				glUniform2f(glGetUniformLocation(program, "uOrigin"), 3000, 3000);
				glUniform1f(glGetUniformLocation(program, "uMaxRough"), 1f);
				if (glGetUniformLocation(program, "uFoam") != -1) {
					java.awt.image.BufferedImage foam = javax.imageio.ImageIO.read(new File("water_foam.jpg"));
					int fw = foam.getWidth(), fh = foam.getHeight();
					java.nio.ByteBuffer fb = BufferUtils.createByteBuffer(fw * fh * 4);
					for (int y = 0; y < fh; y++)
						for (int x = 0; x < fw; x++) {
							int c = foam.getRGB(x, y);
							fb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 255);
						}
					fb.flip();
					glActiveTexture(GL_TEXTURE1);
					int ft = glGenTextures();
					glBindTexture(GL_TEXTURE_2D, ft);
					glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fw, fh, 0, GL_RGBA, GL_UNSIGNED_BYTE, fb);
					glGenerateMipmap(GL_TEXTURE_2D);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
					glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
					glActiveTexture(GL_TEXTURE0);
					glUniform1i(glGetUniformLocation(program, "uFoam"), 1);
				}
				int block = glGetUniformBlockIndex(program, "HistoryBlock");
				if (block != -1) {
					java.nio.FloatBuffer hist = BufferUtils.createFloatBuffer(4096);
					String uboFile = System.getenv("BENCH_UBO");
					if (uboFile != null) {
						java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(Files.readAllBytes(new File(uboFile).toPath())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
						while (bytes.hasRemaining()) hist.put(bytes.getFloat());
						while (hist.hasRemaining()) hist.put(0f);
					} else {
					for (int k = 0; k < 32; k++) hist.put(0.7f);
					for (int k = 0; k < 32; k++) hist.put(1f);
					while (hist.hasRemaining()) hist.put(0f);
					}
					hist.flip();
					int ubo = glGenBuffers();
					glBindBuffer(GL_UNIFORM_BUFFER, ubo);
					glBufferData(GL_UNIFORM_BUFFER, hist, GL_STATIC_DRAW);
					glUniformBlockBinding(program, block, 0);
					glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
				}
				// Warm up, then several timed rounds, keeping the best
				double best = Double.MAX_VALUE;
				for (int round = 0; round < 8; round++) {
					glBeginQuery(GL_TIME_ELAPSED, query);
					for (int i = 0; i < 50; i++) {
						glUniform1f(timeLoc, 100 + i * 0.37f);
						glDrawArrays(GL_TRIANGLES, 0, 3);
					}
					glEndQuery(GL_TIME_ELAPSED);
					long ns = glGetQueryObjectui64(query, GL_QUERY_RESULT);
					if (round > 0)
						best = Math.min(best, ns / 50.0 / 1e6);
				}
				glUniform1f(timeLoc, 123.4f);
				glDrawArrays(GL_TRIANGLES, 0, 3);
				FloatBuffer pixels = BufferUtils.createFloatBuffer(w * h);
				glReadPixels(0, 0, w, h, GL_RED, GL_FLOAT, pixels);
				float[] out = new float[w * h];
				pixels.get(out);
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < h; y++)
					for (int x = 0; x < w; x++) {
						float v = Math.max(0, Math.min(1, out[(h - 1 - y) * w + x]));
						int r = (int) ((0.04 + (0.92 - 0.04) * v) * 255), gg = (int) ((0.16 + (0.96 - 0.16) * v) * 255), b = (int) ((0.22 + (0.98 - 0.22) * v) * 255);
						img.setRGB(x, y, r << 16 | gg << 8 | b);
					}
				ImageIO.write(img, "png", new File(args[s].replace(".glsl", ".png")));
				java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(out.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
				for (float v : out)
					raw.putFloat(v);
				Files.write(new File(args[s].replace(".glsl", ".f32")).toPath(), raw.array());
				String cmp = "";
				if (reference == null) {
					reference = out;
				} else {
					double maxDiff = 0;
					int differ = 0;
					for (int i = 0; i < out.length; i++) {
						double d = Math.abs(out[i] - reference[i]);
						maxDiff = Math.max(maxDiff, d);
						if (d > 1 / 512.0)
							differ++;
					}
					cmp = String.format("  max diff %.5f, %d pixels differ by over 1/512", maxDiff, differ);
				}
				long lit = 0;
				for (float v : out)
					if (v > 0)
						lit++;
				System.out.printf("%-28s %.3f ms/frame  lace on %.1f%% of pixels%s%n",
					new File(args[s]).getName(), best, 100.0 * lit / out.length, cmp);
			}
		} finally {
			frame.dispose();
		}
		System.exit(0);
	}

	static int link(String vertSource, String fragSource) {
		int program = glCreateProgram();
		for (int[] stage : new int[][] { { GL_VERTEX_SHADER, 0 }, { GL_FRAGMENT_SHADER, 1 } }) {
			int shader = glCreateShader(stage[0]);
			glShaderSource(shader, stage[1] == 0 ? vertSource : fragSource);
			glCompileShader(shader);
			if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				throw new IllegalStateException(glGetShaderInfoLog(shader));
			glAttachShader(program, shader);
		}
		glLinkProgram(program);
		if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE)
			throw new IllegalStateException(glGetProgramInfoLog(program));
		return program;
	}
}
