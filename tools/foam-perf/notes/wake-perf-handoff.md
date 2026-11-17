# Handoff: boat wake optimisation, ready for benchmarking

Follows `wake-perf-brief.md` (same folder). Its rules still apply: don't drive the game, every shader save must
compile, no commits unless asked, no Co-Authored-By Claude line.

## State (2026-11-16, 17:50)

- Branch `shore-foam`, nothing committed. Uncommitted:
  - `src/main/resources/rs117/hd/utils/water.glsl`: the optimisation (this work).
  - `HdPluginConfig.java`, `BoatWakes.java`: the "Test boat wakes" mode, from before this work. Untouched.
- The client was restarted at 17:46 and is running the new shader. `117hd.log` shows no shader errors.
- **Measured in game on 2026-11-16 (see "In-game results").** Only the open sea and the Iris Xe laptop are left.

## What changed in water.glsl

Output is pixel-identical to the old shader in every offline configuration tested.

1. **Foam before lighting.** In `sampleWater`, all foam (wakes, shore, lace, waves) now runs before the normals and
   lighting, with `boatWakes()` called first. The old order kept about 40 lighting values live through the foam's
   loops, which slowed every water pixel even with no boats.
2. **Wake foam reduced early.** The wake's speck lookup and strength are worked out before lace and waves, so only
   `wakeAmount`, `wakeSpecks` and `churned` are carried through them.
3. **Bitmask cull** in `boatWakes()`: a small loop marks the boats whose bounds hold the pixel, and the heavy loop
   visits only those.
4. **Second path loop skipped** when no other segment is within `BOAT_WAKE_SWEPT_RANGE` (tracked as `second` in the
   first loop).

Not done: a second water program without wakes (lead 1, judged unnecessary unless the game disagrees), a distance
cull for diagonal boats (lead 3, needs the reach uploaded from Java), the Iris Xe laptop (lead 5).

## Offline results to beat or confirm

RTX 3080, real water program, full-screen open water at 1920x1080, median of 50 interleaved rounds, all foam on.

| Case | Old | New |
|---|---|---|
| Wakes off | 0.586 ms | 0.565 ms |
| Wakes on, no boats | 0.632 ms | 0.573 ms |
| 1 boat | 0.643 ms | 0.575 ms |
| 4 boats | 0.671 ms | 0.587 ms |
| 16 boats | 0.853 ms | 0.666 ms |
| Wakes only, 16 boats | 0.383 ms | 0.300 ms |
| Shore foam only | 0.253 ms | 0.212 ms |

## In-game results (2026-11-16, 19:05–20:05)

The new shader is confirmed in game. RTX 3080, 150% render scale, camera still, two back-to-back snapshots per
setting (median "gpu.Render scene and sky", averaged over the pair). Pairs agreed within 0.07 ms. "Cost" is the time
over everything off at the same spot. Estimates for 100% divide by 2.25, so are probably slightly low.

Old vs new, same spot and camera:

| Test | Old | New | Change |
|---|---|---|---|
| 16 boats, no foam | 2.629 ms | 2.627 ms | −0.1% |
| 16 boats, boat wakes only | 3.119 ms | 2.985 ms | −4.3% |
| 16 boats, everything except wakes | 3.676 ms | 3.608 ms | −1.8% |
| 16 boats, everything | 4.241 ms | 3.807 ms | −10.2% |
| Port Khazard, no boats, everything except wakes | 3.627 ms | 3.577 ms | −1.4% |
| Port Khazard, no boats, everything | 3.869 ms | 3.614 ms | −6.6% |

| Cost | Old | New | Change |
|---|---|---|---|
| Boat wakes alone (16 boats) | 0.490 ms | 0.358 ms | −27% |
| All foam (16 boats) | 1.612 ms | 1.180 ms | −27% |
| Wakes on top of the other foam (16 boats) | 0.565 ms | 0.199 ms | −65% |
| Wakes on with no boats | 0.243 ms | 0.037 ms | −85% |
| All foam, no boats (Port Khazard) | 1.231 ms | 0.976 ms | −21% |

With the new shader, all foam is 31% of the scene pass with 16 boats and 27% in normal play at Port Khazard. At 100%
that's about 0.52 ms and 0.43 ms. The Port Khazard view was water-heavy; the cost follows how much water is on screen.

No water in view, new shader, everything off vs on: Arceuus 5.378 vs 5.363 ms, Varrock 4.542 vs 4.538 ms. No cost.

