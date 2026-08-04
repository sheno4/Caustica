package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.api.CausticaRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One immutable slot selection and the complete source identity compiled for it. */
public record Composition(CausticaRegistry.Selection selection, String rootModule, String rootType,
                          String rootSource, String contentHash) {
    public Composition {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(rootModule, "rootModule");
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(rootSource, "rootSource");
        Objects.requireNonNull(contentHash, "contentHash");
    }

    static Composition create(CausticaRegistry.Selection selection, String rootModule, String rootType,
                              String rootSource, Map<String, byte[]> sources) {
        TreeMap<String, byte[]> ordered = new TreeMap<>(sources);
        ordered.put("generated/" + rootModule + ".slang",
                rootSource.getBytes(StandardCharsets.UTF_8));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
            }
            return new Composition(selection, rootModule, rootType, rootSource,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }
}
