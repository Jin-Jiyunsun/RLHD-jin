#pragma once

#include <utils/celestial_projection.glsl>
#include <utils/starfield.glsl>

struct SkyGradient {
    vec3 sunDir;         // sun direction with the perceived-horizon offset applied
    float upAmount;      // how much the view is looking up (-viewDir.y)
    float sunSideBlend;  // 0 = facing away from sun, 1 = facing toward sun
    float zenithBlend;   // 0 = horizon, 1 = zenith
    float nightFade;     // 0 = deep night, 1 = sun at/above horizon
    vec3 color;          // linear sRGB gradient + sun glow (before haze/stars/moon)
};

// The camera makes the perceived horizon about 5° below astronomical 0°.
#define HORIZON_OFFSET 0.087

SkyGradient computeSkyGradient(vec3 viewDir) {
    SkyGradient g;

    g.sunDir = normalize(vec3(uboSky.sunDir.x, -uboSky.sunDir.y + HORIZON_OFFSET, uboSky.sunDir.z));
    g.upAmount = -viewDir.y;

    // Fade the sun-facing bias near vertical views to avoid pinching.
    vec2 viewHoriz = vec2(viewDir.x, viewDir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(g.sunDir.x, 0.0, g.sunDir.z));

    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    g.sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    g.zenithBlend = smoothstep(-0.1, 0.7, g.upAmount);

    float sunAltitude = clamp(uboSky.sunDir.y, 0.0, 1.0);
    float daytimeFactor = smoothstep(0.0, 0.64, sunAltitude);
    float dimFadeout = smoothstep(0.0, 0.34, sunAltitude);
    float darkSideDim = mix(0.7, 1.0, dimFadeout);
    vec3 darkSideColor = mix(uboSky.zenithColor * darkSideDim, uboSky.horizonColor, daytimeFactor);
    vec3 sunSideColor = uboSky.horizonColor;

    // Retain residual twilight until the sun reaches astronomical night at -18 degrees.
    g.nightFade = smoothstep(sin(radians(-18.0)), 0.0, uboSky.sunDir.y) * (1.0 - uboSky.customGradient);

    vec3 horizonColor = mix(darkSideColor, sunSideColor, g.sunSideBlend);
    horizonColor = mix(uboSky.zenithColor, horizonColor, g.nightFade);

    g.color = mix(horizonColor, uboSky.zenithColor, g.zenithBlend);

    // Custom skies have a symmetric horizon band independent of the sun's direction.
    vec3 customColor = mix(uboSky.horizonColor, uboSky.zenithColor,
        smoothstep(0.0, uboSky.horizonWidth, abs(g.upAmount)));
    g.color = mix(g.color, customColor, uboSky.customGradient);

    // Use multiply/sqrt equivalents of pow for the glow falloffs.
    // Follow the sun while its disk is still drawn, then keep scattered sunlight near the
    // perceived horizon. Its color and disappearance are authored in sunGlow.
    vec3 glowDir = normalize(vec3(uboSky.sunDir.x, -max(sin(radians(-4.0)), uboSky.sunDir.y) + HORIZON_OFFSET, uboSky.sunDir.z));
    float sunDot = dot(viewDir, glowDir);
    if (sunDot > 0.0) {
        float s2 = sunDot * sunDot;
        float s4 = s2 * s2;
        float s8 = s4 * s4;
        float midGlow = s8 * 0.15;
        float outerGlow = s2 * sunDot * sqrt(sunDot) * 0.08;
        // Measure the tight lobes like the disk, so perspective near the screen edges
        // cannot stretch them off-center from it. That projection is linearized around the
        // sun, so fall back to the plain angle away from it: at wide FOVs it still reports
        // a small angle where sunDot reaches zero, which would leave a visible edge.
        float tightDot = sunDot;
        float correction = smoothstep(0.5, 0.8, sunDot);
        if (correction > 0.0)
            tightDot = mix(sunDot, max(dot(celestialViewDirection(viewDir, glowDir), glowDir), 0.0), correction);
        float t2 = tightDot * tightDot;
        float t4 = t2 * t2;
        float t8 = t4 * t4;
        float t16 = t8 * t8;
        float t32 = t16 * t16;
        float t128 = t32 * t32; t128 = t128 * t128;
        float coreGlow = t128 * 0.4;
        float innerGlow = t32 * 0.25;
        // The sunGlow keyframes dim toward the horizon to redden the disk and sky. Restore
        // the tight glow's presence at dawn and dusk without changing the disk's brightness
        // or widening the broad lobes across the horizon.
        float lowSunGlowBoost = 1.0 + 2.5 * (1.0 - smoothstep(0.0, sin(radians(40.0)), uboSky.sunDir.y));
        g.color += uboSky.sunColor * ((coreGlow + innerGlow) * lowSunGlowBoost + midGlow + outerGlow);
    }

    return g;
}

vec3 blendSkyBackground(vec3 gradient, vec3 background, float amount) {
    // Keep an authored gradient visible behind stars and nebulae, including below the horizon.
    return mix(gradient, background + gradient * uboSky.customGradient, amount);
}

float nightSkyBlend(SkyGradient sky) {
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    return pow(baseProgress, mix(0.4, 0.9, sunProximity));
}

float nightSkyHorizonFade(float upAmount, float horizonShift) {
    return smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, upAmount);
}

// Broad visible sky color, excluding celestial disks, aurorae, and individual stars.
vec3 visibleSkyColor(SkyGradient sky, vec3 viewDir, float elapsedSeconds) {
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);
    float amount = nightSkyBlend(sky) * nightSkyHorizonFade(sky.upAmount, horizonShift);
    if (amount <= 0.001)
        return sky.color;
    return blendSkyBackground(sky.color, nightSkyBackground(viewDir, elapsedSeconds), amount);
}
