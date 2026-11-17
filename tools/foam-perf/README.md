# Water foam performance tools

Tools used to measure and optimise the water foam on the `shore-foam` branch, kept on the `foam-tools` branch so
they don't live only in temporary folders. They're for local use on this machine and aren't meant for 117 HD itself.

The `foam-tools` branch is `shore-foam` plus two things:

- This folder.
- Two in-game profiling helpers, in their own commit:
  - **"Test boat wakes"** (`HdPluginConfig`, `BoatWakes`): fills all 16 wake slots with boats sailing in a 4x4 grid
    around the camera.
  - **Two snapshots per keypress** (`FrameTimingsRecorder`): one Ctrl+Shift+F4 takes two 20 s snapshots, 3 s apart.

To use the in-game helpers on another branch, cherry-pick that commit. Don't merge `foam-tools` into a branch meant
for 117 HD.

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
    | `BENCH_SET` | Overrides UBO fields |
    | `BENCH_DUMP=1` | Writes the program binary; `asm.py` pulls out the NVIDIA assembly |

  - `wb2.sh` uses `../wavesim/d45_ubo.f32` for 16 diagonal boats.
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

Snapshots are saved to `~/.runelite/117hd/snapshots`. Read the median of `gpu.Render scene and sky`, and compare
only pairs recorded from the same spot and camera.

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
  between two levels. 16x MSAA at 100% also makes the GPU the bottleneck.
- Two back-to-back snapshots per setting, with about 10 s of settling after any settings change.
- For a before-and-after, swap shader files in place, since they hot-reload. If the vertex and fragment shaders both
  change, order the swap so that the in-between pair still links.

## notes

The handoffs and results from each round of work, newest last:

1. `wake-perf-brief.md`
2. `wake-perf-handoff.md`
3. `foam-perf-handoff.md`
4. `foam-cleanup-handoff.md`
