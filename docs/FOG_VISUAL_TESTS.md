# Fog regression experiments

The 96-sample result did not resolve the user-reported planes and performance problems. Active acceptance requires continuous-motion review and banding checks; sparse stills and zero false-dark coverage are incomplete evidence.

Use an explicitly selected disposable save copy. Start the intended binary before recording; do not rebuild its loaded JARs during a run. `status.world.directory` must match `--copied-world` exactly. The harness preserves fog settings, debug view, daytime progression, player position/orientation, game mode and spectator speed in `finally`, and releases held inputs. It does not undo chunk generation or world progression; the save remains disposable. Begin without held input and with unfrozen ticks.

```powershell
uv run python tools/debug/check_fog.py --copied-world caustica-fog-regression --output tmp/fog/regression
uv run python tools/debug/analyze_fog.py tmp/fog/regression
uv run python -m unittest discover -s tools/debug -p test_fog_tools.py
```

For a short saved-camera comparison use `--cases stationary --modes off post`. Path-volume mode is removed from active recipes. Repeat the same command after fixes in a separate output directory. JFR and raw image files remain in their service output directories; the manifest stores paths and complete conditions.

| Case | What to inspect | Failure evidence |
| --- | --- | --- |
| Saved camera, four stationary repeats | Start at the reported overhead camera; preserve output resolution, reconstruction route, density, light count, bounce count and time | Repeated low GPU throughput at fixed pose; a moving planar density boundary; stationary radiance variance away from geometry edges |
| Pitch 0, 45, 89, -45, -89 degrees | World-anchored density at the same position, including steep sky and ground rays | Shafts slide with pitch, depth clipping changes discontinuously, or downward views have disproportionately high trace/scene-effects cost |
| Fixed-position yaw at 12 degrees/second | Broad density remains anchored; thin trees, roof gaps and silhouettes remain coherent | Coarse blocks, leaking light behind occluders, moving bands, persistent trails after stopping |
| Level flight over 768 blocks and teleport back | Flight JFR, end image, immediate return, 180-frame warm return, 1200-frame settled return | A long-lived cost increase after return; missing fog field tiles; a slab boundary aligned to a field/origin change |
| Off / post | Primary-depth retains the same physical meaning | Foreground fog crossing the first physical hit, displaced scattering planes, visible bands |

Each beauty sample bundles `primary-depth`, `depth`, `trace-color`, `reconstructed-color` and `scene-color` in one submitted frame. Its PNG is a separate frame. The analyzer reports finite ranges at native resolution, divides beauty RGB by each buffer's pre-exposure, and builds a labeled contact sheet. It compares stationary `trace-color` repeats within each mode using the native-resolution primary-depth guide, without resampling the guide to output resolution. The mask accepts relative reverse-Z changes up to 0.1%, with an absolute tolerance of 1e-8, and excludes sky/geometry transitions. Primary depth is reverse-Z, not metres; zero depth is sky. Differences include stochastic path noise and animated scene content, so compare their magnitude and spatial distribution across repeated runs. Under NRD/SR, `trace-color` contains the denoised SR input. Moving frames are not compared pixel by pixel.

Inspect the full-size frames and upstream buffers in addition to the sheet. Screen-aligned bands in both depth and color point toward the guide/ray contract; stable depth with displaced fog points toward field sampling, visibility or reconstruction. These are diagnostic hypotheses, not a substitute for inspecting the shader contract. Water, foliage, temporal sampling and auto-exposure can vary even at a stationary pose.

For performance, analyze every manifest `recording.path` with `analyze_recording.py`. Compare paired repeat medians/p95 of world trace and scene effects, and frame-start cadence. Scene effects are nested inside post processing; do not sum parent and child. Retain full outlier intervals and terrain counts when examining the intermittent slowdown. Compare flight and return intervals independently. Image readbacks occur after JFR stops. The 180/1200-frame waits are observation intervals and do not assert that terrain or exposure is settled.

