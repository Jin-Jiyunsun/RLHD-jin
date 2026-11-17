# Handoff: ocean wave optimisation, ready for in-game testing

Follows `wake-perf-brief.md` and `wake-perf-handoff.md` (same folder). Their rules still apply: don't drive the game,
every shader save must compile, no commits unless asked, no Co-Authored-By Claude line. The user has said rounding-level
pixel differences don't matter; visible pattern changes still need calling out first.

## State (2026-11-16, about 21:50)

- Branch `shore-foam`, last commit `028a14e68` (the boat wake work). Uncommitted from this pass:
  - `src/main/resources/rs117/hd/utils/ocean_wave_cells.glsl` (new)
  - `src/main/resources/rs117/hd/utils/water.glsl`
  - `src/main/resources/rs117/hd/scene_vert.glsl` (19 added lines, all under the water pass with ocean waves on)
- Also uncommitted, from before and untouched: `HdPluginConfig.java`, `BoatWakes.java`, `FrameTimingsRecorder.java`.
- All 16 shader variants compile and link offline (vertex and fragment together, geometry for legacy).
- **Not seen in game.** The client has been closed since 20:43, before these shaders were saved.

## What changed

1. **Per-face wave list.** The zone renderer's vertex shader marks which of the 5x5 wave cells around each water
   face's last vertex can reach it (`oceanWaveList`, flat outputs `fOceanWaveOrigin` and `fOceanWaveList`). In
   `oceanWaveCells` the pixels only look at listed cells and return at once when none are. A pixel further than
   `OCEAN_WAVE_FACE_REACH` from that vertex in wave space ignores the list and searches all 9 cells, so water that
   doesn't scroll and region edges stay correct.
2. **Shared wave code.** `oceanWaveNear` in `ocean_wave_cells.glsl` decides where and when a cell has a wave, for both
   shaders, so the two can't drift apart. Eight wave settings moved there from `water.glsl`. With a margin it checks
   everywhere near a spot, with leeway for rounding.
3. **Two earlier skips in the wave check, both exact:** the front is only 0.3 as ragged as the rest
   (`oceanWaveFrontRagged`), and trails whose foam has fully faded are skipped.
4. **Shore waves:** the breakup noise is worked out only once a wave needs it.

Lace is unchanged. The legacy renderer has no list and its waves are about 0.03 ms slower offline.

## Offline results

RTX 3080, real vertex and water shaders, a 1080p screen of one-tile water faces, game closed, 10th percentile of 80
rounds.

| Case | Before | After |
|---|---|---|
| Plain water | 0.106 ms | 0.106 ms |
| Lace only | 0.258 ms | 0.258 ms |
| Waves only | 0.433 ms | 0.269 ms |
| All foam, wakes off | 0.564 ms | 0.391 ms |
| All foam, wakes on, no boats | 0.568 ms | 0.419 ms |
| All foam, 16 test boats | 0.671 ms | 0.589 ms |
| Shore foam only, within two tiles of the shore | 0.332 ms | 0.321 ms |

The list drops nothing: against the same shader with every list bit forced on, output is exactly identical in 10
conditions (region repeat, scrolling, rough and calm water, changing wind, the shore, several times). Against the old
shader about 50,000 values differ by under 0.001, from rounding along wave fronts.

## Open step: in-game test

Ask the user to start the client on the zone renderer with ocean waves on, then:

1. Look at open sea for wave foam cut off along tile edges. That is how a wrong list would show.
2. Record snapshots as before (150% scale, camera still, Ctrl+Shift+F4) at a water-heavy spot with no boats: everything
   on, then everything except ocean waves, then no foam. Compare with the Port Khazard figures in
   `wake-perf-handoff.md` only from the same spot.

Check `117hd.log` for shader errors after the client starts.

## Not done

- **Lace:** no exact saving found. Swapping its hash noise for a small noise texture would save about 0.05 of its
  0.15 ms, but changes the pattern and needs a new texture. The user hasn't decided.
- **Waves further:** even a perfect per-face list leaves about 45% of pixels checking a wave. Passing a listed wave's
  values from the vertex shader would save roughly 0.03 to 0.05 ms more, for 16 extra flat values.
- Iris Xe laptop and open-sea timings, as before.

## Tools

In this session's scratchpad, which can be cleared:
`C:\Users\Jin\AppData\Local\Temp\claude\290d926abbd9aaba\2a5404c8-7a20-4a41-af7e-8eccc7e372b8\scratchpad\`

- `backup\water.before-wavelist.glsl`, `backup\scene_vert.before-wavelist.glsl`: the shaders before this pass.
- `shadercheck\run2.sh [shader folder]`: compiles and links all 16 variants. Run before any shader save.
- `shadercheck\wb.sh <rounds> <spec>...`: WaterBench, as in `wake-perf-handoff.md`, with these additions:
  - `BENCH_TILES=1`: real one-tile faces through the real `scene_vert.glsl`. Required now, since the stub vertex
    shader has no wave list.
  - `BENCH_DRAWS=4`, `BENCH_TIME=<s>`, `BENCH_DIFF=1` (difference image), `BENCH_SHORE_STEPS=16,21`,
    `BENCH_SET="UBOGlobal.waveRegionRepeat=i:1;UBOGlobal.waveScroll=f:13.4,-4.2"`.
  - Old vs new: `BENCH_TILES=1 BENCH_DRAWS=4 ./wb.sh 80 old,V:base,1,1,1,0,0 new,HD,1,1,1,0,0,old`
    (`vars\base` is the shader folder at `028a14e68`).
  - List check: `vars\fc_full` has every list bit forced on. It predates the shore change, so rebuild it from the
    repo with `python vd.py fc_full` and add `| 0x1FFFFFF` to the `oceanWaveList` call in its `scene_vert.glsl`.
- Time with the game closed. With it running, results are several times too high.
