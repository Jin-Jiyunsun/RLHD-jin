# Handoff: code cleanup of the water foam branch

Follows `wake-perf-brief.md`, `wake-perf-handoff.md` and `foam-perf-handoff.md` (same folder). Those cover how the
performance work was measured; this is about making the branch's code ready for review.

## Goal

Clean up the code the `shore-foam` branch adds to 117 HD, so it reads well to maintainers who didn't write it and
needs little manual cleanup before merging. **Nothing may change how the foam looks or what it costs.** Follow the
project guide at `C:\Users\Jin\Downloads\AGENTS.md` closely; it's the maintainers' own list of preferences and
recurring problems.

This is cleanup, not new work: no retuning, no new features, no further optimisation. If you find a bug or an
optimisation, write it down for the user rather than fixing it in the same pass.

## State (2026-11-17, about 00:45)

- Branch `shore-foam`, 33 commits on `master` (merge base `e69c97e2a`), last commit `19e464225`.
- 30 files changed, about 2,600 lines added. Most of it is in:
  - `src/main/resources/rs117/hd/utils/water.glsl` (1,380 lines, about 1,050 of them new)
  - `src/main/resources/rs117/hd/utils/ocean_wave_cells.glsl` (new, 194 lines, shared by the vertex and fragment
    shaders)
  - `src/main/java/rs117/hd/scene/BoatWakes.java` (new), `WaterSurfaceManager.java` (new),
    `ProceduralGenerator.java`, `SceneContext.java`, `WaterTypeManager.java`
  - small hooks in `HdPlugin`, `HdPluginConfig`, `UBOGlobal`, both renderers and both scene uploaders
  - JSON data and schemas: environments, water types, areas, tile and model overrides
- See it all with `git diff master...shore-foam`.
- **Uncommitted and not part of the cleanup** (leave them exactly as they are, and never commit them):
  - `HdPluginConfig.java` and `BoatWakes.java`: the "Test boat wakes" profiling mode.
  - `FrameTimingsRecorder.java`: one Ctrl+Shift+F4 takes two snapshots.
  - The `*-handoff.md` and `wake-perf-brief.md` notes.

  Commit only your own changes, file by file. Those files also hold committed branch code that is in scope; if you
  edit them, keep the uncommitted parts out of your commit (`git add -p`, or ask the user).
- `jin-wind` has every `shore-foam` commit merged in (last merge `764cc815d`). It's the user's main branch for
  playing, so the cleanup has to be merged there too (see "Merging into jin-wind").

## Cleanup candidates already known

- **Dead code:** `noise2` in `water.glsl` has no callers left since the noise texture commit.
- **Two noise families:** `waterNoise1` / `waterNoise2` (texture) sit beside `noise` from `misc.glsl` (hashed, used
  by other shaders). Decide whether the water ones are named and placed clearly, and document which to use.
- **A size kept in two places:** `WATER_NOISE_SIZE` is 256 in both `HdPlugin.java` and `water.glsl`, linked only
  by comments. Other shared values come in through `ShaderIncludes` defines (see `HdPlugin.getShaderIncludes`),
  which would remove the duplicate.
- **Boat wake bounds in two places:** the bounds constants in `BoatWakes.java` must match the `BOAT_*` defines in
  `water.glsl`. Same fix: pass them in as defines, or at least make the link explicit.
- **Comments:** many were written during experiments and narrate history ("rather than ...", "no longer ...").
  AGENTS.md asks for comments on intent, invariants and non-obvious maths, not narratives of abandoned versions.
- **Long functions:** `sampleWater`, `oceanWaveCells`, `shoreWaves`, `boatWakes`, `boatWakeFoam`. AGENTS.md accepts
  long functions with a clear linear flow, so this is about readability, not splitting for its own sake.
- **Constants:** dozens of `OCEAN_*`, `SHORE_*`, `BOAT_*` tuning defines spread through `water.glsl`. Check that they
  are grouped, named consistently and have units where it isn't obvious.
- **Java style per AGENTS.md:** visibility as narrow as possible, no defensive null checks on injected
  dependencies, braces on every branch of an `if` that has an `else`, tabs, no blank line before a class's
  closing `}`.
- **Commit history:** some commits have placeholder messages (`2c8fa5659 idk`, `5dba62a25 part way through
  optimisations`, `6e4089de9 1st ver of boat wakes`). Rewriting history is the user's call: ask, don't do it.

## Things that look odd but are deliberate

These were measured and must survive the cleanup. "Simplifying" them back has a real GPU cost.

- **All foam runs before the water's normals and lighting in `sampleWater`.** Values worked out before heavy
  loops stay live through them and slow every water pixel (see `water-shader-live-state-cost` in the user's
  memory notes, and `wake-perf-handoff.md`). Don't move foam code after the lighting, and don't hoist lighting
  values above the foam.
- **Wake foam is reduced early** to `wakeAmount`, `wakeSpecks` and `churned`, so less is carried through lace and
  waves.
- **Lazy noise:** structs like `WakeNoise`, and flags like `raggedReady` and `breakupReady`, work out noise only
  once something needs it. Keep it lazy.
