#pragma once

#include NEBULA_CLUSTER_COUNT

layout(std140) uniform UBOSky {
    bool enabled;
    vec3 zenithColor;
    vec3 horizonColor;
    vec3 sunColor;
    float customGradient;
    float horizonWidth;
    vec3 sunDir;
    vec3 celestialPole;
    float celestialRotation;

    vec3 moonDir;
    vec3 moonDiskColor;
    float moonIllumination;
    float moonReflectionVisibility;
    vec3 moonSurfaceLightDirection;
    vec2 moonLibration;

    vec3 fogColor;
    float fogDensity;
    vec3 groundFogLight;
    float visibility;
    float moonVisibility;
    float starVisibility;
    float nebulaVisibility;
    float auroraVisibility;

    float moonSizeMult;

    float starHorizonHeight;

    // Moon glint placement on water, resolved per frame by SkyRenderer.
    float moonGlintShift; // horizontal screen shift, in NDC
    float moonGlintFacing; // 1 while the moon is in front of the camera's facing, else 0

    vec4 nebulaClusters[NEBULA_CLUSTER_COUNT];
} uboSky;
