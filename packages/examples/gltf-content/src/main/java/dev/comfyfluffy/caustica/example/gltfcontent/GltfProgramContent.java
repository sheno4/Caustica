package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
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
                        SHADERS.definition("caustica_gltf_viewer_material_surface", "GltfViewerMaterialSurface"),
                        SHADERS.definition("caustica_gltf_viewer_material_coverage", "GltfViewerMaterialCoverage"),
                        GltfProgramExports.IMPLEMENTATION.data(0L), GltfProgramExports.PRIMITIVE,
                        GltfProgramExports.INSTANCE)),
                builder.surface(SurfaceDefinition.opaque(
                        SHADERS.definition("caustica_gltf_viewer_portal_surface", "GltfViewerPortalSurface"),
                        GltfProgramExports.IMPLEMENTATION.data(0L), GltfProgramExports.PRIMITIVE,
                        GltfProgramExports.INSTANCE))));
    }

}
