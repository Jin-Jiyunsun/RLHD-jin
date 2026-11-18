# Water foam performance tools

Tools used to measure and optimise the water foam on the `shore-foam` branch, kept on the `foam-tools` branch so
they don't live only in temporary folders. They're for local use on this machine and aren't meant for 117 HD itself.

The `foam-tools` branch is an older `shore-foam` plus two things:

- This folder.
- Two in-game profiling helpers. Their commit on this branch, `d780e25e1`, no longer applies to `shore-foam` since
  the cleanup, so they're kept up to date as a patch instead: `patches/in-game-tools.patch`, made against `shore-foam`
  at `41b64f252`.
  - **"Test boat wakes"** (`HdPluginConfig`, `BoatWakes`): see below.
  - **Two snapshots per keypress** (`FrameTimingsRecorder`): one Ctrl+Shift+F4 takes two 20 s snapshots, 3 s apart.

Don't merge `foam-tools` into a branch meant for 117 HD.

## In-game helpers

Apply the patch to a `shore-foam` checkout, from the repo root, and take it out again before committing:

```bash
git apply tools/foam-perf/patches/in-game-tools.patch
git apply -R tools/foam-perf/patches/in-game-tools.patch
```

(From another checkout, give the patch's full path.) Restart the client after applying; it changes Java.

**Test boat wakes** replaces the real wakes with 16 boats for repeatable profiling:

- Turning it on places them around the camera's focal point: 4 columns 7 tiles apart, each odd row shifted half a
  column, rows 12 tiles apart, all facing north.
- Each is a 10 by 3 tile boat sailing north at 3 tiles per second, with its full-length path behind it, and counts as
  sailing the whole time.
- A boat that sails off the top of the grid jumps back to the bottom, so the grid stays put and looks the same every
  two rows, 8 seconds of sailing. Where the grid crosses land, the scene pass rises and falls over those 8 seconds.
- It fills every wake slot, so real boats get no wakes while it's on. It only does anything with "Boat wakes" on.

Paths in the scripts assume this machine (JDKs under `C:\Users\Jin\.jdks`, the repo at
`C:\Users\Jin\IdeaProjects\RLHD-jin`). Keep `shadercheck` and `wavesim` side by side: `wb.sh` and `bench.sh` find
each other's files through `..`.

## Setup: the classpath file

The Java tools run against 117 HD's own classes, for `ShaderIncludes` and its constants. After `compileJava`, write
the classpath to `shadercheck/cp.txt` from the repo root:

```bash
./gradlew -q --offline -I tools/foam-perf/shadercheck/cp.gradle printRuntimeCp -DcpOut="$(cygpath -w "$PWD/tools/foam-perf/shadercheck/cp.txt")"
```

Regenerate it whenever dependencies change. It lists absolute paths, so it isn't committed.

## shadercheck: compile checks and the full-program benchmark

- **`ShaderCheck.java`, `run.sh`, `run2.sh`:** compile 117 HD's scene shaders on the GPU without the client, for
  all 16 combinations of the foam options, daylight cycle, glare, legacy renderer and water pass.
  - `run2.sh [shader folder]` also links each vertex and fragment shader pair, and the geometry shader for legacy.
    Use it before every shader save.
  - Needs corretto-11.
  - On `jin-wind`, `#include TEXEL_SIZE` isn't defined by the checker; check a copy with that line commented out.
- **`WaterBench.java`, `wb.sh`, `wb2.sh`:** draws the real water program over a full 1920x1080 screen of water,
  times it with GPU queries and saves each render as a `.png`.
  - Usage: `wb.sh <rounds> <spec>...`, where a spec is `name,dir,shore,lace,waves,wakes,boats[,reference]`.
  - `dir` is `HD` for the repo's shaders, or `V:x` for `vars/x`.
  - Naming a reference compares the float pixels against that spec and prints the largest difference.
  - Environment options:

    | Variable | Effect |
    |---|---|
    | `BENCH_TILES=1` | Real one-tile faces through the real `scene_vert.glsl` (needed for the ocean wave list) |
    | `BENCH_DRAWS` | Draws per round |
    | `BENCH_TIME` | The time used for the renders |
    | `BENCH_DIFF=1` | Writes a difference image |
    | `BENCH_DIFFLIST` | Lists differences above a threshold |
    | `BENCH_SHORE`, `BENCH_SHORE_STEPS` | Shoreline data |
    | `BENCH_CYCLE=1` | Daylight cycle and glare reduction |
    | `BENCH_SET` | Overrides UBO fields, for example `UBOGlobal.waterRoughness=f:2` |
    | `BENCH_FACE` | Each vertex's raw shore data, comma separated, for every face: for example a river's middle |
    | `BENCH_CAMERA` | The camera's position in wave cells for the far wave thinning, `x,z` |
    | `BENCH_SPAN` | Tiles across the screen, 48 by default; more makes smaller faces, like water far away |
    | `BENCH_DUMP=1` | Writes the program binary; `asm.py` pulls out the NVIDIA assembly |

  - `wb2.sh` uses `../wavesim/d45_ubo.f32` for 16 diagonal boats.
  - The far wave thinning reads the camera's recent positions. The saved uniform data predates them, so the benchmark
    puts the camera in the middle of its own view, where nothing counts as far. Set `BENCH_CAMERA=100000,100000` to
    thin every wave.
  - To change the roughness, set `waterRoughness`, `waveMaxRoughness`, `roughnessHistory[0]` (32 values) and
    `waveHistorySteady=i:1` together. In game it's 1 under the overworld environment, and only a little higher in a
    few stormy areas.
  - **The flat view has big faces,** about 40 px wide at 48 tiles across. Looking out to the horizon, most faces are a
    few pixels wide and the waves cost several times more per pixel (2x2 shading blocks straddling faces), so
    open-sea timings here understate the game. Use `BENCH_SPAN` to check: at 960 tiles across, 2 px faces, the waves
    cost about 9 times as much.
  - The noise texture is generated on unit 5 and bound to the `waterNoise` sampler.
  - **Time with the game closed.** With it running, timings are several times higher and noisy. Use the median of
    30 or more interleaved rounds.
- **Variant folders:** `vdir.py` and `vd.py` copy the repo's shaders into `vars/<name>`, applying named patches from
  `patches.py`. `lacevars.py` and `noisevars.py` build the noise texture experiments. The other `*.py` files are
  patch sets from earlier experiments, kept for reference.
- **`RegCheck.java`, `regpatch.py`:** register and occupancy experiments.
- **`water_foam.jpg`:** the foam texture the benchmark loads.

## wavesim: isolated function benchmarks and simulations

- **`LaceBench.java`, `bench.sh`:** times a single fragment shader full screen.
  - Usage: `bench.sh <span tiles> <rounds> <shader>...`. It interleaves the shaders and reports each one's best time.
  - Needs corretto-17.
  - `BENCH_UBO=<file>` loads a uniform block, for example `w16_ubo.f32` with 16 test boats.
  - This misses costs that only show in the full water program, such as live state across loops, so check with
    WaterBench too.
- **Shader builders:** `make_shader.py`, `lace_shader.py`, `shore_shader.py`, `wake_shader.py` and `wake16.py` build
  those benchmark shaders from the real `water.glsl`.
- **UBO writers:** `ubo_wake.py` and `diag_ubo.py` write the boat UBO files.
- **`WakeSim.java`, `WaveSim.java`, `WaveGallery.java`:** CPU-side simulations and image galleries used while
  designing the wakes and waves.

## snapshots: in-game timing snapshots

Snapshots are saved to `~/.runelite/117hd/snapshots`. Compare only snapshots recorded from the same spot and camera.

**Use the fast frames, not the plain median.** Every snapshot's frames fall into two groups about 0.5 ms apart, at
every setting, foam or not: other GPU work (the desktop, other programs) landing during the scene pass. Their share
swings from 0 to over 90% between snapshots, and a plain median jumps between the groups. The median of the frames
within 0.25 ms of the 1st percentile repeats to within a few hundredths of a millisecond. See
`notes/foam-ingame-costs.md`.

- **`fast.py [since]`:** each snapshot's fast-frame time, the plain median, the foam settings and the dynamic
  objects drawn. The main tool.
- **`modes.py <stamp> [cut]`:** splits one snapshot into its fast and slow frames: when the slow ones come, and which
  other timers differ.
- **`foamkeys.py`:** which foam settings a snapshot had on, across the settings' renames (`experimentalWaterFoam`
  now covers shore foam, lace and waves). Used by the other scripts.
