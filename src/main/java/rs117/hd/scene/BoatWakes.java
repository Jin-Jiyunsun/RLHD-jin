package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.opengl.uniforms.UBOGlobal;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.JAU_TO_RAD;

/**
 * Records the paths of nearby boats, so the water shader can draw wakes along them
 */
@Singleton
public class BoatWakes {
	public static final int MAX_BOATS = 4;
	public static final int MAX_POINTS = 16;
	// Points curving from the stern into the recorded path, so the hull and tail meet without a corner
	private static final int CURVE_POINTS = 3;
	// Recorded points, leaving room for the curve and the path's start
	private static final int RECORDED_POINTS = MAX_POINTS - CURVE_POINTS - 1;
	private static final float POINT_INTERVAL = 0.25f;
	private static final float LIFETIME = RECORDED_POINTS * POINT_INTERVAL;
	// Seconds of the boat's movement averaged into the recorded path, a game tick
	private static final float SMOOTHING = 0.6f;
	private static final int RAW_SAMPLES = 512;
	// Seconds the wake's speed takes to mostly catch up with the boat's, so it grows and shrinks smoothly
	private static final float SPEED_EASING = 0.2f;
	// Seconds the hull's position and heading take to mostly catch up with the boat's, smoothing out its jitter
	private static final float HULL_EASING = 0.1f;
	// Speed below which a boat stops making foam, in tiles per second, passed to the shader as BOAT_WAKE_MIN_SPEED
	public static final float MIN_SPEED = 0.3f;
	// Seconds the boat's recent peak speed takes to fall, which sets the strength of the foam along its hull
	private static final float PEAK_MEMORY = 4;
	// How far below its recent peak speed the boat has to slow for its wake to stop ageing, as a fraction
	private static final float SLOWING = 0.5f;
	// How far, in tiles, and how fast, in tiles per second, a boat has to move between frames to be treated as having
	// jumped to a new spot, rather than sailed there through a slow frame
	private static final float JUMP_DISTANCE = 4;
	private static final float JUMP_SPEED = 20;
	// Seconds an old wake is kept once its boat moves off again, to finish fading out where it was. Longer than
	// BOAT_WAKE_STOP_TIME in water.glsl, which decides when it's gone.
	private static final float OLD_WAKE_TIME = 8;

	/**
	 * A wake as it's drawn, in world local units: the boat's stern and heading, then the points of its path from the
	 * stern back, each with its age in seconds and its distance back from the stern in tiles
	 */
	private static class Wake {
		double sternX, sternZ;
		float headingX, headingZ;
		final double[] x = new double[MAX_POINTS];
		final double[] z = new double[MAX_POINTS];
		final float[] age = new float[MAX_POINTS];
		final float[] distance = new float[MAX_POINTS];
		int count;
		// The speed that sets the wake's strength
		float wakeSpeed;
		// When the boat moved off and started this wake
		float startTime;
		// When the boat slowed and the wake started breaking up, which is now while it's still making foam
		float fadeStart;
		// Squared distance from the camera's focal point, to pick the nearest wakes
		double focusDistance;

		void copy(Wake other) {
			sternX = other.sternX;
			sternZ = other.sternZ;
			headingX = other.headingX;
			headingZ = other.headingZ;
			System.arraycopy(other.x, 0, x, 0, other.count);
			System.arraycopy(other.z, 0, z, 0, other.count);
			System.arraycopy(other.age, 0, age, 0, other.count);
			System.arraycopy(other.distance, 0, distance, 0, other.count);
			count = other.count;
			wakeSpeed = other.wakeSpeed;
			startTime = other.startTime;
			fadeStart = other.fadeStart;
		}
	}

	private static class Trail {
		// Positions in world local units, and when they were recorded, newest last
		final double[] x = new double[MAX_POINTS];
		final double[] z = new double[MAX_POINTS];
		final float[] time = new float[MAX_POINTS];
		int count;
		// The wake as it's drawn, built every frame
		final Wake wake = new Wake();
		// The boat's position and the direction it faces, eased so the foam along its hull doesn't jitter
		double headX, headZ;
		double headingX, headingZ;
		// The boat's position averaged over a game tick, and the average time, where the path starts
		double averageX, averageZ;
		float averageTime;
		// The boat's raw positions over the last SMOOTHING seconds, as a ring, and their running sums
		final double[] rawX = new double[RAW_SAMPLES];
		final double[] rawZ = new double[RAW_SAMPLES];
		final float[] rawTime = new float[RAW_SAMPLES];
		int rawStart, rawCount;
		double rawSumX, rawSumZ, rawSumTime;
		// The boat's speed over the last game tick, and eased towards it, in tiles per second
		float measuredSpeed, headSpeed;
		float lastUpdate = Float.NaN;
		// When the boat last made foam, so its wake ages from then once it stops
		float lastMoving;
		// When the boat moved off and started its current wake
		float startedMoving;
		// The fastest the foam has recently gone, falling slowly, and the speed that sets the wake's strength, held once
		// the boat slows and its wake fades out
		float peakSpeed, wakeSpeed;
		boolean seen;

