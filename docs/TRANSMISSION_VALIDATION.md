# Transmission validation — 2026-09-11

The SPBR-21 comparison used `caustica-transmission-20260911`, copied from the debug workbench. The fixture contains glass, ice, and white stained glass walls with colored concrete behind them. The client used DLSS Ray Reconstruction at 854×480 output and 427×240 trace resolution, with four path bounces. Baseline and rebuilt runs used the same saved camera and fixture. Daylight and automatic exposure were active; absolute screenshot brightness is not a controlled radiometric comparison.

## Findings and changes

- Material normal maps followed outward mesh winding on exit faces while geometric normals faced the ray. Closest-hit now orients authored current and previous normals together into the geometric normal's hemisphere.
- Shadow closest-hit treated all non-volume surfaces as opaque without evaluating their material. It now evaluates thin-sheet transmission. Sheet crossings retain the incoming medium absorption and IOR. Straight shadow connections use Fresnel sheet attenuation; they do not simulate rough angular scattering, which remains in the sampled BSDF path.
- Full terrain coverage and inverse-alpha transmission remain enabled for translucent terrain. No roughness decode or denoiser setting was changed.

## Evidence

`tmp/transmission-before.json` and `tmp/transmission-after.json` link final screenshots and same-frame EXR bundles containing trace radiance, reconstructed color, normal/roughness, and BSDF estimates. Both raw and reconstructed after images show transmission through glass where the baseline appeared blocked. The captures verify this bounded scene, not every material, view, or denoiser route.

Source PNG inspection found SPBR glass smoothness R=243–254, corresponding to perceptual roughness 0.0039–0.0471. Its captured guide is approximately 0.020–0.0471 because the BSDF's GGX alpha floor is reflected in the guide. Ice smoothness R=204–255 gives roughness 0–0.20; the sampled guide mean is approximately 0.158 before and after. These values do not indicate a channel decoding error.

SPBR ice base alpha is 190/255. The requested inverse-alpha mapping gives transmission weight 65/255 per face, approximately 0.065 across two faces before Fresnel losses. Its remaining opaque appearance is therefore expected under this mapping. Glass normal maps also perturb reflection direction even where scalar roughness is small.

Validation passed: `:packages:minecraft-client:test :packages:renderer-raytracing:check :packages:minecraft-rendering:check`, including shader compilation. Log: `tmp/transmission-fix-check.log`. The rebuilt live client successfully rendered the fixture; log: `tmp/transmission-fixed-client.log`. A subsequent comment-only clarification in shadow any-hit does not change shader behavior.

The debug client was left running in the copied fixture world for inspection. Original worlds were not modified.
