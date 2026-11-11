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

#define SHORE_FOAM_STRENGTH 0.31   // peak foam opacity before lighting
#define SHORE_FOAM_SOFTNESS 0.3    // width of the eroded foam edge in texture space
#define SHORE_FOAM_EDGE_NOISE 0.35 // how far the foam line wanders, in tiles
#define SHORE_FOAM_REACH 0.8       // how far the solid shore foam fades out to between waves, in tiles
#define SHORE_HAZE_STRENGTH 0.25   // strength of the cloudy layer beneath the foam patches, relative to full foam
#define SHORE_HAZE_REACH 1.5       // how far the cloudy layer fades out from the shore, in tiles
// How far the foam pattern gets pushed around, in foam texture repeats. The warp noise cells are about one texture
// repeat wide, so this is also roughly how much the foam stretches; much above 0.1 visibly distorts its shapes.
#define SHORE_FOAM_CHURN 0.08
#define SHORE_FOAM_SCALE 1.0       // tiles per foam texture repeat; lower packs the foam patches more densely
#define SHORE_FOAM_EVOLVE_PERIOD 2.2 // seconds for each foam pattern to fade in and back out
#define SHORE_WAVE_PERIOD 4.5      // seconds between foam waves reaching the shore
#define SHORE_WAVE_DENSITY 1.6     // foam waves per tile
#define SHORE_WAVE_STRENGTH 0.5
#define SHORE_WAVE_REACH 2.0       // how far out from the shore foam waves start forming, in tiles
#define SHORE_SURGE_REACH 0.2      // how far the shore foam pushes out when a wave arrives, in tiles
#define SHORE_FOAM_SWAY 0.08       // how far the foam sways in and out from its resting place, in tiles
// Seconds for the foam to sway in and back out, including the lull after it. A sway takes between
// (1 - SHORE_FOAM_SWAY_MAX_LULL) and 1 times this, so the quickest sway here lasts 7.2 seconds.
#define SHORE_FOAM_SWAY_PERIOD 12.0
#define SHORE_FOAM_SWAY_MAX_LULL 0.4 // longest lull between sways, as a fraction of the period
#define SHORE_FOAM_LULL_DRIFT 0.25 // how far the foam drifts during the longest lull, relative to a full sway

#if DAYLIGHT_CYCLE
    #include <utils/sky_sampling.glsl>
#endif

