import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A boat sailing straight along +x over the ocean waves. Usage: WakeSim <shader> <out> [speed tiles/s] [frames]
 * [step s] [showSwept 0/1] [width px] [span tiles]
 */
public class WakeSim {
	public static void main(String[] args) throws Exception {
		String frag = new String(Files.readAllBytes(new File(args[0]).toPath()), "UTF-8");
		File outDir = new File(args[1]);
		outDir.mkdirs();
		float speed = args.length > 2 ? Float.parseFloat(args[2]) : 2;
		int frames = args.length > 3 ? Integer.parseInt(args[3]) : 60;
		float step = args.length > 4 ? Float.parseFloat(args[4]) : 0.1f;
		int showSwept = args.length > 5 ? Integer.parseInt(args[5]) : 1;
		int width = args.length > 6 ? Integer.parseInt(args[6]) : 640;
		float span = args.length > 7 ? Float.parseFloat(args[7]) : 32;
		boolean boat = args.length <= 8 || !args[8].equals("0");
		int height = width / 2;

		Frame frame = new Frame("wake sim");
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
			int program = link(
				"#version 330\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
					+ " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }", frag);
			glBindVertexArray(glGenVertexArrays());
			glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
			int tex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, tex);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
			glViewport(0, 0, width, height);
			glUseProgram(program);
			float originX = 1000, originZ = 1000;
			glUniform2f(glGetUniformLocation(program, "uOrigin"), originX, originZ);
			glUniform1f(glGetUniformLocation(program, "uSpan"), span);
			glUniform2f(glGetUniformLocation(program, "uSize"), width, width);
			glUniform1i(glGetUniformLocation(program, "uShowSwept"), showSwept);
			glUniform4f(glGetUniformLocation(program, "uBoatSize"), 10, 3, 0, 0);
			glUniform4f(glGetUniformLocation(program, "uBounds"), -1e6f, -1e6f, 1e6f, 1e6f);
			int pointsLoc = glGetUniformLocation(program, "uPoints");
			ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
			float startX = originX + 2;
			float z = originZ + span / 4;
			for (int f = 0; f < frames; f++) {
				float t = f * step;
				float x = startX + speed * t;
				// Points from the stern back every 0.25 s, back to 4.5 s or the start
				float[] pts = new float[40 * 4];
				int n = 0;
				for (float a = 0; a <= Math.min(t, 4.5f) + 1e-4f && n < 40; a += 0.25f) {
					pts[n * 4] = x - speed * a;
					pts[n * 4 + 1] = z;
					pts[n * 4 + 2] = a;
					pts[n * 4 + 3] = speed * a;
					n++;
				}
				glUniform4fv(pointsLoc, pts);
				glUniform4f(glGetUniformLocation(program, "uHeading"), 1, 0, x, z);
				glUniform4f(glGetUniformLocation(program, "uHull"), boat ? n : 0, 0, t, 0);
				glUniform1f(glGetUniformLocation(program, "uTime"), 2000 + t);
				glClear(GL_COLOR_BUFFER_BIT);
				glDrawArrays(GL_TRIANGLES, 0, 3);
				glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
				BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < height; y++)
					for (int px = 0; px < width; px++) {
						int i = ((height - 1 - y) * width + px) * 4;
						image.setRGB(px, y, (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | pixels.get(i + 2) & 0xFF);
					}
				Graphics2D g = image.createGraphics();
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
				g.setColor(Color.WHITE);
				g.drawString(String.format("%.1f s  %.1f tiles/s", t, speed), 8, 18);
				g.dispose();
				ImageIO.write(image, "png", new File(outDir, String.format("frame_%03d.png", f)));
			}
			System.out.println("rendered " + frames);
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
