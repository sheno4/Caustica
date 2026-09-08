# Refactor validation

The project-wide aesthetic review includes interactive behavior and visual inspection. Failures found by these checks are part of the refactor/fix work. Compilation and unit tests do not substitute for the interactive checks.

## Queued lifecycle checks

- Enter the debug workbench, wait for active RT and visible geometry, exit to the title screen, enter a different existing test world in the same client process, and return to the first world. Check coherent scene replacement, camera/history reset, stale geometry, errors and shutdown.
- Toggle RT off/on five times in one world, waiting for the requested state and fresh rendered frames each time. Inspect vanilla terrain while disabled and world, hand, HUD and transparency after enabling. Repeat a toggle after switching worlds.
- Leave a world while RT preparation is active, then open another world. Check that completed jobs from the old world cannot publish into the new session.

## Queued interactive exploration

- Use computer use to navigate pause/title/world-selection menus and inspect the settings screen. Check opening/closing screens, UI placement, readable text, transparency and correct input restoration.
- Inspect a stationary workbench camera, then move through terrain and look at cutout surfaces, emissive geometry, water/reflections and moving entities. Compare final output with relevant normal/depth/radiance views when a visual defect appears.
- Exercise window resizing and reconstruction-mode changes where available; inspect resolution transitions, stale targets, ghosting and exposure settling.
- Verify the NGX RR diagnostic overlay's axis controls with a before/after capture. Do not alter world/UI orientation to compensate for the native indicator.

Use named debug operations for repeatable state checks and computer use for actual UI exploration. Save evidence under `tmp/` and `run/caustica-debug/`; restore changed settings, input state and views. Make checkpoint commits after verified coherent changes. Each run must record actual results and limits rather than marking a queued check complete from intent.
