package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.engine.program.ProgramKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable generated Slang composition identity. */
record Composition(Map<ProgramKey, Integer> implementationIndices, List<Long> implementationData,
                   String rootModule, String rootType, String rootSource, String contentHash) {
    public Composition {
        implementationIndices = Map.copyOf(implementationIndices);
        implementationData = List.copyOf(implementationData);
        Objects.requireNonNull(rootModule, "rootModule");
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(rootSource, "rootSource");
        Objects.requireNonNull(contentHash, "contentHash");
    }

    public int implementationIndex(ProgramKey key) {
        Integer index = implementationIndices.get(Objects.requireNonNull(key, "key"));
        if (index == null) throw new IllegalArgumentException("program key is not in this composition: " + key);
        return index;
    }

    static Composition create(Map<ProgramKey, Integer> indices, List<Long> implementationData,
                              String rootModule, String rootType,
                              String rootSource, Map<String, byte[]> sources) {
        TreeMap<String, byte[]> ordered = new TreeMap<>(sources);
        ordered.put("generated/" + rootModule + ".slang", rootSource.getBytes(StandardCharsets.UTF_8));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
            }
            return new Composition(indices, implementationData, rootModule, rootType, rootSource,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }
}