Lead 1 (a second water program without wakes) isn't needed: wakes on with no boats now costs 0.037 ms, within noise.

### Open sea: not done

Stopped on a boat at open sea with the test boats, back-to-back pairs didn't hold steady. With no foam, one recording
jumped from 2.50 to 3.03 ms partway through, with 9 more dynamic objects drawn (passing ships or NPCs). With lace and
waves on, it swung between 4.1 and 5.0 ms within 40 s. Planned retry: record the four test-boat settings in order and
then in reverse (1, 2, 3, 4, 4, 3, 2, 1) and average each setting's two runs. Check `drawnDynamic` and the UI timers
in each snapshot to spot scene changes.

### Tools added for this

- `FrameTimingsRecorder.java` (uncommitted): one Ctrl+Shift+F4 now takes two 20 s snapshots with a 3 s pause
  (`SNAPSHOT_COUNT`, `SNAPSHOT_PAUSE_MS`). This changes a core 117 HD tool, so keep it out of the shader commit.
- `last.py [n]` in this session's scratchpad
  (`C:\Users\Jin\AppData\Local\Temp\claude\94a7fe0a55faade3\f680e363-9211-4ea7-b7f0-2091fc9908a4\scratchpad\`):
  prints the newest n snapshots with the foam options on and the median scene pass.

## In-game benchmark (done, see above)

The user records these; ask them, with the exact options. RTX 3080, 150% render scale, camera still, two
back-to-back Ctrl+Shift+F4 snapshots per setting. Snapshots go to `~/.runelite/117hd/snapshots`.

1. "Test boat wakes" on, same spot for all four: no foam options, boat wakes only, everything except wakes,
   everything.
2. "Test boat wakes" off, no boats on screen, at Port Khazard or Kastori: everything on, with "Boat wakes" on and
   then off.

The brief's old figures (2.909 / 3.502 / 4.413 / 5.379 ms; no-boat cost 0.15 and 0.30 ms) only compare from the same
spot. For a same-spot A/B, `git stash push src/main/resources/rs117/hd/utils/water.glsl` hot-reloads the old shader
and `git stash pop` brings the new one back.

Summarise with `snapshots.py <since, as YYYY-MM-DD_HH-MM-SS>` (reports the median "gpu.Render scene and sky" by
which foam options were on; its `LOCATIONS` list needs a line for each new run).

## Tools

All in a session scratchpad that can be cleared. Base folder:

`C:\Users\Jin\AppData\Local\Temp\claude\290d926abbd9aaba\2a5404c8-7a20-4a41-af7e-8eccc7e372b8\scratchpad\`

- `snapshots.py`, `snap_iris.py` (takes the snapshot folder as a second argument, for the laptop's files).
- `backup\water.before.glsl`: the old shader.
- `shadercheck\run.sh`: ShaderCheck, compiles all 16 variants of the repo's shaders. Run before any shader save.
- `shadercheck\wb.sh <rounds> <spec>...`: WaterBench, the full-program benchmark.
  - Spec: `name,dir,shore,lace,waves,wakes,boats[,reference name]`. `dir` is `HD` for the repo's shaders or `V:x`
    for the copy in `shadercheck\vars\x`. A reference makes it diff the float pixels against that spec.
  - Old vs new at 16 boats: `./wb.sh 50 old,V:orig,1,1,1,1,16 new,HD,1,1,1,1,16,old`
  - `vars\orig` is the old shader folder, `vars\final` the new one. `vdir.py` and `patches.py` build patched copies.
  - Env: `BENCH_SHORE=1` shoreline mode, `BENCH_CYCLE=1` daylight cycle and glare reduction, `BENCH_DUMP=1` program
    binary. `wb2.sh` adds `UBO=d45_ubo.f32` for 16 diagonal boats.
  - Use the median of 30 or more rounds. Fewer is too noisy while the game or other apps use the GPU.
- Needs `C:\Users\Jin\.jdks\corretto-11.0.31\bin\java`. The classpath is in `shadercheck\cp.txt`.

## Pitfalls

- Scene shaders are `#version 330`: no `findLSB` or `bitCount`.
- The cost is set by how many values are live across heavy code, and only shows in the full water program. An
  isolated-function benchmark (LaceBench) misses it.
- Bash-tool heredocs halve `\\`. Write Java and regex-heavy files with the Write or Edit tools.
- The wake bounds constants in `BoatWakes.java` must match the `BOAT_*` defines in `water.glsl`.
