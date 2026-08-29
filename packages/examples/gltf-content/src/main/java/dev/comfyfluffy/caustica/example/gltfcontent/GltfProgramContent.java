package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;

/** Declares the reusable glTF material and portal programs as one atomic program set. */
public final class GltfProgramContent {
    private static final ShaderSource SHADERS = ShaderSource.classpath(
            GltfProgramContent.class, "/caustica_gltf_viewer/shaders");

    private GltfProgramContent() { }

    public static ProgramRegistration<GltfProgramExports> register(ProgramChannel programs) {
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