Additional focused visual fixtures should use the same copied save and camera/settings bookkeeping:

For a geometric narrow-shaft test, see [the noon roof-slit acceptance plan](FOG_SHAFT_TEST.md). Its offline planner computes expected illuminated ray lengths, and the protocol isolates post scattering diagnostics. A mean-placement or zero-hole result is insufficient; continuous-motion and banding acceptance remain required.

`fog_fixtures.py` writes a concrete fixture plan without contacting the client. Add `--build` to apply that plan to the exact copied save. It replaces five 17x13x17 regions spaced 48 blocks apart along X, so choose an expendable location. It stores every block command, completed command result and suggested camera in the output JSON, and restores player pose/game mode/speed afterward. The fixtures remain in the copy for repeated comparisons. No original blocks are restored.

```powershell
uv run python tools/debug/fog_fixtures.py --copied-world caustica-fog-regression --origin 4096 64 4096 --output tmp/fog/fixtures.json
uv run python tools/debug/fog_fixtures.py --copied-world caustica-fog-regression --origin 4096 64 4096 --output tmp/fog/fixtures.json --build
```

Move to a stored camera and run `check_fog.py --cases stationary yaw` for each fixture. Use roof-slit at dawn and the emitter fixture at night. The oblique wall is a flat matte surface viewed at yaw 20 and pitch 15; its close reference camera fills the view with a plane for analytic depth/jitter checks. The tank separates water and air behind matching glass; two equal local emitters expose one source and hide the other behind stone. Inspect each fixture after chunk loading before accepting captures. Light/environment density depends on the local biome and chosen time; an ocean site near Y64 provides a useful fog-bearing fixture location.

The fifth fixture is an open sandstone basin with an exposed water-to-air surface. Its `below-surface` camera looks upward through that boundary; `outside-above` looks down through it. Compare off/post in both positions. This separates free-surface transmission from water touching glass, whose culled or missing boundaries require independent validation. A dark glass-enclosed tank must not be treated as proof that all underwater transport fails.

| Fixture | Conditions and capture | Acceptance |
| --- | --- | --- |
| Slotted roof / thin foliage | Low sun, lateral movement, normal and post transmittance/scattering diagnostics | Shadows remain behind the occluder without a displaced illuminated plane; no coarse edge steps larger than the chosen integration resolution |
| Ground layer from above | Y90, Y180 and Y320 with downward pitch; repeat dawn/noon | Continuous height falloff and surface clipping; no camera-aligned slab |
| Air / glass / water | Identical camera outside and inside each boundary, density 0/1/4 | Check first-hit clipping against physical boundaries; density zero agrees with disabled within stochastic variance |
| Open water basin | Below-surface upward and outside-above downward cameras, fog off and post | Visible transmitted sky/geometry across the free water-to-air boundary, finite radiance; compare separately from the glass-enclosed tank |
| Local emitter at night | Emissive block in fog and behind an opaque wall, same view in each mode | Inspect finite radiance and separate existing surface-lighting changes from directional post-fog scattering |
| Post resolution | Divisors 4/8 at the same output size and a slow yaw | Quality/cost scales predictably; severe bands or shafts in the wrong place are not accepted as a resolution tradeoff |
| Reconstruction routes | RR and NRD/SR at the same trace/output dimensions | Finite guides; motion stabilizes after stopping; fog does not change physical depth/normal meaning |

Transmittance diagnostics require post mode, bloom disabled and `fog.debug=1`; capture `scene-color` without dividing by pre-exposure and check finite values within [0,1]. Restore normal fog/debug/bloom and allow exposure to settle before beauty images or timing. A handful of moving stills cannot establish flicker-free video; use repeated short sequences at the reported motion speed, with a separate readback-free performance interval.

## Paired 4K flying performance

