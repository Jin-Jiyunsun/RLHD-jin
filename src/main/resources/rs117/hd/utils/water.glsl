/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>
#include <uniforms/materials.glsl>
#include <uniforms/water_types.glsl>

#include <utils/constants.glsl>
#include <utils/lights.glsl>
#include <utils/misc.glsl>

#if WATER_PASS || LEGACY_RENDERER

// Shore foam
#define SHORE_FOAM_STRENGTH 0.31           // peak foam opacity before lighting
#define SHORE_FOAM_SOFTNESS 0.3            // width of the eroded foam edge in texture space
#define SHORE_FOAM_EDGE_NOISE 0.35         // how far the foam line wanders, in tiles
#define SHORE_FOAM_REACH 0.8               // how far the solid shore foam fades out to between waves, in tiles
#define SHORE_FOAM_CHURN 0.3               // how far the foam pattern is warped, in texture repeats. Much above 0.1 stretches it.
#define SHORE_FOAM_SCALE 1.25              // tiles per foam texture repeat; lower packs the foam patches more densely
#define SHORE_FOAM_EVOLVE_PERIOD 2.2       // seconds for each foam pattern to fade in and back out
#define SHORE_FOAM_SWAY 0.08               // how far the foam sways in and out from its resting place, in tiles
#define SHORE_FOAM_SWAY_PERIOD 12.0        // seconds per sway cycle, including the lull after it
#define SHORE_FOAM_SWAY_MAX_LULL 0.4       // longest lull between sways, as a fraction of the period
#define SHORE_FOAM_LULL_DRIFT 0.25         // how far the foam drifts during the longest lull, relative to a full sway

// Waves, each spawned along a stretch of shore at random and travelling in until it lands
#define SHORE_WAVE_SPACING 2.0             // size of the stretches of shore which each spawn their own waves, in tiles
#define SHORE_WAVE_INTERVAL 6.0            // average seconds between waves from each stretch
#define SHORE_WAVE_MIN_GAP 5.0             // fewest seconds between waves from the same stretch, at most the interval
#define SHORE_WAVE_SET_PERIOD 10.0         // seconds over which waves come in sets and lulls
#define SHORE_WAVE_SET_CONTRAST 0.8        // how busy sets and how quiet lulls are, from 0 for steady to 1
#define SHORE_WAVE_MIN_LENGTH 1.5          // range of each wave's length along the shore, in tiles
#define SHORE_WAVE_MAX_LENGTH 4.0
#define SHORE_WAVE_END_TAPER 0.3           // how much of each wave's length fades out towards its ends
#define SHORE_WAVE_START_THICKNESS 0.3     // average depth of each wave from front to back where it forms, in tiles
#define SHORE_WAVE_END_THICKNESS 0.6      // and at the shore, growing evenly in between
#define SHORE_WAVE_THICKNESS_RANDOMNESS 0.3 // how much each wave's thickness varies, as a fraction either way
#define SHORE_WAVE_FRONT 0.25              // how gradually each wave's front rises, as a fraction of its thickness
#define SHORE_WAVE_TAIL 0.6                // how gradually each wave fades out behind its crest, as a fraction of its thickness
#define SHORE_WAVE_FRONT_BREAKUP 0.12      // how far the foam texture breaks up each wave's front, in tiles
#define SHORE_WAVE_MIN_STRENGTH 0.6        // weakest wave's coverage, relative to the strongest
#define SHORE_WAVE_COVERAGE 1.5            // boosts each wave's coverage before capping it, so most waves are solid
#define SHORE_WAVE_REACH 2.25               // how far from the shore waves form, in tiles. At most about 3.6.
#define SHORE_WAVE_SPEED 0.32              // tiles per second as waves form, slowing evenly as they come in
#define SHORE_WAVE_END_SPEED 0.7           // waves' speed at the shore, as a fraction of their starting speed
#define SHORE_WAVE_SPEED_RANDOMNESS 0.12   // how much each wave's speed varies, as a fraction either way
#define SHORE_WAVE_FULL_OPACITY 0.3        // distance from the shore where waves reach full opacity, in tiles
#define SHORE_WAVE_OPACITY_CURVE 1.5       // how late waves fade in, from 1 for steady; higher stays fainter for longer
#define SHORE_WAVE_MIN_ROOM 5.0            // waves only form with this much open water nearby, in tiles
#define SHORE_WAVE_FULL_ROOM 6.0           // and reach full strength with this much, up to WATER_ROOM_MAX

#if DAYLIGHT_CYCLE
    #include <utils/sky_sampling.glsl>
#endif

