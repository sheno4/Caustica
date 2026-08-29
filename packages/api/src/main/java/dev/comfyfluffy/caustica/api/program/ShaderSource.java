package dev.comfyfluffy.caustica.api.program;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Where a feature's {@code .slang} modules resolve from. The resource anchor owns the class loader;
 * this is required when an extension is isolated in a separate mod jar. Module lookup checks the
 * classpath root, then each declared subdirectory in order. Dotted Slang module names map to nested
 * classpath paths.
 */
public final class ShaderSource {
    private final Class<?> resourceAnchor;
    private final String classpathRoot;
    private final List<String> subdirectories;

    public ShaderSource(Class<?> resourceAnchor, String classpathRoot, List<String> subdirectories) {
        this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        this.classpathRoot = normalizedRoot(classpathRoot);
        this.subdirectories = List.copyOf(Objects.requireNonNull(subdirectories, "subdirectories"));
        for (String subdirectory : this.subdirectories) {
            if (subdirectory.isEmpty() || subdirectory.startsWith("/") || subdirectory.endsWith("/")
                    || subdirectory.contains("..") || subdirectory.contains("\\")) {
                throw new IllegalArgumentException("shader subdirectory must be normalized: " + subdirectory);
            }
        }
    }

    /** Resolves resources through the class loader that owns {@code resourceAnchor}. */
    public static ShaderSource classpath(Class<?> resourceAnchor, String root, String... subdirectories) {
        return new ShaderSource(resourceAnchor, root, List.of(subdirectories));
    }

    public Class<?> resourceAnchor() {
        return resourceAnchor;
    }

    public String classpathRoot() {
        return classpathRoot;
    }

    public List<String> subdirectories() {
        return subdirectories;
    }

    /** Defines one Slang type resolved through this source. */
    public ShaderDefinition definition(String module, String type) {
        return new ShaderDefinition(this, module, type);
    }

    public InputStream openModule(String module) {
        SlangIdentifier.requireModule(module);
        String modulePath = module.replace('.', '/');
        InputStream direct = resourceAnchor.getResourceAsStream(classpathRoot + '/' + modulePath + ".slang");
        if (direct != null) {
            return direct;
        }
        for (String subdirectory : subdirectories) {
            InputStream nested = resourceAnchor.getResourceAsStream(
                    classpathRoot + '/' + subdirectory + '/' + modulePath + ".slang");
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String normalizedRoot(String root) {
        Objects.requireNonNull(root, "classpathRoot");
        if (!root.startsWith("/") || root.endsWith("/") || root.contains("..") || root.contains("\\")) {
            throw new IllegalArgumentException(
                    "classpath shader root must be an absolute normalized resource path: " + root);
        }
        return root;
    }
}
