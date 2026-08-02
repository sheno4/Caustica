package dev.comfyfluffy.caustica.rt.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RayPackEpochTest {
    private static final RayPackManifest MANIFEST = new RayPackManifest(
            RayPackContract.MANIFEST_FORMAT, new RayPackId("caustica", "default"), "0.1.0",
            RayPackContract.API_VERSION,
            new RayPackManifest.SlangImplementation("default_pack", "DefaultRayPack", "shaders"),
            new RayPackId("caustica", "default"));

    @Test
    void contentHashIsDeterministicAndBoundToTheExactManifestBytes() {
        RayPackEpoch first = RayPackEpoch.of(MANIFEST, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        RayPackEpoch same = RayPackEpoch.of(MANIFEST, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        RayPackEpoch different = RayPackEpoch.of(MANIFEST, "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(first.contentHash(), same.contentHash());
        assertNotEquals(first.contentHash(), different.contentHash());
        assertEquals(64, first.contentHash().length()); // hex-encoded SHA-256
    }

    @Test
    void rejectsAnEpochIdThatDoesNotMatchTheManifestId() {
        assertThrows(IllegalArgumentException.class, () -> new RayPackEpoch(
                new RayPackId("caustica", "not-default"), MANIFEST, "0".repeat(64)));
    }
}
