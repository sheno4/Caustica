# Roof-slit shaft placement acceptance

The user still reports planes and poor performance with the 96-step result. Earlier mean-placement and zero-hole checks missed these failures. They remain diagnostic measurements, not acceptance; the continuous-motion and banding requirements below must also pass.

`plan_fog_shafts.py` creates commands and an analytic mask without contacting the client:

```powershell
uv run python tools/debug/plan_fog_shafts.py --fixtures tmp/fog-review/fixtures.json --capture-metadata scene-color-response.json --output tmp/fog-review/shaft-plan
```

The capture-image response or its metadata object supplies the submitted inverse projection, absolute eye, and output dimensions. Without it, explicitly supply actual settled `--vertical-fov` and capture dimensions. Green marks geometric illuminated primary-ray length. Red marks unknown first surfaces or surfaces changed by closing the slit; exclude them from quantitative comparisons.

The durable capture and analysis commands are:

```powershell
uv run python tools/debug/check_fog_shafts.py --copied-world YOUR_DISPOSABLE_SAVE --fixtures tmp/fog-review/fixtures.json --output tmp/fog-review/roof-test
uv run python tools/debug/analyze_fog_shafts.py tmp/fog-review/roof-test
```

The capture requires an initially running integrated world, restores ticks and settings, and does not create the fixture. The analyzer writes control metrics, sampling coverage, NPZ masks and open/closed/delta overlays. `tmp/fog-review/compare-roof-coverage.py BASELINE [CANDIDATE]` also compares existing NPZ artifacts without recapturing.

## Geometry and lighting

Coordinates are relative to the roof fixture origin. The left roof occupies `[2,8]x[8,9]x[2,15]`, the right `[10,15]x[8,9]x[2,15]`; the back wall begins at Z14. The useful slit is X8-10, Z2-14. These continuous block-face intervals differ from inclusive fill-command indices.

The bundled Minecraft 26.2 day timeline sets sun angle zero at tick6000. The light direction toward the sun is `(-sin(a), cos(b)cos(a), sin(b)cos(a))` for sun angle a and noon tilt b. Set noon tilt30 degrees and angular radius0.1 degrees. At noon the direction is `(0,sqrt(3)/2,1/2)`, parallel to the slit length. Modified datapacks/environment attributes invalidate this time-to-direction assumption.

Direct-lit points below the roof satisfy:

```text
8 < X < 10
1 < Y < 8
2 - 8/sqrt(3) < Z - Y/sqrt(3) < 14 - 9/sqrt(3)
```

At Y4 the band is X8-10, Z-0.309-11.113. Points `(9,4,6)` and `(9,4,10)` are inside; `(6,4,6)` and `(12,4,6)` are under opaque bands. Use eye `(4,4.5,0.5)`, yaw0, pitch0, looking along +Z. This eye is outside the beam, providing both lit and dark controls; an eye at X9 starts inside the beam. Repeat at X4.25 for a lateral comparison.

The planner clips rays against floor/roof/back-wall geometry and measures their intersection with the beam. It excludes rays where the closed-slot box `[8,8,2]..[10,9,14]` precedes the open-fixture endpoint. Closing uses block coordinates X8-9, Y8, Z2-13. Verify these cells were air before editing and restore them in `finally`.

## Post-mode capture and acceptance

1. Select an exact disposable save and verify the fixture. Save fog/sky/exposure/bloom/view/time/player/input settings. Stop movement; set time6000 with advancement disabled, density4, the sky settings above, bloom off and manual EV-12. Settle terrain and verify the camera eye, including the assumed1.62 player-eye offset.
2. Issue `tick freeze` after initial terrain settlement. Record `status.world.gameTime` and verify it before and after every capture. Disabling daytime advancement alone does not freeze procedural fog wind, which uses game time.
3. Capture three same-frame bundles of `primary-depth` and `scene-color` at `fog.debug=1`, then three at `fog.debug=2`. T is dimensionless; S is scattering radiance times pre-exposure. Divide only S by pre-exposure. Retain exact capture projection and frame IDs.
4. Close the slit, wait for geometry publication, and repeat. Keep ticks frozen through edits and the second camera pose. The two poses, two roof states, two signals and three repeats produce24 bundles. More repeats can estimate smaller changes. No JFR runs during readbacks.
5. In `finally`, restore the opening, issue `tick unfreeze` for the initially running world, and restore all saved state.

Before interpreting visibility, require finite T in[0,1], finite nonnegative S, and median(1-T) >=4*2^-11=0.001953125 over the common-endpoint mask: four half-float representable steps below one. Require open/closed T p99 difference <=0.0005. This quantization-based nonvacuum criterion was specified before the frozen captures. The earlier `roof-acceptance-exact` run is excluded because procedural time advanced.