`check_fog_flight.py` records eight routes: off/post at each of four headings and times. It defaults to the initial player position; an explicit origin makes repeated runs comparable. Each route requires a 3840 x 2160 framebuffer and drained terrain before recording, flies 200 ticks at spectator speed 0.2 while turning 8 degrees/second, waits 120 frames, returns to its origin at pitch 65, and records 600 more frames. These intervals contain no image readbacks. Inspect actual elapsed distance/camera poses and newly queued terrain in the manifest; the initial drain does not imply flight streaming is absent.

```powershell
uv run python tools/debug/check_fog_flight.py --copied-world YOUR_DISPOSABLE_SAVE --output tmp/fog-review/flight4k --origin -1632.065859 180 -6503.652155 --headings 47.52295 -43 137 -137 --times 1000 1000 12000 12000
```

The earlier eight-route artifacts `tmp/fog-review/prefix-flight4k/` and `tmp/fog-review/temporal-flight4k-diagnostics/` use that explicit origin (approximately -1632, 180, -6503), headings and times. The first is the uninstrumented prefix baseline; the second uses shadow traversal diagnostics. They are different instrumentation conditions and must not be compared as equal-cost performance measurements. For a separately instrumented binary add `--diagnostics`; that flag selects the extra JFR event, not shader instrumentation itself.

The tool restores original input speed, pose, world time/progression, fog settings, window size and game mode in `finally`; failed flight capture still stops JFR. Begin with unfrozen ticks and no held keys. The manifest records completion separately from restoration success. Framebuffer dimensions are verified before every flight, while reconstruction and frame-generation settings remain recorded conditions from status.

## Temporal visibility pair: measured limits

`tmp/fog-review/temporal-prefix-pair/` completed 48 bundles with successful restoration and constant game time 4692729. The 64-sample candidate and 512-sample reference share that frozen field, geometry, light and camera settings. All four count/view combinations have finite signals, zero false-dark pixels, median T 0.992676 and open/closed T p99 difference 0.

The 64-sample first view **fails the unchanged strict dark-region placement test**: mean delta 0.009478, standard error 0.001693, or 5.60 SE against a 5 SE limit. The mean leakage is 0.0627% of the lit response, but that small magnitude does not turn the failed criterion into a pass. The lateral 64-sample view and both 512-sample views pass the mean-placement test.

| Same-field 64 versus 512 comparison | First view | Lateral view |
| --- | ---: | ---: |
| Lit normalized mean absolute error | 4.710% | 4.568% |
| Lit normalized p99 absolute error | 18.019% | 16.807% |
| Lit normalized p99 residual curvature | 1.305% | 1.245% |
| Lit normalized signed mean error | -0.328% | -0.667% |

Inspected 64-sample diagnostic previews show less fine grain than the earlier static-prefix previews; mild cloudy spatial noise remains, while 512 is smoother. The earlier static-prefix pair measured 13.678-14.895% lit mean absolute error against its own 512-sample reference. However, the static and temporal experiments froze different procedural fields, so their difference does not isolate temporal accumulation as the cause. Each 64/512 pair supports only its own same-field comparison.

The 512-sample result is a denser stochastic reference, not proven converged truth. Neither low reference error nor zero holes establishes absolute radiance accuracy, absence of motion trails, or flicker-free flying. Three repeated captures yield a limited standard-error estimate; their temporal history can correlate samples. Keep the failed placement result, denser-reference limitations and separate continuous-motion/performance requirements alongside the apparent visual improvement.

## Sparse 4K motion observations

`tmp/fog-review/temporal-motion/` contains 16 completed same-frame depth/scattering bundles: a settled start, 12 moving observations and 3 after release. Captured camera positions travel 4.459 m by the final moving sample and 4.752 m by release; final yaw is 38.699 degrees. Raw source-frame gaps between moving samples are 10-17 frames. All inspected scattering components are finite. Contact and 1600-pixel previews show the shaft following the changed view without an obvious repeated 96-step band pattern in those images; mild mottled grain remains. These sparse observations cannot establish uninterrupted motion quality, absent trails, or flicker-free output.

