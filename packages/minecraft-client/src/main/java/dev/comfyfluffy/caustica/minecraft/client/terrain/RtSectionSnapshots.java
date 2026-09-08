package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.biome.Biome;

/**
 * Render-thread cache of immutable palette snapshots shared by neighbouring tessellation jobs.
 * Entries live until section invalidation, column removal, or LRU eviction. Jobs retain their palette
 * revisions independently of cache eviction. Block entities are rendered separately by {@code RtEntities}.
 * Each region's decoded state cache belongs exclusively to the worker processing that region.
 */
final class RtSectionSnapshots {
    // Cache retention is bounded independently of palettes still owned by queued or running jobs.
    private static final int MAX_ENTRIES = 4096;
    /** Cache/region marker for a section with no copyable states (all air, unloaded, or out of range). */
    static final Object AIR = new Object();

    private final MinecraftTelemetry.Instrumentation instrumentation;
    private final Cache cache = new Cache(MAX_ENTRIES);

    RtSectionSnapshots(MinecraftTelemetry.Instrumentation instrumentation) {
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
    }

    /** Snapshot the 3×3×3 neighbourhood of a section, reusing cached palette copies (render thread). */
    Region createRegion(ClientLevel level, int scx, int scy, int scz) {
        Object[] sections = new Object[27];
        LevelChunk[] columns = new LevelChunk[9];
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) columns[(x + 1) + (z + 1) * 3] = level.getChunk(scx + x, scz + z);
        }
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    sections[(x + 1) + (y + 1) * 3 + (z + 1) * 9] = section(
                            columns[(x + 1) + (z + 1) * 3], scx + x, scy + y, scz + z);
                }
            }
        }
        return new Region(level, scx - 1, scy - 1, scz - 1, sections);
    }

    /** Drop a stale entry (edited section, or its column unloaded / left the window). Render thread. */
    void invalidate(long sectionKey) {
        cache.invalidate(sectionKey);
    }

    void clear() {
        cache.clear();
    }

    private Object section(LevelChunk chunk, int scx, int scy, int scz) {
        long key = RtTerrain.sectionKey(scx, scy, scz);
        Object cached = cache.get(key, chunk);
        if (cached != null) {
            return cached;
        }
        Object copy = copySection(chunk, scy);
        cache.put(key, chunk, copy);
        return copy;
    }

    private Object copySection(LevelChunk chunk, int scy) {
        if (chunk instanceof EmptyLevelChunk) {
            return AIR;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int index = chunk.getSectionIndexFromSectionY(scy);
        if (index < 0 || index >= sections.length) {
            return AIR;
        }
        LevelChunkSection section = sections[index];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        instrumentation.count("sectionCopies", 1);
        return section.getStates().copy();
    }

    /** Palette reuse follows exact column identity without retaining unloaded live Minecraft columns. */
    static final class Cache {
        private final int capacity;
        private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>();

        Cache(int capacity) { this.capacity = capacity; }

        Object get(long key, Object column) {
            var entry = entries.getAndMoveToLast(key);
            return entry != null && entry.column.get() == column ? entry.palette : null;
        }

        void put(long key, Object column, Object palette) {
            entries.putAndMoveToLast(key, new Entry(new java.lang.ref.WeakReference<>(column), palette));
            if (entries.size() > capacity) entries.removeFirst();
        }

        void invalidate(long key) { entries.remove(key); }
        void clear() { entries.clear(); }

        private record Entry(java.lang.ref.WeakReference<Object> column, Object palette) { }
    }

    /** Immutable section revisions with a lazy, worker-confined cache of the center and one-block halo. */
    static final class BlockStates {
        private static final int WIDTH = 18;
        private final int minSectionX;
        private final int minSectionY;
        private final int minSectionZ;
        private final Object[] sections; // PalettedContainer<BlockState> or AIR, x-then-y-then-z minor
        private BlockState[] decoded;

        BlockStates(int minSectionX, int minSectionY, int minSectionZ, Object[] sections) {
            this.minSectionX = minSectionX;
            this.minSectionY = minSectionY;
            this.minSectionZ = minSectionZ;
            this.sections = sections;
        }

        BlockState get(int x, int y, int z) {
            int localX = x - ((minSectionX + 1) << 4) + 1;
            int localY = y - ((minSectionY + 1) << 4) + 1;
            int localZ = z - ((minSectionZ + 1) << 4) + 1;
            if (localX < 0 || localX >= WIDTH || localY < 0 || localY >= WIDTH
                    || localZ < 0 || localZ >= WIDTH) {
                return readSnapshot(x, y, z);
            }
            // Queued regions retain only palettes. Each running job decodes at most 5,832 references.
            if (decoded == null) {
                decoded = new BlockState[WIDTH * WIDTH * WIDTH];
            }
            int index = localX + WIDTH * (localZ + WIDTH * localY);
            BlockState state = decoded[index];
            if (state == null) {
                state = readSnapshot(x, y, z);
                decoded[index] = state;
            }
            return state;
        }

        @SuppressWarnings("unchecked")
        private BlockState readSnapshot(int x, int y, int z) {
            int index = (SectionPos.blockToSectionCoord(x) - minSectionX)
                    + (SectionPos.blockToSectionCoord(y) - minSectionY) * 3
                    + (SectionPos.blockToSectionCoord(z) - minSectionZ) * 9;
            Object section = sections[index];
            return section == AIR ? Blocks.AIR.defaultBlockState()
                    : ((PalettedContainer<BlockState>) section).get(x & 15, y & 15, z & 15);
        }
    }

    /** A tessellation job's 3×3×3 state snapshot and live biome/lighting lookup context. */
    static final class Region implements BlockAndTintGetter {
        private final ClientLevel level;
        private final BlockStates blocks;
        private final CardinalLighting cardinalLighting;
        private final LevelLightEngine lightEngine;
        private final boolean debug;

        Region(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ, Object[] sections) {
            this.level = level;
            this.blocks = new BlockStates(minSectionX, minSectionY, minSectionZ, sections);
            this.cardinalLighting = level.cardinalLighting();
            this.lightEngine = level.getLightEngine();
            this.debug = level.isDebug();
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            if (debug) {
                BlockState state = null;
                if (y == 60) {
                    state = Blocks.BARRIER.defaultBlockState();
                }
                if (y == 70) {
                    state = DebugLevelSource.getBlockStateFor(x, z);
                }
                return state == null ? Blocks.AIR.defaultBlockState() : state;
            }
            return blocks.get(x, y, z);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return cardinalLighting;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return lightEngine;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null; // Block entities are rendered separately by RtEntities.
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return level.getBlockTint(pos, resolver);
        }

        public boolean hasBiomes() {
            return true;
        }

        public Holder<Biome> getBiomeFabric(BlockPos pos) {
            return level.getBiomeManager().getBiome(pos);
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }
    }
}
