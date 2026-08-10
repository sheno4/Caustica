module caustica_minecraft_sky_bindings;

import caustica_minecraft_sky_atmosphere;

// SkyLutPass's own descriptor bindings, at a set (2) reserved for pass-declared resources rather than
// the engine's fixed set 0 (bindings.slang). The engine never names these — it discovers them from
// Slang reflection when the composition that imports this module compiles, and SkyLutPass writes into
// them by NAME (matching the identifiers below) through PassSetup.publishWorldResource. See
// A pass owns its own resources end to end; the engine's only job is reserving the descriptor set index.
[[vk::binding(0, 2)]] public Sampler2D skyView;
[[vk::binding(1, 2)]] public Sampler2D transmittance;
// This frame's sky inputs, filled by SkyLutPass. See SkyInputs in caustica_minecraft_sky_atmosphere.
[[vk::binding(2, 2)]] public ConstantBuffer<SkyInputs> skyInputs;
// Vanilla's sun and moon-phase sprite atlas, sampled only inside the drawn discs. It belongs to whichever
// slot actually draws celestial sprites: a sky for another dimension binds no such texture.
[[vk::binding(3, 2)]] public Sampler2D celestialsAtlas;
