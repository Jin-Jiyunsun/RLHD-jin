#version 330

// Player depth pre-pass, drawn depth-only from the player colour VAO. Faces are flagged during upload, and rejected
// here rather than in the vertex shader, so the vertex shader stays identical to the colour pass. Changing how it
// produces gl_Position would break its invariance, and with it the exact depth match between the passes.
#define TERRAIN_FLAG_PLAYER_DEPTH_PREPASS 30 // Must match SceneUploader

flat in ivec3 fTerrainData;

void main() {
    if ((fTerrainData[0] >> TERRAIN_FLAG_PLAYER_DEPTH_PREPASS & 1) == 0)
        discard;
}
