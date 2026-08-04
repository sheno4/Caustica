package dev.comfyfluffy.caustica.api;

import java.io.InputStream;
import java.util.Objects;

public record ShaderSource(String classpathRoot) {
    public ShaderSource {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        if (!classpathRoot.startsWith("/") || classpathRoot.endsWith("/")
                || classpathRoot.contains("..") || classpathRoot.contains("\\")) {
            throw new IllegalArgumentException(
                    "classpath shader root must be an absolute normalized resource path: " + classpathRoot);
        }
    }

    public static ShaderSource classpath(String root) {
        return new ShaderSource(root);
    }

    public InputStream openModule(String module) {
        Slot.requireSlangIdentifier(module, "module");
        return ShaderSource.class.getResourceAsStream(classpathRoot + '/' + module + ".slang");
    }
}
