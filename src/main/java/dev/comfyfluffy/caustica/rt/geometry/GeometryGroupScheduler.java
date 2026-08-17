package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.rt.RtFrameStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static dev.comfyfluffy.caustica.rt.geometry.GeometryUpdates.*;
import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.*;

/** Global resident ownership with transient group barriers and conflict reservations. */
final class GeometryGroupScheduler {
    private final Map<ResidentId, PublishedResidentSlot> publishedResidents = new LinkedHashMap<>();
    private final Map<InstanceId, PublishedPlacement> publishedPlacements = new LinkedHashMap<>();
    private final Set<PublishedResidentSlot> previousResidentSlots = new LinkedHashSet<>();
    private final Map<GroupKey, Barrier> pending = new LinkedHashMap<>();
    private final Set<ResidentId> reservedResidents = new LinkedHashSet<>();
    private final Set<InstanceId> reservedInstances = new LinkedHashSet<>();
    private final Set<GroupKey> reservedGroups = new LinkedHashSet<>();
    private final Set<Barrier> running = new LinkedHashSet<>();
    private final Map<GroupKey, Long> latestAccepted = new HashMap<>();
    /** Ordered accepted Place/Remove state lets Transform wait for creation without provider publication flags. */
    private final Map<InstanceId, ArrayList<PlacementIntent>> placementIntents = new HashMap<>();
    private long nextAcceptanceOrder;

    void submit(Group update) {
        submit(update, null);
    }

    void submit(Group update, Consumer<Publication> acknowledgment) {
        submit(update, acknowledgment, null);
    }

    void submit(Group update, Consumer<Publication> acknowledgment,
                Consumer<Throwable> failureHandler) {
        Long previous = latestAccepted.get(update.key());
        if (previous != null && update.revision() <= previous) {
            return;
        }
        Barrier barrier = barrier(update, acknowledgment, failureHandler);
        Barrier queued = pending.get(barrier.key);
        Map<InstanceId, List<PlacementIntent>> originalIntents = new HashMap<>();
        long originalOrder = nextAcceptanceOrder;
        Barrier merged;
        try {
            merged = stageMerged(queued, barrier, originalIntents);
        } catch (RuntimeException | Error failure) {
            restorePlacementIntents(originalIntents);
            nextAcceptanceOrder = originalOrder;
            throw failure;
        }
        pending.remove(barrier.key);
        pending.put(barrier.key, merged);
        latestAccepted.put(update.key(), update.revision());
        recordAccepted(barrier);
        recordAcceptedCoalescing(queued, barrier);
    }

