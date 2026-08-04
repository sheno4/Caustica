package dev.comfyfluffy.caustica.rt.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One immutable loaded ray-pack version: a validated manifest plus the content hash it was published
 * with (docs/EXTENSION_API.md section 9). Switching or reloading the selected pack creates a
 * new epoch rather than mutating this one.
 *
 * <p>{@code contentHash} covers only the manifest bytes for now. A future compilation coordinator must
 * extend it to cover every transitively imported Slang source and specialization-affecting asset
 * (section 16.3) before it can double as a compiled-artifact cache key; until then it only identifies
 * which manifest text this epoch was published from.
 */
public record RayPackEpoch(RayPackId id, RayPackManifest manifest, String contentHash) {
    public RayPackEpoch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contentHash, "contentHash");
        if (!id.equals(manifest.id())) {
            throw new IllegalArgumentException("epoch id " + id + " does not match manifest id "
                    + manifest.id());
        }
    }

    public static RayPackEpoch of(RayPackManifest manifest, byte[] manifestBytes) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        return new RayPackEpoch(manifest.id(), manifest, sha256Hex(manifestBytes));
    }

    public static RayPackEpoch of(RayPackManifest manifest, String manifestJson) {
        return of(manifest, manifestJson.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