The analyzer checks captured native jittered primary-depth against analytic fixture endpoints, explicitly expands native cells to output resolution and erodes eight output pixels at boundaries (two reduced-resolution cells at divisor4). Average S repeats and examine signed `S_open-S_closed`. The lit interior mean must exceed five repeat standard errors; the adjacent dark interior mean must remain within five standard errors of zero. If no resolvable signal remains, the experiment is uninformative. A placement pass describes these mean controls, not perfect shaft reconstruction.

## Sampling coverage

On the eroded analytic lit mask with common measured endpoints, a **false-dark pixel** has signed mean-RGB delta scattering below1% of the mean response over that lit mask. Report its count and fraction separately from mean placement and T controls. A nonpositive mean response makes coverage inconclusive. Geometry predicts support, not constant brightness: density, phase and attenuation also vary, so inspect images before attributing every low-response pixel to integration.

The frozen48-step baseline (`roof-acceptance-frozen`) passes mean placement but has false-dark fractions18.0951% and21.7159% at the two poses. Its256/48=5.333-metre spacing exceeds the slit width. The96-step quadratic candidate (`roof-quadratic96`) retains both mean-placement controls and has zero false-dark pixels among326678 and343712 eligible lit pixels. Both views have open/closed T p99 difference0 and median T0.993652. Diagnostic previews retain visible discrete intensity bands. Separate runs froze different game times, so coverage improvement does not establish brightness causality or unbiased radiance; these sparse poses do not prove flicker-free motion.

## Captured projection

Capture metadata and the EXR `causticaProjection` JSON attribute contain column-major inverse projection/view rotation, absolute camera, native trace dimensions, and signed trace jitter. For top-down post pixels use `UV=((x+.5)/W,1-(y+.5)/H)`; for primary depth use `UV=((x+.5+jitterX)/renderWidth,1-(y+.5-jitterY)/renderHeight)`. Unproject reverse-Z and add cameraWorld; zero depth denotes environment.

Path-volume mode is removed from active testing at the user's request. Historical measurements remain in FOG_VALIDATION.md.

## Continuous-motion and banding acceptance

A zero false-dark fraction only establishes that every selected pixel has some response. A sequence of bright planar slices can satisfy it. Mean lit/dark controls likewise cannot distinguish smooth integration from stair steps. Neither result accepts the remaining appearance or performance.

Use three paired scenes: the noon slit (thin shadow boundaries), the saved overhead world view (long depth range), and the close oblique wall (smooth depth with no internal silhouette). Keep density, field time, sky, exposure, render/output resolution and reconstruction route fixed. Run fog off and post on each. Begin with terrain drained, then repeat the flight/return route separately to test transient cost.

For motion, capture at least eight seconds of uninterrupted output at a fixed measured cadence, including two seconds stationary, a four-second 0.25-metre lateral traverse or 12-degree/second yaw, and two seconds after stopping. Repeat with a downward pitch sweep across the reported overhead view. Review normal display output at native resolution and normal playback speed; use slow playback and scattering-only output to locate a failure, not to excuse visible bands. Require no new planar bands, camera-locked slabs, abrupt intensity steps, flicker, or persistent trails during motion and settling. Passing two endpoint stills is insufficient.

Synchronous `image.capture` waits for readback and can skip many displayed frames. A sequence made that way is a sparse pose sweep, not continuous-motion evidence. A valid renderer capture needs a bounded asynchronous staging ring with submitted frame IDs, camera/projection, time, and an explicit dropped-frame count, or a separately verified screen-video recorder with frame timestamps. Do not benchmark while either recorder is active. No such consecutive-frame renderer capture is supplied by the current debug image API; report missing motion evidence explicitly until a recorder is available.

For banding, compare diagnostic S and T against a high-sample spatial reference at the identical frozen field, camera and light. Establish reference convergence by doubling sample count until residual changes are below the intended error tolerance. An alternate constant-density medium with an analytic slit integral would also be suitable, but the current public fog settings do not provide that fixture override. The 48- and96-sample implementations are not ground truth for one another. Different frozen game times cannot be compared for radiance accuracy.

Mask physical surface and slit-visibility boundaries using the captured projection and depth. Within the common interior report normalized absolute radiance error, p99 error, and residual second spatial differences along rows and columns; broad repeated peaks identify integration bands even when every pixel is nonzero. Compare motion residuals against the matched reference at each recorded pose, not raw adjacent-frame RGB, which changes legitimately with viewpoint. Inspect every connected error band in the normal output. Record thresholds before the next candidate run, with the reference convergence error below those thresholds; these metrics alone have no calibrated perceptual pass threshold yet.

Measure readback-free GPU scene-effects mean/p95/max and total frame cadence on the same stationary, motion, and return trajectories. Include off controls, repeated post runs, terrain counts and the full transient intervals. Do not accept a quality change that merely moves the user-visible stall into another stage. Root-cause counter improvements are supporting evidence, not a substitute for these quality and end-to-end performance checks.