		/**
		 * @return the old wake, left where it was to finish fading out, when the boat moves off again before it's gone or
		 * jumps to a new spot, or null
		 */
		Wake update(double posX, double posZ, int orientation, float now) {
			// JAU orientation 0 faces south, and turns anticlockwise seen from above
			double angle = orientation * JAU_TO_RAD;
			double faceX = -Math.sin(angle);
			double faceZ = -Math.cos(angle);
			// A boat that jumps to a new spot starts afresh there, rather than drawing foam across the gap and taking the
			// jump as a burst of speed. Its old wake is left to fade out where it was.
			Wake oldWake = null;
			if (rawCount > 0) {
				int last = (rawStart + rawCount - 1) % RAW_SAMPLES;
				double moved = Math.hypot(posX - rawX[last], posZ - rawZ[last]) / LOCAL_TILE_SIZE;
				if (moved > JUMP_DISTANCE && moved > JUMP_SPEED * (now - rawTime[last])) {
					if (count > 0) {
						oldWake = new Wake();
						oldWake.copy(wake);
					}
					count = 0;
					rawCount = 0;
					rawSumX = rawSumZ = rawSumTime = 0;
					lastUpdate = Float.NaN;
					// The new wake fades in, like one started by moving off
					startedMoving = now;
				}
			}
			// The wake starts right at the boat, eased a little. The path behind it averages the boat's position over a
			// game tick, so the steps it takes while turning don't zigzag the path.
			if (Float.isNaN(lastUpdate)) {
				headX = posX;
				headZ = posZ;
				headingX = faceX;
				headingZ = faceZ;
			} else {
				double follow = 1 - Math.exp(-(now - lastUpdate) / HULL_EASING);
				headX += (posX - headX) * follow;
				headZ += (posZ - headZ) * follow;
				headingX += (faceX - headingX) * follow;
				headingZ += (faceZ - headingZ) * follow;
				double length = Math.hypot(headingX, headingZ);
				if (length > 1e-6) {
					headingX /= length;
					headingZ /= length;
				} else {
					headingX = faceX;
					headingZ = faceZ;
				}
			}
			while (rawCount > 0 && (rawCount == RAW_SAMPLES || now - rawTime[rawStart] > SMOOTHING)) {
				rawSumX -= rawX[rawStart];
				rawSumZ -= rawZ[rawStart];
				rawSumTime -= rawTime[rawStart];
				rawStart = (rawStart + 1) % RAW_SAMPLES;
				rawCount--;
			}
			if (rawCount == 0)
				rawSumX = rawSumZ = rawSumTime = 0;
			int slot = (rawStart + rawCount) % RAW_SAMPLES;
			rawX[slot] = posX;
			rawZ[slot] = posZ;
			rawTime[slot] = now;
			rawCount++;
			rawSumX += posX;
			rawSumZ += posZ;
			rawSumTime += now;
			// Stamped with the average time too, since that's when the boat was at the average position. Stamping it
			// with now would make the foam's age jump each time a point is added.
			posX = rawSumX / rawCount;
			posZ = rawSumZ / rawCount;
			float pointTime = (float) (rawSumTime / rawCount);
			// How far the boat has come over the last game tick, every frame, so it falls as soon as it stops and rises
			// as soon as it moves off, even while the path is frozen
			float span = now - rawTime[rawStart];
			if (span > 0.05f) {
				measuredSpeed = (float) (Math.hypot(rawX[slot] - rawX[rawStart], rawZ[slot] - rawZ[rawStart]) /
					LOCAL_TILE_SIZE / span);
			}
			if (!Float.isNaN(lastUpdate)) {
				float dt = now - lastUpdate;
				headSpeed += (measuredSpeed - headSpeed) * (float) (1 - Math.exp(-dt / SPEED_EASING));
				peakSpeed = Math.max(headSpeed, peakSpeed * (float) Math.exp(-dt / PEAK_MEMORY));
			}
			lastUpdate = now;
			// Making foam while it keeps up a good part of its recent speed. Once it slows well below that, or stops, the
			// wake stops ageing and fades out all together.
			boolean making = headSpeed >= Math.max(MIN_SPEED, peakSpeed * SLOWING);
			if (making) {
				// Speeding up again after that starts a new wake. The old one, as it was last drawn, is left where it
				// was to carry on fading out, so it doesn't vanish or follow the boat.
				if (now - lastMoving > 0.25f) {
					if (count > 0 && now - lastMoving < OLD_WAKE_TIME) {
						oldWake = new Wake();
						oldWake.copy(wake);
					}
					count = 0;
					startedMoving = now;
				}
				lastMoving = now;
				wakeSpeed = peakSpeed;
			}
			averageX = posX;
			averageZ = posZ;
			averageTime = pointTime;
			// Once the boat slows, its path stays as it was while its wake fades out, so new points don't push old ones
			// out
			if (!making)
				return oldWake;
			// Forget points too old to have any wake left
			int expired = 0;
			while (expired < count && lastMoving - time[expired] > LIFETIME)
				expired++;
			if (expired > 0) {
				System.arraycopy(x, expired, x, 0, count - expired);
				System.arraycopy(z, expired, z, 0, count - expired);
				System.arraycopy(time, expired, time, 0, count - expired);
				count -= expired;
			}
			if (count > 0 && pointTime - time[count - 1] < POINT_INTERVAL)
				return oldWake;
			if (count == RECORDED_POINTS) {
				System.arraycopy(x, 1, x, 0, count - 1);
				System.arraycopy(z, 1, z, 0, count - 1);
				System.arraycopy(time, 1, time, 0, count - 1);
				count--;
			}
			x[count] = posX;
			z[count] = posZ;
			time[count] = pointTime;
			count++;
			return oldWake;
		}

