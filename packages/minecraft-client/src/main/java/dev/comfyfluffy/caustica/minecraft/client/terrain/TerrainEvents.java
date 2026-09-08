package dev.comfyfluffy.caustica.minecraft.client.terrain;

import jdk.jfr.*;

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

}
