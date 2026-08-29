package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.List;

/** Stable Minecraft material identities shared by extraction and program resources. */
public final class MinecraftMaterialIds {
    public static final ResourceId WATER = ResourceId.of("minecraft", "water");
    public static final ResourceId LAVA = ResourceId.of("minecraft", "block/lava_still");
    public static final ResourceId END_PORTAL = ResourceId.of("minecraft", "end_portal");
    public static final ResourceId PARTICLE_BILLBOARD =
            ResourceId.of("caustica", "minecraft_particle_billboard");
    public static final ResourceId VERTEX_COLOR = ResourceId.of("caustica", "minecraft_vertex_color");

    /** Neutral material-table identities shaded by captured primitive data or their selected program. */
    public static final List<ResourceId> CAPTURE_SURFACES = List.of(
            END_PORTAL, PARTICLE_BILLBOARD, VERTEX_COLOR);

    private MinecraftMaterialIds() { }
}
