package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Process-scoped Minecraft extension registrations, frozen after extension discovery. */
public final class MinecraftExtensionRegistry {
    private static final System.Logger LOGGER = System.getLogger(MinecraftExtensionRegistry.class.getName());
    private final Map<ResourceId, Entry> registered = new HashMap<>();
    private List<Entry> resolvers;

    public synchronized void registerMaterialResolver(ResourceId id, int priority,
                                                      List<MinecraftMaterialSelector> selectors,
                                                      MinecraftMaterialResolver resolver) {
        if (resolvers != null) throw new IllegalStateException("Minecraft extension registry is frozen");
        Objects.requireNonNull(selectors, "selectors");
        if (selectors.isEmpty()) throw new IllegalArgumentException("material resolver needs a selector");
        Entry entry = new Entry(Objects.requireNonNull(id, "id"), priority, List.copyOf(selectors),
                Objects.requireNonNull(resolver, "resolver"));
        if (registered.putIfAbsent(id, entry) != null) {
            throw new IllegalStateException("duplicate Minecraft material resolver " + id);
        }
    }

    /** Freezes deterministic resolver order: higher priority first, then lexicographic resolver id. */
    public synchronized void freeze() {
        if (resolvers != null) throw new IllegalStateException("Minecraft extension registry is already frozen");
        ArrayList<Entry> ordered = new ArrayList<>(registered.values());
        ordered.sort(Comparator.comparingInt(Entry::priority).reversed()
                .thenComparing(entry -> entry.id().toString()));
        resolvers = List.copyOf(ordered);
    }

    /**
     * Returns the first successful resolution, or the request's complete default resolution. A resolver
     * failure is logged with its id and material key, then resolution continues downstream.
     */
    public MinecraftMaterialResolution resolve(MinecraftMaterialRequest request) {
        Objects.requireNonNull(request, "request");
        List<Entry> snapshot;
        synchronized (this) {
            if (resolvers == null) throw new IllegalStateException("Minecraft extension registry is not frozen");
            snapshot = resolvers;
        }
        for (Entry entry : snapshot) {
            if (entry.selectors().stream().noneMatch(selector ->
                    selector.matches(request.material(), request.geometry()))) continue;
            try {
                MinecraftMaterialResolution resolved = entry.resolver().resolve(request);
                if (resolved == null) continue;
                if (!resolved.definition().handle().equals(request.fallback().definition().handle())) {
                    throw new IllegalArgumentException("resolver changed the canonical material handle");
                }
                return resolved;
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "Minecraft material resolver " + entry.id() + " failed for material "
                        + request.material() + " and geometry " + request.geometry(), failure);
            }
        }
        return request.fallback();
    }

    /** Ordered immutable selector declarations used to build the finite epoch catalog. */
    public List<MinecraftMaterialSelector> selectors() {
        List<Entry> snapshot = frozenResolvers();
        return snapshot.stream().flatMap(entry -> entry.selectors().stream()).toList();
    }

    /** Concrete geometry cases declared for {@code material}, sorted by identifier. */
    public Set<ResourceId> geometryCases(ResourceId material) {
        Objects.requireNonNull(material, "material");
        TreeSet<ResourceId> geometries = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (Entry entry : frozenResolvers()) {
            for (MinecraftMaterialSelector selector : entry.selectors()) {
                if (selector.geometry() != null
                        && (selector.material() == null || selector.material().equals(material))) {
                    geometries.add(selector.geometry());
                }
            }
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(geometries));
    }

    public synchronized boolean frozen() {
        return resolvers != null;
    }

    private synchronized List<Entry> frozenResolvers() {
        if (resolvers == null) throw new IllegalStateException("Minecraft extension registry is not frozen");
        return resolvers;
    }

    private record Entry(ResourceId id, int priority, List<MinecraftMaterialSelector> selectors,
                         MinecraftMaterialResolver resolver) { }
}