- **`snaplast.py [n]`:** the newest n snapshots, with:
  - which foam options were on and the render scale;
  - the median scene pass, and its median in each 5 s quarter, to spot a run that changed partway;
  - the number of dynamic objects drawn.
- **`snapshots.py [since]`:** groups snapshots by which foam options were on. Add a line to `LOCATIONS` for each new
  run.
- **`snap_iris.py`:** the same for a folder of snapshots copied from another machine.
- **`stack.py <out> <w> <h> <png>...`:** stacks centre crops of WaterBench renders, for comparing by eye.

How to record:
- Use 150% render scale on the 3080, so the GPU is the bottleneck. At 100% it often isn't, and the timings flip
  between two levels. 16x MSAA at 100% also makes the GPU the bottleneck. The screen is 2560x1440, so 150% draws
  3840x2160: divide costs by 2.25 for native 1440p, or by 4 to compare with WaterBench's 1080p.
- One keypress (two snapshots) per setting, about 20 s after any settings change.
- Cost: a setting's time minus no foam at the same spot. With all foam off the old shore fade runs, which reads a
  texture for every water pixel, so that's the baseline a player turning the foam off gets.
- Order settings so drift shows: record no foam again at the end, or go A, B, A.
- For a before-and-after of shaders, swap the files in place, since they hot-reload, and check each in-between state
  compiles first. Constants such as `OCEAN_WAVE_AMOUNT` can be tuned live the same way.
- Watch for passing NPC boats (their wakes add time) and for the number of dynamic objects jumping.

## notes

The handoffs and results from each round of work, newest last:

1. `wake-perf-brief.md`
2. `wake-perf-handoff.md`
3. `foam-perf-handoff.md`
4. `foam-cleanup-handoff.md`
5. `foam-benchmarks.md`: offline results after the wave list, noise texture and cleanup
6. `foam-ingame-costs.md`: in-game costs at six locations, the fast-frame method, why boat wakes, narrow water and
   the open sea cost what they do, and the far wave thinning, density and spacing tests
