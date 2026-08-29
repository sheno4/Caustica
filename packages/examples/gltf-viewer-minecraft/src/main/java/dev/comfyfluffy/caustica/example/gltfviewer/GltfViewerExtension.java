package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;

/** Installs the Minecraft world contribution for the glTF viewer example. */
public final class GltfViewerExtension implements MinecraftExtension {
    @Override
    public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(GltfWorldContribution::open);
    }
}
