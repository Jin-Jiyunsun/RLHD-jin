W = 'src/main/resources/rs117/hd/utils/water.glsl'

edits = {W: [
    # Shore waves: shared constants for the travel distance
    ('''// Distance in steady travel units. Speed falls linearly from SHORE_WAVE_REACH to the shore, so this is the integral of
// its inverse, a logarithm.
float waveTravelDistance(float shoreDist) {
    const float reach = float(SHORE_WAVE_REACH);
    const float endSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
    if (endSpeed >= 0.999)
        return shoreDist;
''',
     '''// Shore waves slow from SHORE_WAVE_REACH in to the shore, down to this fraction of their starting speed
const float shoreWaveReach = float(SHORE_WAVE_REACH);
const float shoreWaveEndSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
// waveTravelDistance out at the reach, where the waves form, worked out once when compiling
const float shoreWaveStartTravel = shoreWaveEndSpeed >= 0.999 ? shoreWaveReach :
    shoreWaveReach / max(1.0 - shoreWaveEndSpeed, 1e-3) * log(1.0 / shoreWaveEndSpeed);

// Distance in steady travel units. Speed falls linearly from SHORE_WAVE_REACH to the shore, so this is the integral of
// its inverse, a logarithm.
float waveTravelDistance(float shoreDist) {
    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
    if (endSpeed >= 0.999)
        return shoreDist;
'''),
    ('''    // waveTravelDistance out at the reach, where waves form, written out so it's worked out once when compiling
    const float reach = float(SHORE_WAVE_REACH);
    const float endSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
    const float startTravel = endSpeed >= 0.999 ? reach : reach / max(1.0 - endSpeed, 1e-3) * log(1.0 / endSpeed);
''',
     '''    const float startTravel = shoreWaveStartTravel;
'''),
    # Shore waves: no spawn window
    ('''    // Each stretch may spawn one wave per slot, early enough to stay SHORE_WAVE_MIN_GAP apart
    const float slot = max(SHORE_WAVE_INTERVAL * 0.75, float(SHORE_WAVE_MIN_GAP));
    const float baseChance = min(1.0, slot / SHORE_WAVE_INTERVAL);
    const float spawnWindow = 1.0 - SHORE_WAVE_MIN_GAP / slot;
''',
     '''    // Each stretch may spawn one wave at the start of each slot, so they stay at least SHORE_WAVE_MIN_GAP apart
    const float slot = max(SHORE_WAVE_INTERVAL * 0.75, float(SHORE_WAVE_MIN_GAP));
    const float baseChance = min(1.0, slot / SHORE_WAVE_INTERVAL);
'''),
    ('''    float oldestSlot = (time - maxAge) / slot - spawnWindow;
''',
     '''    float oldestSlot = (time - maxAge) / slot;
'''),
    ('''                // Cheapest checks first, with each random number worked out only once it's needed, since most waves
                // are nowhere near. The spawn time and strength share theirs, which the spawn time only uses when
                // there's a window to spawn in. Otherwise it's left until the strength.
                vec2 r1 = vec2(0.0);
                if (spawnWindow > 0.0)
                    r1 = hash22(cell + n * vec2(17.31, 5.13));
                float age = time - (n + slotOffset + r1.x * spawnWindow) * slot;
                if (age < 0.0)
                    continue;
                vec2 r2''',
     '''                // Cheapest checks first, with each random number worked out only once it's needed, since most waves
                // are nowhere near
                float age = time - (n + slotOffset) * slot;
                vec2 r2'''),
    ('''                if (spawnWindow <= 0.0)
                    r1 = hash22(cell + n * vec2(17.31, 5.13));
                float strength = mix(SHORE_WAVE_MIN_STRENGTH, 1.0, r1.y) * (1.0 - smoothstep(1.0 - SHORE_WAVE_END_TAPER, 1.0, along));
''',
     '''                float strength = mix(SHORE_WAVE_MIN_STRENGTH, 1.0, hash22(cell + n * vec2(17.31, 5.13)).y) *
                    (1.0 - smoothstep(1.0 - SHORE_WAVE_END_TAPER, 1.0, along));
'''),
    # Ocean waves: the roughness is always set, and its effects are in plain proportion
    ('''            float spawnRoughness = uboGlobal.roughnessHistory[historySecond >> 2][historySecond & 3];
            spawnRoughness = spawnRoughness > 0.0 ? spawnRoughness : 1.0;
            float chance = saturate(OCEAN_WAVE_AMOUNT * pow(spawnRoughness, OCEAN_WAVE_ROUGHNESS_AMOUNT));
''',
     '''            float spawnRoughness = uboGlobal.roughnessHistory[historySecond >> 2][historySecond & 3];
            // More waves the rougher the water
            float chance = saturate(OCEAN_WAVE_AMOUNT * spawnRoughness);
'''),
    ('''            // Bigger in rougher water, from when it spawned, still fitting the neighbouring cells
            radius = min(radius * pow(spawnRoughness, OCEAN_WAVE_ROUGHNESS_SIZE), 0.9 / crestLength);
''',
     '''            // Bigger in rougher water, from when it spawned, by the square root of the roughness, still fitting the
            // neighbouring cells
            radius = min(radius * sqrt(spawnRoughness), 0.9 / crestLength);
'''),
    ('''#define OCEAN_WAVE_ROUGHNESS_AMOUNT 1.0    // how much rougher water adds waves, as a power of the roughness
#define OCEAN_WAVE_ROUGHNESS_STRENGTH 1.0  // how much rougher water strengthens them, as a power of the roughness
#define OCEAN_WAVE_ROUGHNESS_SIZE 0.5      // how much rougher water enlarges them, as a power of the roughness
''',
     '''#define OCEAN_WAVE_ROUGHNESS_STRENGTH 1.0  // how much rougher water strengthens them, as a power of the roughness
'''),
    # Lace: the detail distance is always set
    ('''    float maxDistance = uboGlobal.detailDrawDistance > 0.0 ?
        min(float(OCEAN_LACE_DISTANCE), uboGlobal.detailDrawDistance) : float(OCEAN_LACE_DISTANCE);
''',
     '''    float maxDistance = min(float(OCEAN_LACE_DISTANCE), uboGlobal.detailDrawDistance);
'''),
]}