    private static Barrier barrier(Group update, Consumer<Publication> acknowledgment,
                                   Consumer<Throwable> failureHandler) {
        Map<SceneGeometryKey, GeometryPayload> puts = new LinkedHashMap<>();
        Set<SceneGeometryKey> drops = new LinkedHashSet<>();
        Map<SceneGeometryKey, Placement> places = new LinkedHashMap<>();
        Map<SceneGeometryKey, PlacementUpdate> placementUpdates = new LinkedHashMap<>();
        Set<SceneGeometryKey> removes = new LinkedHashSet<>();
        for (GeometryOperation operation : update.operations()) {
            switch (operation) {
                case Put put -> {
                    puts.put(put.residentKey(), put.payload()); drops.remove(put.residentKey());
                }
                case Drop drop -> {
                    puts.remove(drop.residentKey()); drops.add(drop.residentKey());
                }
                case Place place -> {
                    Placement value = new Placement(place.residentKey(), place.transform(), place.mask(), place.origin());
                    places.put(place.instanceKey(), value); placementUpdates.remove(place.instanceKey());
                    removes.remove(place.instanceKey());
                }
                case UpdatePlacement transformUpdate -> {
                    Placement placed = places.get(transformUpdate.instanceKey());
                    PlacementUpdate value = new PlacementUpdate(
                            transformUpdate.transform(), transformUpdate.mask(), transformUpdate.origin());
                    if (placed != null) places.put(transformUpdate.instanceKey(), placed.updated(value));
                    else placementUpdates.put(transformUpdate.instanceKey(), value);
                    removes.remove(transformUpdate.instanceKey());
                }
                case Remove remove -> {
                    places.remove(remove.instanceKey()); placementUpdates.remove(remove.instanceKey());
                    removes.add(remove.instanceKey());
                }
            }
        }
        return new Barrier(update.key(), update.revision(),
                new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places),
                        Map.copyOf(placementUpdates), Set.copyOf(removes)),
                acknowledgment, failureHandler);
    }

    void submitAll(List<Group> updates, Consumer<Publication> acknowledgment,
                   Consumer<Throwable> failureHandler) {
        if (updates.isEmpty()) return;
        if (updates.size() == 1) {
            submit(updates.getFirst(), acknowledgment, failureHandler);
            return;
        }
        LinkedHashMap<GroupKey, Barrier> stagedPending = LinkedHashMap.newLinkedHashMap(updates.size());
        boolean profiling = RtFrameStats.enabled();
        int acceptedGroups = 0;
        int acceptedPuts = 0;
        int coalescedGroups = 0;
        int coalescedPuts = 0;
        Map<InstanceId, List<PlacementIntent>> originalIntents = new HashMap<>();
        long originalOrder = nextAcceptanceOrder;
        try {
            for (Group update : updates) {
                Barrier staged = stagedPending.get(update.key());
                Long previous = staged != null ? Long.valueOf(staged.revision) : latestAccepted.get(update.key());
                if (previous != null && update.revision() <= previous) {
                    continue;
                }
                Barrier next = barrier(update, acknowledgment, failureHandler);
                Barrier queued = staged != null ? staged : pending.get(update.key());
                Barrier merged = stageMerged(queued, next, originalIntents);
                if (profiling) {
                    acceptedGroups++;
                    acceptedPuts += next.diff.puts.size();
                    if (queued != null) {
                        coalescedGroups++;
                        coalescedPuts += supersededPutCount(queued, next);
                    }
                }
                stagedPending.remove(update.key());
                stagedPending.put(update.key(), merged);
            }
        } catch (RuntimeException | Error failure) {
            restorePlacementIntents(originalIntents);
            nextAcceptanceOrder = originalOrder;
            throw failure;
        }
        pending.keySet().removeAll(stagedPending.keySet());
        pending.putAll(stagedPending);
        stagedPending.forEach((key, barrier) -> latestAccepted.put(key, barrier.revision));
        if (profiling) {
            RtFrameStats.FRAME.count("geometryGroupsAccepted", acceptedGroups);
            RtFrameStats.FRAME.count("geometryPutsAccepted", acceptedPuts);
            RtFrameStats.FRAME.count("geometryGroupRevisionsCoalesced", coalescedGroups);
            RtFrameStats.FRAME.count("geometryPutRevisionsCoalesced", coalescedPuts);
        }
    }

    private static void recordAccepted(Barrier barrier) {
        if (!RtFrameStats.enabled()) return;
        RtFrameStats.FRAME.count("geometryGroupsAccepted", 1);
        RtFrameStats.FRAME.count("geometryPutsAccepted", barrier.diff.puts.size());
    }

    private static void recordAcceptedCoalescing(Barrier queued, Barrier next) {
        if (queued == null || !RtFrameStats.enabled()) return;
        RtFrameStats.FRAME.count("geometryGroupRevisionsCoalesced", 1);
        RtFrameStats.FRAME.count("geometryPutRevisionsCoalesced", supersededPutCount(queued, next));
    }

    private static int supersededPutCount(Barrier queued, Barrier next) {
        int puts = 0;
        for (SceneGeometryKey resident : queued.diff.puts.keySet()) {
            if (next.diff.puts.containsKey(resident) || next.diff.drops.contains(resident)) puts++;
        }
        return puts;
    }

    private Barrier stageMerged(Barrier queued, Barrier next,
                                Map<InstanceId, List<PlacementIntent>> originalIntents) {
        Barrier merged = queued != null ? queued.merge(next) : next;
        snapshotPlacementIntents(originalIntents, queued);
        snapshotPlacementIntents(originalIntents, merged);
        unregisterPlacementIntents(queued);
        merged.acceptanceOrder = ++nextAcceptanceOrder;
        validateBarrier(merged, true);
        registerPlacementIntents(merged);
        return merged;
    }

    private BarrierValidation validateBarrier(Barrier barrier, boolean admission) {
        long profilingStart = RtFrameStats.FRAME.startStage();
        try {
            boolean waiting = false;
            boolean invalidated = false;
            Set<InstanceId> promised = null;
            for (Placement placement : barrier.diff.placements.values()) {
                ResidentId target = new ResidentId(barrier.key.source(), placement.residentKey);
                if (barrier.diff.drops.contains(placement.residentKey)
                        || (!publishedResidents.containsKey(target)
                        && !barrier.diff.puts.containsKey(placement.residentKey))) {
                    throw new IllegalArgumentException(
                            "geometry placement references a resident absent from its final barrier state "
                                    + placement.residentKey);
                }
            }
            for (SceneGeometryKey instance : barrier.diff.placementUpdates.keySet()) {
                InstanceId id = new InstanceId(barrier.key.source(), instance);
                PlacementAvailability availability = placementAvailability(id, barrier.acceptanceOrder);
                if (admission && availability == PlacementAvailability.ABSENT) {
                    throw new IllegalArgumentException(
                            "geometry transform references neither a published nor promised placement " + instance);
                }
                if (admission && availability == PlacementAvailability.PROMISED) {
                    if (promised == null) promised = new LinkedHashSet<>();
                    promised.add(id);
                } else if (!admission && availability == PlacementAvailability.PROMISED) {
                    waiting = true;
                } else if (!admission && availability == PlacementAvailability.ABSENT) {
                    if (barrier.promisedPlacementUpdates.contains(id)) invalidated = true;
                    else throw new IllegalArgumentException(
                            "geometry transform references an absent placement " + instance);
                }
            }
            for (SceneGeometryKey drop : barrier.diff.drops) {
                PublishedResidentSlot slot = publishedResidents.get(new ResidentId(barrier.key.source(), drop));
                if (slot == null) continue;
                for (PublishedPlacement published : slot.placements) {
                    Placement replacement = barrier.diff.placements.get(published.id.key());
                    if (!barrier.diff.removes.contains(published.id.key())
                            && (replacement == null || replacement.residentKey.equals(drop))) {
                        throw new IllegalArgumentException(
                                "geometry drop leaves a published placement referencing resident " + drop);
                    }
                }
            }
            if (admission) {
                barrier.promisedPlacementUpdates = promised == null ? Set.of() : Set.copyOf(promised);
            }
            if (invalidated) return BarrierValidation.INVALIDATED;
            return waiting ? BarrierValidation.WAITING : BarrierValidation.READY;
        } finally {
            RtFrameStats.FRAME.endStage("geometry.schedulerValidate", profilingStart);
        }
    }

    private PlacementAvailability placementAvailability(InstanceId id, long beforeOrder) {
        ArrayList<PlacementIntent> intents = placementIntents.get(id);
        PlacementIntent latest = null;
        if (intents != null) {
            for (PlacementIntent intent : intents) {
                if (intent.barrier().acceptanceOrder < beforeOrder) latest = intent;
            }
        }
        if (latest != null) {
            return latest.present() ? PlacementAvailability.PROMISED : PlacementAvailability.ABSENT;
        }
        return publishedPlacements.containsKey(id)
                ? PlacementAvailability.PUBLISHED : PlacementAvailability.ABSENT;
    }

    private void snapshotPlacementIntents(Map<InstanceId, List<PlacementIntent>> originals, Barrier barrier) {
        if (barrier == null) return;
        for (SceneGeometryKey key : barrier.diff.placements.keySet()) {
            snapshotPlacementIntents(originals, new InstanceId(barrier.key.source(), key));
        }
        for (SceneGeometryKey key : barrier.diff.removes) {
            snapshotPlacementIntents(originals, new InstanceId(barrier.key.source(), key));
        }
    }

    private void snapshotPlacementIntents(Map<InstanceId, List<PlacementIntent>> originals, InstanceId id) {
        originals.computeIfAbsent(id, ignored -> {
            ArrayList<PlacementIntent> current = placementIntents.get(id);
            return current == null ? List.of() : List.copyOf(current);
        });
    }

    private void restorePlacementIntents(Map<InstanceId, List<PlacementIntent>> originals) {
        originals.forEach((id, intents) -> {
            if (intents.isEmpty()) placementIntents.remove(id);
            else placementIntents.put(id, new ArrayList<>(intents));
        });
    }

    private void registerPlacementIntents(Barrier barrier) {
        barrier.diff.placements.keySet().forEach(key -> registerPlacementIntent(
                new InstanceId(barrier.key.source(), key), barrier, true));
        barrier.diff.removes.forEach(key -> registerPlacementIntent(
                new InstanceId(barrier.key.source(), key), barrier, false));
    }

    private void registerPlacementIntent(InstanceId id, Barrier barrier, boolean present) {
        placementIntents.computeIfAbsent(id, ignored -> new ArrayList<>())
                .add(new PlacementIntent(barrier, present));
    }

    private void unregisterPlacementIntents(Barrier barrier) {
        if (barrier == null) return;
        barrier.diff.placements.keySet().forEach(key -> unregisterPlacementIntent(
                new InstanceId(barrier.key.source(), key), barrier));
        barrier.diff.removes.forEach(key -> unregisterPlacementIntent(
                new InstanceId(barrier.key.source(), key), barrier));
    }

    private void unregisterPlacementIntent(InstanceId id, Barrier barrier) {
        ArrayList<PlacementIntent> intents = placementIntents.get(id);
        if (intents == null) return;
        intents.removeIf(intent -> intent.barrier() == barrier);
        if (intents.isEmpty()) placementIntents.remove(id);
    }

    List<GroupRun> startable() {
        List<GroupRun> result = new ArrayList<>();
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Barrier barrier = iterator.next();
            if (reservedGroups.contains(barrier.key)
                    || intersects(barrier.residents, reservedResidents)
                    || intersects(barrier.instances, reservedInstances)) {
                continue;
            }
            BarrierValidation validation;
            try {
                validation = validateBarrier(barrier, false);
            } catch (IllegalArgumentException invalid) {
                unregisterPlacementIntents(barrier);
                iterator.remove();
                throw invalid;
            }
            if (validation == BarrierValidation.WAITING) continue;
            if (validation == BarrierValidation.INVALIDATED) {
                unregisterPlacementIntents(barrier);
                iterator.remove();
                continue;
            }
            iterator.remove();
            reservedResidents.addAll(barrier.residents);
            reservedInstances.addAll(barrier.instances);
            reservedGroups.add(barrier.key);
            running.add(barrier);
            PreparedGroup prepared = new PreparedGroup(barrier);
            barrier.prepared = prepared;
            if (RtFrameStats.enabled()) {
                RtFrameStats.FRAME.count("geometryPutsStarted", barrier.diff.puts.size());
            }
            result.add(new GroupRun(barrier.key, prepared));
        }
        return result;
    }

    private static <T> boolean intersects(Set<T> first, Set<T> second) {
        Set<T> smaller = first.size() <= second.size() ? first : second;
        Set<T> larger = smaller == first ? second : first;
        for (T value : smaller) {
            if (larger.contains(value)) return true;
        }
        return false;
    }

    boolean running(Barrier barrier) { return running.contains(barrier); }
    boolean cancelled(Barrier barrier) { return barrier.cancelled; }
    int pendingCount() { return pending.size(); }
    int runningCount() { return running.size(); }
    int publishedResidentCount() { return publishedResidents.size(); }
    int publishedPlacementCount() { return publishedPlacements.size(); }

    List<GroupResident> clearSource(ResourceId source) {
        var pendingIterator = pending.entrySet().iterator();
        while (pendingIterator.hasNext()) {
            Barrier barrier = pendingIterator.next().getValue();
            if (!barrier.key.source().equals(source)) continue;
            unregisterPlacementIntents(barrier);
            pendingIterator.remove();
        }
        latestAccepted.keySet().removeIf(key -> key.source().equals(source));
        Set<GroupResident> deferred = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Barrier barrier : running) {
            if (!barrier.key.source().equals(source)) continue;
            barrier.cancelled = true;
            unregisterPlacementIntents(barrier);
            if (barrier.prepared != null) {
                for (GroupCandidate candidate : barrier.prepared.candidates) {
                    if (candidate.source != null) {
                        candidate.deferSourceRetirement();
                        deferred.add(candidate.source);
                    }
                }
            }
        }
        ArrayList<GroupResident> retired = new ArrayList<>();
        var placements = publishedPlacements.values().iterator();
        while (placements.hasNext()) {
            PublishedPlacement placement = placements.next();
            if (placement.id.source().equals(source)) {
                placement.resident.placements.remove(placement);
                placements.remove();
            }
        }
        var residents = publishedResidents.values().iterator();
        while (residents.hasNext()) {
            PublishedResidentSlot slot = residents.next();
            if (slot.id.source().equals(source)) {
                retired.add(slot.current);
                if (slot.previous != null) retired.add(slot.previous);
                previousResidentSlots.remove(slot);
                residents.remove();
            }
        }
        retired.removeIf(deferred::contains);
        return retired;
    }

    void complete(Barrier barrier, boolean success) {
        if (!running.remove(barrier)) return;
        unregisterPlacementIntents(barrier);
        reservedResidents.removeAll(barrier.residents);
        reservedInstances.removeAll(barrier.instances);
        reservedGroups.remove(barrier.key);
    }

    GroupResident publishedResident(ResidentId id) {
        PublishedResidentSlot slot = publishedResidents.get(id);
        return slot == null ? null : slot.current;
    }

    List<GroupResident> putPublishedResident(ResidentId id, GroupResident resident, boolean retainPrevious) {
        PublishedResidentSlot slot = publishedResidents.get(id);
        if (slot == null) {
            publishedResidents.put(id, new PublishedResidentSlot(id, resident));
            return List.of();
        }
        if (retainPrevious) {
            GroupResident retired = slot.previous;
            slot.previous = slot.current;
            slot.current = resident;
            previousResidentSlots.add(slot);
            return retired == null ? List.of() : List.of(retired);
        } else {
            GroupResident current = slot.current;
            GroupResident previous = slot.previous;
            slot.previous = null;
            slot.current = resident;
            previousResidentSlots.remove(slot);
            return previous == null ? List.of(current) : List.of(current, previous);
        }
    }

    List<GroupResident> removePublishedResident(ResidentId id) {
        PublishedResidentSlot slot = publishedResidents.get(id);
        if (slot == null) return List.of();
        if (!slot.placements.isEmpty()) {
            throw new IllegalStateException("published resident still has placements " + id);
        }
        publishedResidents.remove(id);
        previousResidentSlots.remove(slot);
        return slot.previous == null ? List.of(slot.current) : List.of(slot.current, slot.previous);
    }

    void putPublishedPlacement(InstanceId id, Placement placement) {
        PublishedResidentSlot target = publishedResidents.get(new ResidentId(id.source(), placement.residentKey));
        if (target == null) throw new IllegalStateException("published placement target is absent " + id);
        PublishedPlacement published = publishedPlacements.get(id);
        if (published == null) {
            published = new PublishedPlacement(id, placement, target);
            publishedPlacements.put(id, published);
            target.placements.add(published);
            return;
        }
        if (published.resident != target) {
            published.resident.placements.remove(published);
            target.placements.add(published);
            published.resident = target;
        }
        published.placement = placement;
    }

    void removePublishedPlacement(InstanceId id) {
        PublishedPlacement published = publishedPlacements.remove(id);
        if (published != null) published.resident.placements.remove(published);
    }

    void updatePublishedPlacement(InstanceId id, PlacementUpdate update) {
        PublishedPlacement published = publishedPlacements.get(id);
        if (published == null) throw new IllegalStateException("published placement is absent " + id);
        published.placement = published.placement.updated(update);
    }

    java.util.Collection<PublishedPlacement> publishedPlacements() { return publishedPlacements.values(); }

    void drainPreviousResidents(Consumer<GroupResident> consumer) {
        for (PublishedResidentSlot slot : previousResidentSlots) {
            consumer.accept(slot.previous);
            slot.previous = null;
        }
        previousResidentSlots.clear();
    }

    PublishedResidentSlot publishedSlot(ResidentId id) { return publishedResidents.get(id); }
    PublishedPlacement publishedPlacement(InstanceId id) { return publishedPlacements.get(id); }
    int previousResidentSlotCount() { return previousResidentSlots.size(); }

    /** Full index audit for focused tests; production mutation paths maintain these links directly. */
    void assertPublishedIndexConsistent() {
        for (Map.Entry<ResidentId, PublishedResidentSlot> entry : publishedResidents.entrySet()) {
            PublishedResidentSlot slot = entry.getValue();
            if (!entry.getKey().equals(slot.id)
                    || (slot.previous != null) != previousResidentSlots.contains(slot)) {
                throw new IllegalStateException("inconsistent published resident index");
            }
        }
        for (PublishedResidentSlot slot : previousResidentSlots) {
            if (slot.previous == null || publishedResidents.get(slot.id) != slot) {
                throw new IllegalStateException("inconsistent previous resident index");
            }
        }
        for (Map.Entry<InstanceId, PublishedPlacement> entry : publishedPlacements.entrySet()) {
            PublishedPlacement placement = entry.getValue();
            if (!entry.getKey().equals(placement.id)
                    || !placement.id.source().equals(placement.resident.id.source())
                    || !placement.placement.residentKey.equals(placement.resident.id.key())
                    || publishedResidents.get(placement.resident.id) != placement.resident
                    || !placement.resident.placements.contains(placement)) {
                throw new IllegalStateException("inconsistent published placement index");
            }
        }
        for (PublishedResidentSlot slot : publishedResidents.values()) {
            for (PublishedPlacement placement : slot.placements) {
                if (placement.resident != slot || publishedPlacements.get(placement.id) != placement) {
                    throw new IllegalStateException("inconsistent resident placement membership");
                }
            }
        }
    }

    void destroyAfterDeviceIdle(Set<PreparedGroup> destroyed,
                                java.util.function.BiConsumer<PreparedGroup, Set<PreparedGroup>> destroyPrepared) {
        for (Barrier barrier : running) {
            if (barrier.prepared != null) destroyPrepared.accept(barrier.prepared, destroyed);
        }
        Set<GroupResident> destroyedResidents = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PublishedResidentSlot slot : publishedResidents.values()) {
            if (destroyedResidents.add(slot.current)) slot.current.destroy();
            if (slot.previous != null && destroyedResidents.add(slot.previous)) slot.previous.destroy();
        }
        publishedResidents.clear();
        publishedPlacements.clear();
        previousResidentSlots.clear();
        placementIntents.clear();
        pending.clear();
        reservedResidents.clear();
        reservedInstances.clear();
        reservedGroups.clear();
        running.clear();
        latestAccepted.clear();
        nextAcceptanceOrder = 0L;
    }

}

