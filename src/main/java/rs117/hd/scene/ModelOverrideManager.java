package rs117.hd.scene;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.TestObjectRendering;
import rs117.hd.utils.collections.Int2ObjectHashMap;
import rs117.hd.utils.collections.IntHashSet;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class ModelOverrideManager {
	private static final ResourcePath MODEL_OVERRIDES_PATH = Props
		.getFile("rlhd.model-overrides-path", () -> path(ModelOverrideManager.class, "model_overrides.json"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	private final Int2ObjectHashMap<ModelOverride> modelOverrides = new Int2ObjectHashMap<>();
	private final IntHashSet detailCullingBlacklist = new IntHashSet();

	// Developer tools: client-side objects which should be treated as the mapped object ID. Only change through the methods
	// below, which also index them by position, since a recreated scene capture can add thousands.
	public final Map<RuneLiteObject, Integer> testObjects = new ConcurrentHashMap<>();
	private final Map<Long, Integer> testObjectPositions = new ConcurrentHashMap<>();
	// Developer tools: recreated objects uploaded with their zone like static scenery, by the extended tile they stand on.
	// Read while zones upload on other threads, so lists are replaced rather than changed.
	public final Map<Integer, List<TestObjectRendering.StaticObject>> staticTestObjects = new ConcurrentHashMap<>();
	// Developer tools: a recreated floor drawn in place of the real tiles it covers, by the same extended tiles.
	// More than one can land on a tile, since recreated captures put every plane on the player's.
	public final Map<Integer, List<TestObjectRendering.FloorTile>> staticTestFloor = new ConcurrentHashMap<>();
	// Developer tools: counts how many times overrides have been loaded, so tools can tell when to refresh
	@Getter
	private int configVersion;

	private FileWatcher.UnregisterCallback fileWatcher;

	public static int packStaticTestObjectTile(int level, int tileExX, int tileExY) {
		return level << 16 | tileExX << 8 | tileExY;
	}

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try (var gamevals = gamevalManager.obtainHandle()) {
				sceneManager.getLoadingLock().lock();
				sceneManager.completeAllStreaming();

				ModelOverride[] parsedOverrides = path.loadJson(plugin.getGson(), ModelOverride[].class);
				if (parsedOverrides == null)
					throw new IOException("Empty or invalid: " + path);

				modelOverrides.clear();
				for (ModelOverride override : parsedOverrides) {
					try {
						override.normalize(plugin);
					} catch (IllegalStateException ex) {
						log.error("Invalid model override '{}': {}", override.description, ex.getMessage());
						continue;
					}

					addOverride(override, gamevals);

					if (override.hideInAreas.length > 0) {
						var hider = override.copy();
						hider.hide = true;
						hider.areas = override.hideInAreas;
						addOverride(hider, gamevals);
					}
				}

				addOverride(fishingSpotReplacer.getModelOverride(), gamevals);
				addSailingCullingOverrides(gamevals);

				detailCullingBlacklist.clear();
				for (var entry : modelOverrides) {
					final ModelOverride override = entry.getValue();
					if (entry.getValue().disableDetailCulling)
						detailCullingBlacklist.add(entry.getKey());
					override.clearIds();
				}

				log.debug("Loaded {} model overrides", modelOverrides.size());
				configVersion++;

				if (first)
					return;

				plugin.renderer.clearCaches();
				plugin.renderer.reloadScene();
			} catch (Exception ex) {
				log.error("Failed to load model overrides:", ex);
			} finally {
				sceneManager.getLoadingLock().unlock();
				log.trace("loadingLock unlocked - holdCount: {}", sceneManager.getLoadingLock().getHoldCount());
			}
		}));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		modelOverrides.clear();
		modelOverrides.trimToSize();

		detailCullingBlacklist.clear();
		detailCullingBlacklist.trimToSize();
	}

	public void reload() {
		shutDown();
		startUp();
	}

	private void addOverride(@Nullable ModelOverride override, GamevalManager.Handle gamevals) {
		if (override == null || override.seasonalTheme != null && override.seasonalTheme != plugin.configSeasonalTheme)
			return;

		for (int id : override.npcIds)
			addEntry(ModelHash.TYPE_NPC, id, override, gamevals);
		for (int id : override.objectIds)
			addEntry(ModelHash.TYPE_OBJECT, id, override, gamevals);
		for (int id : override.projectileIds)
			addEntry(ModelHash.TYPE_PROJECTILE, id, override, gamevals);
		for (int id : override.graphicsObjectIds)
			addEntry(ModelHash.TYPE_GRAPHICS_OBJECT, id, override, gamevals);
	}

	private void addEntry(int type, int id, ModelOverride entry, GamevalManager.Handle gamevals) {
		int uuid = ModelHash.packUuid(type, id);
		ModelOverride current = modelOverrides.get(uuid);

		if (current != null && !Objects.equals(current.seasonalTheme, entry.seasonalTheme)) {
			// Seasonal theme overrides should take precedence
			if (current.seasonalTheme != null)
				return;
			current = null;
		}

		if (entry.areas.length == 0) {
			// Non-area-restricted override, of which there can only be one per UUID

			// A dummy override is used as the base if only area-specific overrides exist
			boolean isDuplicate = current != null && !current.isDummy;

			if (isDuplicate && entry.isGenerated)
				return; // Manually specified model overrides should take precedence over generated ones

			if (isDuplicate && Props.DEVELOPMENT) {
				String name = null;
				switch (type) {
					case ModelHash.TYPE_NPC:
						name = gamevals.getNpcName(id);
						break;
					case ModelHash.TYPE_OBJECT:
						name = gamevals.getObjectName(id);
						break;
					case ModelHash.TYPE_PROJECTILE:
					case ModelHash.TYPE_GRAPHICS_OBJECT:
						name = gamevals.getSpotanimName(id);
						break;
				}

				// This should ideally not be reached, so print helpful warnings in development mode
				if (entry.hideInAreas.length > 0) {
					log.error(
						"Replacing {} ({}) from '{}' with hideInAreas-override '{}'. This is likely a mistake...",
						name, id, current.description, entry.description
					);
				} else {
					log.error(
						"Replacing {} ({}) from '{}' with '{}'. The first-mentioned override should be removed.",
						name, id, current.description, entry.description
					);
				}
			}

			if (current != null && current.areaOverrides != null) {
				var replacement = entry.copy();
				replacement.areaOverrideBounds = current.areaOverrideBounds;
				replacement.areaOverrides = current.areaOverrides;
				current = replacement;
			} else {
				current = entry;
			}

			modelOverrides.put(uuid, current);
		} else {
			if (current == null)
				current = ModelOverride.NONE;

			if (current.areaOverrides == null) {
				// Create a copy to avoid aliasing area overrides with other UUIDs
				current = current.copy();
				modelOverrides.put(uuid, current);
			}

			int size = entry.areas.length;
			if (current.areaOverrides == null) {
				current.areaOverrides = new ModelOverride[size];
				Arrays.fill(current.areaOverrides, 0, size, entry);
				current.areaOverrideBounds = entry.areas;
			} else {
				int offset = current.areaOverrides.length;
				size += offset;
				current.areaOverrides = Arrays.copyOf(current.areaOverrides, size);
				Arrays.fill(current.areaOverrides, offset, size, entry);
				current.areaOverrideBounds = Arrays.copyOf(current.areaOverrideBounds, size);
				System.arraycopy(entry.areas, 0, current.areaOverrideBounds, offset, entry.areas.length);
			}
		}
	}

	private void addSailingCullingOverrides(GamevalManager.Handle gamevals) {
		try {
			for (Integer row : client.getDBTableRows(DBTableID.SailingBoatSail.ID)) {
				Integer sailId = (Integer) client.getDBTableField(row, DBTableID.SailingBoatSail.COL_LOC, 0)[0];
				if (sailId == null)
					continue;
				ModelOverride sailOverride = new ModelOverride();
				sailOverride.isGenerated = true;
				sailOverride.description = "Disable detail culling of boat sails";
				sailOverride.objectIds = Set.of(sailId);
				sailOverride.disableDetailCulling = true;
				sailOverride.normalize(plugin);
				addOverride(sailOverride, gamevals);
			}
		} catch (Exception ex) {
			log.error("Error while setting up model overrides for disabling detail culling of sails:", ex);
		}
	}

	public boolean allowDetailCulling(int uuid) {
		return !detailCullingBlacklist.contains(uuid);
	}

	@Nonnull
	public ModelOverride getOverride(int uuid, int[] worldPos) {
		var override = modelOverrides.get(ModelHash.getUuidWithoutSubType(uuid));
		if (override == null)
			return ModelOverride.NONE;

		if (override.areaOverrides != null)
			for (int i = 0; i < override.areaOverrides.length; i++)
				if (override.areaOverrideBounds[i].contains(worldPos))
					return override.areaOverrides[i];

		return override;
	}

	/**
	 * Find the object ID a developer tools test object is standing in for, or -1 if the renderable isn't one.
	 * Test objects are matched by identity or, failing that, by their exact local position.
	 */
	public int getTestObjectId(Renderable renderable, int localX, int localZ) {
		if (testObjects.isEmpty() || renderable instanceof Actor || renderable instanceof Projectile || renderable instanceof DynamicObject)
			return -1;
		Integer id = renderable instanceof RuneLiteObject ? testObjects.get(renderable) : null;
		if (id == null)
			id = testObjectPositions.get(packTestObjectPosition(localX, localZ));
		return id == null ? -1 : id;
	}

	public boolean isTestObjectAt(int localX, int localZ) {
		return testObjectPositions.containsKey(packTestObjectPosition(localX, localZ));
	}

	// Test objects must be in place before they're added, and never move afterwards
	public void addTestObject(RuneLiteObject object, int objectId) {
		testObjects.put(object, objectId);
		testObjectPositions.put(packTestObjectPosition(object.getX(), object.getY()), objectId);
	}

	public void removeTestObject(RuneLiteObject object) {
		if (testObjects.remove(object) != null)
			testObjectPositions.remove(packTestObjectPosition(object.getX(), object.getY()));
	}

	public void clearTestObjects() {
		testObjects.clear();
		testObjectPositions.clear();
	}

	private static long packTestObjectPosition(int localX, int localZ) {
		return (long) localX << 32 | localZ & 0xFFFFFFFFL;
	}

	/**
	 * Developer tools: collect the IDs of the given ModelHash type which have model overrides.
	 * Must be called on the client thread, where overrides are loaded.
	 */
	public Set<Integer> getOverriddenIds(int type) {
		HashSet<Integer> ids = new HashSet<>();
		for (var entry : modelOverrides)
			if (ModelHash.getUuidType(entry.getKey()) == type)
				ids.add(ModelHash.getUuidId(entry.getKey()));
		return ids;
	}

	@Nonnull
	public ModelOverride getOverride(TileObject tileObject, int[] worldPos) {
		return getOverride(ModelHash.packUuid(ModelHash.TYPE_OBJECT, tileObject.getId()), worldPos);
	}
}