- **Early `continue`s and returns** ("Cheapest checks first", "Most water is between the lines") skip work for
  most pixels.
- **Boat wakes:** a bitmask pass picks the boats whose bounds hold the pixel, and a second path loop is skipped
  when no other segment is within range. Both are exact.
- **The per-face wave list:**
  - `scene_vert.glsl` marks which wave cells can reach each water face, and `oceanWaveCells` only checks those.
  - Its reach (`OCEAN_WAVE_FACE_REACH`, 1.5 tiles, with a 5% margin) is what makes it exact. Pixels beyond it
    fall back to all nine cells.
  - On `jin-wind` the vertex shader tests `gl_VertexID % 3 == 2` for the provoking vertex, because that branch has
    no `isProvoking`.
- **`ocean_wave_cells.glsl` is shared** so the vertex shader's list and the fragment shader's waves can't disagree.
  Keep both shaders using the same function.
- **The noise texture's smoothstep offset** in `waterNoise2` is what makes one filtered read match smooth value
  noise. It isn't a typo.

## How to check that nothing changed

1. **Shaders compile and link:** run ShaderCheck before every save of a shader file. Shaders hot-reload into the
   user's running client, and a broken save can stop 117 HD.
   - `C:\Users\Jin\AppData\Local\Temp\claude\290d926abbd9aaba\2a5404c8-7a20-4a41-af7e-8eccc7e372b8\scratchpad\shadercheck\run2.sh [shader folder]`
   - It compiles and links all 16 variants.
2. **Java compiles:** `./gradlew compileJava -q --offline`. The user restarts the client for Java changes; check
   its start time yourself (the client's java command line ends in `--disable-telemetry`; a long-running jdk-21
   process is the Gradle daemon). The client's log is `RLHD-jin/117hd.log`.
3. **Same pixels:** compare WaterBench renders against the shaders at `19e464225`:
   - Make a reference folder with the shaders from `19e464225`, for example via `git worktree`, then copy its
     `src/main/resources/rs117/hd` to `shadercheck\vars\<name>`.
   - Run, for example:
     `BENCH_TILES=1 BENCH_DRAWS=4 ./wb.sh 30 ref,V:<name>,1,1,1,1,16 new,HD,1,1,1,1,16,ref`
   - A spec reads `name,dir,shore,lace,waves,wakes,boats[,reference]`. `BENCH_DIFF=1` writes a difference image.
   - Check several setups: shore (`BENCH_SHORE=0 BENCH_SHORE_STEPS=16,21`), open water, 16 boats, and the daylight
     cycle (`BENCH_CYCLE=1`).
   - The result should be exact. Rounding-level differences (under 0.001) are acceptable if explained; any visible
     change is not.
   - WaterBench binds the noise texture to unit 5 under the name `waterNoise`. Its random values differ from the
     game's, but that's the same for both versions, so comparisons are fair.
4. **Same speed:** time old against new with the same tool, interleaved. Use the median of 30 or more rounds.
   Close the game first if you can: with it running, timings are several times too high and noisy.
5. **In game:** ask the user to look, and if needed to record. Never drive the game yourself. Ask for two
   back-to-back snapshots per setting at 150% at a water-heavy spot, and have them wait about 10 seconds after a
   settings change. For an A/B test, swap shader files in place; they hot-reload. If the vertex and fragment
   shaders both change, order the swap so that no in-between pair fails to link.

## Merging into jin-wind

- Merge in a temporary worktree (`git worktree add <scratch path> jin-wind`), so the user's checkout and running
  client aren't touched. Remove the worktree afterwards.
- **A clean merge isn't proof that it works.** Compile the merged Java (`./gradlew compileJava` inside the worktree)
  and run ShaderCheck on the merged shaders.
  - `jin-wind` needs a `TEXEL_SIZE` define that the checker doesn't set.
  - Check a copy of its shader folder with `#include TEXEL_SIZE` commented out in
    `utils/texture_buffer_reader.glsl`, which then falls back to its default.
  - The same check fails on `jin-wind` before any merge, so that failure alone isn't yours.
- `jin-wind` changed `HdPlugin.java`, `SceneShaderProgram.java` and `scene_vert.glsl` on its own, so expect
  conflicts there. Keep both sides unless one is clearly superseded.

## Rules

- No commits unless the user asks, and never a Co-Authored-By Claude line. Don't push.
- Keep changes to 117 HD core files small. The user prefers hooks over refactors in shared code.
- Don't drive or interact with the user's game, and don't launch GUI apps from the shell.
- Delete files via the Recycle Bin, after confirming.
- Write-ups for the user: plain language, no "we".

## Suggested order

1. Read AGENTS.md, then the whole `git diff master...shore-foam`, and list proposed changes for the user before
   making them. Group them by file, and mark anything that could change pixels or cost.
2. Do the zero-risk ones first: dead code, comments, naming, Java style.
3. Then the structural ones: shared defines, grouping constants, function layout. Check pixels and timing after each
   step.
4. Summarise for the user with before-and-after evidence. Merge into `jin-wind` only when asked.