		/**
		 * Build the wake as it's drawn: a curve from the stern into the recorded path, then the path back from the boat's
		 * averaged position
		 */
		void build() {
			Wake w = wake;
			w.headingX = (float) headingX;
			w.headingZ = (float) headingZ;
			w.sternX = headX;
			w.sternZ = headZ;
			w.wakeSpeed = wakeSpeed;
			w.startTime = startedMoving;
			w.fadeStart = lastMoving;
			// Ages stop once the boat stops making foam, and the shader adds the time since
			float ageNow = lastMoving;
			float startAge = Math.max(ageNow - averageTime, 0);

			// The direction the path runs towards its start
			double forwardX = headingX, forwardZ = headingZ;
			if (count > 0) {
				double dx = averageX - x[count - 1];
				double dz = averageZ - z[count - 1];
				double length = Math.hypot(dx, dz);
				if (length > 0.01 * LOCAL_TILE_SIZE) {
					forwardX = dx / length;
					forwardZ = dz / length;
				}
			}

			// Curve from the stern, leaving along the boat's heading, into the path's start, arriving along the path
			double handle = Math.hypot(averageX - headX, averageZ - headZ) / 3;
			double c1X = headX - headingX * handle, c1Z = headZ - headingZ * handle;
			double c2X = averageX + forwardX * handle, c2Z = averageZ + forwardZ * handle;
			int n = 0;
			for (int i = 1; i <= CURVE_POINTS; i++) {
				double t = (double) i / (CURVE_POINTS + 1);
				double u = 1 - t;
				w.x[n] = u * u * u * headX + 3 * u * u * t * c1X + 3 * u * t * t * c2X + t * t * t * averageX;
				w.z[n] = u * u * u * headZ + 3 * u * u * t * c1Z + 3 * u * t * t * c2Z + t * t * t * averageZ;
				w.age[n] = (float) (startAge * t);
				n++;
			}

			// The recorded path, newest first, from the path's start
			w.x[n] = averageX;
			w.z[n] = averageZ;
			w.age[n] = startAge;
			n++;
			for (int i = count - 1; i >= 0; i--) {
				w.x[n] = x[i];
				w.z[n] = z[i];
				w.age[n] = Math.max(ageNow - time[i], 0);
				n++;
			}
			w.count = n;

			// How far back from the stern each point is, so the shader needn't measure every segment for every pixel
			double distance = 0;
			double prevX = headX, prevZ = headZ;
			for (int i = 0; i < n; i++) {
				distance += Math.hypot(w.x[i] - prevX, w.z[i] - prevZ);
				w.distance[i] = (float) (distance / LOCAL_TILE_SIZE);
				prevX = w.x[i];
				prevZ = w.z[i];
			}
		}
	}

	private final Map<Integer, Trail> trails = new HashMap<>();
	// Old wakes left fading out where they were after their boats moved off again
	private final List<Wake> oldWakes = new ArrayList<>();
	private final List<Wake> active = new ArrayList<>();
	private final float[] counts = new float[MAX_BOATS];

