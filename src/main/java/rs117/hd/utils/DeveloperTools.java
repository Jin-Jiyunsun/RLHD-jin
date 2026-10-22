package rs117.hd.utils;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;

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
	private int testSpotanimHeight;
	private boolean testSpotanimLoop;
	private int testSpotanimRestartCycle = -1;
	private List<int[]> testSpotanimFrameTimes;
	private int testSpotanimLastFrame;
	// Frame start times recorded during the first play, used to map playback time to frames while controlling playback
	private List<int[]> testSpotanimTimeline;
	private boolean testSpotanimPaused;
	private float testSpotanimSpeed = 1;
	private float testSpotanimTimeMs;

	public void activate() {
		// Listen for commands
		eventBus.register(this);

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
		clientThread.invoke(this::stopTestSpotanim);
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
			case "spotanim":
				handleSpotanimCommand(args);
				break;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		ActorSpotAnim spotanim = findTestSpotanim(player);

		if (isTestSpotanimControlled()) {
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

	private boolean isTestSpotanimControlled() {
		return testSpotanimPaused || testSpotanimSpeed != 1;
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
		if (isTestSpotanimControlled())
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
			testSpotanimTimeMs += testSpotanimSpeed * Constants.CLIENT_TICK_LENGTH;
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
			"Spotanim %s at frame %d, %d ms (speed %sx)",
			testSpotanimPaused ? "paused" : "playing",
			getTestSpotanimFrameAt(testSpotanimTimeMs),
			Math.round(testSpotanimTimeMs),
			testSpotanimSpeed
		));
	}

	private void toggleTestSpotanimPause() {
		if (testSpotanimId == -1)
			return;
		beginControllingTestSpotanim();
		testSpotanimPaused = !testSpotanimPaused;
		if (!isTestSpotanimControlled())
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

	private void handleSpotanimCommand(String[] args) {
		assert client.isClientThread();
		String usage = "Usage: ::117hd spotanim <name|id> [height] [loop] / pause / speed <multiplier> / time <ms> / stop";
		if (args.length < 2 || args.length > 4) {
			postMessage(usage);
			return;
		}

		switch (args[1].toLowerCase()) {
			case "stop":
			case "clear":
				stopTestSpotanim();
				postMessage("Stopped test spotanim");
				return;
			case "pause":
			case "resume":
				if (testSpotanimId == -1) {
					postMessage("No test spotanim is playing");
				} else {
					toggleTestSpotanimPause();
				}
				return;
			case "speed":
			case "time":
				if (testSpotanimId == -1) {
					postMessage("No test spotanim is playing");
					return;
				}
				float value;
				try {
					value = args.length == 3 ? Float.parseFloat(args[2]) : -1;
				} catch (NumberFormatException e) {
					value = -1;
				}
				if (value < 0 || args[1].equalsIgnoreCase("speed") && value == 0) {
					postMessage(usage);
					return;
				}
				beginControllingTestSpotanim();
				if (args[1].equalsIgnoreCase("speed")) {
					testSpotanimSpeed = value;
				} else {
					testSpotanimTimeMs = min(value, getTestSpotanimEndMs());
					testSpotanimPaused = true;
				}
				if (!isTestSpotanimControlled())
					releaseTestSpotanimControl();
				postTestSpotanimPosition();
				return;
		}

		String nameOrId = args[1].toUpperCase();
		Integer id;
		try {
			id = Integer.parseInt(nameOrId);
		} catch (NumberFormatException ignored) {
			try (var gamevals = gamevalManager.obtainHandle()) {
				id = gamevals.getSpotanims().get(nameOrId);
			}
		}
		if (id == null) {
			postMessage("Unknown spotanim: " + nameOrId);
			return;
		}

		int height = 0;
		boolean loop = false;
		for (int i = 2; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("loop")) {
				loop = true;
				continue;
			}
			try {
				height = Integer.parseInt(args[i]);
			} catch (NumberFormatException e) {
				postMessage(usage);
				return;
			}
		}

		if (client.getLocalPlayer() == null) {
			postMessage("No local player to play the spotanim on");
			return;
		}

		stopTestSpotanim();
		testSpotanimId = id;
		testSpotanimHeight = height;
		testSpotanimLoop = loop;
		testSpotanimFrameTimes = new ArrayList<>();
		testSpotanimLastFrame = -1;
		playTestSpotanim();

		String name;
		try (var gamevals = gamevalManager.obtainHandle()) {
			name = gamevals.getSpotanimName(id);
		}
		postMessage("Playing spotanim " + (name == null ? id : name + " (" + id + ")") + " at height " + height + (loop ? ", looping" : ""));
	}

	private void playTestSpotanim() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		player.createSpotAnim(TEST_SPOTANIM_SLOT, testSpotanimId, testSpotanimHeight, 0);

		// Client-side spotanims don't notify plugins, so let the LightManager know to attach lights
		GraphicChanged changed = new GraphicChanged();
		changed.setActor(player);
		eventBus.post(changed);
	}

	private void stopTestSpotanim() {
		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player != null && player.hasSpotAnim(testSpotanimId))
			player.removeSpotAnim(TEST_SPOTANIM_SLOT);

		testSpotanimId = -1;
		testSpotanimLoop = false;
		testSpotanimRestartCycle = -1;
		testSpotanimFrameTimes = null;
		testSpotanimTimeline = null;
		testSpotanimPaused = false;
		testSpotanimSpeed = 1;
		releaseTestSpotanimControl();
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

	private void postMessage(String message) {
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
