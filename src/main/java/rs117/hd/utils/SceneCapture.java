package rs117.hd.utils;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;

/**
 * Developer tools: saves every object in the loaded scene relative to the player, so {@link DeveloperTools} can spawn
 * them again as test objects somewhere else. Captures are kept as JSON files in 117 HD's folder in .runelite.
 */
public class SceneCapture {
	public static final ResourcePath CAPTURES_PATH = HdPlugin.PLUGIN_DIR.resolve("scene_captures");

	private static final int SHAPE_MASK = 0x3F;
	private static final int SHAPE_WALL_CORNER = 2;
	private static final int SHAPE_DECORATION_STRAIGHT = 4;
	private static final int SHAPE_DECORATION_DIAGONAL_OFFSET = 6;
	private static final int SHAPE_DECORATION_DIAGONAL_NO_OFFSET = 7;
	private static final int SHAPE_DECORATION_DIAGONAL_BOTH = 8;
	private static final int SHAPE_CENTREPIECE = 10;
	private static final int SHAPE_CENTREPIECE_DIAGONAL = 11;
	private static final int SHAPE_GROUND_DECORATION = 22;

	/**
	 * How much of the scene to capture. Every recreated object is redrawn every frame, unlike the static objects it
	 * stands in for, so a whole scene's worth is slow.
	 */
	@RequiredArgsConstructor
	public enum Area {
		REGION("This region", -1),
		NEARBY("Within 16 tiles", 16),
		SCENE("Whole scene (slow)", -1);

		private final String label;
		// Tiles in each direction around the player, or -1 for the region or whole scene
		private final int tileRadius;

		@Override
		public String toString() {
			return label;
		}
	}

	public enum Kind {
		GAME,
		WALL,
		DECORATION,
		GROUND
	}

	public static class Capture {
		public String name;
		// Where the player stood, only for reference, since captures are recreated around wherever the player is
		public WorldPoint capturedAt;
		public List<CapturedObject> objects = new ArrayList<>();
	}

	/**
	 * One object, positioned in local units and planes relative to the player, and in height relative to the ground
	 * the player stands on, where more negative is higher up
	 */
	public static class CapturedObject {
		public int id;
		public Kind kind;
		public int shape;
		public int rotation;
		public int x;
		public int y;
		public int height;
		public int plane;
		// Wall decorations' offset from their wall, which only their first part moves by
		public int xOffset;
		public int yOffset;
		// Game objects' extra rotation, such as 45 degrees for diagonal objects
		public int modelOrientation;
	}

	/**
	 * One model to spawn for a captured object, chosen the same way the client builds the object for its scene.
	 * Rotations above 3 pick the mirrored or 45 degree variant of a wall or wall decoration model.
	 */
	@RequiredArgsConstructor
	public static class Part {
		public final int modelType;
		public final int rotation;
		public final boolean offset;

		public boolean isMirrored() {
			return modelType == SHAPE_WALL_CORNER && rotation > 3;
		}

		public boolean isDiagonal() {
			return modelType == SHAPE_DECORATION_STRAIGHT && rotation > 3;
		}
	}

	/**
	 * Capture every object on the player's plane and below within the area. Must be called on the client thread.
	 */
	@Nullable
	public static Capture capture(Client client, String name, Area area) {
		assert client.isClientThread();
		Player player = client.getLocalPlayer();
		WorldView worldView = client.getTopLevelWorldView();
		if (player == null || worldView == null)
			return null;
		LocalPoint origin = player.getLocalLocation();
		int originPlane = worldView.getPlane();
		int groundHeight = Perspective.getTileHeight(client, origin, originPlane);

		Capture capture = new Capture();
		capture.name = name;
		capture.capturedAt = player.getWorldLocation();
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene.getTiles();
		// Skip the planes above the player, which the game hides like roofs, and which instances fill with copies of the map.
		// Recreated objects aren't hidden like that, so they'd stack on top of each other.
		for (int z = 0; z <= originPlane && z < tiles.length; z++) {
			for (Tile[] column : tiles[z]) {
				for (Tile tile : column) {
					if (tile == null)
						continue;
					var sceneLocation = tile.getSceneLocation();
					if (area == Area.REGION ? (
						scene.getBaseX() + sceneLocation.getX() >> 6 != capture.capturedAt.getX() >> 6 ||
						scene.getBaseY() + sceneLocation.getY() >> 6 != capture.capturedAt.getY() >> 6
					) : area.tileRadius != -1 && (
						Math.abs(sceneLocation.getX() - origin.getSceneX()) > area.tileRadius ||
						Math.abs(sceneLocation.getY() - origin.getSceneY()) > area.tileRadius
					))
						continue;

					for (GameObject object : tile.getGameObjects()) {
						// Skip actors & projectiles, and objects bigger than one tile everywhere but their first tile
						if (object == null || ModelHash.isTemporaryObject(object.getHash()) ||
							object.getRenderable() == null || object.getRenderable() instanceof Actor ||
							!object.getSceneMinLocation().equals(sceneLocation))
							continue;
						var captured = capture(client, object, Kind.GAME, object.getConfig(), origin, originPlane, groundHeight);
						if (captured != null) {
							captured.modelOrientation = object.getModelOrientation();
							capture.objects.add(captured);
						}
					}

					WallObject wall = tile.getWallObject();
					if (wall != null && wall.getRenderable1() != null)
						add(capture, capture(client, wall, Kind.WALL, wall.getConfig(), origin, originPlane, groundHeight));

					DecorativeObject decoration = tile.getDecorativeObject();
					if (decoration != null && decoration.getRenderable() != null) {
						var captured = capture(client, decoration, Kind.DECORATION, decoration.getConfig(), origin, originPlane, groundHeight);
						if (captured != null) {
							captured.xOffset = decoration.getXOffset();
							captured.yOffset = decoration.getYOffset();
							capture.objects.add(captured);
						}
					}

					GroundObject ground = tile.getGroundObject();
					if (ground != null && ground.getRenderable() != null)
						add(capture, capture(client, ground, Kind.GROUND, ground.getConfig(), origin, originPlane, groundHeight));
				}
			}
		}
		return capture;
	}

