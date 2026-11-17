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
 * Renders numbered ocean waves side by side, each on its own through one lifetime, with a gallery shader from
 * make_shader.py. Usage: WaveGallery <fragment shader> <out folder> [waves] [columns] [tile pixels] [frames]
 * [step seconds] [first wave] [wind degrees] [roughness]
 */
public class WaveGallery {
	public static void main(String[] args) throws Exception {
		String fragSource = new String(Files.readAllBytes(new File(args[0]).toPath()), "UTF-8");
		File outDir = new File(args[1]);
		outDir.mkdirs();
		int waves = args.length > 2 ? Integer.parseInt(args[2]) : 20;
		int columns = args.length > 3 ? Integer.parseInt(args[3]) : 5;
		int tile = args.length > 4 ? Integer.parseInt(args[4]) : 192;
		int frames = args.length > 5 ? Integer.parseInt(args[5]) : 70;
		float step = args.length > 6 ? Float.parseFloat(args[6]) : 0.2f;
		int firstWave = args.length > 7 ? Integer.parseInt(args[7]) : 1;
		float windDegrees = args.length > 8 ? Float.parseFloat(args[8]) : 0;
		float roughness = args.length > 9 ? Float.parseFloat(args[9]) : 1;
		int rows = (waves + columns - 1) / columns;
		int gap = 4;
		int width = columns * (tile + gap) - gap;
		int height = rows * (tile + gap) - gap;

		Frame frame = new Frame("wave gallery");
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
					+ " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }",
				fragSource);
			glBindVertexArray(glGenVertexArrays());
			glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
			int tex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, tex);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
			glUseProgram(program);
			glUniform1f(glGetUniformLocation(program, "uWindAngle"), (float) Math.toRadians(windDegrees));
			glUniform1f(glGetUniformLocation(program, "uRoughness"), roughness);
			glUniform1f(glGetUniformLocation(program, "uTileSize"), tile);
			int cellLoc = glGetUniformLocation(program, "uCell");
			int originLoc = glGetUniformLocation(program, "uTileOrigin");
			int localLoc = glGetUniformLocation(program, "uLocal");

			ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
			for (int f = 0; f < frames; f++) {
				glViewport(0, 0, width, height);
				glClearColor(0, 0, 0, 1);
				glClear(GL_COLOR_BUFFER_BIT);
				glUniform1f(localLoc, f * step);
				for (int w = 0; w < waves; w++) {
					int x = w % columns * (tile + gap);
					// GL counts rows from the bottom
					int y = height - tile - w / columns * (tile + gap);
					glViewport(x, y, tile, tile);
					int k = firstWave + w;
					glUniform2f(cellLoc, 5 * k + 2, 3 * k + 7);
					glUniform2f(originLoc, x, y);
					glDrawArrays(GL_TRIANGLES, 0, 3);
				}
				glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
				BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int i = ((height - 1 - y) * width + x) * 4;
						image.setRGB(x, y, (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | pixels.get(i + 2) & 0xFF);
					}
				}
				Graphics2D g = image.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
				for (int w = 0; w < waves; w++) {
					int x = w % columns * (tile + gap), y = w / columns * (tile + gap);
					g.setColor(new Color(0, 0, 0, 150));
					g.fillRoundRect(x + 4, y + 4, 34, 22, 6, 6);
					g.setColor(Color.WHITE);
					g.drawString(String.valueOf(firstWave + w), x + 9, y + 21);
				}
				g.setColor(new Color(0, 0, 0, 170));
				g.fillRoundRect(width - 168, height - 30, 164, 26, 6, 6);
				g.setColor(Color.WHITE);
				g.drawString(String.format("%.1f s   wind ->", f * step), width - 160, height - 11);
				g.dispose();
				ImageIO.write(image, "png", new File(outDir, String.format("frame_%03d.png", f)));
			}
			System.out.println("rendered " + frames + " frames of " + waves + " waves");
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
