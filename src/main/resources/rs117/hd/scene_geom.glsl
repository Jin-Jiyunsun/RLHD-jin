/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
#version 330

#include <uniforms/global.glsl>

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

#include <utils/constants.glsl>
#define USE_VANILLA_UV_PROJECTION
#include <utils/uvs.glsl>
#include <utils/color_utils.glsl>

in vec3 gPosition[3];
in int gHsl[3];
in vec3 gUv[3];
in int gMaterialData[3];
in vec4 gNormal[3];

flat out ivec3 vHsl;
flat out ivec3 vMaterialData;
flat out ivec3 vTerrainData;
flat out vec3 T;
flat out vec3 B;

out FragmentData {
    vec3 position;
    vec2 uv;
    vec3 normal;
    vec3 texBlend;
} OUT;

void main() {
    vec3 vUv[3];

    // MacOS doesn't allow assigning these arrays directly.
    // One of the many wonders of Apple software...
    for (int i = 0; i < 3; i++) {
        vHsl[i] = gHsl[i];
        vUv[i] = gUv[i];
        vMaterialData[i] = gMaterialData[i];
        vTerrainData[i] = int(gNormal[i].w);
    }

    computeUvs(vMaterialData[0], vec3[](gPosition[0], gPosition[1], gPosition[2]), vUv);

    // Calculate tangent-space vectors
    mat2 triToUv = mat2(
        vUv[1].xy - vUv[0].xy,
        vUv[2].xy - vUv[0].xy
    );
    if (determinant(triToUv) == 0)
        triToUv = mat2(1);
    mat2 uvToTri = inverse(triToUv) * -1; // Flip UV direction, since OSRS UVs are oriented strangely
    mat2x3 triToWorld = mat2x3(
        gPosition[1] - gPosition[0],
        gPosition[2] - gPosition[0]
    );
    mat2x3 TB = triToWorld * uvToTri; // Preserve scale in order for displacement to interact properly with shadow mapping
    T = TB[0];
    B = TB[1];
    vec3 N = normalize(cross(triToWorld[0], triToWorld[1]));

    for (int i = 0; i < 3; i++) {
        // Flat normals must be applied separately per vertex
        vec3 normal = gNormal[i].xyz;
        OUT.position = gPosition[i];
        OUT.uv = vUv[i].xy;
        #if FLAT_SHADING
        OUT.normal = N;
        #else
        OUT.normal = length(normal) == 0 ? N : normalize(normal);
        #endif
        OUT.texBlend = vec3(0);
        OUT.texBlend[i] = 1;
        gl_Position = projectionMatrix * vec4(OUT.position, 1);
        EmitVertex();
    }

    EndPrimitive();
}
