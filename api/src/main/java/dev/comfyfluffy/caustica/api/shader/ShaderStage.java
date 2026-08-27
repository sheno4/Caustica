package dev.comfyfluffy.caustica.api.shader;

/** Shader entry-point stages supported by the session compiler. */
public enum ShaderStage {
    VERTEX, FRAGMENT, COMPUTE, RAY_GENERATION, INTERSECTION, ANY_HIT, CLOSEST_HIT, MISS, CALLABLE
}
