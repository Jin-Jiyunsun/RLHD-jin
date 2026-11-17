# Investigation brief: boat wake GPU cost in 117 HD's water shader

## Goal

Find and verify optimisations that cut the GPU cost of the experimental boat wakes, without changing how they look.
Output should be identical, or the change should be called out and justified. Report the measured gains.

## Where the code is

- Repo: `C:\Users\Jin\IdeaProjects\RLHD-jin`, branch `shore-foam`.
- Shader: `src/main/resources/rs117/hd/utils/water.glsl`
  - `boatWakes()` (about line 645): loops over up to 16 boats. For each boat whose bounds contain the pixel, it:
    1. searches every path segment for the nearest point;
    2. if near enough, searches every segment again to build `swept`;
    3. calls `boatWakeFoam()`.
  - `oceanWaveCells()` uses `swept` so that waves a boat passed over stay broken up.
  - `sampleWater()` calls `boatWakes()` once, before lace and waves (`wake` and `swept` stay live through both).
- CPU side: `src/main/java/rs117/hd/scene/BoatWakes.java`. It records each boat's path, thins it to as few points as
  possible (up to 40), and uploads per-boat bounds, heading, hull, size and points into the global UBO.
- Water has its own program: `SceneShaderProgram.Water`, compiled with `WATER_PASS 1`. `ZoneRenderer` switches to it
  for water surfaces (`waterProgram`, about line 1164). `BOAT_WAKES` is a compile-time define from config
  (`HdPlugin.getShaderIncludes`).
- A test mode exists for profiling: the config option "Test boat wakes" fills all 16 slots with boats sailing north
  in a 4x4 staggered grid around the camera. Each boat wraps back to the bottom of the grid, so the layout repeats
  every 8 seconds.

## Measurements so far

All measurements are on an RTX 3080 at 150% render scale, so the GPU is the bottleneck. Each figure is the median
"gpu.Render scene and sky" of two back-to-back 117 HD timing snapshots (Ctrl+Shift+F4). Snapshots are saved to
`~/.runelite/117hd/snapshots`, and GPU times are in ns. Estimates for 100% divide the cost by about 2.25.

16 test boats, camera still, same spot:

| Foam options on | Scene pass |
|---|---|
| none | 2.909 ms |
| boat wakes only | 3.502 ms |
| everything except wakes | 4.413 ms |
| everything | 5.379 ms |

- Wakes alone cost 0.59 ms.
- Added on top of the other foam, they cost 0.97 ms, so about 0.38 ms more than the sum of the parts.

No boats on screen, "Boat wakes" on vs off, everything else on:

| Location | Cost of having wakes on |
|---|---|
| Port Khazard | 0.15 ms |
| Kastori | 0.30 ms |

Both pairs were consistent.

Intel Iris Xe laptop, 100%, Rimmington: all the foam costs 6.6 ms, 24% of the scene pass. That's about 20 times the
3080. No per-feature runs have been done on it yet.

## Offline findings

The offline benchmark draws `boatWakes()` plus `oceanWaves()` full-screen at 1920x1080, with 16 boats in a real
std140 UBO.

- **Wakes alone take 0.39 ms; noise isn't the cost.** Replacing every noise call with a constant changed nothing.
- **Removing either part alone saves nothing.** The `swept` loop costs about 0.15 ms when the foam is skipped, but
  removing it while the foam is drawn saves nothing. Removing the foam alone saves about half. The time looks set by
  the heaviest part, which suggests register or occupancy limits rather than instruction count.
- **Loop structure:**
  - Hard-coding the path loop bound to the test paths' actual length halves the cost, to 0.21 ms. The NV assembly
    differs only in the loop count, so the gain is in the final machine-code compile: unrolling, or fusing the two
    identical segment loops.
  - A fixed bound with an early `break`: no gain.
  - Fetching points one segment ahead: 25% slower.
  - Chunked unrolling: about 10% faster, which is within noise.