	/**
	 * Record the paths of visible boats, skipping flattened ones, and fill in the wake uniforms
	 *
	 * @param drawnScene the scene being drawn, which can lag behind the client's across loading lines
	 */
	public void update(Client client, Scene drawnScene, UBOGlobal ubo, float now) {
		WorldView topLevel = client.getTopLevelWorldView();
		Scene scene = topLevel == null ? null : topLevel.getScene();
		active.clear();
		if (scene != null && drawnScene != null) {
			// Boats are placed in the client's scene, but drawn relative to the scene being drawn
			int clientBaseX = scene.getBaseX() * LOCAL_TILE_SIZE;
			int clientBaseZ = scene.getBaseY() * LOCAL_TILE_SIZE;
			int baseX = drawnScene.getBaseX() * LOCAL_TILE_SIZE;
			int baseZ = drawnScene.getBaseY() * LOCAL_TILE_SIZE;
			for (var trail : trails.values())
				trail.seen = false;
			for (WorldEntity entity : topLevel.worldEntities()) {
				// Flattened boats keep their paths, so they don't restart if they're only flattened briefly
				LocalPoint location = entity.getLocalLocation();
				var trail = trails.computeIfAbsent(entity.getWorldView().getId(), id -> new Trail());
				Wake oldWake = trail.update(
					location.getX() + clientBaseX, location.getY() + clientBaseZ, entity.getOrientation(), now);
				if (oldWake != null)
					oldWakes.add(oldWake);
				trail.build();
				trail.seen = true;
				if (!entity.isHiddenForOverlap())
					active.add(trail.wake);
			}
			trails.values().removeIf(trail -> !trail.seen);
			oldWakes.removeIf(wake -> now - wake.fadeStart > OLD_WAKE_TIME);

			// Only the wakes nearest the camera are drawn, up to the four the shader's counts have room for. Boats' own
			// wakes come first, then old ones left fading, which may already be gone.
			double focusX = client.getCameraFocalPointX() + clientBaseX;
			double focusZ = client.getCameraFocalPointZ() + clientBaseZ;
			sortByDistance(active, focusX, focusZ);
			sortByDistance(oldWakes, focusX, focusZ);
			active.addAll(oldWakes);
			while (active.size() > MAX_BOATS)
				active.remove(active.size() - 1);

			for (int boat = 0; boat < active.size(); boat++)
				upload(ubo, boat, active.get(boat), baseX, baseZ, now);
		}

		// Unused slots have no points
		for (int boat = 0; boat < MAX_BOATS; boat++)
			counts[boat] = boat < active.size() ? active.get(boat).count : 0;
		ubo.boatWakeCounts.set(counts);
	}

	private static void sortByDistance(List<Wake> wakes, double focusX, double focusZ) {
		for (var wake : wakes) {
			double dx = wake.sternX - focusX;
			double dz = wake.sternZ - focusZ;
			wake.focusDistance = dx * dx + dz * dz;
		}
		wakes.sort(Comparator.comparingDouble(wake -> wake.focusDistance));
	}

	/**
	 * Send a wake's heading and stern, and its path relative to the drawn scene, in tiles. Each point has its position,
	 * age in seconds and distance back from the stern. The seconds since the boat slowed go with the hull, so how it fades
	 * is decided in the shader, and reloads live. The bounds are just the path's: the shader adds the room the foam needs.
	 */
	private static void upload(UBOGlobal ubo, int boat, Wake wake, int baseX, int baseZ, float now) {
		float sinceSlowed = now - wake.fadeStart;
		float sternX = (float) ((wake.sternX - baseX) / LOCAL_TILE_SIZE);
		float sternZ = (float) ((wake.sternZ - baseZ) / LOCAL_TILE_SIZE);
		ubo.boatWakeHeadings[boat].set(wake.headingX, wake.headingZ, sternX, sternZ);
		ubo.boatWakeHulls[boat].set(0, sinceSlowed, now - wake.startTime, wake.wakeSpeed);

		float minX = sternX, maxX = sternX;
		float minZ = sternZ, maxZ = sternZ;
		int index = boat * MAX_POINTS;
		for (int i = 0; i < wake.count; i++) {
			float px = (float) ((wake.x[i] - baseX) / LOCAL_TILE_SIZE);
			float pz = (float) ((wake.z[i] - baseZ) / LOCAL_TILE_SIZE);
			ubo.boatWakePoints[index++].set(px, pz, wake.age[i], wake.distance[i]);
			minX = Math.min(minX, px);
			maxX = Math.max(maxX, px);
			minZ = Math.min(minZ, pz);
			maxZ = Math.max(maxZ, pz);
		}
		ubo.boatWakeBounds[boat].set(minX, minZ, maxX, maxZ);
	}
}
