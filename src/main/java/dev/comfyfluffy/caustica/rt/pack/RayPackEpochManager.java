package dev.comfyfluffy.caustica.rt.pack;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Owns the currently active {@link RayPackEpoch} and its transactional replacement
 * (docs/RAY_PACK_ARCHITECTURE.md section 9). This is the Java-only publication seam: it validates a
 * candidate and swaps the active reference atomically, exactly as the full lifecycle will, but it does
 * not yet compile anything, allocate a GPU resource, or track a graphics-use lifetime for the retiring
 * epoch — those steps belong to whoever wires this into the renderer (the compilation coordinator and
 * {@code RtComposite}), not to this class.
 *
 * <p>Until that wiring exists, {@link #retireCompleted()} is safe to call immediately after
 * {@link #activate}: there is no GPU resource outstanding to wait for yet.
 */
public final class RayPackEpochManager {
    private RayPackEpoch active;
    private RayPackEpoch retiring;

    public RayPackEpochManager(RayPackEpoch initial) {
        this.active = Objects.requireNonNull(initial, "initial");
    }

    /** The bundled default, validated and published as the initial epoch (section 17.1 startup fallback). */
    public static RayPackEpochManager withBundledDefault() {
        RayPackManifest manifest = RayPackDiscovery.discoverBundled();
        String json = RayPackDiscovery.bundledManifestJson();
        return new RayPackEpochManager(RayPackEpoch.of(manifest, json.getBytes(StandardCharsets.UTF_8)));
    }

    public synchronized RayPackEpoch current() {
        return active;
    }

    /** Non-null while a previously active epoch is retiring; null once {@link #retireCompleted()} runs. */
    public synchronized RayPackEpoch retiringOrNull() {
        return retiring;
    }

    /**
     * Validates {@code candidateManifest} against the engine's API contract and, only if that passes,
     * publishes it as the new active epoch. A rejected candidate never touches {@link #current()} — the
     * previously active epoch stays live (section 17.1: "the current pack remains active").
     *
     * @throws IllegalStateException if the previous epoch has not finished retiring, or the candidate's
     *                                API version is incompatible with the engine's
     */
    public synchronized RayPackEpoch activate(RayPackManifest candidateManifest, byte[] manifestBytes) {
        Objects.requireNonNull(candidateManifest, "candidateManifest");
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        if (retiring != null) {
            throw new IllegalStateException("epoch " + retiring.id()
                    + " has not finished retiring; call retireCompleted() before activating another");
        }
        if (!RayPackContract.supports(candidateManifest.api())) {
            throw new IllegalStateException(candidateManifest.id() + ": engine API "
                    + RayPackContract.API_VERSION + " does not support pack API " + candidateManifest.api());
        }
        RayPackEpoch candidate = RayPackEpoch.of(candidateManifest, manifestBytes);
        retiring = active;
        active = candidate;
        return candidate;
    }

    /** Marks the retiring epoch's last dependent work as complete, freeing the manager to activate again. */
    public synchronized void retireCompleted() {
        retiring = null;
    }
}