// Two independent channels of smooth value noise for the cost of one, by hashing each lattice corner to a vec2
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

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    vec2 anchorUv1 = worldUvs(3).yx;
    vec2 anchorUv2 = worldUvs(5);
    vec2 anchorUv3 = worldUvs(7);
    vec2 uv1 = anchorUv1 - animationFrame(28 * waterType.duration);
    vec2 uv2 = anchorUv2 + animationFrame(24 * waterType.duration);
    vec2 uv3 = anchorUv3 + animationFrame(21 * waterType.duration);
    vec2 uvFoam = IN.uv;

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;
    uvFoam += uvFlow * flowMapStrength;

    // get diffuse textures
    vec3 n1 = linearToSrgb(texture(textureArray, vec3(uv1, waterType.normalMap)).xyz);
    vec3 n2 = linearToSrgb(texture(textureArray, vec3(uv2, waterType.normalMap)).xyz);
    vec3 n3 = linearToSrgb(texture(textureArray, vec3(uv3, waterType.normalMap)).xyz);
    #if IMPROVED_SHORE_FOAM
        float foamMask = 0;
    #else
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
    // Each water surface vertex stores its distance to the nearest land in its lightness,
    // from 0 on the shore to 127 at SHORE_DISTANCE_MAX tiles or further
    ivec3 shoreDistances = fAlphaBiasHsl & 127;
    #if IMPROVED_SHORE_FOAM
    float foamAmount = 0;
    // Only faces with a vertex within reach of the furthest foam effect, allowing for the edge noise, can have foam.
    // Faces further out skip all of it. The check uses flat per-face data, so the branch is uniform across each face
    // and the texture derivatives inside remain valid.
    float foamReach = max(max(SHORE_WAVE_REACH, SHORE_HAZE_REACH), SHORE_FOAM_REACH + SHORE_SURGE_REACH);
    int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * 127.0));
    if (waterType.hasFoam != 0 && any(lessThan(shoreDistances, ivec3(foamReachSteps)))) {
        // Distance from the shore in tiles, warped by slowly drifting noise to hide the tile & triangle edges
        float shoreDist = dot(fTexBlend, vec3(shoreDistances)) / 127.0 * SHORE_DISTANCE_MAX;
        // Distances are only known at the corners, so between two shores, such as in a channel one tile wide, every
        // corner is on the shore and blending them gives 0 across the whole tile. For square tiles with a corner on
        // the shore, work out the distance within the tile instead, to its land corners and the sides bordering land.
        // Bit 15 (SceneContext.WATER_SQUARE_TILE_BIT) on the second vertex marks a square tile, and on the first vertex
        // marks the south-west triangle (sw, se, nw) rather than the north-east one (ne, nw, se).
        if ((fAlphaBiasHsl[1] & 1 << 15) != 0 && any(equal(shoreDistances, ivec3(0)))) {
            bool swTriangle = (fAlphaBiasHsl[0] & 1 << 15) != 0;
            // Position within the tile, from 0 at its south-west corner to 1 at its north-east corner
            vec2 local = swTriangle ?
                vec2(fTexBlend.y, fTexBlend.z) :
                vec2(fTexBlend.x + fTexBlend.z, fTexBlend.x + fTexBlend.y);
            vec2 corner0 = swTriangle ? vec2(0, 0) : vec2(1, 1);
            vec2 corner1 = swTriangle ? vec2(1, 0) : vec2(0, 1);
            vec2 corner2 = swTriangle ? vec2(0, 1) : vec2(1, 0);
            float dist = SHORE_DISTANCE_MAX;
            if (shoreDistances.x == 0)
                dist = min(dist, distance(local, corner0));
            if (shoreDistances.y == 0)
                dist = min(dist, distance(local, corner1));
            if (shoreDistances.z == 0)
                dist = min(dist, distance(local, corner2));
            // Bits 11 to 14 hold the sides bordering land: west, east, south, north
            int landSides = fAlphaBiasHsl[0] >> 11 & 15;
            if ((landSides & 1) != 0)
                dist = min(dist, local.x);
            if ((landSides & 2) != 0)
                dist = min(dist, 1.0 - local.x);
            if ((landSides & 4) != 0)
                dist = min(dist, local.y);
            if ((landSides & 8) != 0)
                dist = min(dist, 1.0 - local.y);
            shoreDist = dist;
        }
        float edgeNoise = noise(worldUvs(0.4) + uboGlobal.elapsedTime * vec2(0.03, -0.02));
        shoreDist += (edgeNoise - 0.5) * SHORE_FOAM_EDGE_NOISE;

        // Direction towards land along the local X & Z axes, stored as an angle in the 4 bits above the distance.
        // Each vertex's direction is blended here rather than its angle, which would wrap around. Where the vertices
        // disagree, such as midway across a narrow river, the blend shrinks, which also slows the scrolling there.
        vec3 shoreAngles = vec3(fAlphaBiasHsl >> 7 & 15) * (TAU / SHORE_DIRECTION_STEPS);
        vec2 shoreDir = vec2(dot(fTexBlend, cos(shoreAngles)), dot(fTexBlend, sin(shoreAngles)));

        // Timing of each wave reaching the shore. fract(shorePhase) is 0 as a wave arrives.
        float shorePhase = uboGlobal.elapsedTime / SHORE_WAVE_PERIOD + edgeNoise * 0.4;
        float shoreT = fract(shorePhase);
        float shoreWaveStrength = mix(0.5, 1.0, hash11(floor(shorePhase)));

        // World-space foam, so the pattern doesn't repeat per tile. Rather than scrolling layers, which read as
        // textures sliding past each other, the foam stays in place and is warped by a slowly drifting noise field,
        // so it churns and deforms as if pushed around by the water.
        vec2 foamWarp = noise2(worldUvs(1.5) + uboGlobal.elapsedTime * vec2(0.07, 0.03)) - 0.5;
        foamWarp = foamWarp * SHORE_FOAM_CHURN + uvFlow * flowMapStrength;
        // Make the pattern itself change constantly, like the caustics, by blending three copies of the foam texture
        // a third of a period apart. Each copy fades in and out with a sin² weight over one period, and jumps to a
        // new random offset at the instant its weight is zero, so foam forms and dissolves in place without anything
        // visibly sliding. Three sin² weights a third of a period apart always sum to 1.5, and their squares to 9/8,
        // so the blend's brightness and contrast never change: there's no cycle to see, only continuous change.
        // The random offsets also hide the texture repeating.
        vec2 foamUv = worldUvs(SHORE_FOAM_SCALE) + foamWarp;
        // Gently sway the foam in towards the shore and back out. The timing is the same everywhere,
        // so the whole pattern moves together instead of stretching, and the sway is small enough that the curving
        // shore direction barely bends it.
        // Moving the pattern by a world offset means sampling it that far behind, which is +offset in world UVs.
        // Each cycle is a sway of random strength, followed by a lull of random length, so it's less predictable.
        // The lull squeezes the sway into the rest of the cycle, so the sway's speed varies along with it.
        float swayCycle = uboGlobal.elapsedTime / SHORE_FOAM_SWAY_PERIOD;
        float swayIndex = floor(swayCycle);
        float swayT = fract(swayCycle);
        float lull = hash11(swayIndex) * SHORE_FOAM_SWAY_MAX_LULL;
        float sway;
        if (swayT < 1.0 - lull) {
            // In, then out, starting and stopping smoothly
            float t = swayT / (1.0 - lull);
            sway = sin(t * TAU) * sin(t * PI) * mix(0.6, 1.0, hash11(swayIndex + 500.0));
        } else {
            // During the lull, keep drifting in and out very slowly rather than stopping. Its size scales with the
            // lull's length, so the drift keeps roughly the same slow speed however long the lull lasts.
            float t = (swayT - (1.0 - lull)) / lull;
            sway = sin(t * TAU) * sin(t * PI) * (lull / SHORE_FOAM_SWAY_MAX_LULL) * SHORE_FOAM_LULL_DRIFT;
        }
        foamUv += shoreDir * (sway * SHORE_FOAM_SWAY / SHORE_FOAM_SCALE);
        float evolveCycle = uboGlobal.elapsedTime / SHORE_FOAM_EVOLVE_PERIOD;
        float foamSum = 0;
        for (int i = 0; i < 3; i++) {
            float copyCycle = evolveCycle + i / 3.0;
            float copyPhase = fract(copyCycle);
            float weight = sin(copyPhase * PI);
            weight *= weight;
            vec2 seed = hash21(floor(copyCycle) + i * 1000.0);
            vec2 offset = seed * 4.0;
            foamSum += weight * texture(textureArray, vec3(foamUv + offset, MAT_WATER_FOAM.colorMap)).r;
        }
        // Averaging unrelated patterns flattens their contrast by the normalized weights' length, sqrt(9/8) / 1.5,
        // which is constant. Undo it around the texture's average brightness (0.58) to keep the original contrast.
        const float foamAverage = 0.58;
        float foamTex = (foamSum / 1.5 - foamAverage) * (1.5 / sqrt(9.0 / 8.0)) + foamAverage;
        // The texture mostly ranges from 0.4 to 0.7, so a low start keeps its darkest blotches from emptying out
        foamMask = smoothstep(0.1, 0.65, foamTex);

        // Waves rolling in toward the shore. The phase increases with time and with distance from the shore,
        // so each crest moves shoreward. Within one cycle, fract(phase) is 0 at the crest's shoreward front and
        // grows behind it. Time isn't wrapped here, so each wave keeps its integer phase as it travels.
        float wavePhase = shoreDist * SHORE_WAVE_DENSITY + uboGlobal.elapsedTime / SHORE_WAVE_PERIOD + edgeNoise * 0.4;
        float waveT = fract(wavePhase);
        // Sharp leading edge, then a long trail of foam left behind the crest, which the erosion below breaks up
        float waveTrail = 1.0 - waveT;
        float wave = smoothstep(0.0, 0.06, waveT) * waveTrail * waveTrail * waveTrail;
        // Give each wave its own strength, so they arrive in uneven sets instead of identical pulses
        wave *= mix(0.5, 1.0, hash11(floor(wavePhase)));
        wave *= 1.0 - smoothstep(0.3, SHORE_WAVE_REACH, shoreDist);

        // Solid foam hugging the shore, which surges outward as each wave arrives, then slowly draws back
        float surgeTrail = 1.0 - shoreT;
        float surge = smoothstep(0.0, 0.1, shoreT) * surgeTrail * surgeTrail * shoreWaveStrength;
        float shoreBand = 1.0 - smoothstep(0.05, SHORE_FOAM_REACH + SHORE_SURGE_REACH * surge, shoreDist);

        float foamCoverage = saturate(max(shoreBand, wave * SHORE_WAVE_STRENGTH));

        // Erode the foam texture by the coverage, so thin foam breaks up into patches instead of fading uniformly
        foamAmount = smoothstep(1.0 - foamCoverage, 1.0 - foamCoverage + SHORE_FOAM_SOFTNESS, foamMask);
        // A mostly solid, cloudy layer beneath the patches, fading out from the shore. This keeps the overall milky
        // look of the original foam, so gaps between patches are cloudy water rather than bare.
        float haze = 1.0 - smoothstep(0.0, SHORE_HAZE_REACH, shoreDist);
        haze *= mix(0.8, 1.0, foamMask) * SHORE_HAZE_STRENGTH;
        // Layer the patches over the haze rather than taking the larger of the two, so even faint patch edges
        // still add to the haze and stay visible against it
        foamAmount = foamAmount + haze * (1.0 - foamAmount);
        foamAmount *= SHORE_FOAM_STRENGTH;
    }
    #else
        // The original foam only knew whether each vertex was on the shore
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
            // The original weighted foam by its sRGB-encoded red channel, which hides green and blue foam.
            // Use luminance instead, re-encoded to sRGB so it matches the red channel for neutral foam colors.
            float litFoamAmount = foamAmount * linearToSrgb(linearSrgbLuminance(srgbToLinear(saturate(foamColor))));
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
