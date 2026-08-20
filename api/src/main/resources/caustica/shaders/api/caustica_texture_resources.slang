module caustica_texture_resources;

/** Bindless source base-colour textures shared by surfaces and surface modifiers. */
[[vk::binding(0, 1)]] public Sampler2D providerTextures[];