- **Unused wake code isn't measurable in the small benchmark.** With no boats, having it compiled in cost nothing
  there. The in-game no-boat cost above therefore probably comes from the full water program's register pressure,
  which the benchmark doesn't reproduce.

## Leads to investigate, best first

1. **A second water program without wakes.**
   - Compile `SceneShaderProgram.Water` a second time with `BOAT_WAKES 0`, and use it whenever `BoatWakes` uploaded
     no active wake that frame.
   - Expected: removes the 0.15–0.3 ms no-boat cost, and probably part of the 0.38 ms interaction.
   - Verify in game with wakes on vs off and no boats, then with the test boats.
2. **Fuse the two path loops** so each segment's projection is computed once. The difficulty is that the `swept`
   pass fades over `dist - side`, where `side` is the final nearest distance. Ideas:
   - Collect candidate segments (`dist < runningNearest + BOAT_WAKE_SWEPT_RANGE`) in a bitmask in pass 1, and run
     pass 2 only over those.
   - Prove that a single-pass approximation is visually identical.
3. **Tighter per-boat culling.** Bounds are axis-aligned boxes around the whole path. Diagonal boats get boxes several
   times larger than their foam. Add a cheap check of the distance to the stern-to-tail strip before the loops.
4. **Less live state across the water shader.**
   - `wake` (vec4) and `swept` (vec4) are computed before lace and waves, and stay live through them.
   - Packing them, or working out the wake's final contribution earlier, may lower register pressure for every water
     pixel.
5. **Check the laptop.** Run per-feature on/off pairs on the Iris Xe so the optimisations are judged where they matter
   most.

## Tools

These are in a scratchpad that can be cleared. Copy anything you need first.

`C:\Users\Jin\AppData\Local\Temp\claude\94a7fe0a55faade3\f680e363-9211-4ea7-b7f0-2091fc9908a4\scratchpad\wavesim\`

- `LaceBench.java`: times fragment shaders full-frame at 1920x1080 with GPU timer queries. It diffs each shader's
  pixels against the first one listed, and writes `.png` and `.f32` images.
  - Usage: `LaceBench <span tiles> <shader>...`
  - `BENCH_UBO=<file>` loads the uniform block from a file.
  - `BENCH_DUMP=1` writes the program binary. The NV assembly is the largest `!!NV` text section inside it.
- `bench.sh <span> <rounds> <shader>...`: interleaves the shaders and reports each one's best time. Use this rather
  than single runs, which vary by up to 50% while the game is running.
- `wake16.py <rs117/hd folder> <prefix> [water.glsl]`:
  - builds `<prefix>_both`, `_wakes`, `_waves` and `_wavesnowake` shaders from the real `water.glsl`;
  - writes `<prefix>_ubo.f32`, holding the 16 test boats as the game lays them out.
  - Example: `BENCH_UBO=w16_ubo.f32 ./bench.sh 48 4 w16_wakes.glsl variant.glsl`
- The benchmark needs a JDK with javac: `C:\Users\Jin\.jdks\corretto-17.0.19\bin\java`. Its classpath is in
  `scratchpad\cp.txt`.
- `snapshots.py [since]`: summarises in-game timing snapshots by which foam options were on.
- ShaderCheck (GPU-compiles every 117 HD shader variant offline):
  `C:\Users\Jin\AppData\Local\Temp\claude\0468c527b7740a11\24b57cd7-4cd3-4644-8d2a-dd3a47c727b1\scratchpad\shadercheck\`

## Rules

- **Don't drive the game.** Ask the user to record snapshots, and say exactly which options to set.
- **Every shader save must compile.** Shaders hot-reload, and a broken save can stop 117 HD. Check offline with
  ShaderCheck before saving `water.glsl`.
- **Java changes need a client restart.** The client's log is at `RLHD-jin/117hd.log`.
- **Commits:** don't commit or push unless asked, and never add a Co-Authored-By Claude line.
- **Keep changes to core files small,** and make them only on this branch.
- **Wake bounds have two copies.** The bounds constants in `BoatWakes.java` must match the `BOAT_*` defines in
  `water.glsl`.
