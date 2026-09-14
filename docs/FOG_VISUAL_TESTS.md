# Fog regression experiments

Use an explicitly selected disposable save copy. Start the intended binary before recording; do not rebuild its loaded JARs during a run. `status.world.directory` must match `--copied-world` exactly. The harness preserves fog settings, debug view, daytime progression, player position/orientation, game mode and spectator speed in `finally`, and releases held inputs. It does not undo chunk generation or world progression; the save remains disposable. Begin without held input and with unfrozen ticks.

```powershell
uv run python tools/debug/check_fog.py --copied-world caustica-fog-regression --output tmp/fog/regression
uv run python tools/debug/analyze_fog.py tmp/fog/regression
uv run python -m unittest discover -s tools/debug -p test_fog_tools.py
```

For a short saved-camera comparison use `--cases stationary --modes off post path`. An older client without `fog.mode` can run `--modes off post`, but needs the world-directory debug field. Repeat the same command after fixes in a separate output directory. JFR and raw image files remain in their service output directories; the manifest stores paths and complete conditions.

| Case | What to inspect | Failure evidence |
| --- | --- | --- |
| Saved camera, four stationary repeats | Start at the reported overhead camera; preserve output resolution, reconstruction route, density, light count, bounce count and time | Repeated low GPU throughput at fixed pose; a moving planar density boundary; stationary radiance variance away from geometry edges |
| Pitch 0, 45, 89, -45, -89 degrees | World-anchored density at the same position, including steep sky and ground rays | Shafts slide with pitch, depth clipping changes discontinuously, or downward views have disproportionately high trace/scene-effects cost |
| Fixed-position yaw at 12 degrees/second | Broad density remains anchored; thin trees, roof gaps and silhouettes remain coherent | Coarse blocks, leaking light behind occluders, moving bands, persistent trails after stopping |
| Level flight over 768 blocks and teleport back | Flight JFR, end image, immediate return, 180-frame warm return, 1200-frame settled return | A long-lived cost increase after return; missing fog field tiles; a slab boundary aligned to a field/origin change |
| Off / post / path | Primary-depth should retain the same physical meaning; upstream reconstructed radiance can change with path transport | Double fogging, post primary scattering applied again in path mode, foreground fog crossing the first physical hit |

Each beauty sample bundles `primary-depth`, `depth`, `trace-color`, `reconstructed-color` and `scene-color` in one submitted frame. Its PNG is a separate frame. The analyzer reports finite ranges at native resolution, divides beauty RGB by each buffer's pre-exposure, and builds a labeled contact sheet. It compares stationary `trace-color` repeats within each mode using the native-resolution primary-depth guide, without resampling the guide to output resolution. The mask accepts relative reverse-Z changes up to 0.1%, with an absolute tolerance of 1e-8, and excludes sky/geometry transitions. Primary depth is reverse-Z, not metres; zero depth is sky. Differences include stochastic path noise and animated scene content, so compare their magnitude and spatial distribution across repeated runs. Under NRD/SR, `trace-color` contains the denoised SR input. Moving frames are not compared pixel by pixel.

Inspect the full-size frames and upstream buffers in addition to the sheet. Screen-aligned bands in both depth and color point toward the guide/ray contract; stable depth with displaced fog points toward field sampling, visibility or reconstruction. These are diagnostic hypotheses, not a substitute for inspecting the shader contract. Water, foliage, temporal sampling and auto-exposure can vary even at a stationary pose.

For performance, analyze every manifest `recording.path` with `analyze_recording.py`. Compare paired repeat medians/p95 of world trace and scene effects, and frame-start cadence. Scene effects are nested inside post processing; do not sum parent and child. Retain full outlier intervals and terrain counts when examining the intermittent slowdown. Compare flight and return intervals independently. Image readbacks occur after JFR stops. The 180/1200-frame waits are observation intervals and do not assert that terrain or exposure is settled.

Additional focused visual fixtures should use the same copied save and camera/settings bookkeeping:

For a geometric narrow-shaft test, see [the noon roof-slit acceptance plan](FOG_SHAFT_TEST.md). Its offline planner computes expected illuminated ray lengths, and the protocol separates post scattering diagnostics from the more limited path-mode beauty controls.

`fog_fixtures.py` writes a concrete fixture plan without contacting the client. Add `--build` to apply that plan to the exact copied save. It replaces five 17x13x17 regions spaced 48 blocks apart along X, so choose an expendable location. It stores every block command, completed command result and suggested camera in the output JSON, and restores player pose/game mode/speed afterward. The fixtures remain in the copy for repeated comparisons. No original blocks are restored.

```powershell
uv run python tools/debug/fog_fixtures.py --copied-world caustica-fog-regression --origin 4096 64 4096 --output tmp/fog/fixtures.json
uv run python tools/debug/fog_fixtures.py --copied-world caustica-fog-regression --origin 4096 64 4096 --output tmp/fog/fixtures.json --build
```

Move to a stored camera and run `check_fog.py --cases stationary yaw` for each fixture. Use roof-slit at dawn and the emitter fixture at night. The oblique wall is a flat matte surface viewed at yaw 20 and pitch 15; its close reference camera fills the view with a plane for analytic depth/jitter checks. The tank separates water and air behind matching glass; two equal local emitters expose one source and hide the other behind stone. Inspect each fixture after chunk loading before accepting captures. Light/environment density depends on the local biome and chosen time; an ocean site near Y64 provides a useful fog-bearing fixture location.

The fifth fixture is an open sandstone basin with an exposed water-to-air surface. Its `below-surface` camera looks upward through that boundary; `outside-above` looks down through it. Compare off/post/path in both positions. This separates free-surface transmission from water touching glass, whose culled or missing boundaries require independent validation. A dark glass-enclosed tank must not be treated as proof that all underwater transport fails.

| Fixture | Conditions and capture | Acceptance |
| --- | --- | --- |
| Slotted roof / thin foliage | Low sun, lateral movement, normal and post transmittance/scattering diagnostics | Shadows remain behind the occluder without a displaced illuminated plane; no coarse edge steps larger than the chosen integration resolution |
| Ground layer from above | Y90, Y180 and Y320 with downward pitch; repeat dawn/noon | Continuous height falloff and surface clipping; no camera-aligned slab |
| Air / glass / water | Identical camera outside and inside each boundary, density 0/1/4 | Path mode affects direct and continuation transport without applying outdoor fog inside explicit water media; density zero agrees with disabled within stochastic variance |
| Open water basin | Below-surface upward and outside-above downward cameras, all fog modes | Visible transmitted sky/geometry across the free water-to-air boundary, finite radiance; compare separately from the glass-enclosed tank |
| Local emitter at night | Emissive block in fog and behind an opaque wall, same view in each mode | Path transport responds to local lighting, occlusion blocks illumination, finite nonnegative radiance |
| Post resolution | Divisors 4/8 at the same output size and a slow yaw | Quality/cost scales predictably; severe bands or shafts in the wrong place are not accepted as a resolution tradeoff |
| Reconstruction routes | RR and NRD/SR at the same trace/output dimensions | Finite guides; motion stabilizes after stopping; path scattering does not contaminate depth/normal meaning |

Transmittance diagnostics require post mode, bloom disabled and `fog.debug=1`; capture `scene-color` without dividing by pre-exposure and check finite values within [0,1]. Restore normal fog/debug/bloom and allow exposure to settle before beauty images or timing. Path mode uses its normal trace signals rather than a post transmittance diagnostic. A handful of moving stills cannot establish flicker-free video; use repeated short sequences at the reported motion speed, with a separate readback-free performance interval.
