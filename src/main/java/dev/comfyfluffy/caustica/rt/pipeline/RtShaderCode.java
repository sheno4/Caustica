package dev.comfyfluffy.caustica.rt.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * SPIR-V for one pipeline stage, plus a debug name used for Vulkan object labels and error messages.
 *
 * <p>Stages come from two places now: shaders compiled at build time and shipped as {@code .spv}
 * resources, and engine entry points compiled at runtime because they are specialized with the selected
 * selected composition. Pipeline creation should not care which, so both
 * arrive here as bytes.
 */
public record RtShaderCode(String debugName, byte[] spirv) {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/world/";

    public RtShaderCode {
        Objects.requireNonNull(debugName, "debugName");
        Objects.requireNonNull(spirv, "spirv");
        if (spirv.length == 0) {
            throw new IllegalArgumentException("empty SPIR-V for " + debugName);
        }
    }

    /** A build-time shader loaded from the world pipeline's SPIR-V resource directory. */
    public static RtShaderCode resource(String name) {
        Objects.requireNonNull(name, "name");
        String resource = SHADER_DIR + name;
        try (InputStream in = RtShaderCode.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + resource);
            }
            return new RtShaderCode(name, in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + resource, e);
        }
    }

    /** Already-compiled SPIR-V, such as a composition-specialized engine entry point. */
    public static RtShaderCode of(String debugName, byte[] spirv) {
        return new RtShaderCode(debugName, spirv);
    }
}
