/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
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
#pragma once

#include MAX_CHARACTER_POSITION_COUNT

struct UBOCompute {
  // Camera uniforms
  float cameraYaw;
  float cameraPitch;
  int centerX;
  int centerY;
  int zoom;
  float cameraX; float cameraY; float cameraZ; // Here be dragons on macOS if converted to float3

  // Wind uniforms
  float windDirectionX;
  float windDirectionZ;
  float windStrength;
  float windCeiling;
  float windOffset;

  int characterPositionCount;
  float3 characterPositions[MAX_CHARACTER_POSITION_COUNT];
};

struct shared_data {
  int totalNum[12];       // number of faces with a given priority
  int totalDistance[12];  // sum of distances to faces of a given priority
  int totalMappedNum[18]; // number of faces with a given adjusted priority
  int min10;              // minimum distance to a face of priority 10
  int renderPris[0];     // priority for face draw order
};

struct ModelInfo {
  int offset;   // offset into buffer
  int uvOffset; // offset into uv buffer
  int size;     // length in faces
  int idx;      // write idx in target buffer
  int flags;    // hillskew, plane, orientation
  int x;        // scene position x
  int y;        // scene position y & model height
  int z;        // scene position z
};

struct VertexData {
  float x;
  float y;
  float z;
  int ahsl;
};

struct UVData {
    float u;
    float v;
    float w;
    int materialData;
};

struct ObjectWindSample {
    float3 direction;
    float3 displacement;
    float heightBasedStrength;
};
