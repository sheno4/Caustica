package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;

/** Minecraft world extension whose one contribution owns its programs and retained geometry together. */
public final class GltfViewerExtension implements MinecraftExtension {
    private static final ShaderSource SHADERS = ShaderSource.classpath(
            GltfViewerExtension.class, "/caustica_gltf_viewer/shaders");

    @Override
    public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(GltfWorldContribution::open);
    }

    static ProgramRegistration<GltfProgramExports> registerPrograms(ProgramChannel programs) {
        return programs.register(builder -> new GltfProgramExports(
                builder.surface(SurfaceDefinition.of(
                        shader("caustica_gltf_viewer_material_surface", "GltfViewerMaterialSurface"),
                        shader("caustica_gltf_viewer_material_coverage", "GltfViewerMaterialCoverage"),
                        GltfProgramExports.IMPLEMENTATION.data(0L), GltfProgramExports.PRIMITIVE,
                        GltfProgramExports.INSTANCE)),
                builder.surface(SurfaceDefinition.opaque(
                        shader("caustica_gltf_viewer_portal_surface", "GltfViewerPortalSurface"),
                        GltfProgramExports.IMPLEMENTATION.data(0L), GltfProgramExports.PRIMITIVE,
                        GltfProgramExports.INSTANCE))));
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(SHADERS, module, type);
    }
}
