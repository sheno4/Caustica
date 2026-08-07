module caustica_lut_sky_bindings;

// SkyLutPass's own descriptor bindings, at a set (2) reserved for pass-declared resources rather than
// the engine's fixed set 0 (bindings.slang). The engine never names these — it discovers them from
// Slang reflection when the composition that imports this module compiles, and SkyLutPass writes into
// them by NAME (matching the identifiers below) through PassSetup.publishWorldResource. See
// docs/ARCHITECTURE.md's render-pass section for why: a pass owns its own resources end to end, the
// engine's only job is reserving the descriptor set index itself.
[[vk::binding(0, 2)]] public Sampler2D skyView;
[[vk::binding(1, 2)]] public Sampler2D transmittance;
