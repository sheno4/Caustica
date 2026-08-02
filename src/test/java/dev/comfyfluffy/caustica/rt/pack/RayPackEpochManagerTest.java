package dev.comfyfluffy.caustica.rt.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RayPackEpochManagerTest {
    private static final RayPackManifest SECOND_PACK = new RayPackManifest(
            RayPackContract.MANIFEST_FORMAT, new RayPackId("test", "second"), "0.1.0",
            RayPackContract.API_VERSION,
            new RayPackManifest.SlangImplementation("second_pack", "SecondRayPack", "shaders"),
            new RayPackId("test", "second"));
    private static final RayPackManifest INCOMPATIBLE_PACK = new RayPackManifest(
            RayPackContract.MANIFEST_FORMAT, new RayPackId("test", "future"), "0.1.0",
            new RayPackContract.ApiVersion(0, 2),
            new RayPackManifest.SlangImplementation("future_pack", "FutureRayPack", "shaders"),
            new RayPackId("test", "future"));

    @Test
    void startsActiveOnTheBundledDefaultWithNothingRetiring() {
        RayPackEpochManager manager = RayPackEpochManager.withBundledDefault();
        assertEquals(new RayPackId("caustica", "default"), manager.current().id());
        assertNull(manager.retiringOrNull());
    }

    @Test
    void activateSwapsAtomicallyAndParksThePreviousEpochAsRetiring() {
        RayPackEpochManager manager = RayPackEpochManager.withBundledDefault();
        RayPackEpoch previous = manager.current();

        RayPackEpoch activated = manager.activate(SECOND_PACK, bytes("{}"));

        assertEquals(SECOND_PACK.id(), manager.current().id());
        assertEquals(activated, manager.current());
        assertEquals(previous.id(), manager.retiringOrNull().id());
    }

    @Test
    void aRejectedCandidateNeverReplacesTheCurrentEpoch() {
        RayPackEpochManager manager = RayPackEpochManager.withBundledDefault();
        RayPackEpoch before = manager.current();

        assertThrows(IllegalStateException.class,
                () -> manager.activate(INCOMPATIBLE_PACK, bytes("{}")));

        assertEquals(before, manager.current());
        assertNull(manager.retiringOrNull());
    }

    @Test
    void refusesToActivateAgainUntilTheRetiringEpochIsCleared() {
        RayPackEpochManager manager = RayPackEpochManager.withBundledDefault();
        manager.activate(SECOND_PACK, bytes("{}"));

        assertThrows(IllegalStateException.class, () -> manager.activate(SECOND_PACK, bytes("{}")));

        manager.retireCompleted();
        assertNull(manager.retiringOrNull());
        manager.activate(SECOND_PACK, bytes("{}")); // no longer throws
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
