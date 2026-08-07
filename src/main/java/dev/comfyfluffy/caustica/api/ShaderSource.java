package dev.comfyfluffy.caustica.api;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Where a module's {@code .slang} source resolves from: {@code classpathRoot}, then — for on-disk
 * organization only, never a search-path change from the module system's point of view — each of
 * {@code subdirectories} in order, first match wins. A module name never contains a slash, so this stays
 * a bounded, explicit list rather than recursive directory search.
 */
public record ShaderSource(String classpathRoot, List<String> subdirectories) {
    public ShaderSource {
        Objects.requireNonNull(classpathRoot, "classpathRoot");
        if (!classpathRoot.startsWith("/") || classpathRoot.endsWith("/")
                || classpathRoot.contains("..") || classpathRoot.contains("\\")) {
            throw new IllegalArgumentException(
                    "classpath shader root must be an absolute normalized resource path: " + classpathRoot);
        }
        subdirectories = List.copyOf(Objects.requireNonNull(subdirectories, "subdirectories"));
    }

    public static ShaderSource classpath(String root, String... subdirectories) {
        return new ShaderSource(root, List.of(subdirectories));
    }

    public InputStream openModule(String module) {
        Slot.requireSlangIdentifier(module, "module");
        InputStream direct = ShaderSource.class.getResourceAsStream(classpathRoot + '/' + module + ".slang");
        if (direct != null) {
            return direct;
        }
        for (String subdirectory : subdirectories) {
            InputStream nested = ShaderSource.class.getResourceAsStream(
                    classpathRoot + '/' + subdirectory + '/' + module + ".slang");
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
