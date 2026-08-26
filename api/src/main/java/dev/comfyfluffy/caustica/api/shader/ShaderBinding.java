package dev.comfyfluffy.caustica.api.shader;

/**
 * The descriptor kind a pass shader declares at one set-zero binding position, in declaration order.
 *
 * <p>Lives in the API rather than beside a dispatch helper because it is the vocabulary
 * {@link ShaderCompiler#validateBindings} checks reflection against — an engine-provided service. A
 * pass that builds its own pipelines still describes what it declared in these terms.
 */
public enum ShaderBinding {
    /** A read-write storage image. */
    STORAGE,
    /** A combined image sampler. */
    SAMPLED
}
