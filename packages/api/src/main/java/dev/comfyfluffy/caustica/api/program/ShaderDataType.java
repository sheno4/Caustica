package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.Objects;

/**
 * Identity of one extension-defined interpretation of a shader data word.
 *
 * <p>The type parameter is a source-defined marker used for Java compile-time checking. The renderer also
 * compares instances of this class by identity when erased or raw API use reaches a submission boundary,
 * so define each schema once and retain that token for as long as it may be used. A token describes the
 * data reachable from a word, not the storage of the word itself; the Slang ABI remains {@code uint64_t}.
 *
 * @param <T> source-defined marker for the shader-visible data schema
 */
public final class ShaderDataType<T> {
    private final String name;

    private ShaderDataType(String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /** Creates a distinct schema identity with a diagnostic name. */
    public static <T> ShaderDataType<T> create(String name) {
        return new ShaderDataType<>(name);
    }

    /**
     * Wraps inline scalar bits, or bits reaching storage guaranteed to live for the render session,
     * using this schema.
     */
    public ShaderData<T> data(long bits) {
        return new ShaderData<>(this, bits, ResourceRef.none());
    }

    /** Wraps bits and the resource generation keeping their reachable storage alive. */
    public ShaderData<T> data(long bits, ResourceRef resource) {
        return new ShaderData<>(this, bits, resource);
    }

    /**
     * Recovers this schema's generic type after checking the runtime identity carried by {@code data}.
     * Submission implementations use this check before accepting values obtained through erased or raw
     * Java types.
     *
     * @throws IllegalArgumentException if {@code data} carries another schema token
     */
    @SuppressWarnings("unchecked")
    public ShaderData<T> require(ShaderData<?> data) {
        Objects.requireNonNull(data, "data");
        if (data.type() != this) {
            throw new IllegalArgumentException("expected shader data type " + name
                    + ", got " + data.type().name());
        }
        return (ShaderData<T>) data;
    }

    /** Diagnostic name; it does not participate in schema identity. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
