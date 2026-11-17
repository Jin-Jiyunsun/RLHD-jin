import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * Renders a top-down patch of open sea with 117 HD's ocean wave code, as built by make_shader.py, to a PNG per frame
 * and a contact sheet. Usage: WaveSim <fragment shader> <out folder> [start seconds] [frames] [step seconds]
 * [tiles across] [pixels] [wind degrees] [roughness]
 */
public class WaveSim {
	public static void main(String[] args) throws Exception {
		String fragSource = new String(Files.readAllBytes(new File(args[0]).toPath()), "UTF-8");
		File outDir = new File(args[1]);
		outDir.mkdirs();
		float start = args.length > 2 ? Float.parseFloat(args[2]) : 1000;
		int frames = args.length > 3 ? Integer.parseInt(args[3]) : 48;
		float step = args.length > 4 ? Float.parseFloat(args[4]) : 0.5f;
		float span = args.length > 5 ? Float.parseFloat(args[5]) : 48;
		int size = args.length > 6 ? Integer.parseInt(args[6]) : 512;
		float windDegrees = args.length > 7 ? Float.parseFloat(args[7]) : 0;
		float roughness = args.length > 8 ? Float.parseFloat(args[8]) : 1;

		Frame frame = new Frame("wave sim");
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
			System.out.println(glGetString(GL_RENDERER));

			int program = link(
				"#version 330\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
					+ " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }",
				fragSource);
			int vao = glGenVertexArrays();
			glBindVertexArray(vao);

			int fbo = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			int tex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, tex);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
			if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
				throw new IllegalStateException("framebuffer incomplete");
			glViewport(0, 0, size, size);

			glUseProgram(program);
			glUniform1f(glGetUniformLocation(program, "uWindAngle"), (float) Math.toRadians(windDegrees));
			glUniform1f(glGetUniformLocation(program, "uRoughness"), roughness);
			glUniform2f(glGetUniformLocation(program, "uOrigin"), 3000, 3000);
			glUniform1f(glGetUniformLocation(program, "uSpan"), span);
			glUniform2f(glGetUniformLocation(program, "uSize"), size, size);
			int timeLoc = glGetUniformLocation(program, "uTime");

			ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
			BufferedImage[] images = new BufferedImage[frames];
			for (int f = 0; f < frames; f++) {
				glUniform1f(timeLoc, start + f * step);
				glDrawArrays(GL_TRIANGLES, 0, 3);
				glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
				BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						int i = ((size - 1 - y) * size + x) * 4;
						image.setRGB(x, y, (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | pixels.get(i + 2) & 0xFF);
					}
				}
				label(image, String.format("%.1f s", f * step), windDegrees);
				images[f] = image;
				ImageIO.write(image, "png", new File(outDir, String.format("frame_%03d.png", f)));
			}

			// Every few frames, four across
			int every = Math.max(1, frames / 12);
			int count = (frames + every - 1) / every;
			int columns = Math.min(4, count);
			int rows = (count + columns - 1) / columns;
			int gap = 6;
			BufferedImage sheet = new BufferedImage(columns * (size + gap) - gap, rows * (size + gap) - gap, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = sheet.createGraphics();
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
			for (int n = 0; n < count; n++)
				g.drawImage(images[n * every], n % columns * (size + gap), n / columns * (size + gap), null);
			g.dispose();
			ImageIO.write(sheet, "png", new File(outDir, "sheet.png"));
			System.out.println("rendered " + frames + " frames");
		} finally {
			frame.dispose();
		}
		System.exit(0);
	}

	static void label(BufferedImage image, String text, float windDegrees) {
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRoundRect(4, 6, 74, 28, 8, 8);
		g.fillRoundRect(image.getWidth() - 62, 4, 58, 60, 8, 8);
		g.setColor(Color.WHITE);
		g.drawString(text, 10, 26);
		// The way the wind blows, with world Z up the image
		double a = Math.toRadians(windDegrees);
		int cx = image.getWidth() - 34, cy = 24;
		int dx = (int) Math.round(Math.cos(a) * 16), dy = (int) Math.round(-Math.sin(a) * 16);
		g.setStroke(new BasicStroke(2.5f));
		g.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);
		double back = Math.atan2(-dy, -dx);
		for (double side : new double[] { -0.5, 0.5 })
			g.drawLine(cx + dx, cy + dy, cx + dx + (int) Math.round(Math.cos(back + side) * 8), cy + dy + (int) Math.round(Math.sin(back + side) * 8));
		g.drawString("wind", cx - 22, cy + 34);
		g.dispose();
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