enum BarrierValidation { READY, WAITING, INVALIDATED }

enum PlacementAvailability { ABSENT, PROMISED, PUBLISHED }

record PlacementIntent(Barrier barrier, boolean present) { }

final class Barrier {
    final GroupKey key;
    final long revision;
    final GroupDiff diff;
    final Set<ResidentId> residents;
    final Set<InstanceId> instances;
    final Consumer<Publication> acknowledgment;
    final Consumer<Throwable> failureHandler;
    PreparedGroup prepared;
    boolean cancelled;
    long acceptanceOrder;
    Set<InstanceId> promisedPlacementUpdates = Set.of();

    Barrier(GroupKey key, long revision, GroupDiff diff, Consumer<Publication> acknowledgment,
            Consumer<Throwable> failureHandler) {
        this.key = key; this.revision = revision; this.diff = diff; this.acknowledgment = acknowledgment;
        this.failureHandler = failureHandler;
        residents = new LinkedHashSet<>();
        diff.puts.keySet().forEach(value -> residents.add(new ResidentId(key.source(), value)));
        diff.drops.forEach(value -> residents.add(new ResidentId(key.source(), value)));
        diff.placements.values().forEach(value -> residents.add(new ResidentId(key.source(), value.residentKey)));
        instances = new LinkedHashSet<>();
        diff.placements.keySet().forEach(value -> instances.add(new InstanceId(key.source(), value)));
        diff.placementUpdates.keySet().forEach(value -> instances.add(new InstanceId(key.source(), value)));
        diff.removes.forEach(value -> instances.add(new InstanceId(key.source(), value)));
    }

