/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

#include UI_SCALING_MODE

#define SAMPLING_MITCHELL 1
#define SAMPLING_CATROM 2
#define SAMPLING_XBR 3

#include <uniforms/global.glsl>
#include <uniforms/ui.glsl>

uniform sampler2D uiTexture;

#include <scaling/bicubic.glsl>
#include <utils/constants.glsl>
#include <utils/color_blindness.glsl>

#if UI_SCALING_MODE == SAMPLING_XBR
#include <scaling/xbr_lv2_frag.glsl>

in XBRTable xbrTable;
#endif

in vec2 fUv;

out vec4 FragColor;

vec4 alphaBlend(vec4 src, vec4 dst) {
    return vec4(
        src.rgb + dst.rgb * (1.0f - src.a),
        src.a + dst.a * (1.0f - src.a)
    );
}

void main() {
    vec4 c;
    #if UI_SCALING_MODE == SAMPLING_MITCHELL || UI_SCALING_MODE == SAMPLING_CATROM
        c = textureCubic(uiTexture, fUv);
    #elif UI_SCALING_MODE == SAMPLING_XBR
        c = textureXBR(uiTexture, fUv, xbrTable, ceil(1.0 * targetDimensions.x / sourceDimensions.x));
    #else // NEAREST or LINEAR, which uses GL_TEXTURE_MIN_FILTER/GL_TEXTURE_MAG_FILTER to affect sampling
        c = texture(uiTexture, fUv);
    #endif

    c = alphaBlend(c, alphaOverlay);
    c.rgb = colorBlindnessCompensation(c.rgb);

    FragColor = c;
}
