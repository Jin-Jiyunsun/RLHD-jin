package rs117.hd.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.opengl.uniforms.UBOGlobal;

import static net.runelite.api.Perspective.*;

/**
 * Records the paths of nearby boats, so the water shader can draw wakes along them
 */
@Singleton
public class BoatWakes {
	public static final int MAX_BOATS = 4;
	public static final int MAX_POINTS = 32;
	private static final float POINT_INTERVAL = 0.5f;
	private static final float LIFETIME = MAX_POINTS * POINT_INTERVAL;
	// Extra room around each path for its wake to spread into, in tiles, and per tile of path
	private static final float BOUNDS_MARGIN = 3;
	private static final float BOUNDS_SPREAD = 0.45f;

	private static class Trail {
		// Positions in world local units, and when they were recorded, newest last
		final double[] x = new double[MAX_POINTS];
		final double[] z = new double[MAX_POINTS];
		final float[] time = new float[MAX_POINTS];
		int count;
		double headX, headZ;
		boolean seen;

		void update(double posX, double posZ, float now) {
			headX = posX;
			headZ = posZ;
			// Forget points too old to have any wake left
			int expired = 0;
			while (expired < count && now - time[expired] > LIFETIME)
				expired++;
			if (expired > 0) {
				System.arraycopy(x, expired, x, 0, count - expired);
				System.arraycopy(z, expired, z, 0, count - expired);
				System.arraycopy(time, expired, time, 0, count - expired);
				count -= expired;
			}
			if (count > 0 && now - time[count - 1] < POINT_INTERVAL)
				return;
			if (count == MAX_POINTS - 1) {
				System.arraycopy(x, 1, x, 0, count - 1);
				System.arraycopy(z, 1, z, 0, count - 1);
				System.arraycopy(time, 1, time, 0, count - 1);
				count--;
			}
			x[count] = posX;
			z[count] = posZ;
			time[count] = now;
			count++;
		}
	}

	private final Map<Integer, Trail> trails = new HashMap<>();
	private final List<Trail> active = new ArrayList<>(MAX_BOATS);
	private final float[] counts = new float[MAX_BOATS];

	/**
	 * Record the paths of visible boats, skipping flattened ones, and fill in the wake uniforms
	 */
	public void update(Client client, UBOGlobal ubo, float now) {
		WorldView topLevel = client.getTopLevelWorldView();
		Scene scene = topLevel == null ? null : topLevel.getScene();
		active.clear();
		if (scene != null) {
			int baseX = scene.getBaseX() * LOCAL_TILE_SIZE;
			int baseZ = scene.getBaseY() * LOCAL_TILE_SIZE;
			for (var trail : trails.values())
				trail.seen = false;
			for (WorldEntity entity : topLevel.worldEntities()) {
				if (entity.isHiddenForOverlap())
					continue;
				LocalPoint location = entity.getLocalLocation();
				var trail = trails.computeIfAbsent(entity.getWorldView().getId(), id -> new Trail());
				trail.update(location.getX() + baseX, location.getY() + baseZ, now);
				trail.seen = true;
				if (active.size() < MAX_BOATS)
					active.add(trail);
			}
			trails.values().removeIf(trail -> !trail.seen);

			for (int boat = 0; boat < active.size(); boat++)
				upload(ubo, boat, active.get(boat), baseX, baseZ, now);
		}

		// Unused boats have no points
		for (int boat = 0; boat < MAX_BOATS; boat++)
			counts[boat] = boat < active.size() ? active.get(boat).count + 1 : 0;
		ubo.boatWakeCounts.set(counts);
	}

	/**
	 * Send a boat's path relative to the current scene, in tiles, from the boat back: position, age in seconds, and
	 * distance behind the boat along the path
	 */
	private static void upload(UBOGlobal ubo, int boat, Trail trail, int baseX, int baseZ, float now) {
		float prevX = (float) ((trail.headX - baseX) / LOCAL_TILE_SIZE);
		float prevZ = (float) ((trail.headZ - baseZ) / LOCAL_TILE_SIZE);
		float distance = 0;
		float minX = prevX, maxX = prevX, minZ = prevZ, maxZ = prevZ;
		ubo.boatWakePoints[boat * MAX_POINTS].set(prevX, prevZ, 0, 0);
		for (int i = 0; i < trail.count; i++) {
			int point = trail.count - 1 - i;
			float px = (float) ((trail.x[point] - baseX) / LOCAL_TILE_SIZE);
			float pz = (float) ((trail.z[point] - baseZ) / LOCAL_TILE_SIZE);
			distance += (float) Math.hypot(px - prevX, pz - prevZ);
			ubo.boatWakePoints[boat * MAX_POINTS + 1 + i].set(px, pz, now - trail.time[point], distance);
			minX = Math.min(minX, px);
			maxX = Math.max(maxX, px);
			minZ = Math.min(minZ, pz);
			maxZ = Math.max(maxZ, pz);
			prevX = px;
			prevZ = pz;
		}
		float margin = BOUNDS_MARGIN + distance * BOUNDS_SPREAD;
		ubo.boatWakeBounds[boat].set(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
	}
}
