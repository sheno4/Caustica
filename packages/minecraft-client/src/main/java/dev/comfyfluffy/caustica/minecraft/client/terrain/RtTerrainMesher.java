package dev.comfyfluffy.caustica.minecraft.client.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialClassification;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.client.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainMesh;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RtTerrainMesher {
    /**
     * Reusable per-worker-thread meshing state. The mesh + captures are reset between tasks so their
     * backing arrays amortize across sections instead of re-growing per task. Each completed mesh is copied
     * before the next task reuses the accumulators. The fluid renderer stays per-task because it captures the
     * dispatch context's model set.
     */
    static final class WorkerTessState {
        final QuadCapture capture = new QuadCapture();
        final RandomSource blockRandom = RandomSource.createThreadLocalInstance(0L);
        final List<BlockStateModelPart> modelParts = new ArrayList<>();
        final FluidCapture fluidCapture = new FluidCapture();
        final SectionMesh mesh = new SectionMesh();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void reset(BlockColors blockColors) {
            capture.blockColors = blockColors;
            capture.reset();
            fluidCapture.reset();
            mesh.reset();
        }
    }

    static final ThreadLocal<WorkerTessState> WORKER_TESS = ThreadLocal.withInitial(WorkerTessState::new);

    /**
     * Tessellate one section to a section-local CPU mesh and CPU light metadata. <b>Pure CPU + lookup reads only</b>
     * — no Vulkan, no shared mutable state — so this is the unit a worker thread runs. The task captures one
     * immutable material lookup, so geometry ordinals and light extraction belong to the same resource epoch.
     * Returns the mesh (possibly empty — caller checks {@code idx}).
     */
    static CpuSection buildCpuSection(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                              RandomSource blockRandom, List<BlockStateModelPart> modelParts,
                                              QuadCapture capture,
                                              FluidStateModelSet fluidModels, FluidCapture fluidCapture,
                                              SectionMesh mesh, BlockPos.MutableBlockPos m,
                                              MinecraftMaterialLookup materials,
                                              int scx, int scy, int scz) {
        capture.materials = materials;
        fluidCapture.materials = materials;
        tessellate(region, modelSet, blockRandom, modelParts, capture,
                fluidModels, fluidCapture, mesh, m, scx, scy, scz);
        if (mesh.isEmpty()) {
            return new CpuSection(null, null);
        }
        // Only opaque and masked surfaces contribute light descriptors; transmissive surfaces use
        // their material path, while lava is represented by opaque terrain geometry.
        FloatArrayList collected = new FloatArrayList();
        collectLights(collected, mesh.geometry, CausticaConfig.Rt.Lights.MIN_FILL_RATIO.value());
        float[] lights = EMPTY_LIGHTS;
        if (!collected.isEmpty()) {
            lights = collected.toFloatArray();
        }
        return new CpuSection(packSection(mesh), lights);
    }

    private static final float[] EMPTY_LIGHTS = new float[0];

    private static void collectLights(FloatArrayList out, Geom geom, float minFillRatio) {
        if (geom != null && !geom.idx.isEmpty()) {
            RtLightCollector.collectClass(out, geom.verts, geom.prim, geom.cornerUv,
                    geom.lightSprites.elements(), geom.materialEmissions.elements(), minFillRatio);
        }
    }

    private static MinecraftTerrainMesh packSection(SectionMesh mesh) {
        Geom geom = mesh.geometry();
        ArrayList<TriangleRouting> routing = new ArrayList<>(geom.surfaces.size());
        for (int triangle = 0; triangle < geom.surfaces.size(); triangle++) {
            TerrainSurface surface = geom.surfaces.get(triangle);
            var coverage = surface.coverage() == Coverage.CUTOUT
                    ? MinecraftTerrainMesh.Coverage.CUTOUT : MinecraftTerrainMesh.Coverage.OPAQUE;
            var range = surface.opacityRange();
            var micromap = range == null ? null : new MinecraftTerrainMesh.OpacityMicromap(
                    range.transparentAlpha(), range.opaqueAlpha(), 2);
            var program = surface.material().material().equals(MinecraftMaterialIds.WATER)
                    ? MinecraftTerrainMesh.ProgramCategory.WATER
                    : MinecraftTerrainMesh.ProgramCategory.MATERIAL;
            routing.add(new TriangleRouting(program, coverage, 0.5f, micromap));
        }
        var packed = bucketTriangles(
                java.util.Arrays.copyOf(geom.idx.elements(), geom.idx.size()),
                java.util.Arrays.copyOf(geom.cornerUv.elements(), geom.cornerUv.size()),
                java.util.Arrays.copyOf(geom.prim.elements(), geom.prim.size()), routing);
        return new MinecraftTerrainMesh(java.util.Arrays.copyOf(geom.verts.elements(), geom.verts.size()),
                packed.indices(), packed.cornerUvs(), packed.primitiveData(), packed.geometries(), 0L);
    }

    /** Vulkan routing shared by triangles that may occupy one contiguous acceleration-geometry range. */
    record TriangleRouting(MinecraftTerrainMesh.ProgramCategory program,
                           MinecraftTerrainMesh.Coverage coverage,
                           float alphaCutoff,
                           MinecraftTerrainMesh.OpacityMicromap opacityMicromap) { }

    /** Triangle streams packed in stable routing-bucket order. */
    record PackedTriangles(int[] indices, float[] cornerUvs, float[] primitiveData,
                           List<MinecraftTerrainMesh.Geometry> geometries) { }

    static PackedTriangles bucketTriangles(int[] sourceIndices, float[] sourceCornerUvs,
                                           float[] sourcePrimitiveData, List<TriangleRouting> routing) {
        var buckets = new LinkedHashMap<TriangleRouting, IntArrayList>();
        for (int triangle = 0; triangle < routing.size(); triangle++) {
            buckets.computeIfAbsent(routing.get(triangle), ignored -> new IntArrayList()).add(triangle);
        }

        int[] indices = new int[sourceIndices.length];
        float[] cornerUvs = new float[sourceCornerUvs.length];
        float[] primitiveData = new float[sourcePrimitiveData.length];
        ArrayList<MinecraftTerrainMesh.Geometry> geometries = new ArrayList<>(buckets.size());
        int destinationTriangle = 0;
        for (var bucket : buckets.entrySet()) {
            int firstIndex = destinationTriangle * 3;
            IntArrayList sourceTriangles = bucket.getValue();
            for (int i = 0; i < sourceTriangles.size(); i++) {
                int sourceTriangle = sourceTriangles.getInt(i);
                System.arraycopy(sourceIndices, sourceTriangle * 3, indices, destinationTriangle * 3, 3);
                System.arraycopy(sourceCornerUvs, sourceTriangle * 6, cornerUvs, destinationTriangle * 6, 6);
                System.arraycopy(sourcePrimitiveData, sourceTriangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS,
                        primitiveData, destinationTriangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS,
                        MinecraftTerrainMesh.PRIMITIVE_FLOATS);
                destinationTriangle++;
            }
            TriangleRouting route = bucket.getKey();
            geometries.add(new MinecraftTerrainMesh.Geometry(route.program(), route.coverage(), firstIndex,
                    sourceTriangles.size() * 3, route.alphaCutoff(), route.opacityMicromap()));
        }
        return new PackedTriangles(indices, cornerUvs, primitiveData, geometries);
    }

    private static void tessellate(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                   RandomSource blockRandom, List<BlockStateModelPart> modelParts,
                                   QuadCapture capture, FluidStateModelSet fluidModels, FluidCapture fluidCapture,
                                   SectionMesh mesh, BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        capture.cur = mesh;
        capture.view = region;
        fluidCapture.cur = mesh;
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = sox + lx, wy = soy + ly, wz = soz + lz;
                    m.set(wx, wy, wz);
                    BlockState state = region.getBlockState(m);
                    if (state.isAir()) {
                        continue;
                    }
                    // Fluids (water/lava, incl. waterlogged blocks): separate mesher, INVISIBLE render
                    // shape, so handled independently of the block model below. Emits section-local
                    // coords + atlas sprite UVs straight into the capturing consumer. Lava's block light
                    // (15) rides the emission channel (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Minecraft selects the named transmissive fluid material; lava stays the
                        // adapter's opaque emitter fallback.
                        fluidCapture.water = fluid.is(FluidTags.WATER);
                        RtFluidMesher.tesselate(region, m, fluidCapture, fluidModels, state, fluid);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    capture.state = state;
                    capture.pos = m;
                    Vec3 offset = state.getOffset(m);
                    capture.originX = lx + (float) offset.x;
                    capture.originY = ly + (float) offset.y;
                    capture.originZ = lz + (float) offset.z;
                    blockRandom.setSeed(state.getSeed(m));
                    modelParts.clear();
                    model.collectParts(blockRandom, modelParts);
                    for (BlockStateModelPart part : modelParts) {
                        for (BakedQuad quad : part.getQuads(null)) {
                            capture.putVanilla(quad);
                        }
                        for (Direction direction : Direction.values()) {
                            if (!capture.isCulled(direction)) {
                                for (BakedQuad quad : part.getQuads(direction)) {
                                    capture.putVanilla(quad);
                                }
                            }
                        }
                    }
                    capture.flushBlock(); // resolve coplanar ties (grass overlay / cross faces), then emit
                }
            }
        }
    }


    /** Pure-CPU worker result: tessellated mesh and CPU-only light metadata. */
    record CpuSection(MinecraftTerrainMesh mesh, float[] lights) {
    }


    /** Transient CPU accumulator for one section's source-order mesh. */
    private static final class SectionMesh {
        final Geom geometry = new Geom(1216);

        Geom geometry() { return geometry; }

        boolean isEmpty() {
            return geometry.idx.isEmpty();
        }

        /** Empty the classes keeping their backing arrays — the mesh is reused across jobs per worker thread. */
        void reset() {
            geometry.reset();
        }
    }

    private enum Coverage { OPAQUE, CUTOUT }

    private record OpacityRange(float transparentAlpha, float opaqueAlpha) { }

    private record TerrainMaterial(int materialIndex, ResourceId material, ResourceId texture) { }

    private record TerrainSurface(TerrainMaterial material, Coverage coverage, OpacityRange opacityRange) { }

    /** One geometry class's packed, section-local mesh data. */
    private static final class Geom {
        final FloatArrayList verts;
        final IntArrayList idx;
        // Lever B: per-triangle corner UVs in primitive order — 6 floats/triangle (3 corners x u,v),
        // aligned with `idx`'s triangle order so the hit shader reads cornerUv[3*pid + k] directly with no
        // index->vertex-UV gather. The index buffer is still emitted (above) for the BLAS build.
        final FloatArrayList cornerUv;
        // 12 CPU lanes/triangle: normal/emission float4, tint float4, then reserved scratch lanes.
        final FloatArrayList prim;
        final List<TerrainSurface> surfaces;
        // One sprite per triangle for CPU light extraction.
        final SpriteList lightSprites;
        final MaterialEmissionList materialEmissions;

        Geom(int triCapacity) {
            int cap = Math.max(2, triCapacity);
            int quadCapacity = (cap + 1) >>> 1;
            verts = new FloatArrayList(quadCapacity * 12); // 4 xyz vertices per quad
            idx = new IntArrayList(cap * 3);
            cornerUv = new FloatArrayList(cap * 6);
            prim = new FloatArrayList(cap * 12);
            surfaces = new ArrayList<>(cap);
            lightSprites = new SpriteList(cap);
            materialEmissions = new MaterialEmissionList(cap);
        }

        int triCount() {
            return idx.size() / 3;
        }

        void reset() {
            verts.clear();       // fastutil clear() keeps the backing array
            idx.clear();
            cornerUv.clear();
            prim.clear();
            surfaces.clear();
            lightSprites.clear();
            materialEmissions.clear();
        }
    }

    /** Growable reference array paired one-to-one with triangles; entries belong to the captured lookup epoch. */
    private static final class MaterialEmissionList {
        private MinecraftMaterialEmission[] elements;
        private int size;

        MaterialEmissionList(int capacity) {
            elements = new MinecraftMaterialEmission[Math.max(2, capacity)];
        }

        void add(MinecraftMaterialEmission emission) {
            if (size == elements.length) {
                elements = java.util.Arrays.copyOf(elements, size * 2);
            }
            elements[size++] = emission;
        }

        MinecraftMaterialEmission[] elements() {
            return elements;
        }

        void clear() {
            java.util.Arrays.fill(elements, 0, size, null);
            size = 0;
        }
    }

    /** Minimal growable sprite array for the worker path; avoids ArrayList object churn and per-copy gets. */
    private static final class SpriteList {
        private TextureAtlasSprite[] elements;
        private int size;

        SpriteList(int capacity) {
            elements = new TextureAtlasSprite[Math.max(2, capacity)];
        }

        void add(TextureAtlasSprite sprite) {
            if (size == elements.length) {
                TextureAtlasSprite[] grown = new TextureAtlasSprite[elements.length + (elements.length >>> 1)];
                System.arraycopy(elements, 0, grown, 0, elements.length);
                elements = grown;
            }
            elements[size++] = sprite;
        }

        TextureAtlasSprite[] elements() {
            return elements;
        }

        int size() {
            return size;
        }

        void copyInto(TextureAtlasSprite[] dst, int offset) {
            System.arraycopy(elements, 0, dst, offset, size);
        }

        void clear() {
            java.util.Arrays.fill(elements, 0, size, null); // don't retain sprites across atlas reloads
            size = 0;
        }
    }

    /** Captures vanilla baked model quads into the current section's mesh. */
    private static final class QuadCapture {
        SectionMesh cur; // set before each block model emission
        MinecraftMaterialLookup materials;

        // Per-block context for biome tint, set before each model emission. We resolve it straight from
        // BlockColors resolves biome tint before raster lighting, so the path tracer receives unlit albedo
        // rather than vanilla AO + directional shading.
        BlockColors blockColors;
        BlockAndTintGetter view;
        BlockState state;
        BlockPos pos;
        float originX, originY, originZ;
        private final BlockPos.MutableBlockPos cullPos = new BlockPos.MutableBlockPos();

        // Coplanar-resolution: vanilla emits coincident quads that tie on depth in the BVH and flicker —
        // a block face's opaque base + its tinted cutout overlay (grass/snowy sides), and a cross model's
        // two-sided faces. put() buffers a block's quads here; flushBlock() (called per block) nudges all
        // but the first member of each coincident group outward along its own normal so each lands on its
        // own plane (base stays, overlay moves in front so its cutout reveals the base; cross back-face
        // separates from the front). Pooled — reset each block, never reallocated steady-state.
        private static final float OFFSET = 2.0e-4f;         // outward nudge (blocks) to break coplanar depth ties
        private static final float TRANSLUCENT_INSET = 2.0e-4f; // inward recess (blocks) for glass/ice vs coplanar neighbours
        private static final float COINCIDENT_EPS = 1.0e-4f; // verts this close are "the same" point
        private static final int RESOLVE_CAP = 128;          // skip the O(n^2) resolve for pathological blocks
        private final List<PendingQuad> pending = new ArrayList<>(8);
        private final Map<BlockState, MinecraftMaterialClassification> classifications = new IdentityHashMap<>();
        private final Map<TextureAtlasSprite, SpriteMaterial> spriteMaterials = new IdentityHashMap<>();
        private int pendingCount;
        private int[] gidScratch = new int[0];

        /** Capture a vanilla baked quad before raster AO/directional lighting is applied. */
        private void putVanilla(BakedQuad quad) {
            PendingQuad q = acquire();
            for (int i = 0; i < 4; i++) {
                q.x[i] = quad.position(i).x() + originX;
                q.y[i] = quad.position(i).y() + originY;
                q.z[i] = quad.position(i).z() + originZ;
                q.uv[i] = quad.packedUV(i);
            }

            float ex1 = q.x[1] - q.x[0], ey1 = q.y[1] - q.y[0], ez1 = q.z[1] - q.z[0];
            float ex2 = q.x[2] - q.x[0], ey2 = q.y[2] - q.y[0], ez2 = q.z[2] - q.z[0];
            float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) { nx /= len; ny /= len; nz /= len; }
            q.nx = nx; q.ny = ny; q.nz = nz;

            ChunkSectionLayer layer = quad.materialInfo().layer();
            q.cutout = layer != ChunkSectionLayer.SOLID;
            q.translucent = layer == ChunkSectionLayer.TRANSLUCENT;

            float tr = 1f;
            float tg = 1f;
            float tb = 1f;
            int tintIndex = quad.materialInfo().tintIndex();
            q.tinted = tintIndex >= 0;
            if (tintIndex >= 0 && blockColors != null && state != null) {
                BlockTintSource src = blockColors.getTintSource(state, tintIndex);
                if (src != null) {
                    int rgb = src.colorInWorld(state, view, pos);
                    float[] tint = ColorSpaces.srgbToAcesCg(
                            ((rgb >> 16) & 0xFF) * (1f / 255f),
                            ((rgb >> 8) & 0xFF) * (1f / 255f),
                            (rgb & 0xFF) * (1f / 255f));
                    tr = tint[0];
                    tg = tint[1];
                    tb = tint[2];
                }
            }
            q.tr = tr; q.tg = tg; q.tb = tb;

            q.emission = Math.max(quad.materialInfo().lightEmission(),
                    state != null ? state.getLightEmission() : 0) / 15f;
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            q.sprite = sprite;
            MinecraftMaterialClassification classification = classifications.computeIfAbsent(
                    state, MinecraftMaterialClassifier::classify);
            MinecraftMaterialKey key = new MinecraftMaterialKey(
                    MinecraftResourceIds.material(sprite), classification.geometry(), classification.profile(),
                    q.translucent ? MinecraftMaterialTopology.MEDIUM_BOUNDARY
                            : MinecraftMaterialTopology.SURFACE);
            SpriteMaterial spriteMaterial = spriteMaterials.computeIfAbsent(sprite, current ->
                    new SpriteMaterial(MinecraftResourceIds.material(current), ResourceId.of(
                            current.atlasLocation().getNamespace(), current.atlasLocation().getPath())));
            MinecraftMaterialResolution terrainMaterial = materials.resolve(key);
            q.material = new TerrainMaterial(terrainMaterial.materialIndex(), terrainMaterial.material(),
                    spriteMaterial.texture());
            q.coverage = q.cutout && !q.translucent ? Coverage.CUTOUT : Coverage.OPAQUE;
            q.materialEmission = terrainMaterial.emission();
            q.opacityMicromapRange = q.coverage == Coverage.CUTOUT && terrainMaterial.opacityMicromap() != null
                    ? new OpacityRange(terrainMaterial.opacityMicromap().transparentAlpha(),
                    terrainMaterial.opacityMicromap().opaqueAlpha()) : null;
        }

        /** Returns true when vanilla's nominal face should be discarded. */
        private boolean isCulled(Direction direction) {
            if (direction == null) {
                return false;
            }
            BlockState neighbor = view.getBlockState(cullPos.setWithOffset(pos, direction));
            return !Block.shouldRenderFace(state, neighbor, direction);
        }

        /** Acquire a pooled PendingQuad for the current block (grown on demand, count reset by flushBlock). */
        private PendingQuad acquire() {
            if (pendingCount == pending.size()) {
                pending.add(new PendingQuad());
            }
            return pending.get(pendingCount++);
        }

        /** Reset the current section while retaining reusable backing storage. */
        void reset() {
            discardBlock();
            classifications.clear();
            spriteMaterials.clear();
        }

        /** Drop the current block's buffered quads without emitting. */
        void discardBlock() {
            pendingCount = 0;
        }

        private record SpriteMaterial(ResourceId material, ResourceId texture) {
        }

        /** Resolve coplanar ties among the current block's quads, then emit them into the section classes. */
        void flushBlock() {
            int n = pendingCount;
            if (n == 0) {
                return;
            }
            if (n >= 2 && n <= RESOLVE_CAP) {
                resolveCoplanar(n);
            }
            for (int i = 0; i < n; i++) {
                emit(pending.get(i));
            }
            pendingCount = 0;
        }

        /**
         * Union coincident quads (same 4 corners, any winding) into groups, then within each group keep the
         * first member (the opaque/untinted base if present) in place and push the rest outward along their
         * own normals by {@link #OFFSET} × rank. Same-normal layers (grass base/overlay) fan out along one
         * direction; opposite-normal pairs (cross faces) separate because each moves along its own normal.
         */
        private void resolveCoplanar(int n) {
            int[] gid = gidScratch.length >= n ? gidScratch : (gidScratch = new int[n]);
            for (int i = 0; i < n; i++) {
                gid[i] = -1;
            }
            for (int i = 0; i < n; i++) {
                if (gid[i] != -1) {
                    continue;
                }
                gid[i] = i;
                for (int j = i + 1; j < n; j++) {
                    if (gid[j] == -1 && coincident(pending.get(i), pending.get(j))) {
                        gid[j] = i;
                    }
                }
            }
            for (int r = 0; r < n; r++) {
                if (gid[r] != r) {
                    continue; // not a group representative
                }
                int rank = 0;
                // Pass 1: bases (opaque + untinted) — the first stays put (rank 0), so the overlay lands in
                // front of it. Pass 2: overlays (cutout or tinted) — always pushed outward.
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && !(q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && (q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
            }
        }

        /** True if every corner of {@code a} coincides with a corner of {@code b} (same quad, any winding). */
        private static boolean coincident(PendingQuad a, PendingQuad b) {
            for (int k = 0; k < 4; k++) {
                boolean found = false;
                for (int m = 0; m < 4; m++) {
                    if (Math.abs(a.x[k] - b.x[m]) < COINCIDENT_EPS
                            && Math.abs(a.y[k] - b.y[m]) < COINCIDENT_EPS
                            && Math.abs(a.z[k] - b.z[m]) < COINCIDENT_EPS) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /** Shift all four of a quad's corners by {@code d} along its (outward) normal. */
        private static void offset(PendingQuad q, float d) {
            for (int v = 0; v < 4; v++) {
                q.x[v] += q.nx * d;
                q.y[v] += q.ny * d;
                q.z[v] += q.nz * d;
            }
        }

        /** Emit one resolved quad into its section class (2 triangles, corner UVs, per-prim records). */
        private void emit(PendingQuad q) {
            // Recess translucent (glass / ice) faces slightly into their own block. Vanilla culls a glass
            // face that touches a full solid block, but KEEPS the one touching a non-occluding neighbour
            // (slabs / stairs) — which lands exactly coplanar with that neighbour's face and z-fights. A tiny
            // inward inset makes the glass resolve consistently behind the neighbour's surface.
            if (q.translucent) {
                offset(q, -TRANSLUCENT_INSET);
            }
            Geom g = cur.geometry();
            int base = g.verts.size() / 3;
            for (int k = 0; k < 4; k++) {
                g.verts.add(q.x[k]);
                g.verts.add(q.y[k]);
                g.verts.add(q.z[k]);
            }
            IntArrayList idx = g.idx;
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order matching the two triangles: 0,1,2 then 0,2,3).
            addTriUv(g, q.uv[0], q.uv[1], q.uv[2]);
            addTriUv(g, q.uv[0], q.uv[2], q.uv[3]);
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) {
                prim.add(q.nx);
                prim.add(q.ny);
                prim.add(q.nz);
                // normal.w is the primitive's state-derived emission strength in [0,1].
                prim.add(q.emission);
                prim.add(q.tr);
                prim.add(q.tg);
                prim.add(q.tb);
                prim.add(0f);
                prim.add(q.material.materialIndex());
                prim.add(0f); // flags
                prim.add(0f); // aux0
                prim.add(0f); // aux1
                g.lightSprites.add(q.sprite);
                g.materialEmissions.add(q.materialEmission);
                g.surfaces.add(new TerrainSurface(q.material, q.coverage, q.opacityMicromapRange));
            }
        }
    }

    /** One block's buffered quad, awaiting coplanar resolution before it is emitted into a section class. */
    private static final class PendingQuad {
        final float[] x = new float[4], y = new float[4], z = new float[4];
        final long[] uv = new long[4];
        float nx, ny, nz;
        boolean cutout; // non-SOLID render layer (alpha-tested) — also an overlay candidate
        boolean translucent; // TRANSLUCENT layer (stained glass / ice): colored-transmission dielectric
        boolean tinted; // tintIndex >= 0 — the tinted member of a base+overlay pair
        float tr, tg, tb, emission;
        MinecraftMaterialEmission materialEmission;
        TerrainMaterial material;
        Coverage coverage;
        OpacityRange opacityMicromapRange;
        TextureAtlasSprite sprite;
    }

    /** Append one triangle's 3 corner UVs (6 floats) from packed atlas-space UVs. */
    private static void addTriUv(Geom g, long pa, long pb, long pc) {
        FloatArrayList c = g.cornerUv;
        c.add(Float.intBitsToFloat((int) (pa >>> 32)));
        c.add(Float.intBitsToFloat((int) pa));
        c.add(Float.intBitsToFloat((int) (pb >>> 32)));
        c.add(Float.intBitsToFloat((int) pb));
        c.add(Float.intBitsToFloat((int) (pc >>> 32)));
        c.add(Float.intBitsToFloat((int) pc));
    }

    /** Append one triangle's 3 corner UVs (6 floats) from float u,v pairs (fluid path). */
    private static void addTriUv(Geom g, float ua, float va, float ub, float vb, float uc, float vc) {
        FloatArrayList c = g.cornerUv;
        c.add(ua);
        c.add(va);
        c.add(ub);
        c.add(vb);
        c.add(uc);
        c.add(vc);
    }

    /**
     * Captures the quads {@link FluidRenderer} emits (water/lava) into the current section's mesh. It
     * is both the {@link FluidRenderer.Output} and the {@link VertexConsumer} it hands back. Vertices
     * arrive in groups of 4 (one quad) via the bulk {@code addVertex}; we keep position + atlas UV,
     * compute a geometric normal (sign is irrelevant — the closest-hit flips it toward the viewer), and
     * emit two triangles like {@link QuadCapture}. Coords are already section-local (FluidRenderer uses
     * {@code pos & 15}). Albedo comes from the atlas; RGB primitive tint carries the fluid source colour.
     * Topology and appearance come from the resolved named material rather than a primitive semantic bit.
     */
    private static final class FluidCapture implements VertexConsumer, FluidRenderer.Output {
        private static final MinecraftMaterialKey LAVA_KEY = new MinecraftMaterialKey(
                MinecraftMaterialIds.LAVA, null,
                MinecraftMaterialProfile.MEDIUM_ROUGH_DIELECTRIC, MinecraftMaterialTopology.SURFACE);
        private static final ResourceId BLOCK_ATLAS = ResourceId.of(
                TextureAtlas.LOCATION_BLOCKS.getNamespace(), TextureAtlas.LOCATION_BLOCKS.getPath());

        SectionMesh cur;     // set before each section
        MinecraftMaterialLookup materials;
        MinecraftMaterialEmission waterEmission;
        MinecraftMaterialEmission lavaEmission;
        TerrainMaterial lavaMaterial;
        float emission;      // set per fluid block (lava = 1, water = 0)
        boolean water;       // set per fluid block: true for water (dielectric), false for lava
        private int n;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private final int[] qc = new int[4]; // per-vertex packed ARGB (vanilla bakes the biome water tint here)

        /** Reset per-job assembly state (a mid-quad meshing throw could leave a partial quad buffered). */
        void reset() {
            n = 0;
            waterEmission = null;
            lavaEmission = null;
            lavaMaterial = null;
        }

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            return this; // one capturing builder regardless of the fluid's render layer
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            qx[n] = x; qy[n] = y; qz[n] = z; qu[n] = u; qv[n] = v; qc[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }

        private void emitQuad() {
            Geom g = cur.geometry();
            TerrainMaterial material;
            MinecraftMaterialEmission materialEmission;
            if (water) {
                MinecraftMaterialResolution waterMaterial = materials.resolve(MinecraftMaterialIds.WATER);
                material = new TerrainMaterial(waterMaterial.materialIndex(), waterMaterial.material(), null);
                materialEmission = waterEmission;
                if (materialEmission == null) {
                    materialEmission = waterEmission = waterMaterial.emission();
                }
            } else {
                materialEmission = lavaEmission;
                if (materialEmission == null) {
                    var terrainMaterial = materials.resolve(LAVA_KEY);
                    lavaMaterial = new TerrainMaterial(terrainMaterial.materialIndex(),
                            terrainMaterial.material(), BLOCK_ATLAS);
                    materialEmission = lavaEmission = terrainMaterial.emission();
                }
                material = lavaMaterial;
            }
            FloatArrayList verts = g.verts;
            IntArrayList idx = g.idx;
            int base = verts.size() / 3;
            for (int i = 0; i < 4; i++) {
                verts.add(qx[i]);
                verts.add(qy[i]);
                verts.add(qz[i]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order: 0,1,2 then 0,2,3), matching the two triangles above.
            addTriUv(g, qu[0], qv[0], qu[1], qv[1], qu[2], qv[2]);
            addTriUv(g, qu[0], qv[0], qu[2], qv[2], qu[3], qv[3]);

            float ex1 = qx[1] - qx[0], ey1 = qy[1] - qy[0], ez1 = qz[1] - qz[0];
            float ex2 = qx[2] - qx[0], ey2 = qy[2] - qy[0], ez2 = qz[2] - qz[0];
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            // Biome water tint: vanilla's FluidRenderer bakes BiomeColors.getAverageWaterColor into the
            // per-vertex colour, so the average of the quad's four colours is this water body's tint. The
            // path tracer turns it into a per-channel Beer–Lambert extinction (ocean blue vs swamp green).
            // Lava keeps a white tint (its colour rides the emission channel, not absorption).
            float tr = 1f, tg = 1f, tb = 1f;
            if (water) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < 4; i++) {
                    sr += (qc[i] >> 16) & 0xFF;
                    sg += (qc[i] >> 8) & 0xFF;
                    sb += qc[i] & 0xFF;
                }
                float[] tint = ColorSpaces.srgbToAcesCg(
                        sr / 1020f, sg / 1020f, sb / 1020f); // 4 vertices * 255
                tr = tint[0];
                tg = tint[1];
                tb = tint[2];
            }
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
                prim.add(material.materialIndex());
                prim.add(0f);
                prim.add(0f);
                prim.add(0f);
                g.lightSprites.add(null);
                g.materialEmissions.add(materialEmission);
                g.surfaces.add(new TerrainSurface(material, Coverage.OPAQUE, null));
            }
        }

        // Unused VertexConsumer surface — FluidRenderer only calls the bulk addVertex above.
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

}
