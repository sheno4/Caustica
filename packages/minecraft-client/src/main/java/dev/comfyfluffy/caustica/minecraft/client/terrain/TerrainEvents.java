package dev.comfyfluffy.caustica.minecraft.client.terrain;

import jdk.jfr.*;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainGeometry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import static dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain.*;

/** JFR observations emitted by terrain coordination, preparation, and publication. */
final class TerrainEvents {
    private static final java.lang.management.ThreadMXBean THREAD_METRICS = java.lang.management.ManagementFactory.getThreadMXBean();

    private TerrainEvents() { }

    @Name("dev.comfyfluffy.caustica.TerrainJob")
    @Label("Terrain section job observation") @Category({"Caustica", "Terrain"}) @StackTrace(false) @Enabled(false)
    static final class TerrainJobEvent extends Event {
        @Description("Most recently observed telemetry frame serial on the emitting thread")
        public long observedFrameId;
        public long epoch, revision;
        public int sectionX, sectionY, sectionZ;
        public String action;
        public boolean failed;
    }

    @Name("dev.comfyfluffy.caustica.TerrainPublication")
    @Label("Terrain worker publication") @Category({"Caustica", "Terrain"}) @StackTrace(false) @Enabled(false)
    static final class TerrainPublicationEvent extends Event {
        public long observedFrameId, epoch;
        public int groups, sections;
        public boolean published, failed;
        public long cpuNanos, allocatedBytes;
    }

    static long threadCpuNanos() {
        return THREAD_METRICS.isThreadCpuTimeEnabled() ? THREAD_METRICS.getCurrentThreadCpuTime() : -1;
    }

    static long threadAllocatedBytes() {
        return THREAD_METRICS instanceof com.sun.management.ThreadMXBean metrics
                && metrics.isThreadAllocatedMemoryEnabled() ? metrics.getCurrentThreadAllocatedBytes() : -1;
    }

    @Name("dev.comfyfluffy.caustica.TerrainState")
    @Label("Terrain state at client tick") @Category({"Caustica", "Terrain"}) @StackTrace(false) @Enabled(false)
    static final class TerrainStateEvent extends Event {
        @Description("Most recently observed telemetry frame serial; tick snapshot is not a rendered frame")
        public long observedFrameId;
        public boolean worldPresent, materialsPresent, geometryBound;
        public long epoch, lastDispatchedRevision;
        public int playerBlockX, playerBlockY, playerBlockZ;
        public int windowCenterChunkX, windowCenterChunkZ, renderDistanceChunks;
        public int minSectionY, maxSectionY;
        public int originBlockX, originBlockY, originBlockZ;
        public int loadedWindowColumns, trackedSections, wantedSections, removingSections;
        @Description("Published section state, including sections with empty geometry")
        public int publishedSections;
        public int residentGeometrySections;
        public int requests, undispatchedRequests, dispatchedRequests, completedRequests;
        public int pendingGroups, readyGroups, neighborBlockedRequests, neighborBlockedColumns;
        @Description("Outstanding CPU extraction, upload, and GPU preparation across epochs")
        public int outstandingBuilds;
        @Description("Concurrent queue sizes are individually sampled, not an atomic worker snapshot")
        public int discardedQueue;
        public int dirtyGroupsQueue;
        public boolean publicationScheduled;
        public int workerThreads, activeWorkers, queuedWorkerTasks;
    }

    /** Immutable worker counters; the tick event can observe a snapshot collected before its own tick. */
    record StateCounts(int columns, int sections, int geometry, int wanted, int removing, int published,
                       int requests, int complete, int dispatched, int pending, int blocked,
                       int groups, int ready, int blockedColumns) {
        static final StateCounts EMPTY = new StateCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** Called by the coordinator while holding the terrain preparation lock. */
        static StateCounts capture(TerrainUpdates<?> updates, TerrainWindow window, MinecraftTerrainGeometry geometry) {
            int wanted = 0, removing = 0, published = 0, requests = 0, complete = 0, dispatched = 0, pending = 0, blocked = 0;
            var groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TerrainUpdates.Group<?>, Boolean>());
            var blockedColumns = new LongOpenHashSet();
            var checkedColumns = new LongOpenHashSet();
            for (var section : updates.sections.values()) {
                if (section.wanted) wanted++; else removing++;
                if (section.ready) published++;
                var request = section.request;
                if (request == null) continue;
                requests++;
                groups.add(request.group);
                if (request.complete()) complete++;
                else if (request.dispatched()) dispatched++;
                else {
                    pending++;
                    long column = columnKey(sectionX(section.key), sectionZ(section.key));
                    if (checkedColumns.add(column) && !window.neighborsLoaded(sectionX(section.key), sectionZ(section.key))) {
                        blockedColumns.add(column);
                    }
                    if (blockedColumns.contains(column)) blocked++;
                }
            }
            int ready = 0;
            for (var group : groups) if (group.remaining == 0) ready++;
            return new StateCounts(window.columns(), updates.sections.size(),
                    geometry == null ? 0 : geometry.sectionKeys().size(), wanted, removing, published,
                    requests, complete, dispatched, pending, blocked, groups.size(), ready, blockedColumns.size());
        }

        void writeTo(TerrainStateEvent event) {
            event.loadedWindowColumns = columns;
            event.trackedSections = sections;
            event.residentGeometrySections = geometry;
            event.wantedSections = wanted;
            event.removingSections = removing;
            event.publishedSections = published;
            event.requests = requests;
            event.completedRequests = complete;
            event.dispatchedRequests = dispatched;
            event.undispatchedRequests = pending;
            event.neighborBlockedRequests = blocked;
            event.pendingGroups = groups;
            event.readyGroups = ready;
            event.neighborBlockedColumns = blockedColumns;
        }
    }

}