The stored `releaseSourceFrame=16893` came from stale `latestFrame` telemetry, while raw captures use source frames 47932-48234. Its recorded release offsets are invalid. The status frame count is a different counter and is not a replacement baseline. Labels `released-1`, `released-8` and `released-60` are requested minimum targets, not verified ages after release.

The first and last released captures have identical captured camera positions and inverse projection matrices, with an actual raw source-frame gap of 98. After explicitly reducing 4K scattering by 2 x 2 area averaging to native 1920 x 1080 depth, 501319 stable physical-depth pixels give mean absolute difference 0.685834, or 0.4572% of reference mean radiance 149.999. Absolute p99 is 3.25; large edge outliers remain. This is a same-pose 98-frame comparison, not proof of 1-to-60-frame convergence or settling latency. Artifacts are `scattering-contact.png`, per-sample scattering previews and `motion-analysis.json` in that directory.


## Overhead density integration and final shaft controls

The 18 captures in `tmp/fog-review/overhead-prefix-pair/` compare 64 and 512 samples at the reported overhead pose, in 3840 x 2160, with constant procedural time. Transmittance previews expose distant bands in the midpoint-density candidate that are reduced in the dense reference. That observation motivated four-point Gauss density integration inside each cell. The cell's averaged density is computed once during ray preparation, stored temporarily in its forthcoming prefix slot, and consumed before that slot becomes cumulative transport. Shadow-ray count is unchanged.

`tmp/fog-review/gauss-overhead-prefix-pair/` repeats the 18 captures with the density fix. All 746,496,000 inspected scalar components are finite. RGB is explicitly reduced by 2 x 2 area averaging to the native depth grid; a common 0.1% reverse-Z tolerance and depth-discontinuity exclusion retain 1,083,545 pixels. Default 64 versus dense 512 samples yield:

| Signal | Mean absolute difference / reference scale | p99 absolute difference / reference scale |
| --- | ---: | ---: |
| Optical depth, `-log(T)` | 0.531% | Not computed |
| Scattering | 0.843% | 11.097% |
| Final scene | 1.776% | 12.921% |

Mean absolute transmittance difference is about 0.000046. Scattering and scene comparisons remove captured pre-exposure; transmittance is dimensionless. Scene differences also include stochastic surface rendering and reconstruction. Inspected previews show reduced distant density bands, with residual coarse shadow differences. The midpoint and Gauss experiments use different frozen procedural fields, so their aggregate error difference is not an isolated causal accuracy measurement. Each candidate/reference pair shares its own field; neither 512-sample reference is proven converged. Partial-cell reconstruction still assumes homogeneous density, so sharp transitions remain an approximation limit.

The final 64-sample roof controls in `tmp/fog-review/gauss-roof/` complete all 24 bundles with restoration. Both views pass the existing placement and non-vacuum controls, have median T 0.991211, open/closed T p99 difference zero, and zero false-dark pixels. Dark-region mean differences are 0.008617 and 0.004059, with standard errors 0.002993 and 0.001600. Mild grain remains in the diagnostic previews. Three potentially correlated repeats and two poses do not prove universal placement accuracy or flicker-free movement; the earlier failed view remains documented above.


The final density build also repeats the sparse 4K movement capture in `tmp/fog-review/gauss-motion/`: 16 bundles, successful restoration, finite scattering and inspected contact previews without obvious repeated parallel bands in those views. Mild grain remains. The first and last released bundles have identical camera and inverse projection, separated by 80 actual source frames; 518,020 stable native-depth pixels give a mean scattering difference of 0.1908% of reference radiance (absolute p99 4.25). The capture helper uses minimum relative waits and reports actual raw gaps; it does not infer release age from stale telemetry. This remains sparse evidence, not a consecutive-frame flicker or motion-trailing guarantee.