    Barrier merge(Barrier newer) {
        if (!key.source().equals(newer.key.source())) throw new IllegalArgumentException("cannot merge different sources");
        Map<SceneGeometryKey, GeometryPayload> puts = new LinkedHashMap<>(diff.puts);
        Set<SceneGeometryKey> drops = new LinkedHashSet<>(diff.drops);
        newer.diff.puts.forEach((id, payload) -> { puts.put(id, payload); drops.remove(id); });
        newer.diff.drops.forEach(id -> { puts.remove(id); drops.add(id); });
        Map<SceneGeometryKey, Placement> places = new LinkedHashMap<>(diff.placements);
        Map<SceneGeometryKey, PlacementUpdate> placementUpdates = new LinkedHashMap<>(diff.placementUpdates);
        Set<SceneGeometryKey> removes = new LinkedHashSet<>(diff.removes);
        newer.diff.placements.forEach((id, placement) -> {
            places.put(id, placement); placementUpdates.remove(id); removes.remove(id);
        });
        newer.diff.placementUpdates.forEach((id, update) -> {
            Placement placed = places.get(id);
            if (placed != null) places.put(id, placed.updated(update));
            else placementUpdates.put(id, update);
            removes.remove(id);
        });
        newer.diff.removes.forEach(id -> {
            places.remove(id); placementUpdates.remove(id); removes.add(id);
        });
        return new Barrier(newer.key, newer.revision,
                new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places),
                        Map.copyOf(placementUpdates), Set.copyOf(removes)),
                newer.acknowledgment != null ? newer.acknowledgment : acknowledgment,
                newer.failureHandler != null ? newer.failureHandler : failureHandler);
    }

    List<GeometryOperation> operations() {
        ArrayList<GeometryOperation> result = new ArrayList<>();
        diff.puts.forEach((id, payload) -> result.add(new Put(id, payload)));
        diff.drops.forEach(id -> result.add(new Drop(id)));
        diff.placements.forEach((id, placement) -> result.add(new Place(id, placement.residentKey,
                placement.transform, placement.mask, placement.origin)));
        diff.placementUpdates.forEach((id, update) -> result.add(new UpdatePlacement(
                id, update.transform, update.mask, update.origin)));
        diff.removes.forEach(id -> result.add(new Remove(id)));
        return List.copyOf(result);
    }
}

record GroupRun(GroupKey key, PreparedGroup prepared) { }
