package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One surface implementation, its optional traversal-safe coverage implementation, and their
 * extension-owned data root. Each implementation carries its own module resolver. Coverage is nullable
 * for a surface used only with {@link dev.comfyfluffy.caustica.api.geometry.MeshBuild.CoveragePolicy.Opaque};
 * geometry using {@code Cutout} requires it. A volume is an independent geometry slot registered with
 * {@link ProgramBuilder#volume}.
 *
 * <p>{@code implementationData} reaches both implementations unchanged. It is commonly a device address for the
 * extension's session material/texture table, but may be any packed 64-bit value. {@code bindingDataType}
 * and {@code instanceDataType} declare the schemas accepted by geometry slots and mesh placements selecting
 * this implementation. Their token identities survive Java generic erasure and are validated when geometry
 * is submitted. Material variants are extension data indexed from these roots; they are not renderer objects.
 *
 * @param <B> geometry-slot binding data schema
 * @param <N> mesh-placement instance data schema
 */
public record SurfaceDefinition<B, N>(ShaderDefinition surface, ShaderDefinition coverage,
                                      ShaderData<?> implementationData,
                                      ShaderDataType<B> bindingDataType,
                                      ShaderDataType<N> instanceDataType) {
    public SurfaceDefinition {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(implementationData, "implementationData");
        Objects.requireNonNull(bindingDataType, "bindingDataType");
        Objects.requireNonNull(instanceDataType, "instanceDataType");
    }

    /** Creates a definition with a traversal-safe coverage implementation. */
    public static <B, N> SurfaceDefinition<B, N> of(
            ShaderDefinition surface, ShaderDefinition coverage,
            ShaderData<?> implementationData, ShaderDataType<B> bindingDataType,
            ShaderDataType<N> instanceDataType) {
        Objects.requireNonNull(coverage, "coverage");
        return new SurfaceDefinition<>(surface, coverage, implementationData, bindingDataType,
                instanceDataType);
    }

    /** Creates a definition that can only be used by opaque geometry. */
    public static <B, N> SurfaceDefinition<B, N> opaque(
            ShaderDefinition surface, ShaderData<?> implementationData,
            ShaderDataType<B> bindingDataType, ShaderDataType<N> instanceDataType) {
        return new SurfaceDefinition<>(surface, null, implementationData, bindingDataType,
                instanceDataType);
    }
}