// Travel distance to the shore, which waves cover at a steady rate. Waves start at full speed at SHORE_WAVE_REACH and
// slow evenly to SHORE_WAVE_END_SPEED of it at the shore. With speed falling linearly with distance, the travel
// distance is the integral of its inverse, a logarithm.
float waveTravelDistance(float shoreDist) {
    const float reach = float(SHORE_WAVE_REACH);
    const float endSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
    if (endSpeed >= 0.999)
        return shoreDist;
    // Waves keep their starting speed beyond the reach. Inside the shore the speed would reach 0, so stop short of that.
    float d = clamp(shoreDist, -0.9 * endSpeed / (1.0 - endSpeed) * reach, reach);
    float travel = reach / (1.0 - endSpeed) * log((endSpeed + (1.0 - endSpeed) * d / reach) / endSpeed);
    return travel + max(shoreDist - reach, 0.0);
}

// Two channels of value noise for the cost of one
vec2 noise2(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    vec2 a = hash22(i);
    vec2 b = hash22(i + vec2(1, 0));
    vec2 c = hash22(i + vec2(0, 1));
    vec2 d = hash22(i + vec2(1, 1));
    vec2 u = smoothstep(0.0, 1.0, f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Waves travelling in to the shore, returning their foam coverage. The shore is split into stretches which spawn waves
// at random times, each with its own place, length, thickness, speed and strength. Waves can overlap, and a fast wave
// can catch a slower one.
//  shorePoint: the nearest point on the shore, in world tiles
//  foamMask:   the foam texture, which breaks up each wave's front
//  room:       how much room there is for waves to form, from 0 to 1
float shoreWaves(float shoreDist, vec2 shorePoint, float foamMask, float room) {
    float time = uboGlobal.elapsedTime;
    // Positions are in travel distance, which waves cover at a steady rate
    float travel = waveTravelDistance(shoreDist) + (foamMask - 0.5) * SHORE_WAVE_FRONT_BREAKUP;
    float startTravel = waveTravelDistance(float(SHORE_WAVE_REACH));
    // Waves thicken as they come in
    float baseThickness = mix(float(SHORE_WAVE_END_THICKNESS), float(SHORE_WAVE_START_THICKNESS), saturate(shoreDist / SHORE_WAVE_REACH));

    // Each stretch has a chance to spawn a wave once per slot, averaging one per interval, with room for sets to spawn
    // more. Each spawns early enough in its slot that waves from the same stretch are at least SHORE_WAVE_MIN_GAP apart.
    const float slot = max(SHORE_WAVE_INTERVAL * 0.75, float(SHORE_WAVE_MIN_GAP));
    const float baseChance = min(1.0, slot / SHORE_WAVE_INTERVAL);
    const float spawnWindow = 1.0 - SHORE_WAVE_MIN_GAP / slot;
    const float minSpeed = SHORE_WAVE_SPEED * max(1.0 - SHORE_WAVE_SPEED_RANDOMNESS, 0.01);
    const float maxSpeed = SHORE_WAVE_SPEED * (1.0 + SHORE_WAVE_SPEED_RANDOMNESS);
    const float maxThickness =
        max(float(SHORE_WAVE_START_THICKNESS), float(SHORE_WAVE_END_THICKNESS)) * (1.0 + SHORE_WAVE_THICKNESS_RANDOMNESS);
    const float maxHalfLength = SHORE_WAVE_MAX_LENGTH * 0.5;
    const int cellRadius = int(ceil(maxHalfLength / SHORE_WAVE_SPACING));

    // Only waves of an age between these can be covering this point, so only check the slots they could spawn in
    float minAge = max((startTravel - travel) / maxSpeed, 0.0);
    float maxAge = (startTravel - travel + maxThickness) / minSpeed;
    if (maxAge <= 0.0)
        return 0.0;
    float newestSlot = (time - minAge) / slot;
    float oldestSlot = (time - maxAge) / slot - spawnWindow;

    vec2 stretch = floor(shorePoint / SHORE_WAVE_SPACING);
    float wave = 0;
    for (int sy = -cellRadius; sy <= cellRadius; sy++) {
        for (int sx = -cellRadius; sx <= cellRadius; sx++) {
            vec2 cell = stretch + vec2(sx, sy);
            // Skip stretches too far away for any of their waves to reach this point
            vec2 toCell = abs(shorePoint - (cell + 0.5) * SHORE_WAVE_SPACING) - 0.5 * SHORE_WAVE_SPACING;
            if (length(max(toCell, 0.0)) >= maxHalfLength)
                continue;

            // Offset each stretch's slots at random, so neighbouring stretches don't spawn together and line up
            float slotOffset = hash12(cell + 0.73);
            float lastN = floor(newestSlot - slotOffset);
            for (float n = floor(oldestSlot - slotOffset); n <= lastN; n++) {
                // Cheapest checks first. Spawned early in its slot, travelling in at its own speed.
                vec2 r1 = hash22(cell + n * vec2(17.31, 5.13));
                vec2 r2 = hash22(cell * 3.71 + n * vec2(2.27, 11.9) + 0.5);
                float age = time - (n + slotOffset + r1.x * spawnWindow) * slot;
                if (age < 0.0)
                    continue;
                float speed = SHORE_WAVE_SPEED * (1.0 + (r2.x - 0.5) * 2.0 * SHORE_WAVE_SPEED_RANDOMNESS);
                float front = startTravel - age * speed;

                // The wave's body, from 0 at its front to 1 at its back
                float thickness = baseThickness *
                    (1.0 + (hash12(cell * 5.17 + n * vec2(3.31, 9.13) + 2.5) - 0.5) * 2.0 * SHORE_WAVE_THICKNESS_RANDOMNESS);
                float across = (travel - front) / thickness;
                if (across <= 0.0 || across >= 1.0)
                    continue;

                // How far along the wave this point is, from 0 in its middle to 1 at its ends
                vec2 r3 = hash22(cell * 1.93 - n * vec2(7.13, 3.37) + 1.5);
                vec2 center = (cell + r2) * SHORE_WAVE_SPACING;
                float halfLength = mix(SHORE_WAVE_MIN_LENGTH, SHORE_WAVE_MAX_LENGTH, r3.y) * 0.5;
                float along = length(shorePoint - center) / halfLength;
                if (along >= 1.0)
                    continue;

                // The chance rises and falls slowly, giving sets and lulls. Nearby stretches have similar chances,
                // so a set rolls along the coast together.
                float sets = noise(vec2(n * slot / SHORE_WAVE_SET_PERIOD, dot(cell, vec2(0.37, 0.21))));
                float spawnChance = saturate(baseChance * (1.0 + (sets - 0.5) * 2.0 * SHORE_WAVE_SET_CONTRAST));
                if (r3.x >= spawnChance)
                    continue;

                float strength = mix(SHORE_WAVE_MIN_STRENGTH, 1.0, r1.y) * (1.0 - smoothstep(1.0 - SHORE_WAVE_END_TAPER, 1.0, along));
                float body = smoothstep(0.0, SHORE_WAVE_FRONT, across) * (1.0 - smoothstep(1.0 - SHORE_WAVE_TAIL, 1.0, across));
                // Capped after the boost, so strong waves are solid and weaker ones and the tapered ends thin out
                wave = max(wave, body * saturate(strength * room * SHORE_WAVE_COVERAGE));
            }
        }
    }
    return wave;
}

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    vec2 anchorUv1 = worldUvs(3).yx;
    vec2 anchorUv2 = worldUvs(5);
    vec2 anchorUv3 = worldUvs(7);
    vec2 uv1 = anchorUv1 - animationFrame(28 * waterType.duration);
    vec2 uv2 = anchorUv2 + animationFrame(24 * waterType.duration);
    vec2 uv3 = anchorUv3 + animationFrame(21 * waterType.duration);

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

    // get diffuse textures
    vec3 n1 = linearToSrgb(texture(textureArray, vec3(uv1, waterType.normalMap)).xyz);
    vec3 n2 = linearToSrgb(texture(textureArray, vec3(uv2, waterType.normalMap)).xyz);
    vec3 n3 = linearToSrgb(texture(textureArray, vec3(uv3, waterType.normalMap)).xyz);
    #if IMPROVED_SHORE_FOAM
        float foamMask = 0;
    #else
        vec2 uvFoam = IN.uv + uvFlow * flowMapStrength;
        float foamMask = texture(textureArray, vec3(uvFoam, MAT_WATER_FOAM.colorMap)).r;
    #endif

    // normals
    n1 = -vec3((n1.x * 2 - 1) * waterType.normalStrength, n1.z, (n1.y * 2 - 1) * waterType.normalStrength);
    n2 = -vec3((n2.x * 2 - 1) * waterType.normalStrength, n2.z, (n2.y * 2 - 1) * waterType.normalStrength);
    n3 = -vec3((n3.x * 2 - 1) * waterType.normalStrength, n3.z, (n3.y * 2 - 1) * waterType.normalStrength);
    vec3 normals = normalize(n1 + n2 + n3);

    float lightDotNormals = dot(normals, uboGlobal.lightDir);
    float downDotNormals = -normals.y;
    float viewDotNormals = dot(viewDir, normals);

    vec3 distortion = mat3(uboGlobal.invLightProjectionMatrix) * vec3(uvFlow * .0015, 0);
    vec3 lightAxis = uboGlobal.invLightProjectionMatrix[2].xyz;
    vec2 surfaceDistortion = vec2(0);
    if (abs(lightAxis.y) > .001 * length(lightAxis))
        surfaceDistortion = distortion.xz - lightAxis.xz * (distortion.y / lightAxis.y);
    vec3 shadowReceiver = IN.position + vec3(surfaceDistortion.x, 0, surfaceDistortion.y);
    float shadow = sampleShadowMap(shadowReceiver, vec3(0, -1, 0), false, false, true);
    float inverseShadow = 1 - shadow;

    vec3 vSpecularStrength = vec3(waterType.specularStrength);
    vec3 vSpecularGloss = vec3(waterType.specularGloss);
    float combinedSpecularStrength = waterType.specularStrength;

    // calculate lighting

    // ambient light
    vec3 ambientLightOut = uboGlobal.ambientColor * uboGlobal.ambientStrength;

    // directional light
    vec3 dirLightColor = uboGlobal.lightColor * uboGlobal.lightStrength;
    vec3 unshadowedLightOut = max(lightDotNormals, 0.0) * dirLightColor;

    // apply shadows
    dirLightColor *= inverseShadow;

    vec3 lightColor = dirLightColor;
    vec3 lightOut = max(lightDotNormals, 0.0) * lightColor;

    // directional light specular
    vec3 lightSpecularOut = vec3(0);
    #if WATER_SPECULAR_REFLECTIONS
        #if DAYLIGHT_CYCLE
        if (!uboSky.enabled)
        #endif
            lightSpecularOut = lightColor * specular(fTexBlend, viewDir, reflect(-uboGlobal.lightDir, normals), vSpecularGloss, vSpecularStrength);
    #endif

    // point lights
    vec3 pointLightsOut = vec3(0);
    vec3 pointLightsSpecularOut = vec3(0);
    calculateLighting(IN.position, normals, viewDir, fTexBlend, vSpecularGloss, vSpecularStrength,
        0.0, pointLightsOut, pointLightsSpecularOut);

    // sky light
    vec3 skyLightColor = uboGlobal.fogColor;
    float skyLightStrength = 0.5;
    float skyDotNormals = downDotNormals;
    vec3 skyLightOut = max(skyDotNormals, 0.0) * skyLightColor * skyLightStrength;


    // lightning
    vec3 lightningColor = vec3(1.0, 1.0, 1.0);
    float lightningStrength = uboGlobal.lightningBrightness;
    float lightningDotNormals = downDotNormals;
    vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningStrength;


    // underglow
    vec3 underglowOut = uboGlobal.underglowColor * max(normals.y, 0) * uboGlobal.underglowStrength;


    // fresnel reflection
    float baseOpacity = 0.4;
    float fresnel = 1.0 - clamp(viewDotNormals, 0.0, 1.0);
    float finalFresnel = clamp(mix(baseOpacity, 1.0, fresnel * 1.2), 0.0, 1.0);
    vec3 surfaceColor = vec3(0);
    #if DAYLIGHT_CYCLE && WATER_SPECULAR_REFLECTIONS
    vec3 celestialReflection = vec3(0);
    #endif
    #if REDUCE_GLARE_INTENSITY
        vec3 surfaceWithoutGlare;
    #endif

    #if DAYLIGHT_CYCLE
    if (uboSky.enabled) {
        vec3 n = normals;
        n.y *= 0.16;
        // Compress exaggerated slopes before they reflect below the water plane.
        // For slope p = n.xz / -n.y, the reflected ray points upward when
        // v * (1 - dot(p, p)) + 2 * dot(p, viewDir.xz) > 0, where v is view elevation.
        // Resolve that slope limit, then approach it smoothly instead of pinning rays
        // to the horizon. This also keeps the normal facing an above-water camera.
        float viewElevation = -viewDir.y;
        if (viewElevation > 0.0) {
            float towardCamera = dot(n.xz, viewDir.xz);
            float slopeLimitRatio = sqrt(towardCamera * towardCamera + viewElevation * viewElevation * dot(n.xz, n.xz)) - towardCamera;
            slopeLimitRatio /= viewElevation * -n.y;
            n.xz *= inversesqrt(1.0 + slopeLimitRatio * slopeLimitRatio);
        } else {
            n = vec3(0, -1, 0);
        }
        n = normalize(n);
        vec3 skyNormal = normalize(normals * vec3(0.2, 1, 0.2));
        vec3 skyViewDir = reflect(-viewDir, skyNormal);
        vec3 diskViewDir = reflect(-viewDir, n);
        // Reduce only nebula emission to suit the deliberately strong legacy reflection.
        const float nebulaReflectionStrength = .25f;
        SkySample sky = sampleSky(
            skyViewDir,
            diskViewDir,
            false,
            false,
            n,
            0.07,
            nebulaReflectionStrength,
            WATER_SPECULAR_REFLECTIONS != 0,
            0
        );

        #if !DECOUPLE_WATER_FROM_SKY_COLOR
            surfaceColor = linearToSrgb(sky.background * finalFresnel);
            #if REDUCE_GLARE_INTENSITY
                surfaceWithoutGlare = linearToSrgb(max(sky.background - sky.glare, vec3(0.0)) * finalFresnel);
            #endif
        #endif

        #if WATER_SPECULAR_REFLECTIONS
            // Only the active source has a shadow map. Do not shadow the other disk
            // with unrelated geometry projected along the active light's direction.
            vec3 sunDir = uboSky.sunDir * vec3(1, -1, 1);
            vec3 moonDir = uboSky.moonDir * vec3(1, -1, 1);
            bool moonOwnsShadowMap = dot(uboGlobal.lightDir, moonDir) > dot(uboGlobal.lightDir, sunDir);
            vec3 shadowedReflection = moonOwnsShadowMap ?
                sky.moon * uboSky.moonReflectionVisibility * uboSky.moonShadowVisibility : sky.sun;
            float reflectionShadow = 0.0;
            if (any(greaterThan(shadowedReflection, vec3(0.0))))
                reflectionShadow = sampleShadowMap(shadowReceiver, vec3(0, -1, 0), false, false, false);
            celestialReflection =
                sky.sun * (moonOwnsShadowMap ? 1.0 : 1.0 - reflectionShadow) +
                sky.moon * uboSky.moonReflectionVisibility *
                    (moonOwnsShadowMap ? 1.0 - reflectionShadow * uboSky.moonShadowVisibility : 1.0);
        #endif
    }
    #if !DECOUPLE_WATER_FROM_SKY_COLOR
        // When not decoupling the water color, only run the old calculation when the sky is disabled
        else
    #endif
    #endif // DAYLIGHT_CYCLE
    if (finalFresnel < 0.5) {
        surfaceColor = mix(uboGlobal.waterColorDark, uboGlobal.waterColorMid, finalFresnel * 2);
    } else {
        surfaceColor = mix(uboGlobal.waterColorMid, uboGlobal.waterColorLight, (finalFresnel - 0.5) * 2);
    }

    #if REDUCE_GLARE_INTENSITY
        #if DAYLIGHT_CYCLE && !DECOUPLE_WATER_FROM_SKY_COLOR
        if (!uboSky.enabled)
        #endif
            surfaceWithoutGlare = surfaceColor;
        vec4 waterWithoutGlare;
    #endif

    vec4 water;
    // Distance to land per vertex. See SceneContext for the shore data layout.
    ivec3 shoreDistances = fAlphaBiasHsl & SHORE_DISTANCE_STEPS;
    #if IMPROVED_SHORE_FOAM
    float foamAmount = 0;
    // Skip faces beyond the furthest foam effect. The check is uniform per face, so texture derivatives stay valid.
    // Cast, since max is overloaded for ints and floats, making whole number settings ambiguous
    float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * SHORE_DISTANCE_STEPS));
    if (waterType.hasFoam != 0 && any(lessThan(shoreDistances, ivec3(foamReachSteps)))) {
        // Distance to the shore in tiles
        float shoreDist = dot(fTexBlend, vec3(shoreDistances)) / SHORE_DISTANCE_STEPS * SHORE_DISTANCE_MAX;
        // Blending corners gives 0 everywhere between two shores, like in a one tile wide channel. On square tiles
        // touching land, measure to the land corners and sides within the tile instead. WATER_SQUARE_TILE_BIT on the
        // second vertex marks a square tile, and on the first the sw triangle (sw, se, nw) rather than (ne, nw, se).
        if ((fAlphaBiasHsl[1] & WATER_SQUARE_TILE_BIT) != 0 && any(equal(shoreDistances, ivec3(0)))) {
            bool swTriangle = (fAlphaBiasHsl[0] & WATER_SQUARE_TILE_BIT) != 0;
            // Position within the tile, from (0, 0) at sw to (1, 1) at ne
            vec2 local = swTriangle ?
                vec2(fTexBlend.y, fTexBlend.z) :
                vec2(fTexBlend.x + fTexBlend.z, fTexBlend.x + fTexBlend.y);
            vec2 corners[3] = vec2[](vec2(0, 0), vec2(1, 0), vec2(0, 1));
            if (!swTriangle)
                corners = vec2[](vec2(1, 1), vec2(0, 1), vec2(1, 0));
            shoreDist = SHORE_DISTANCE_MAX;
            for (int i = 0; i < 3; i++)
                if (shoreDistances[i] == 0)
                    shoreDist = min(shoreDist, distance(local, corners[i]));
            // Sides bordering land: west, east, south, north
            int landSides = fAlphaBiasHsl[0] >> SHORE_SIDES_SHIFT & 15;
            if ((landSides & 1) != 0)
                shoreDist = min(shoreDist, local.x);
            if ((landSides & 2) != 0)
                shoreDist = min(shoreDist, 1.0 - local.x);
            if ((landSides & 4) != 0)
                shoreDist = min(shoreDist, local.y);
            if ((landSides & 8) != 0)
                shoreDist = min(shoreDist, 1.0 - local.y);
        }
        // Warp the distance with drifting noise to hide tile and triangle edges
        float edgeNoise = noise(worldUvs(0.4) + uboGlobal.elapsedTime * vec2(0.03, -0.02));
        shoreDist += (edgeNoise - 0.5) * SHORE_FOAM_EDGE_NOISE;

        // Direction towards land. Blending vectors rather than angles avoids wrapping, and weakens it where
        // vertices disagree, like midway across a river.
        vec3 shoreAngles = vec3(fAlphaBiasHsl >> SHORE_DIRECTION_SHIFT & SHORE_DIRECTION_STEPS - 1) *
            (TAU / SHORE_DIRECTION_STEPS);
        vec2 shoreDir = vec2(dot(fTexBlend, cos(shoreAngles)), dot(fTexBlend, sin(shoreAngles)));
        // The nearest point on the shore, in world tiles. It stays the same as a wave travels in towards it, so patterns
        // based on it vary along the shore without shifting as the waves move.
        vec2 towardsShore = length(shoreDir) > 0.1 ? normalize(shoreDir) : vec2(0);
        vec2 shorePoint = -worldUvs(1.0) + towardsShore * shoreDist;

        // Only form waves where there's room, so not in small ponds or narrow channels
        vec3 enclosure = vec3(fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS) / WATER_ENCLOSURE_STEPS;
        float waterRoom = (1.0 - dot(fTexBlend, enclosure)) * WATER_ROOM_MAX;
        float roomForWaves = smoothstep(SHORE_WAVE_MIN_ROOM, SHORE_WAVE_FULL_ROOM, waterRoom);

        // World-space foam, warped by drifting noise so it churns in place
        vec2 foamWarp = noise2(worldUvs(1.5) + uboGlobal.elapsedTime * vec2(0.07, 0.03)) - 0.5;
        foamWarp = foamWarp * SHORE_FOAM_CHURN + uvFlow * flowMapStrength;
        vec2 foamUv = worldUvs(SHORE_FOAM_SCALE) + foamWarp;

        // Sway the foam towards the shore and back, with the same timing everywhere so it doesn't stretch.
        // Each cycle is a sway of random strength, then a lull of random length.
        float swayCycle = uboGlobal.elapsedTime / SHORE_FOAM_SWAY_PERIOD;
        float swayIndex = floor(swayCycle);
        float swayT = fract(swayCycle);
        float lull = hash11(swayIndex) * SHORE_FOAM_SWAY_MAX_LULL;
        float swayStrength;
        if (swayT < 1.0 - lull) {
            swayT /= 1.0 - lull;
            swayStrength = mix(0.6, 1.0, hash11(swayIndex + 500.0));
        } else {
            // Drift slowly during the lull, sized to keep about the same speed whatever its length
            swayT = (swayT - (1.0 - lull)) / lull;
            swayStrength = lull / SHORE_FOAM_SWAY_MAX_LULL * SHORE_FOAM_LULL_DRIFT;
        }
        // In, then out, starting and stopping smoothly
        float sway = sin(swayT * TAU) * sin(swayT * PI) * swayStrength;
        // World UVs are negated, so adding the offset moves the pattern towards the shore
        foamUv += shoreDir * (sway * SHORE_FOAM_SWAY / SHORE_FOAM_SCALE);

        // Blend three copies of the texture a third of a period apart, so the pattern keeps changing in place.
        // Each fades in and out with a sin² weight, jumping to a random offset while invisible. The weights always
        // sum to 1.5 and their squares to 9/8, so brightness and contrast stay constant.
        float evolveCycle = uboGlobal.elapsedTime / SHORE_FOAM_EVOLVE_PERIOD;
        float foamSum = 0;
        for (int i = 0; i < 3; i++) {
            float copyCycle = evolveCycle + i / 3.0;
            float weight = sin(fract(copyCycle) * PI);
            vec2 offset = hash21(floor(copyCycle) + i * 1000.0) * 4.0;
            foamSum += weight * weight * texture(textureArray, vec3(foamUv + offset, MAT_WATER_FOAM.colorMap)).r;
        }
        // Blending flattens contrast by sqrt(9/8) / 1.5, so restore it around the texture's average brightness
        const float foamAverage = 0.58;
        float foamTex = (foamSum / 1.5 - foamAverage) * (1.5 / sqrt(9.0 / 8.0)) + foamAverage;
        // The texture mostly ranges from 0.4 to 0.7
        foamMask = smoothstep(0.1, 0.65, foamTex);

        // Waves travelling towards the shore, fading in from nothing as they come in, over solid foam along it.
        // Skipped where they'd be invisible.
        float waveOpacity = saturate((SHORE_WAVE_REACH - shoreDist) / max(SHORE_WAVE_REACH - SHORE_WAVE_FULL_OPACITY, 0.01));
        waveOpacity = pow(waveOpacity, SHORE_WAVE_OPACITY_CURVE);
        float wave = 0.0;
        if (roomForWaves > 0.0 && waveOpacity > 0.0)
            wave = shoreWaves(shoreDist, shorePoint, foamMask, roomForWaves);
        float shoreBand = 1.0 - smoothstep(0.05, SHORE_FOAM_REACH, shoreDist);

        // Erode the texture by the coverage, so thin foam breaks into patches
        float foamCoverage = max(shoreBand, wave);
        foamAmount = smoothstep(1.0 - foamCoverage, 1.0 - foamCoverage + SHORE_FOAM_SOFTNESS, foamMask);
        // The shore foam keeps its own strength, but only where it's the stronger foam, so waves crossing it keep fading
        float bandDominance = smoothstep(-0.15, 0.15, shoreBand - wave);
        foamAmount *= SHORE_FOAM_STRENGTH * mix(waveOpacity, max(waveOpacity, shoreBand), bandDominance);
    }
    #else
        // The original foam only checks whether each vertex is on the shore
        float shoreLineMask = 1 - dot(fTexBlend, vec3(notEqual(shoreDistances, ivec3(0))));
        float foamAmount = min(shoreLineMask, 0.8);
        foamAmount = clamp(pow(1.0 - ((1.0 - foamAmount) / 0.7), 3), 0.0, 1.0) * waterType.hasFoam;
    #endif
    float opacityFresnel = max(finalFresnel, 1.0 - dot(viewDir, vec3(0, -1, 0)));

    #if REDUCE_GLARE_INTENSITY
    // With glare reduction enabled, first evaluate the glare-free baseline, then the normal water.
    for (int pass = 0; pass <= REDUCE_GLARE_INTENSITY; pass++)
    #endif
    {
        vec3 surface = surfaceColor;
        vec3 directionalSpecular = lightSpecularOut;
        #if REDUCE_GLARE_INTENSITY
            if (pass == 0) {
                surface = surfaceWithoutGlare;
                directionalSpecular = vec3(0.0);
            }
        #endif

        vec3 surfaceColorOut = surface * max(combinedSpecularStrength, 0.2);


        // apply lighting
        vec3 compositeLight =
            ambientLightOut + lightOut + directionalSpecular + skyLightOut + lightningOut +
            underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

        vec3 baseColor = waterType.surfaceColor * compositeLight;

        // Hacks to improve visibility between different sailing water types, based on unshadowed luminance
        bool isHazardous = waterType.fresnelAmount == 0.86;
        float fresnelAmount = isHazardous ? 0.85 : waterType.fresnelAmount;
        baseColor = mix(baseColor, surface, fresnelAmount);
        if (isHazardous) {
            vec3 unshadowedBaseColor = waterType.surfaceColor *
                (ambientLightOut + unshadowedLightOut + skyLightOut + underglowOut);
            float waterLuminance = linearSrgbLuminance(srgbToLinear(max(unshadowedBaseColor, vec3(0))));
            float skyLuminance = linearSrgbLuminance(srgbToLinear(max(surface, vec3(0))));
            float reflectionDominance = skyLuminance / max(waterLuminance, 1e-6);
            float reflectionAmount = mix(fresnelAmount, fresnelAmount * 0.65, smoothstep(1.0, 4.0, reflectionDominance));
            baseColor += (fresnelAmount - reflectionAmount) * (unshadowedBaseColor - surface);
        }
        if (fresnelAmount == 0.85)
            baseColor *= .75f; // Sailing hack

        #if IMPROVED_SHORE_FOAM
            vec3 foamColor = waterType.foamColor * mix(0.5, 0.7, foamMask) * compositeLight;
            // Weight by luminance rather than red, re-encoded to sRGB so neutral colors match. Skipped without foam.
            float litFoamAmount = 0;
            if (foamAmount > 0)
                litFoamAmount = foamAmount * linearToSrgb(linearSrgbLuminance(srgbToLinear(saturate(foamColor))));
        #else
            vec3 foamColor = waterType.foamColor * foamMask * compositeLight;
            float litFoamAmount = foamAmount * foamColor.r;
        #endif
        baseColor = mix(baseColor, foamColor, litFoamAmount);
        vec3 specularComposite = mix(directionalSpecular, vec3(0.0), litFoamAmount);
        baseColor += pointLightsSpecularOut + directionalSpecular / 3;

        float alpha = max(waterType.baseOpacity, max(litFoamAmount, max(opacityFresnel, length(specularComposite / 3))));

        if (waterType.isFlat) {
            baseColor = mix(waterType.depthColor, baseColor, alpha);
            alpha = 1;
        }

        water = vec4(baseColor, alpha);
        #if REDUCE_GLARE_INTENSITY
            if (pass == 0)
                waterWithoutGlare = water;
        #endif
    }

    #if DAYLIGHT_CYCLE && WATER_SPECULAR_REFLECTIONS
        if (uboSky.enabled) {
            float reflectionLuminance = linearSrgbLuminance(celestialReflection);
            if (reflectionLuminance > 0.0) {
                celestialReflection *= 4.4; // looks about right
                // Approximate the reflection's dominance over at most 18% linear-gray
                // underwater light. Bright HDR highlights approach opaque before source-alpha
                // blending; this is an artistic approximation to the actual sRGB composite.
                float reflectionOpacity = reflectionLuminance / (reflectionLuminance + 0.18);
                water.a = mix(water.a, 1.0, reflectionOpacity);
                // Reconstruct approximate linear light, add the shadowed reflection, then tonemap them together.
                vec3 light = inverse_tonemap_hue_preserving(srgbToLinear(water.rgb));
                light += celestialReflection;
                light = tonemap_hue_preserving(light);
                light = softClipColor(light);
                light = linearToSrgb(light);
                water.rgb = light;
            }
        }
    #endif

    #if REDUCE_GLARE_INTENSITY
        water.rgb = linearToSrgb(reduceGlare(srgbToLinear(water.rgb), srgbToLinear(waterWithoutGlare.rgb)));
        // Undo the reflection's opacity boost as it is suppressed, too.
        water.a = mix(waterWithoutGlare.a, water.a, uboGlobal.glareIntensity);
    #endif
    return water;
}

