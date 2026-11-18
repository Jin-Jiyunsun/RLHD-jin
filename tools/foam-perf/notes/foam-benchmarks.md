# Water foam benchmarks after the wave list, noise texture and cleanup

Shaders at `b87d3734b` (shore-foam, same water shaders as jin-wind at `d06d8321f`) against `028a14e68`, the last
commit before the wave list and the noise texture.

## Offline (2026-11-17)

RTX 3080, WaterBench with the real vertex and water shaders, a 1920x1080 screen of one-tile water faces, 4 draws per
round, game closed. 10th percentile of 80 interleaved rounds.

| Case | 028a14e68 | Now | Change |
|---|---|---|---|
| Plain water | 0.108 ms | 0.108 ms | 0% |
| Lace only | 0.263 ms | 0.183 ms | −30% |
| Ocean waves only | 0.447 ms | 0.264 ms | −41% |
| All foam, wakes off | 0.583 ms | 0.317 ms | −46% |
| All foam, wakes on, no boats | 0.590 ms | 0.359 ms | −39% |
| All foam, 16 test boats | 0.689 ms | 0.537 ms | −22% |
| Boat wakes only, 16 test boats | 0.322 ms | 0.300 ms | −7% |
| Shore foam only, within two tiles of the shore | 0.335 ms | 0.213 ms | −36% |
| All foam, wakes on, no boats, near the shore | 0.552 ms | 0.374 ms | −32% |

Foam on top of plain water, open sea, all foam with wakes on and no boats: 0.482 ms before, 0.251 ms now (−48%).

The cleanup commit itself changed no pixels and no timing; these gains come from the wave list (`20aa49d6e`) and the
noise texture (`19e464225`).
