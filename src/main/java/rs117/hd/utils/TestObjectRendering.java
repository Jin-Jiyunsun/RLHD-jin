package rs117.hd.utils;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.InheritTileColorType;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.*;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.MathUtils.*;

/**
 * Developer tools: how 117 HD renders and lights the client-side test objects spawned by {@link DeveloperTools}.
 * Kept here rather than in the renderer, so 117 HD's own classes only need small hooks.
 */
public class TestObjectRendering {
	/**
	 * A recreated object uploaded with its zone like static scenery, instead of being redrawn every frame like a
	 * RuneLiteObject. Its model is already rotated the way the client rotates the object it stands in for.
	 */
	@RequiredArgsConstructor
	public static class StaticObject {
		public final int objectId;
		public final Model model;
		// The same orientations 117 HD gets for the real object: before & while being drawn
		public final int preOrientation;
		public final int orientation;
		// For the object's lights, which real objects orient by their rotation
		public final int lightOrientation;
		public final int plane;
		// Local coordinates in the scene
		public final int x;
		public final int y;
		public final int height;
		public volatile boolean removed;
	}

	/**
	 * Whether a static recreated object's light should stay
	 */
	public static boolean updateLight(Light light, StaticObject object) {
		if (object.removed) {
			light.markedForRemoval = true;
			return false;
		}
		return true;
	}

	/**
	 * Update a light attached to a test object, returning whether the object still exists to keep the light on.
	 */
	public static boolean updateLight(Light light, RuneLiteObject object) {
		light.orientation = getLightOrientation(object);
		if (!object.isActive()) {
			light.markedForRemoval = true;
			return false;
		}
		if (!light.animationSpecific)
			return true;
		var controller = object.getAnimationController();
		var animation = controller == null ? null : controller.getAnimation();
		return light.def.animationIds.contains(animation == null ? -1 : animation.getId());
	}

	/**
	 * Real objects' lights are oriented 1024 + 512 * rotation, so match that for test objects rotated in 90 degree steps.
	 */
	public static int getLightOrientation(RuneLiteObject object) {
		return mod(1024 + object.getOrientation(), 2048);
	}

	/**
	 * Find the ground tile under a test object, for it to inherit colors from like the static object it stands in for.
	 */
	@Nullable
	public static TileColors findTileColors(SceneContext ctx, int plane, float localX, float localZ) {
		int tileExX = ((int) localX >> LOCAL_COORD_BITS) + ctx.sceneOffset;
		int tileExY = ((int) localZ >> LOCAL_COORD_BITS) + ctx.sceneOffset;
		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		if (plane < 0 || plane >= tiles.length ||
			tileExX < 0 || tileExX >= tiles[plane].length ||
			tileExY < 0 || tileExY >= tiles[plane][tileExX].length)
			return null;
		Tile tile = tiles[plane][tileExX][tileExY];
		return tile == null ? null : new TileColors(ctx, tile, tileExX, tileExY, plane);
	}

	/**
	 * The colors a test object's faces can inherit from the tile it stands on
	 */
	@RequiredArgsConstructor
	public static class TileColors {
		private final SceneContext ctx;
		private final Tile tile;
		private final int tileExX;
		private final int tileExY;
		private final int tileZ;

		/**
		 * The color a face with this inherit type should take from the tile, or -1 if the tile has no suitable color.
		 * Mirrors how 117 HD's SceneUploader colors static objects, so keep the two in sync.
		 */
		public int get(InheritTileColorType inheritTileColorType) {
			if (inheritTileColorType == InheritTileColorType.NONE)
				return -1;

			SceneTileModel tileModel = tile.getSceneTileModel();
			SceneTilePaint tilePaint = tile.getSceneTilePaint();

			// No point in inheriting tilepaint color if the ground tile does not have a color, for example above a cave wall
			if (
				tilePaint != null &&
				tilePaint.getTexture() == -1 &&
				tilePaint.getRBG() != 0 &&
				tilePaint.getNeColor() != HIDDEN_HSL
			) {
				// Since tile colors are guaranteed to have the same hue and saturation per face,
				// we can blend without converting from HSL to RGB
				int averageColor =
					(tilePaint.getSwColor() + tilePaint.getNwColor() + tilePaint.getNeColor() + tilePaint.getSeColor()) / 4;
				return ctx.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_MAIN).modifyColor(averageColor);
			}

			if (tileModel == null || tileModel.getTriangleTextureId() != null)
				return -1;

			int faceColorIndex = -1;
			for (int i = 0; i < tileModel.getTriangleColorA().length; i++) {
				boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, i);
				// Use underlay if the tile does not have an overlay, useful for rocks in cave corners
				if (inheritTileColorType == InheritTileColorType.UNDERLAY || tileModel.getModelOverlay() == 0) {
					if (!isOverlay) {
						faceColorIndex = i;
						break;
					}
				} else if (isOverlay) {
					faceColorIndex = i;
					break;
				}
			}
			if (faceColorIndex == -1)
				return -1;

			int color = tileModel.getTriangleColorA()[faceColorIndex];
			if (color == HIDDEN_HSL)
				return -1;
			int overrideType = inheritTileColorType == InheritTileColorType.OVERLAY ? TILE_OVERRIDE_OVERLAY : TILE_OVERRIDE_UNDERLAY;
			return ctx.getTileOverride(tileZ, tileExX, tileExY, overrideType).modifyColor(color);
		}
	}
}
