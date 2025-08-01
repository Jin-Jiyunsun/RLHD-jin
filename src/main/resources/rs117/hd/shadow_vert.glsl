/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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
#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 1) in int vHsl;
layout (location = 2) in vec3 vUv;
layout (location = 3) in int vMaterialData;
layout (location = 4) in vec4 vNormal;

#include <utils/constants.glsl>

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    // Pass to geometry shader
    flat out vec3 gPosition;
    flat out vec3 gUv;
    flat out int gMaterialData;
    flat out int gCastShadow;
    #if SHADOW_TRANSPARENCY
        flat out float gOpacity;
    #endif
#else
    #if SHADOW_TRANSPARENCY
        // Pass to fragment shader
        out float fOpacity;
    #endif
#endif

void main() {
    int terrainData = int(vNormal.w);
    int waterTypeIndex = terrainData >> 3 & 0x1F;
    float opacity = 1 - (vHsl >> 24 & 0xFF) / float(0xFF);

    float opacityThreshold = float(vMaterialData >> MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT & 0x3F) / 0x3F;
    if (opacityThreshold == 0)
        opacityThreshold = SHADOW_DEFAULT_OPACITY_THRESHOLD;

    bool isTransparent = opacity <= opacityThreshold;
    bool isGroundPlaneTile = (terrainData & 0xF) == 1; // plane == 0 && isTerrain
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;

    bool isShadowDisabled =
        isGroundPlaneTile ||
        isWaterSurfaceOrUnderwaterTile ||
        isTransparent;

    int shouldCastShadow = isShadowDisabled ? 0 : 1;

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        gPosition = vPosition;
        gUv = vec3(vUv);
        gMaterialData = vMaterialData;
        gCastShadow = shouldCastShadow;
        #if SHADOW_TRANSPARENCY
            gOpacity = opacity;
        #endif
    #else
        gl_Position = lightProjectionMatrix * vec4(vPosition, shouldCastShadow);
        #if SHADOW_TRANSPARENCY
            fOpacity = opacity;
        #endif
    #endif
}
