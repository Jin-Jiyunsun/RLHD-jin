package rs117.hd.utils;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.environments.Environment;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class DeveloperTools implements KeyListener {
	public static final float[] COLOR_PICKER = new float[4]; // non-linear sRGB & alpha
	public static final float[] COLOR_PICKER_LINEAR = new float[4]; // linear sRGB, non-linear alpha

	// This could be part of the config if we had developer mode config sections
	private static final Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK);
	private static final Keybind KEY_RECORD_TIMINGS_SNAPSHOT = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_SHADOW_MAP_OVERLAY = new Keybind(KeyEvent.VK_F5, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = new Keybind(KeyEvent.VK_F6, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_TILED_LIGHTING_OVERLAY = new Keybind(KeyEvent.VK_F7, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FREEZE_FRAME = new Keybind(KeyEvent.VK_ESCAPE, SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_ORTHOGRAPHIC = new Keybind(KeyEvent.VK_TAB, SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_HIDE_UI = new Keybind(KeyEvent.VK_H, CTRL_DOWN_MASK);
	private static final Keybind KEY_RELOAD_SCENE = new Keybind(KeyEvent.VK_R, CTRL_DOWN_MASK);
	private static final Keybind KEY_COLOR_PICKER = new Keybind(KeyEvent.VK_P, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);

	private static final Keybind KEY_SPOTANIM_PAUSE = new Keybind(KeyEvent.VK_SPACE, CTRL_DOWN_MASK);
	private static final Keybind KEY_SPOTANIM_STEP_BACK = new Keybind(KeyEvent.VK_LEFT, CTRL_DOWN_MASK);
	private static final Keybind KEY_SPOTANIM_STEP_FORWARD = new Keybind(KeyEvent.VK_RIGHT, CTRL_DOWN_MASK);
	private static final Keybind KEY_SPOTANIM_BIG_STEP_BACK = new Keybind(KeyEvent.VK_LEFT, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	private static final Keybind KEY_SPOTANIM_BIG_STEP_FORWARD = new Keybind(KeyEvent.VK_RIGHT, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	private static final int SPOTANIM_BIG_STEP_TICKS = 10;

	// Default flight settings for test projectiles, in the units used by the client. Heights are above the ground.
	// Land on the target tile's ground
	private static final int TEST_PROJECTILE_END_HEIGHT = 0;
	// How much projectiles arc on the way, lower is flatter
	private static final int TEST_PROJECTILE_SLOPE = 10;
	private static final int TEST_PROJECTILE_START_POS = 64;
	private static final int TEST_PROJECTILE_BASE_CYCLES = 20;
	private static final int TEST_PROJECTILE_CYCLES_PER_TILE = 5;
	private static final int TEST_PROJECTILE_LOOP_DELAY = 25;

	// Objects' definitions are in this group of the client's config cache index
	private static final int OBJECT_CONFIG_GROUP = 6;
	// Spread decoding every object's definition over a few ticks, to avoid a hitch
	private static final long TEST_OBJECT_DEFINITION_NANOS_PER_TICK = 2_000_000;
	// Spread recreating a scene capture's objects over ticks too, since a whole scene can hold thousands
	private static final long SCENE_RECREATION_NANOS_PER_TICK = 4_000_000;

	private static final int TEST_SPOTANIM_SLOT = 0;
	// Pause between loops in client cycles (20 ms each), long enough for the previous lights to be marked for removal
	private static final int TEST_SPOTANIM_LOOP_DELAY = 50;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ColorPickerManager colorPickerManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private FrameTimerOverlay frameTimerOverlay;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private LightGizmoOverlay lightGizmoOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	private boolean keyBindingsEnabled;
	private boolean tileInfoOverlayEnabled;
	@Getter
	private boolean frameTimingsOverlayEnabled;
	private boolean shadowMapOverlayEnabled;
	private boolean lightGizmoOverlayEnabled;
	@Getter
	private boolean hideUiEnabled;
	private boolean tiledLightingOverlayEnabled;

	private RuneliteColorPicker colorPicker;

	private int testSpotanimId = -1;
	private boolean testSpotanimLoop;
	private int testSpotanimRestartCycle = -1;
	private List<int[]> testSpotanimFrameTimes;
	private int testSpotanimLastFrame;
	// Frame start times recorded during the first play, used to map playback time to frames while controlling playback
	private List<int[]> testSpotanimTimeline;
	private boolean testSpotanimPaused;
	private float testSpotanimTimeMs;
	private boolean testSpotanimIsPreview;
	private volatile boolean postSpotanimFrameTimings;
	private final AtomicInteger testSpotanimPreviewRequest = new AtomicInteger();

	private NavigationButton objectSpawnerButton;
	@Nullable
	private volatile ObjectSpawnerPanel objectSpawnerPanel;
	private final Map<Integer, SpawnedTestObject> spawnedTestObjects = new LinkedHashMap<>();
	private int sentLightsVersion = -1;
	private int sentOverridesVersion = -1;
	private int nextTestObjectHandle;
	private int testProjectileId = -1;
	private boolean testProjectileLoop;
	private boolean testProjectileIsPreview;
	private boolean testProjectileFired;
	private int testProjectileNextCycle;
	@Nullable
	private Projectile testProjectile;
	private volatile int testProjectileDistance = 10;
	// Height above the ground which test projectiles start from, set from the sidebar
	private volatile int testProjectileStartHeight = 150;
	private final AtomicInteger testProjectilePreviewRequest = new AtomicInteger();
	private RuneLiteObject testObjectPreview;
	private int testObjectPreviewId = -1;
	private final AtomicInteger testObjectPreviewRequest = new AtomicInteger();
	// Every object definition with models, decoded from the client's cache for the sidebar once, then kept
	@Nullable
	private Map<Integer, TestObjectDefinition> testObjectDefinitions;
	@Nullable
	private TestObjectDefinitionLoad testObjectDefinitionLoad;
	private boolean loadingTestObjectNames;
	private final List<Consumer<Map<Integer, TestObjectDefinition>>> testObjectDefinitionCallbacks = new ArrayList<>();
	// Objects recreated from a scene capture, kept out of the spawned objects list since there can be thousands. Most are
	// uploaded like static scenery, while animated ones need RuneLiteObjects.
	private final List<RuneLiteObject> recreatedSceneObjects = new ArrayList<>();
	private final List<TestObjectRendering.StaticObject> recreatedStaticObjects = new ArrayList<>();
	@Nullable
	private SceneRecreation sceneRecreation;
	private boolean onlyDrawingTestObjects;
	// Stop drawing everything but test objects, the same way the Blindfold plugin hides things. It only affects drawing,
	// leaving clickboxes, menus and everything else alone.
	private final RenderCallback onlyDrawTestObjects = new RenderCallback() {
		@Override
		public boolean drawTile(Scene scene, Tile tile) {
			return false;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object) {
			if (!(object instanceof GameObject))
				return false;
			// Keep the local player, but nobody else, like the Blindfold plugin does
			var renderable = ((GameObject) object).getRenderable();
			if (renderable != null && renderable == client.getLocalPlayer())
				return true;
			// Test objects are drawn wrapped in a temporary game object, which the renderer matches by its position
			return modelOverrideManager.getTestObjectId(renderable, object.getX(), object.getY()) != -1;
		}
	};
	// Leaves what the game and 117 HD aren't drawing out of scene captures, checked the same way 117 HD's SceneUploader does
	private final SceneCapture.Visibility sceneVisibility = new SceneCapture.Visibility() {
		private final int[] worldPos = new int[3];

		@Nullable
		@Override
		public Boolean isLevelDrawn(int level, int roofId) {
			return sceneManager.isLevelDrawn(level, roofId);
		}

		@Override
		public boolean isHiddenByArea(Tile tile) {
			var ctx = plugin.getSceneContext();
			if (ctx == null || ctx.currentArea == null)
				return false;
			var sceneLocation = tile.getSceneLocation();
			ctx.sceneToWorld(sceneLocation.getX(), sceneLocation.getY(), tile.getPlane(), worldPos);
			return !ctx.currentArea.containsPoint(worldPos);
		}

		@Override
		public boolean isHiddenByOverride(TileObject object, int type) {
			var ctx = plugin.getSceneContext();
			if (ctx == null)
				return false;
			var location = object.getLocalLocation();
			ctx.localToWorld(location.getX(), location.getY(), object.getPlane(), worldPos);
			return modelOverrideManager.getOverride(ModelHash.packUuid(type, object.getId()), worldPos).hide;
		}
	};

	/**
	 * Object models & animations decoded from the client's cache by {@link ObjectDefinitionParser}, used to spawn test objects
	 */
	public static class TestObjectDefinition {
		@Nullable
		public String name;
		@Nullable
		public String displayName;
		public int[] models;
		// Every model with its placement type, or null types if the object only has centrepiece models, for scene captures
		public int[] allModels;
		@Nullable
		public int[] modelTypes;
		public boolean mirrored;
		// -1 if the model doesn't follow the ground, 0 if it all does, or how much of its lower part does, out of 65536
		public int contouredGround = -1;
		public int animation = -1;
		public int ambient;
		public int contrast;
		public short[] recolorFrom;
		public short[] recolorTo;
		public short[] retextureFrom;
		public short[] retextureTo;
		public int[] scale;
		public int[] offset;
	}

	public void activate() {
		// Listen for commands
		eventBus.register(this);

		// Swing components must only be created and modified on the Swing thread
		// The spawner must stay developer-mode only, since RuneLite's plugin rules reject user-entered IDs and spawned content for normal users
		if (developerMode) {
			SwingUtilities.invokeLater(() -> {
				if (objectSpawnerButton != null)
					return;
				objectSpawnerButton = NavigationButton.builder()
					.tooltip("117 HD Spawner")
					.icon(ObjectSpawnerPanel.createIcon())
					// Sorted by ascending priority, so this goes right below RuneLite's configuration button at priority 0
					.priority(1)
					.panel(objectSpawnerPanel = new ObjectSpawnerPanel(this))
					.build();
				clientToolbar.addNavigation(objectSpawnerButton);
			});
		}

		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;
		keyManager.registerKeyListener(this);

		clientThread.invokeLater(() -> {
			tileInfoOverlay.setActive(tileInfoOverlayEnabled);
			frameTimerOverlay.setActive(frameTimingsOverlayEnabled);
			shadowMapOverlay.setActive(shadowMapOverlayEnabled);
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled);
			tiledLightingOverlay.setActive(tiledLightingOverlayEnabled);
		});
	}

	public void deactivate() {
		eventBus.unregister(this);
		keyManager.unregisterKeyListener(this);
		tileInfoOverlay.setActive(false);
		frameTimerOverlay.setActive(false);
		shadowMapOverlay.setActive(false);
		lightGizmoOverlay.setActive(false);
		tiledLightingOverlay.setActive(false);
		hideUiEnabled = false;
		SwingUtilities.invokeLater(() -> {
			if (objectSpawnerButton != null) {
				clientToolbar.removeNavigation(objectSpawnerButton);
				objectSpawnerButton = null;
				objectSpawnerPanel = null;
				clientThread.invoke(() -> sentLightsVersion = sentOverridesVersion = -1);
			}
		});
		clientThread.invoke(() -> {
			stopTestSpotanim();
			stopTestProjectile();
			clearTestObjects();
			if (onlyDrawingTestObjects) {
				onlyDrawingTestObjects = false;
				lightManager.hideUndrawnObjectLights = false;
				renderCallbackManager.unregister(onlyDrawTestObjects);
			}
		});
	}

	public void updateConfig() {
		frameTimerOverlay.reset();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();
		switch (action) {
			case "tileinfo":
				tileInfoOverlay.setActive(tileInfoOverlayEnabled = !tileInfoOverlayEnabled);
				break;
			case "timers":
			case "timings":
				frameTimerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				break;
			case "snapshot":
				frameTimingsRecorder.recordSnapshot();
				break;
			case "shadowmap":
				shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
				break;
			case "lights":
				lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
				break;
			case "tiledlights":
			case "tiledlighting":
				tiledLightingOverlay.setActive(tiledLightingOverlayEnabled = !tiledLightingOverlayEnabled);
				break;
			case "keybinds":
			case "keybindings":
				keyBindingsEnabled = !keyBindingsEnabled;
				if (keyBindingsEnabled) {
					keyManager.registerKeyListener(this);
				} else {
					keyManager.unregisterKeyListener(this);
				}
				break;
			case "reload":
				plugin.renderer.reloadScene();
				break;
			case "culling":
				plugin.freezeCulling = !plugin.freezeCulling;
				break;
			case "colorpicker":
				toggleColorPicker();
				break;
			case "latlon":
				handleLatLonCommand(args);
				break;
		}

		// Other commands are gated behind RuneLite's --developer-mode
		if (!developerMode)
			return;

		switch (action) {
			case "varbit":
			case "varp":
				handleVarCommand(action, args);
				break;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		// Test objects are positioned in local coordinates, which change meaning when a new scene loads
		if (gameStateChanged.getGameState() != GameState.LOADING)
			return;
		stopTestProjectile();
		sceneRecreation = null;
		if (!modelOverrideManager.testObjects.isEmpty() || !recreatedStaticObjects.isEmpty()) {
			clearTestObjects();
			postMessage("Removed test objects due to scene load");
		}
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		lightManager.addTestObjectLights(modelOverrideManager.testObjects);
		lightManager.addStaticTestObjectLights(recreatedStaticObjects);
		updateTestProjectile();
		updateSpawnerHdConfigIds();
		updateTestObjectDefinitionLoad();
		updateSceneRecreation();

		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		ActorSpotAnim spotanim = findTestSpotanim(player);

		// The client plays the spotanim, except while it's paused
		if (testSpotanimPaused) {
			updateControlledSpotanim(player, spotanim);
			return;
		}

		if (spotanim != null) {
			testSpotanimRestartCycle = -1;
			// Record when each frame starts during the first play, relative to the spotanim's start cycle
			int elapsedCycles = client.getGameCycle() - spotanim.getStartCycle();
			if (testSpotanimFrameTimes != null && elapsedCycles >= 0 && spotanim.getFrame() != testSpotanimLastFrame) {
				testSpotanimLastFrame = spotanim.getFrame();
				testSpotanimFrameTimes.add(new int[] { testSpotanimLastFrame, elapsedCycles * Constants.CLIENT_TICK_LENGTH });
			}
			return;
		}

		if (testSpotanimFrameTimes != null) {
			if (postSpotanimFrameTimings)
				postSpotanimTimeline(testSpotanimFrameTimes);
			testSpotanimTimeline = testSpotanimFrameTimes;
			testSpotanimFrameTimes = null;
		}

		if (!testSpotanimLoop) {
			testSpotanimId = -1;
			return;
		}

		int cycle = client.getGameCycle();
		if (testSpotanimRestartCycle == -1) {
			testSpotanimRestartCycle = cycle + TEST_SPOTANIM_LOOP_DELAY;
		} else if (cycle >= testSpotanimRestartCycle) {
			testSpotanimRestartCycle = -1;
			playTestSpotanim();
		}
	}

	@Nullable
	private ActorSpotAnim findTestSpotanim(Player player) {
		for (var s : player.getSpotAnims())
			if (s.getId() == testSpotanimId)
				return s;
		return null;
	}

	private int getTestSpotanimEndMs() {
		if (testSpotanimTimeline == null || testSpotanimTimeline.isEmpty())
			return Integer.MAX_VALUE;
		return testSpotanimTimeline.get(testSpotanimTimeline.size() - 1)[1];
	}

	private int getTestSpotanimFrameAt(float timeMs) {
		// Before the first play has been recorded, assume one frame per client tick, like Maya animations
		if (testSpotanimTimeline == null || testSpotanimTimeline.isEmpty())
			return (int) (timeMs / Constants.CLIENT_TICK_LENGTH);
		int frame = testSpotanimTimeline.get(0)[0];
		for (int[] entry : testSpotanimTimeline) {
			if (entry[1] > timeMs)
				break;
			frame = entry[0];
		}
		return frame;
	}

	/**
	 * Take over playback of the test spotanim from the client, starting from wherever it currently is.
	 */
	private void beginControllingTestSpotanim() {
		if (testSpotanimPaused)
			return;

		Player player = client.getLocalPlayer();
		ActorSpotAnim spotanim = player == null ? null : findTestSpotanim(player);
		testSpotanimTimeMs = spotanim == null ? 0 : max(0, client.getGameCycle() - spotanim.getStartCycle()) * Constants.CLIENT_TICK_LENGTH;

		// Measurements of the first play would be meaningless once playback is altered
		testSpotanimFrameTimes = null;
	}

	private void updateControlledSpotanim(Player player, @Nullable ActorSpotAnim spotanim) {
		int endMs = getTestSpotanimEndMs();
		if (!testSpotanimPaused) {
			testSpotanimTimeMs += Constants.CLIENT_TICK_LENGTH;
			if (testSpotanimTimeMs >= endMs) {
				if (testSpotanimLoop) {
					testSpotanimTimeMs = 0;
				} else {
					testSpotanimTimeMs = endMs;
					testSpotanimPaused = true;
				}
			}
		}

		if (spotanim == null) {
			// The client finished or replaced it, so bring it back and keep controlling it from the next tick
			playTestSpotanim();
			return;
		}

		// Hold the spotanim on the frame for the current playback time, so the client never advances or finishes it
		spotanim.setFrame(getTestSpotanimFrameAt(testSpotanimTimeMs));
		spotanim.setCycle(0);

		lightManager.spotanimTimeOverrideActor = player;
		lightManager.spotanimTimeOverrideId = testSpotanimId;
		lightManager.spotanimTimeOverride = testSpotanimTimeMs / 1000;
	}

	private void releaseTestSpotanimControl() {
		lightManager.spotanimTimeOverrideActor = null;
		lightManager.spotanimTimeOverrideId = -1;
		lightManager.spotanimTimeOverride = -1;
	}

	private void postTestSpotanimPosition() {
		postMessage(String.format(
			"Spotanim %s at frame %d, %d ms",
			testSpotanimPaused ? "paused" : "playing",
			getTestSpotanimFrameAt(testSpotanimTimeMs),
			Math.round(testSpotanimTimeMs)
		));
	}

	private void toggleTestSpotanimPause() {
		if (testSpotanimId == -1)
			return;
		beginControllingTestSpotanim();
		testSpotanimPaused = !testSpotanimPaused;
		if (!testSpotanimPaused)
			releaseTestSpotanimControl();
		postTestSpotanimPosition();
	}

	private void stepTestSpotanim(int ticks) {
		if (testSpotanimId == -1)
			return;
		beginControllingTestSpotanim();
		testSpotanimPaused = true;
		testSpotanimTimeMs = clamp(testSpotanimTimeMs + ticks * Constants.CLIENT_TICK_LENGTH, 0, getTestSpotanimEndMs());
		postTestSpotanimPosition();
	}

	private void startTestSpotanim(int id, boolean loop, boolean recordTimeline) {
		if (!developerMode)
			return;
		// Start from the beginning, including the lights, even when replacing the same spotanim or its preview
		int previousId = testSpotanimId;
		stopTestSpotanim();
		Player player = client.getLocalPlayer();
		if (previousId != -1 && player != null)
			lightManager.removeSpotanimLightsImmediately(player, previousId);

		testSpotanimId = id;
		testSpotanimLoop = loop;
		// Previews skip recording, so hovering doesn't post a timeline to the chat
		testSpotanimFrameTimes = recordTimeline ? new ArrayList<>() : null;
		testSpotanimLastFrame = -1;
		playTestSpotanim();
	}

	private void postPlayingSpotanim(int id, boolean loop) {
		String name;
		try (var gamevals = gamevalManager.obtainHandle()) {
			name = gamevals.getSpotanimName(id);
		}
		postMessage("Playing spotanim " + (name == null ? id : name + " (" + id + ")") + (loop ? ", looping" : ""));
	}

	/**
	 * Play a spotanim on the local player. Can be called from any thread.
	 */
	public void playSpotanim(int id, boolean loop) {
		clientThread.invoke(() -> {
			if (client.getLocalPlayer() == null) {
				postMessage("No local player to play the spotanim on");
				return;
			}
			startTestSpotanim(id, loop, true);
			postPlayingSpotanim(id, loop);
		});
	}

	// The frame timings are always recorded for pausing & stepping, but only posted to the chat when turned on
	public void setPostSpotanimFrameTimings(boolean post) {
		postSpotanimFrameTimings = post;
	}

	/**
	 * Change whether the playing test spotanim loops, unless it's a hover preview. Can be called from any thread.
	 */
	public void setSpotanimLoop(boolean loop) {
		clientThread.invoke(() -> {
			if (testSpotanimId != -1 && !testSpotanimIsPreview)
				testSpotanimLoop = loop;
		});
	}

	/**
	 * Show a looping preview of the spotanim on the local player, replacing whichever test spotanim is playing.
	 * Can be called from any thread.
	 */
	public void previewSpotanim(int id) {
		int request = testSpotanimPreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (request != testSpotanimPreviewRequest.get() || client.getLocalPlayer() == null)
				return;
			startTestSpotanim(id, true, false);
			testSpotanimIsPreview = true;
		});
	}

	public void clearSpotanimPreview() {
		testSpotanimPreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (testSpotanimIsPreview)
				stopTestSpotanim();
		});
	}

	public void stopSpotanimAndNotify() {
		clientThread.invoke(() -> {
			boolean wasPlaying = testSpotanimId != -1;
			stopTestSpotanim();
			postMessage(wasPlaying ? "Stopped test spotanim" : "No test spotanim is playing");
		});
	}

	/**
	 * Read the spotanim gamevals off the client thread, then pass them to the callback on the Swing thread.
	 */
	public void loadSpotanimNames(Consumer<Map<String, Integer>> callback) {
		executor.execute(() -> {
			Map<String, Integer> spotanims;
			try {
				spotanims = gamevalManager.readSpotanims();
			} catch (IOException | RuntimeException ex) {
				log.error("Failed to read spotanim gamevals", ex);
				spotanims = Map.of();
			}
			Map<String, Integer> result = spotanims;
			SwingUtilities.invokeLater(() -> callback.accept(result));
		});
	}

	private void playTestSpotanim() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		// Replays, such as loops, need fresh lights rather than the previous play's, which may still be fading out
		lightManager.removeSpotanimLightsImmediately(player, testSpotanimId);
		player.createSpotAnim(TEST_SPOTANIM_SLOT, testSpotanimId, 0, 0);

		// Client-side spotanims don't notify plugins, so let the LightManager know to attach lights. This goes to it
		// directly rather than through the event bus, so other plugins never mistake the test spotanim for a real one.
		GraphicChanged changed = new GraphicChanged();
		changed.setActor(player);
		lightManager.onGraphicChanged(changed);
	}

	private void stopTestSpotanim() {
		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player != null && player.hasSpotAnim(testSpotanimId))
			player.removeSpotAnim(TEST_SPOTANIM_SLOT);

		testSpotanimId = -1;
		testSpotanimIsPreview = false;
		testSpotanimLoop = false;
		testSpotanimRestartCycle = -1;
		testSpotanimFrameTimes = null;
		testSpotanimTimeline = null;
		testSpotanimPaused = false;
		releaseTestSpotanimControl();
	}

	/**
	 * Spawn a test object with the given object ID at the local player's location. Can be called from any thread.
	 */
	public void spawnTestObject(int objectId) {
		withTestObjectDefinition(objectId, true, def -> {
			// Keep the preview of the same object instead of spawning a duplicate on top of it
			RuneLiteObject object;
			if (testObjectPreview != null && testObjectPreviewId == objectId) {
				object = testObjectPreview;
				testObjectPreview = null;
				testObjectPreviewId = -1;
			} else {
				object = createTestObject(objectId, def, true);
				if (object == null)
					return;
			}

			String name;
			try (var gamevals = gamevalManager.obtainHandle()) {
				name = gamevals.getObjectName(objectId);
			}
			Player player = client.getLocalPlayer();
			WorldPoint location = player == null ? null : player.getWorldLocation();
			int handle = nextTestObjectHandle++;
			String label = def.name != null ? def.name : def.displayName != null ? def.displayName : "Object " + objectId;
			spawnedTestObjects.put(handle, new SpawnedTestObject(handle, object, objectId, label));
			notifySpawnedTestObjectsChanged();
			postMessage("Spawned test object " + (name == null ? objectId : name + " (" + objectId + ")") +
						(location == null ? "" : " at " + location));
		});
	}

	public void removeTestObject(int handle) {
		clientThread.invoke(() -> {
			var spawned = spawnedTestObjects.remove(handle);
			if (spawned == null)
				return;
			spawned.object.setActive(false);
			modelOverrideManager.removeTestObject(spawned.object);
			notifySpawnedTestObjectsChanged();
		});
	}

	/**
	 * Rotate a spawned test object in 90 degree steps, like real objects. Its lights follow along.
	 */
	public void rotateTestObject(int handle, int quarterTurns) {
		clientThread.invoke(() -> {
			var spawned = spawnedTestObjects.get(handle);
			if (spawned == null)
				return;
			spawned.object.setOrientation(mod(spawned.object.getOrientation() + quarterTurns * 512, 2048));
			notifySpawnedTestObjectsChanged();
		});
	}

	private void notifySpawnedTestObjectsChanged() {
		assert client.isClientThread();
		var panel = objectSpawnerPanel;
		if (panel == null)
			return;
		List<SpawnedObjectInfo> infos = new ArrayList<>();
		for (var spawned : spawnedTestObjects.values()) {
			infos.add(new SpawnedObjectInfo(
				spawned.handle,
				spawned.objectId,
				spawned.label,
				spawned.object.getOrientation() / 512 * 90
			));
		}
		SwingUtilities.invokeLater(() -> panel.setSpawnedObjects(infos));
	}

	/**
	 * Send the sidebar which IDs have lights or model overrides whenever either has been (re)loaded,
	 * since they load in the background at startup and again whenever their JSON files are saved.
	 */
	private void updateSpawnerHdConfigIds() {
		var panel = objectSpawnerPanel;
		if (panel == null)
			return;
		int lightsVersion = lightManager.getConfigVersion();
		int overridesVersion = modelOverrideManager.getConfigVersion();
		if (lightsVersion == sentLightsVersion && overridesVersion == sentOverridesVersion)
			return;
		sentLightsVersion = lightsVersion;
		sentOverridesVersion = overridesVersion;

		var ids = new HdConfigIds(
			lightManager.getObjectLightIds(),
			modelOverrideManager.getOverriddenIds(ModelHash.TYPE_OBJECT),
			lightManager.getGraphicsObjectLightIds(),
			modelOverrideManager.getOverriddenIds(ModelHash.TYPE_GRAPHICS_OBJECT),
			lightManager.getProjectileLightIds(),
			modelOverrideManager.getOverriddenIds(ModelHash.TYPE_PROJECTILE)
		);
		SwingUtilities.invokeLater(() -> panel.setHdConfigIds(ids));
	}

	/**
	 * Fire a projectile from the local player to the tile the configured distance ahead. Can be called from any thread.
	 */
	public void playProjectile(int id, boolean loop) {
		clientThread.invoke(() -> {
			if (client.getLocalPlayer() == null) {
				postMessage("No local player to fire the projectile from");
				return;
			}
			startTestProjectile(id, loop);
			String name;
			try (var gamevals = gamevalManager.obtainHandle()) {
				name = gamevals.getSpotanimName(id);
			}
			postMessage("Firing projectile " + (name == null ? id : name + " (" + id + ")") + " " +
						testProjectileDistance + " tiles ahead" + (loop ? ", looping" : ""));
		});
	}

	/**
	 * Keep firing the projectile as a preview, replacing whichever test projectile is active. Can be called from any thread.
	 */
	public void previewProjectile(int id) {
		int request = testProjectilePreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (request != testProjectilePreviewRequest.get() || client.getLocalPlayer() == null)
				return;
			startTestProjectile(id, true);
			testProjectileIsPreview = true;
		});
	}

	public void clearProjectilePreview() {
		testProjectilePreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (testProjectileIsPreview)
				stopTestProjectile();
		});
	}

	public void stopProjectileAndNotify() {
		clientThread.invoke(() -> {
			boolean wasActive = testProjectileId != -1;
			stopTestProjectile();
			postMessage(wasActive ? "Stopped test projectile" : "No test projectile is active");
		});
	}

	public void setProjectileLoop(boolean loop) {
		clientThread.invoke(() -> {
			if (testProjectileId != -1 && !testProjectileIsPreview)
				testProjectileLoop = loop;
		});
	}

	public void setProjectileStartHeight(int height) {
		testProjectileStartHeight = height;
	}

	public void setProjectileDistance(int tiles) {
		testProjectileDistance = tiles;
	}

	private void startTestProjectile(int id, boolean loop) {
		if (!developerMode)
			return;
		stopTestProjectile();
		testProjectileId = id;
		testProjectileLoop = loop;
		testProjectileFired = false;
		testProjectileNextCycle = client.getGameCycle();
		updateTestProjectile();
	}

	private void stopTestProjectile() {
		// End the flight early, so the client removes it along with its lights
		if (testProjectile != null && testProjectile.getRemainingCycles() > 0)
			testProjectile.setEndCycle(client.getGameCycle());
		testProjectile = null;
		testProjectileId = -1;
		testProjectileIsPreview = false;
		testProjectileLoop = false;
	}

	private void updateTestProjectile() {
		if (testProjectileId == -1)
			return;

		// Wait for the current flight to land
		int cycle = client.getGameCycle();
		if (testProjectile != null) {
			if (testProjectile.getRemainingCycles() > 0)
				return;
			testProjectile = null;
			testProjectileNextCycle = cycle + TEST_PROJECTILE_LOOP_DELAY;
		}

		if (testProjectileFired && !testProjectileLoop) {
			stopTestProjectile();
			return;
		}
		if (cycle >= testProjectileNextCycle)
			fireTestProjectile();
	}

	private void fireTestProjectile() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		// Aim at the tile the set distance ahead, in the direction the player is facing (0 = south, 512 = west)
		WorldPoint source = player.getWorldLocation();
		double angle = player.getOrientation() * JAU_TO_RAD;
		int distance = testProjectileDistance;
		WorldPoint target = source
			.dx((int) Math.round(-Math.sin(angle) * distance))
			.dy((int) Math.round(-Math.cos(angle) * distance));

		// Purely visual: a client-side projectile isn't sent to the server
		int cycle = client.getGameCycle();
		Projectile projectile = client.createProjectile(
			testProjectileId,
			source,
			testProjectileStartHeight,
			player,
			target,
			TEST_PROJECTILE_END_HEIGHT,
			null,
			cycle,
			cycle + TEST_PROJECTILE_BASE_CYCLES + distance * TEST_PROJECTILE_CYCLES_PER_TILE,
			TEST_PROJECTILE_SLOPE,
			TEST_PROJECTILE_START_POS
		);
		client.getProjectiles().addLast(projectile);
		testProjectile = projectile;
		testProjectileFired = true;

		// Client-side projectiles may not notify plugins, so let the LightManager know to attach lights. This goes to it
		// directly rather than through the event bus, so other plugins never mistake the test projectile for a real one.
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(projectile);
		moved.setPosition(LocalPoint.fromWorld(client.getTopLevelWorldView(), target));
		moved.setZ(0);
		lightManager.onProjectileMoved(moved);
	}

	/**
	 * Show a temporary preview of the object at the local player's location, replacing any previous preview.
	 * Can be called from any thread.
	 */
	public void previewTestObject(int objectId) {
		int request = testObjectPreviewRequest.incrementAndGet();
		withTestObjectDefinition(objectId, false, def -> {
			// Skip previews which have already been replaced or cleared while loading
			if (request != testObjectPreviewRequest.get())
				return;
			removeTestObjectPreview();
			testObjectPreview = createTestObject(objectId, def, false);
			testObjectPreviewId = testObjectPreview == null ? -1 : objectId;
		});
	}

	public void clearTestObjectPreview() {
		testObjectPreviewRequest.incrementAndGet();
		clientThread.invoke(this::removeTestObjectPreview);
	}

	private void removeTestObjectPreview() {
		if (testObjectPreview == null)
			return;
		testObjectPreview.setActive(false);
		modelOverrideManager.removeTestObject(testObjectPreview);
		testObjectPreview = null;
		testObjectPreviewId = -1;
	}

	private void withTestObjectDefinition(int objectId, boolean reportErrors, Consumer<TestObjectDefinition> callback) {
		clientThread.invoke(() -> {
			var def = readTestObjectDefinition(objectId);
			if (def != null) {
				callback.accept(def);
			} else if (reportErrors) {
				postMessage("Object " + objectId + " has no models, or couldn't be read from the client");
			}
		});
	}

	/**
	 * Decode one object's definition from the cache data the client has loaded. Must be called on the client thread.
	 */
	@Nullable
	private TestObjectDefinition readTestObjectDefinition(int objectId) {
		assert client.isClientThread();
		var cached = testObjectDefinitions == null ? null : testObjectDefinitions.get(objectId);
		if (cached != null)
			return cached;
		byte[] data = client.getIndexConfig().loadData(OBJECT_CONFIG_GROUP, objectId);
		return data == null ? null : ObjectDefinitionParser.parse(objectId, data);
	}

	/**
	 * Decode every object with models for the sidebar, then pass them to the callback on the Swing thread.
	 * The callback receives null if the client's cache data couldn't be read. Can be called from any thread.
	 */
	public void loadTestObjectDefinitions(Consumer<Map<Integer, TestObjectDefinition>> callback) {
		clientThread.invoke(() -> {
			var definitions = testObjectDefinitions;
			if (definitions != null) {
				SwingUtilities.invokeLater(() -> callback.accept(definitions));
				return;
			}
			testObjectDefinitionCallbacks.add(callback);
			if (testObjectDefinitionLoad != null || loadingTestObjectNames)
				return;

			// Read the gameval names off the client thread, then decode the definitions a batch per tick
			loadingTestObjectNames = true;
			executor.execute(() -> {
				Map<Integer, String> names = new HashMap<>();
				try {
					gamevalManager.readObjects().forEach((name, id) -> names.putIfAbsent(id, name));
				} catch (IOException | RuntimeException ex) {
					log.error("Failed to read object gamevals", ex);
				}
				clientThread.invoke(() -> {
					loadingTestObjectNames = false;
					int[] ids = client.getIndexConfig().getFileIds(OBJECT_CONFIG_GROUP);
					if (ids == null) {
						finishTestObjectDefinitionLoad(null);
						return;
					}
					testObjectDefinitionLoad = new TestObjectDefinitionLoad(ids, names);
				});
			});
		});
	}

	private void updateTestObjectDefinitionLoad() {
		var load = testObjectDefinitionLoad;
		if (load == null)
			return;

		long deadline = System.nanoTime() + TEST_OBJECT_DEFINITION_NANOS_PER_TICK;
		for (int decoded = 0; load.next < load.ids.length; load.next++, decoded++) {
			// Checking the time every so often is enough, and cheaper than every definition
			if (decoded % 100 == 0 && decoded > 0 && System.nanoTime() > deadline)
				break;
			int id = load.ids[load.next];
			byte[] data = client.getIndexConfig().loadData(OBJECT_CONFIG_GROUP, id);
			var def = data == null ? null : ObjectDefinitionParser.parse(id, data);
			if (def != null) {
				def.name = load.names.get(id);
				load.definitions.put(id, def);
			}
		}
		if (load.next == load.ids.length) {
			testObjectDefinitionLoad = null;
			finishTestObjectDefinitionLoad(Collections.unmodifiableMap(load.definitions));
		}
	}

	private void finishTestObjectDefinitionLoad(@Nullable Map<Integer, TestObjectDefinition> definitions) {
		testObjectDefinitions = definitions;
		var callbacks = new ArrayList<>(testObjectDefinitionCallbacks);
		testObjectDefinitionCallbacks.clear();
		SwingUtilities.invokeLater(() -> callbacks.forEach(callback -> callback.accept(definitions)));
	}

	@RequiredArgsConstructor
	private static class TestObjectDefinitionLoad {
		final int[] ids;
		final Map<Integer, String> names;
		final Map<Integer, TestObjectDefinition> definitions = new HashMap<>();
		int next;
	}

	public void clearTestObjectsAndNotify() {
		clientThread.invoke(() -> {
			int count = modelOverrideManager.testObjects.size();
			clearTestObjects();
			postMessage("Removed " + count + " test object" + (count == 1 ? "" : "s"));
		});
	}

	/**
	 * Capture every object in an area around the player, relative to the player, and save it to a file.
	 * Can be called from any thread.
	 */
	public void captureScene(String name, SceneCapture.Area area) {
		clientThread.invoke(() -> {
			if (!developerMode)
				return;
			String captureName = name.isEmpty() ? SceneCapture.getDefaultName(getPlayerEnvironmentName()) : name;
			var capture = SceneCapture.capture(client, captureName, area, sceneVisibility);
			if (capture == null) {
				postMessage("No local player to capture the scene around");
				return;
			}
			var environment = environmentManager.getTargetEnvironment();
			if (environment != Environment.NONE)
				capture.environment = environment.toString();
			executor.execute(() -> {
				try {
					String fileName = SceneCapture.save(gson, capture);
					postMessage("Captured " + capture.objects.size() + " objects to " + SceneCapture.CAPTURES_PATH.resolve(fileName));
					var panel = objectSpawnerPanel;
					if (panel != null)
						SwingUtilities.invokeLater(() -> panel.setSelectedSceneCapture(fileName));
				} catch (IOException ex) {
					log.error("Failed to save scene capture", ex);
					postMessage("Failed to save the scene capture: " + ex.getMessage());
				}
			});
		});
	}

	/**
	 * The name of the 117 HD environment the player is in, like the tile info overlay shows, or null if there's none
	 */
	@Nullable
	private String getPlayerEnvironmentName() {
		var ctx = plugin.getSceneContext();
		Player player = client.getLocalPlayer();
		if (ctx == null || player == null)
			return null;
		var location = player.getLocalLocation();
		int[] worldPos = ctx.localToWorld(location.getX(), location.getY(), client.getTopLevelWorldView().getPlane());
		for (var environment : ctx.environments)
			if (environment.area.containsPoint(worldPos))
				return environment.toString();
		return null;
	}

	/**
	 * Pass the file names of the saved scene captures to the callback on the Swing thread
	 */
	public void listSceneCaptures(Consumer<List<String>> callback) {
		if (!developerMode)
			return;
		executor.execute(() -> {
			List<String> fileNames;
			try {
				fileNames = SceneCapture.list();
			} catch (IOException ex) {
				log.error("Failed to list scene captures", ex);
				fileNames = List.of();
			}
			var result = fileNames;
			SwingUtilities.invokeLater(() -> callback.accept(result));
		});
	}

	/**
	 * Spawn a saved scene capture's objects around the player, replacing any previously recreated capture
	 */
	public void recreateSceneCapture(String fileName) {
		if (!developerMode)
			return;
		executor.execute(() -> {
			SceneCapture.Capture capture;
			try {
				capture = SceneCapture.load(gson, fileName);
			} catch (IOException | RuntimeException ex) {
				log.error("Failed to load scene capture {}", fileName, ex);
				postMessage("Failed to load the scene capture: " + ex.getMessage());
				return;
			}
			clientThread.invoke(() -> startSceneRecreation(capture));
		});
	}

	private void startSceneRecreation(SceneCapture.Capture capture) {
		assert client.isClientThread();
		if (!developerMode)
			return;
		Player player = client.getLocalPlayer();
		var worldView = client.getTopLevelWorldView();
		if (player == null || worldView == null) {
			postMessage("No local player to recreate the scene around");
			return;
		}
		removeRecreatedScene();
		// Light the recreated scene like where it was captured, until it's removed
		environmentManager.overrideEnvironmentName = capture.environment;

		// Work out where everything goes now, so moving while it spawns doesn't move the rest
		LocalPoint origin = player.getLocalLocation();
		int originPlane = worldView.getPlane();
		int groundHeight = Perspective.getTileHeight(client, origin, originPlane);
		var recreation = new SceneRecreation(capture.name == null ? "the scene capture" : capture.name);
		recreation.terrain = capture.terrain;
		recreation.capturedPlane = capture.capturedAt == null ? 0 : capture.capturedAt.getPlane();
		// Line objects up with the region grid like where they were captured, or around the player for older captures
		var scene = worldView.getScene();
		int baseX = capture.regionAligned ? SceneCapture.getRegionCorner(scene.getBaseX(), origin.getX()) : origin.getX();
		int baseY = capture.regionAligned ? SceneCapture.getRegionCorner(scene.getBaseY(), origin.getY()) : origin.getY();
		for (var object : capture.objects) {
			if (object.kind == null) {
				recreation.skippedUnreadable++;
				continue;
			}
			for (var part : SceneCapture.getParts(object)) {
				int x = baseX + object.x + (part.offset ? object.xOffset : 0);
				int y = baseY + object.y + (part.offset ? object.yOffset : 0);
				// Static objects can go in the extended area 117 HD loads around the scene, unlike RuneLiteObjects
				var location = new LocalPoint(x, y, worldView);
				if (getSceneTile(location, originPlane) == null && !location.isInScene()) {
					recreation.skippedOutsideScene++;
					continue;
				}
				int orientation = mod((part.rotation & 3) * 512 + object.modelOrientation, 2048);
				// Everything goes on the player's plane, since the captured heights already keep objects on other planes
				// where they were, and the player's plane isn't hidden like the planes above it can be
				recreation.pending.add(new PendingScenePart(object, part, location, originPlane, orientation, groundHeight + object.height));
			}
		}
		// Big captures take a while, so start with what's around the player rather than the scene's edge
		recreation.pending.sort(Comparator.comparingLong(pending -> {
			long dx = pending.location.getX() - origin.getX();
			long dy = pending.location.getY() - origin.getY();
			return dx * dx + dy * dy;
		}));
		sceneRecreation = recreation;
		postMessage("Recreating " + recreation.pending.size() + " objects from " + recreation.name + "...");
	}

	private void updateSceneRecreation() {
		var recreation = sceneRecreation;
		if (recreation == null)
			return;

		long deadline = System.nanoTime() + SCENE_RECREATION_NANOS_PER_TICK;
		while (recreation.next < recreation.pending.size() && System.nanoTime() < deadline) {
			var pending = recreation.pending.get(recreation.next++);
			var captured = pending.object;
			var def = readTestObjectDefinition(captured.id);
			if (def == null) {
				recreation.skippedUnreadable++;
				continue;
			}
			int[] modelIds = SceneCapture.getModelIds(def, pending.part);
			if (modelIds == null) {
				recreation.skippedNoModel++;
				continue;
			}
			int quarterTurns = pending.part.rotation & 3;
			Consumer<ModelData> contour = def.contouredGround < 0 || recreation.terrain == null ? null : rotated -> SceneCapture.contour(
				rotated,
				def.contouredGround,
				recreation.terrain,
				recreation.capturedPlane + captured.plane,
				captured.x + (pending.part.offset ? captured.xOffset : 0),
				captured.y + (pending.part.offset ? captured.yOffset : 0),
				captured.height
			);

			// Upload objects like static scenery where possible, so they're not redrawn every frame. Animations need
			// RuneLiteObjects though, as do objects without a tile to be uploaded with.
			Tile tile = def.animation == -1 ? getSceneTile(pending.location, pending.plane) : null;
			if (tile != null) {
				Model model = buildTestObjectModel(
					def,
					modelIds,
					def.mirrored != pending.part.isMirrored(),
					pending.part.isDiagonal(),
					// Kept rotated like the client's, since static scenery isn't rotated again when drawn
					modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, 0, true),
					false
				);
				if (model == null) {
					recreation.skippedNoModel++;
					continue;
				}
				var object = new TestObjectRendering.StaticObject(
					captured.id,
					model,
					HDUtils.getModelPreOrientation(captured.shape | captured.rotation << 6),
					captured.modelOrientation,
					mod(1024 + pending.orientation, 2048),
					pending.plane,
					pending.location.getX(),
					pending.location.getY(),
					pending.height
				);
				addStaticSceneObject(tile, object);
				recreation.addedStaticObjects = true;
				continue;
			}

			if (!pending.location.isInScene()) {
				recreation.skippedOutsideScene++;
				continue;
			}

			// The renderer recognises test objects by their exact position, so move each off the spot where the real object
			// it stands in for would be, and off other test objects, such as parts of the same wall
			var location = pending.location;
			int nudge = 0;
			do {
				location = location.dx(1);
				nudge++;
			} while (isTestObjectAt(location));

			int nudgeBack = nudge;
			Model model = buildTestObjectModel(
				def,
				modelIds,
				def.mirrored != pending.part.isMirrored(),
				pending.part.isDiagonal(),
				// Moving the model back by the nudge lets pieces nudged by different amounts still meet without cracks
				modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, nudgeBack, false),
				false
			);
			if (model == null) {
				recreation.skippedNoModel++;
				continue;
			}
			var object = placeTestObject(captured.id, def, model, location, pending.plane, pending.orientation, pending.height);
			if (object != null)
				recreatedSceneObjects.add(object);
		}

		if (recreation.next == recreation.pending.size()) {
			sceneRecreation = null;
			// Static objects only show once their zones are uploaded again, which 117 HD can do without a game reload
			if (recreation.addedStaticObjects)
				plugin.renderer.reloadScene();
			String message = "Recreated " + (recreatedSceneObjects.size() + recreatedStaticObjects.size()) + " objects from " +
							 recreation.name;
			if (recreation.skippedOutsideScene > 0)
				message += ". Skipped " + recreation.skippedOutsideScene + " outside the loaded scene";
			if (recreation.skippedUnreadable > 0)
				message += ". Skipped " + recreation.skippedUnreadable + " whose definition couldn't be read";
			if (recreation.skippedNoModel > 0)
				message += ". Skipped " + recreation.skippedNoModel + " without a model for their shape";
			postMessage(message);
		}
	}

	/**
	 * The tile at a local point in the top-level scene, including the extended area around it, or null if there's none
	 */
	@Nullable
	private Tile getSceneTile(LocalPoint location, int plane) {
		var tiles = client.getTopLevelWorldView().getScene().getExtendedTiles();
		int tileExX = (location.getX() >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET;
		int tileExY = (location.getY() >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET;
		if (plane < 0 || plane >= tiles.length || tileExX < 0 || tileExY < 0 ||
			tileExX >= tiles[plane].length || tileExY >= tiles[plane][tileExX].length)
			return null;
		return tiles[plane][tileExX][tileExY];
	}

	/**
	 * Store a static recreated object to be uploaded with its tile, replacing the tile's list for zones uploading meanwhile
	 */
	private void addStaticSceneObject(Tile tile, TestObjectRendering.StaticObject object) {
		var sceneLocation = tile.getSceneLocation();
		int key = ModelOverrideManager.packStaticTestObjectTile(
			tile.getRenderLevel(),
			sceneLocation.getX() + HDUtils.EXTENDED_SCENE_OFFSET,
			sceneLocation.getY() + HDUtils.EXTENDED_SCENE_OFFSET
		);
		modelOverrideManager.staticTestObjects.compute(key, (k, objects) -> {
			var copy = objects == null ? new ArrayList<TestObjectRendering.StaticObject>() : new ArrayList<>(objects);
			copy.add(object);
			return copy;
		});
		recreatedStaticObjects.add(object);
	}

	private void removeStaticSceneObjects() {
		if (recreatedStaticObjects.isEmpty())
			return;
		for (var object : recreatedStaticObjects)
			object.removed = true;
		recreatedStaticObjects.clear();
		modelOverrideManager.staticTestObjects.clear();
		plugin.renderer.reloadScene();
	}

	/**
	 * Hide the tiles, objects, NPCs and other players, leaving only spawned & recreated test objects and the local player drawn
	 */
	public void setOnlyDrawTestObjects(boolean only) {
		clientThread.invoke(() -> {
			if (!developerMode || only == onlyDrawingTestObjects)
				return;
			onlyDrawingTestObjects = only;
			lightManager.hideUndrawnObjectLights = only;
			if (only) {
				renderCallbackManager.register(onlyDrawTestObjects);
			} else {
				renderCallbackManager.unregister(onlyDrawTestObjects);
			}
			// Static tiles & objects are only checked when the scene is uploaded, which 117 HD can redo without a game
			// reload, unlike Blindfold, so test objects aren't removed
			plugin.renderer.reloadScene();
		});
	}

	public void removeRecreatedSceneAndNotify() {
		clientThread.invoke(() -> {
			int count = recreatedSceneObjects.size() + recreatedStaticObjects.size();
			removeRecreatedScene();
			postMessage("Removed " + count + " recreated object" + (count == 1 ? "" : "s"));
		});
	}

	private void removeRecreatedScene() {
		sceneRecreation = null;
		for (var object : recreatedSceneObjects) {
			object.setActive(false);
			modelOverrideManager.removeTestObject(object);
		}
		recreatedSceneObjects.clear();
		removeStaticSceneObjects();
		environmentManager.overrideEnvironmentName = null;
	}

	@RequiredArgsConstructor
	private static class SceneRecreation {
		final String name;
		final List<PendingScenePart> pending = new ArrayList<>();
		int next;
		boolean addedStaticObjects;
		int skippedOutsideScene;
		int skippedUnreadable;
		int skippedNoModel;
		@Nullable
		SceneCapture.Terrain terrain;
		int capturedPlane;
	}

	@RequiredArgsConstructor
	private static class PendingScenePart {
		final SceneCapture.CapturedObject object;
		final SceneCapture.Part part;
		final LocalPoint location;
		final int plane;
		final int orientation;
		final int height;
	}

	@Nullable
	private RuneLiteObject createTestObject(int objectId, TestObjectDefinition def, boolean reportErrors) {
		assert client.isClientThread();
		if (!developerMode)
			return null;
		Player player = client.getLocalPlayer();
		if (player == null) {
			if (reportErrors)
				postMessage("No local player to spawn the object at");
			return null;
		}

		Model model = buildTestObjectModel(def, def.models, false, false, null, reportErrors);
		if (model == null)
			return null;

		var worldView = client.getTopLevelWorldView();
		LocalPoint location = LocalPoint.fromWorld(worldView, player.getWorldLocation());
		if (location == null) {
			if (reportErrors)
				postMessage("The player isn't in the loaded scene");
			return null;
		}
		// The renderer recognises test objects by their exact position, so give each its own, off the tile centre where
		// game graphics usually sit. Each step is 1/128 of a tile, far too small to see.
		do {
			location = location.dx(1);
		} while (isTestObjectAt(location));

		return placeTestObject(objectId, def, model, location, worldView.getPlane(), 0, null);
	}

	/**
	 * Build an object's model from some of its model IDs, applying the definition's model changes like the client does.
	 * Mirroring, the 45 degree turn of diagonal wall decorations and finishing the model in place of scaling & offsetting
	 * it are for objects recreated from a scene capture, which the client builds from more than the centrepiece model.
	 */
	@Nullable
	private Model buildTestObjectModel(
		TestObjectDefinition def,
		int[] modelIds,
		boolean mirror,
		boolean diagonal,
		@Nullable UnaryOperator<ModelData> finish,
		boolean reportErrors
	) {
		ModelData[] models = new ModelData[modelIds.length];
		for (int i = 0; i < models.length; i++) {
			models[i] = client.loadModelData(modelIds[i]);
			if (models[i] == null) {
				if (reportErrors)
					postMessage("Unable to load model " + modelIds[i]);
				return null;
			}
		}

		// Apply the object definition's model changes the same way the client does
		ModelData modelData = models.length == 1 && !mirror && !diagonal ? models[0] : client.mergeModels(models);
		if (mirror)
			SceneCapture.mirror(modelData);
		if (diagonal)
			SceneCapture.rotateDiagonal(modelData);
		if (def.recolorFrom != null && def.recolorTo != null) {
			modelData = modelData.cloneColors();
			for (int i = 0; i < min(def.recolorFrom.length, def.recolorTo.length); i++)
				modelData.recolor(def.recolorFrom[i], def.recolorTo[i]);
		}
		if (def.retextureFrom != null && def.retextureTo != null) {
			modelData = modelData.cloneTextures();
			for (int i = 0; i < min(def.retextureFrom.length, def.retextureTo.length); i++)
				modelData.retexture(def.retextureFrom[i], def.retextureTo[i]);
		}
		if (finish != null) {
			// Recreated objects are scaled, offset and more along the world's axes after rotating, like the client does
			modelData = finish.apply(modelData.cloneVertices());
		} else if (def.scale != null || def.offset != null) {
			modelData = modelData.cloneVertices();
			if (def.scale != null)
				modelData.scale(def.scale[0], def.scale[1], def.scale[2]);
			if (def.offset != null)
				modelData.translate(def.offset[0], def.offset[1], def.offset[2]);
		}

		return modelData.light(
			ModelData.DEFAULT_AMBIENT + def.ambient,
			ModelData.DEFAULT_CONTRAST + def.contrast,
			ModelData.DEFAULT_X,
			ModelData.DEFAULT_Y,
			ModelData.DEFAULT_Z
		);
	}

	/**
	 * Spawn a test object with a built model. Every spawned, previewed or recreated test object goes through here.
	 * Its location must be free of other test objects, and a null height keeps it on the ground.
	 */
	@Nullable
	private RuneLiteObject placeTestObject(
		int objectId,
		TestObjectDefinition def,
		Model model,
		LocalPoint location,
		int plane,
		int orientation,
		@Nullable Integer height
	) {
		if (!developerMode)
			return null;
		// Purely visual: a RuneLiteObject has no clickbox or menu entries, and nothing is sent to the server
		RuneLiteObject object = client.createRuneLiteObject();
		object.setModel(model);
		if (def.animation != -1)
			object.setAnimationController(new AnimationController(client, def.animation));
		object.setShouldLoop(true);
		object.setDrawFrontTilesFirst(false);
		object.setLocation(location, plane);
		object.setOrientation(orientation);
		if (height != null)
			object.setZ(height);
		object.setActive(true);
		modelOverrideManager.addTestObject(object, objectId);
		return object;
	}

	private boolean isTestObjectAt(LocalPoint location) {
		return modelOverrideManager.isTestObjectAt(location.getX(), location.getY());
	}

	private void clearTestObjects() {
		for (var object : modelOverrideManager.testObjects.keySet())
			object.setActive(false);
		modelOverrideManager.clearTestObjects();
		testObjectPreview = null;
		testObjectPreviewId = -1;
		spawnedTestObjects.clear();
		removeRecreatedScene();
		notifySpawnedTestObjectsChanged();
	}

	@RequiredArgsConstructor
	private static class SpawnedTestObject {
		final int handle;
		final RuneLiteObject object;
		final int objectId;
		final String label;
	}

	/**
	 * A snapshot of a spawned test object for the sidebar, safe to use on the Swing thread
	 */
	@RequiredArgsConstructor
	public static class SpawnedObjectInfo {
		public final int handle;
		public final int objectId;
		public final String label;
		public final int rotationDegrees;
	}

	/**
	 * IDs with lights.json lights or model_overrides.json overrides, per kind of entry in the sidebar
	 */
	@RequiredArgsConstructor
	public static class HdConfigIds {
		public final Set<Integer> objectLights;
		public final Set<Integer> objectOverrides;
		public final Set<Integer> spotanimLights;
		public final Set<Integer> spotanimOverrides;
		public final Set<Integer> projectileLights;
		public final Set<Integer> projectileOverrides;
	}

	private void postSpotanimTimeline(List<int[]> frameTimes) {
		if (frameTimes.isEmpty())
			return;

		int[] last = frameTimes.get(frameTimes.size() - 1);

		// Maya animations advance one frame per client tick, so a full timeline would just be frame * tick length
		boolean onePerTick = frameTimes.stream().allMatch(e -> e[1] == e[0] * Constants.CLIENT_TICK_LENGTH);
		if (onePerTick) {
			postMessage(String.format(
				"Spotanim runs one frame per client tick: frame N starts at N * %d ms. Frames 0-%d, ending around %d ms",
				Constants.CLIENT_TICK_LENGTH, last[0], last[1] + Constants.CLIENT_TICK_LENGTH
			));
			return;
		}

		postMessage("Spotanim frame timeline (frame @ ms since start), " + frameTimes.size() + " frames over " + last[1] + "+ ms:");
		var sb = new StringBuilder();
		for (int i = 0; i < frameTimes.size(); i++) {
			int[] entry = frameTimes.get(i);
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(entry[0]).append(" @ ").append(entry[1]);
			if (i % 10 == 9 || i == frameTimes.size() - 1) {
				postMessage(sb.toString());
				log.debug("Spotanim timeline: {}", sb);
				sb.setLength(0);
			}
		}
	}

	private void handleLatLonCommand(String[] args) {
		if (args.length == 1) {
			String current = config.preciseLatLon();
			if (current.isEmpty())
				current = config.latitudeDegrees() + "," + config.longitudeDegrees() + " (from config panel)";
			postMessage("Current latitude & longitude: " + current);
		} else if (
			args.length == 2 &&
			(args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("clear"))
		) {
			config.setPreciseLatLon("");
			postMessage("Reset latitude & longitude coordinates");
		} else if (args.length == 3) {
			float[] latLon = HDUtils.parseLatLon(args[1] + "," + args[2]);
			if (latLon == null) {
				postMessage("Latitude & longitude must be decimal numbers, within ±90 and ±180 degrees respectively");
				return;
			}

			config.setPreciseLatLon(latLon[0] + "," + latLon[1]);
			postMessage(
				"Changed latitude & longitude to: " + latLon[0] + "," + latLon[1] + ". Note, this will not show up in the config panel.");
		} else {
			postMessage("Usage: ::117hd latlon <lt>latitude<gt> <lt>longitude<gt> / reset");
		}
	}

	private void handleVarCommand(String type, String[] args) {
		assert client.isClientThread();
		String usage = "Usage: ::117hd " + type + " <name|id> [value]";
		if (args.length != 2 && args.length != 3) {
			postMessage(usage);
			return;
		}

		String nameOrId = args[1].toUpperCase();
		Integer id;
		try {
			id = Integer.parseInt(nameOrId);
		} catch (NumberFormatException ignored) {
			try (var gamevals = gamevalManager.obtainHandle()) {
				switch (type) {
					case "varbit":
						id = gamevals.getVarbits().get(nameOrId);
						break;
					case "varp":
						id = gamevals.getVarps().get(nameOrId);
						break;
					default:
						throw new IllegalStateException("Unhandled variable kind: " + type);
				}
			}
		}
		if (id == null) {
			postMessage("Unknown " + type + ": " + nameOrId);
			return;
		}

		int[] varps = client.getVarps();
		if (args.length == 2) {
			int value;
			switch (type) {
				case "varbit":
					value = client.getVarbitValue(varps, id);
					break;
				case "varp":
					value = varps[id];
					break;
				default:
					throw new IllegalStateException("Unhandled variable kind: " + type);
			}
			postMessage(type + " " + nameOrId + " (" + id + ") = " + value);
			return;
		}

		final int value;
		try {
			value = Integer.parseInt(args[2]);
		} catch (NumberFormatException e) {
			postMessage("Invalid value: " + args[2]);
			return;
		}

		VarbitChanged changed = new VarbitChanged();
		changed.setValue(value);
		switch (type) {
			case "varbit":
				client.setVarbitValue(varps, id, value);
				client.queueChangedVarp(client.getVarbit(id).getIndex());
				changed.setVarbitId(id);
				break;
			case "varp":
				varps[id] = value;
				client.queueChangedVarp(id);
				changed.setVarpId(id);
				break;
			default:
				throw new IllegalStateException("Unhandled variable kind: " + type);
		}
		eventBus.post(changed);
		postMessage("Set " + type + " " + nameOrId + " (" + id + ") = " + value);
	}

	private void toggleColorPicker() {
		plugin.uboGlobal.colorPicker.set(1, 1, 1, 1);
		if (colorPicker == null) {
			colorPicker = colorPickerManager.create(
				client,
				Color.WHITE,
				"Shader Color Picker",
				false
			);
			colorPicker.setLocationRelativeTo(client.getCanvas());
			colorPicker.setOnColorChange(c -> clientThread.invoke(() -> {
				float[] srgb = ColorUtils.srgb(c);
				float alpha = c.getAlpha() / 255.f;
				copyTo(COLOR_PICKER, srgb);
				copyTo(COLOR_PICKER_LINEAR, ColorUtils.srgbToLinear(srgb));
				COLOR_PICKER_LINEAR[3] = COLOR_PICKER[3] = alpha;
				plugin.uboGlobal.colorPicker.set(COLOR_PICKER_LINEAR);
			}));
			colorPicker.setOnClose(e -> colorPicker = null);
			colorPicker.setVisible(true);
		} else {
			colorPicker.setVisible(false);
			colorPicker = null;
		}
	}

	void postMessage(String message) {
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			"<col=006600>[117 HD] " + message + "</col>",
			"117 HD"
		));
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (KEY_TOGGLE_TILE_INFO.matches(e)) {
			tileInfoOverlay.setActive(tileInfoOverlayEnabled = !tileInfoOverlayEnabled);
		} else if (KEY_TOGGLE_FRAME_TIMINGS.matches(e)) {
			frameTimerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
		} else if (KEY_RECORD_TIMINGS_SNAPSHOT.matches(e)) {
			frameTimingsRecorder.recordSnapshot();
		} else if (KEY_TOGGLE_SHADOW_MAP_OVERLAY.matches(e)) {
			shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
		} else if (KEY_TOGGLE_LIGHT_GIZMO_OVERLAY.matches(e)) {
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
		} else if (KEY_TOGGLE_TILED_LIGHTING_OVERLAY.matches(e)) {
			tiledLightingOverlay.setActive(tiledLightingOverlayEnabled = !tiledLightingOverlayEnabled);
		} else if (KEY_TOGGLE_FREEZE_FRAME.matches(e)) {
			plugin.toggleFreezeFrame();
		} else if (KEY_TOGGLE_ORTHOGRAPHIC.matches(e)) {
			plugin.orthographicProjection = !plugin.orthographicProjection;
		} else if (KEY_TOGGLE_HIDE_UI.matches(e)) {
			hideUiEnabled = !hideUiEnabled;
		} else if (KEY_RELOAD_SCENE.matches(e)) {
			plugin.renderer.reloadScene();
		} else if (KEY_COLOR_PICKER.matches(e)) {
			toggleColorPicker();
		} else if (testSpotanimId != -1 && KEY_SPOTANIM_PAUSE.matches(e)) {
			clientThread.invoke(this::toggleTestSpotanimPause);
		} else if (testSpotanimId != -1 && KEY_SPOTANIM_STEP_BACK.matches(e)) {
			clientThread.invoke(() -> stepTestSpotanim(-1));
		} else if (testSpotanimId != -1 && KEY_SPOTANIM_STEP_FORWARD.matches(e)) {
			clientThread.invoke(() -> stepTestSpotanim(1));
		} else if (testSpotanimId != -1 && KEY_SPOTANIM_BIG_STEP_BACK.matches(e)) {
			clientThread.invoke(() -> stepTestSpotanim(-SPOTANIM_BIG_STEP_TICKS));
		} else if (testSpotanimId != -1 && KEY_SPOTANIM_BIG_STEP_FORWARD.matches(e)) {
			clientThread.invoke(() -> stepTestSpotanim(SPOTANIM_BIG_STEP_TICKS));
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