	private static void add(Capture capture, @Nullable CapturedObject object) {
		if (object != null)
			capture.objects.add(object);
	}

	@Nullable
	private static CapturedObject capture(
		Client client,
		TileObject object,
		Kind kind,
		int config,
		LocalPoint origin,
		int originPlane,
		int groundHeight
	) {
		// Objects which change with varbits are captured as whatever they currently look like
		int id = object.getId();
		var def = client.getObjectDefinition(id);
		if (def.getImpostorIds() != null) {
			var impostor = def.getImpostor();
			if (impostor == null)
				return null;
			id = impostor.getId();
		}

		var captured = new CapturedObject();
		captured.id = id;
		captured.kind = kind;
		captured.shape = config & SHAPE_MASK;
		captured.rotation = config >>> 6 & 3;
		captured.x = object.getX() - origin.getX();
		captured.y = object.getY() - origin.getY();
		captured.height = object.getZ() - groundHeight;
		captured.plane = object.getPlane() - originPlane;
		return captured;
	}

	/**
	 * The models the client builds for an object of this kind, shape & rotation, which most objects only have one of
	 */
	public static List<Part> getParts(CapturedObject object) {
		int rotation = object.rotation;
		switch (object.kind) {
			case GROUND:
				return List.of(new Part(SHAPE_GROUND_DECORATION, rotation, false));
			case WALL:
				if (object.shape == SHAPE_WALL_CORNER)
					return List.of(new Part(SHAPE_WALL_CORNER, rotation + 4, false), new Part(SHAPE_WALL_CORNER, rotation + 1 & 3, false));
				return List.of(new Part(object.shape, rotation, false));
			case DECORATION:
				switch (object.shape) {
					case SHAPE_DECORATION_DIAGONAL_OFFSET:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, rotation + 4, true));
					case SHAPE_DECORATION_DIAGONAL_NO_OFFSET:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, (rotation + 2 & 3) + 4, true));
					case SHAPE_DECORATION_DIAGONAL_BOTH:
						return List.of(
							new Part(SHAPE_DECORATION_STRAIGHT, rotation + 4, true),
							new Part(SHAPE_DECORATION_STRAIGHT, (rotation + 2 & 3) + 4, false)
						);
					default:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, rotation, true));
				}
			case GAME:
			default:
				return List.of(new Part(object.shape == SHAPE_CENTREPIECE_DIAGONAL ? SHAPE_CENTREPIECE : object.shape, rotation, false));
		}
	}

	/**
	 * The model IDs for a part, or null if the object has no model of that type.
	 * Objects without model types only have centrepiece models, which the client merges.
	 */
	@Nullable
	public static int[] getModelIds(DeveloperTools.TestObjectDefinition def, Part part) {
		if (def.modelTypes == null)
			return part.modelType == SHAPE_CENTREPIECE ? def.allModels : null;
		for (int i = 0; i < def.modelTypes.length; i++)
			if (def.modelTypes[i] == part.modelType)
				return new int[] { def.allModels[i] };
		return null;
	}

	/**
	 * Mirror a model the way the client does, which also flips its faces' winding so they don't turn inside out.
	 * Only call this on a model from {@link Client#mergeModels}, since it changes the face arrays in place.
	 */
	public static void mirror(ModelData model) {
		float[] z = model.getVerticesZ();
		for (int i = 0; i < model.getVerticesCount(); i++)
			z[i] = -z[i];
		int[] a = model.getFaceIndices1();
		int[] c = model.getFaceIndices3();
		for (int i = 0; i < model.getFaceCount(); i++) {
			int tmp = a[i];
			a[i] = c[i];
			c[i] = tmp;
		}
	}

	/**
	 * Turn a wall decoration model 45 degrees to sit against a diagonal wall, and move it out to the wall like the client does
	 */
	public static void rotateDiagonal(ModelData model) {
		float[] x = model.getVerticesX();
		float[] z = model.getVerticesZ();
		float sin = (float) Math.sin(Math.PI / 4);
		for (int i = 0; i < model.getVerticesCount(); i++) {
			float rotatedX = z[i] * sin + x[i] * sin;
			z[i] = z[i] * sin - x[i] * sin;
			x[i] = rotatedX;
		}
		model.translate(45, 0, -45);
	}

	/**
	 * Save a capture under a file name made from its name, without replacing an earlier capture. Returns the file name.
	 */
	public static String save(Gson gson, Capture capture) throws IOException {
		CAPTURES_PATH.mkdirs();
		String baseName = capture.name.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
		if (baseName.isEmpty())
			baseName = "capture-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		String fileName = baseName + ".json";
		for (int i = 2; CAPTURES_PATH.resolve(fileName).exists(); i++)
			fileName = baseName + " (" + i + ").json";
		CAPTURES_PATH.resolve(fileName).writeString(gson.toJson(capture));
		return fileName;
	}

	public static Capture load(Gson gson, String fileName) throws IOException {
		return CAPTURES_PATH.resolve(fileName).loadJson(gson, Capture.class);
	}

	/**
	 * The file names of every saved capture, sorted by name
	 */
	public static List<String> list() throws IOException {
		if (!CAPTURES_PATH.exists())
			return List.of();
		try (Stream<Path> files = Files.list(CAPTURES_PATH.toPath())) {
			return files
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.collect(Collectors.toList());
		}
	}
}
