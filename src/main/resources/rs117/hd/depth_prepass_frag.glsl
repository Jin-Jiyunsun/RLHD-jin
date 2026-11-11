#version 330

// Player depth pre-pass. Unflagged faces are discarded here, since culling them in the vertex shader would break
// gl_Position invariance with the colour pass
#define TERRAIN_FLAG_PLAYER_DEPTH_PREPASS 30 // Must match SceneUploader

flat in ivec3 fTerrainData;

void main() {
    if ((fTerrainData[0] >> TERRAIN_FLAG_PLAYER_DEPTH_PREPASS & 1) == 0)
        discard;
}