#endif // WATER_PASS || LEGACY_RENDERER

void sampleUnderwater(inout vec3 outputColor, WaterType waterType, float depth, float lightDotNormals) {
    // underwater terrain
    float lowestColorLevel = 500;
    float midColorLevel = 150;
    float surfaceLevel = IN.position.y - depth; // e.g. -1600

    if (depth < midColorLevel) {
        outputColor *= mix(vec3(1), waterType.depthColor, translateRange(0, midColorLevel, depth));
    } else if (depth < lowestColorLevel) {
        outputColor *= mix(waterType.depthColor, vec3(0), translateRange(midColorLevel, lowestColorLevel, depth));
    } else {
        outputColor = vec3(0);
    }

    if (uboGlobal.underwaterCaustics) {
        const float scale = 1.75;
        const float maxCausticsDepth = 128 * 4;

        vec2 causticsUv = worldUvs(scale);

        float depthMultiplier = (IN.position.y - surfaceLevel - maxCausticsDepth) / -maxCausticsDepth;
        depthMultiplier *= depthMultiplier;

        causticsUv *= .75;

        const ivec2 direction = ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(17) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, .005);

        vec3 causticsColor = uboGlobal.underwaterCausticsColor * uboGlobal.underwaterCausticsStrength;
        outputColor.rgb *= 1 + caustics * causticsColor * depthMultiplier * lightDotNormals * uboGlobal.lightStrength;
    }
}
